package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.RechargeCommand;
import com.wherelee.base.modules.contracts.metering.ReserveCommand;
import com.wherelee.base.modules.contracts.metering.SettleCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.identity.invitation.UserInvitationService;
import com.wherelee.base.modules.identity.invitation.dto.UserInvitationDtos;
import com.wherelee.base.modules.identity.security.LoginThrottleService;
import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.resilience.LlmRateLimiter;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.config.SecurityProperties;
import com.wherelee.base.modules.platform.resilience.LocalRateLimiter;
import com.wherelee.base.modules.platform.resilience.RedisFallbackMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发/压力集成测试（真实 MySQL + Redis，有限资源、确定性断言）。
 *
 * <p>覆盖：预扣并发防超卖、同一预扣并发结算只记一次账、并发充值只入账一次、
 * Redis 共享令牌桶跨实例一致、支付发件箱并发抢占不重复投递、支付回调并发幂等、
 * 邀请接受并发只成功一次、登录节流 Redis 计数生效。</p>
 */
class ConcurrencyIntegrationTest extends IntegrationTestSupport {

    private static final int THREADS = 32;

    @Autowired
    private MeteringOperations metering;
    @Autowired
    private MeteringAdminOperations meteringAdmin;
    @Autowired
    private UserInvitationService invitationService;

    private static <T> List<T> runConcurrently(int threads, java.util.concurrent.Callable<T> task)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(threads, THREADS));
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 并发预扣不超卖_余额10元每次3元32线程恰好3成功() throws Exception {
        long tenant = newTenant();
        SubjectRef subject = new SubjectRef(tenant, 0, 0);
        meteringAdmin.recharge(new RechargeCommand(subject, new BigDecimal("10.000000"), "CNY",
                "CONC-RECHARGE"));

        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        runConcurrently(THREADS, () -> {
            String reservationId = "CONC-RES-" + System.nanoTime();
            try {
                metering.reserve(new ReserveCommand(reservationId, subject, new BigDecimal("3.000000"),
                        "CNY", Instant.now().plusSeconds(120), "biz"));
                success.incrementAndGet();
            } catch (BizException e) {
                insufficient.incrementAndGet();
            } catch (RuntimeException e) {
                unexpected.incrementAndGet();
            }
            return null;
        });

        assertThat(success.get()).isEqualTo(3);
        assertThat(insufficient.get()).isEqualTo(THREADS - 3);
        assertThat(unexpected.get()).isZero();
        var balance = metering.queryBalance(subject);
        assertThat(balance.balance()).isEqualByComparingTo("10.000000");
        assertThat(balance.frozen()).isEqualByComparingTo("9.000000");
        assertThat(countRows("SELECT COUNT(*) FROM t_metering_reservation WHERE account_id IN "
                + "(SELECT id FROM t_metering_account WHERE tenant_id = ?) AND status = 'PENDING'", tenant))
                .isEqualTo(3);
    }

    @Test
    void 同一预扣并发结算_只产生一条资金流水() throws Exception {
        long tenant = newTenant();
        SubjectRef subject = new SubjectRef(tenant, 0, 0);
        meteringAdmin.recharge(new RechargeCommand(subject, new BigDecimal("10.000000"), "CNY",
                "CONC-SETTLE-RECHARGE"));
        metering.reserve(new ReserveCommand("CONC-SETTLE-RES", subject, new BigDecimal("3.000000"),
                "CNY", Instant.now().plusSeconds(120), "biz"));

        AtomicInteger conflicts = new AtomicInteger();
        runConcurrently(8, () -> {
            try {
                metering.settle(new SettleCommand("CONC-SETTLE-RES", new BigDecimal("2.000000"),
                        "CNY", "biz"));
            } catch (BizException e) {
                conflicts.incrementAndGet();
            }
            return null;
        });

        assertThat(countRows("SELECT COUNT(*) FROM t_metering_ledger WHERE biz_no = 'settle:CONC-SETTLE-RES'"))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_metering_reservation "
                + "WHERE reservation_id = 'CONC-SETTLE-RES' AND status = 'SETTLED'")).isEqualTo(1);
        var balance = metering.queryBalance(subject);
        assertThat(balance.balance()).isEqualByComparingTo("8.000000");
        assertThat(balance.frozen()).isEqualByComparingTo("0.000000");
    }

    @Test
    void 并发充值同一bizNo_只入账一次() throws Exception {
        long tenant = newTenant();
        SubjectRef subject = new SubjectRef(tenant, 0, 0);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        runConcurrently(8, () -> {
            try {
                meteringAdmin.recharge(new RechargeCommand(subject, new BigDecimal("10.000000"),
                        "CNY", "CONC-RECHARGE-SAME"));
                successes.incrementAndGet();
            } catch (RuntimeException e) {
                failures.incrementAndGet();
            }
            return null;
        });

        assertThat(successes.get()).isGreaterThanOrEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_metering_ledger WHERE biz_no = 'CONC-RECHARGE-SAME'"))
                .isEqualTo(1);
        assertThat(metering.queryBalance(subject).balance()).isEqualByComparingTo("10.000000");
    }

    @Test
    void Redis共享令牌桶跨实例一致_多实例共享租户额度() {
        clearRedisKeys();
        long tenant = newTenant();
        LlmProperties properties = new LlmProperties(
                new LlmProperties.Mock(0, "mock-fail", "mock-flaky", "mock-slow", 200,
                        64, 4, 0, "mock-stream-mid-fail"),
                new LlmProperties.Timeouts(1000, 3000, 3000, 15000),
                new LlmProperties.Retry(2, 10, 20, 0),
                new LlmProperties.Circuit(50, 20, 30000, 5, 0, 60),
                new LlmProperties.RateLimit(60, 60000, 100, "redis"));
        var fallback = new RedisFallbackMetrics(new SimpleMeterRegistry());
        LlmRateLimiter instanceA = new LlmRateLimiter(properties, redis, fallback, null);
        LlmRateLimiter instanceB = new LlmRateLimiter(properties, redis, fallback, null);

        // rpm=60 => 桶容量 2：实例 A 用掉 2 次后，实例 B 必须被同一份额度拒绝
        instanceA.acquire("mock", tenant);
        instanceA.acquire("mock", tenant);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> instanceB.acquire("mock", tenant))
                .isInstanceOf(com.wherelee.base.modules.contracts.llm.LlmException.class)
                .satisfies(e -> assertThat(((com.wherelee.base.modules.contracts.llm.LlmException) e)
                        .error().category())
                        .isEqualTo(com.wherelee.base.modules.contracts.llm.LlmErrorCategory.RATE_LIMITED));
        // 另一个租户额度独立
        instanceB.acquire("mock", tenant + 1);
        clearRedisKeys();
    }

    @Test
    void 支付回调并发幂等_只产生一条事件与一条发件箱() throws Exception {
        wipePaymentTables();
        long tenant = newTenant();
        var created = asTenant(tenant, () -> paymentOperations.initiate(
                new com.wherelee.base.modules.contracts.payment.PaymentOperations.InitiateCommand(
                        null, "CONC_ORDER", unique("CONC-BIZ"), 5000, "CNY", "并发回调",
                        LocalDateTime.now().plusMinutes(15))));
        String paymentNo = created.paymentNo();
        String bizNo = queryOne("SELECT biz_no FROM t_payment_order WHERE payment_no = ?",
                String.class, paymentNo);
        var notification = signedNotification("CONC-EVT-" + paymentNo, bizNo,
                "PAYMENT_SUCCESS", 5000, "TRADE-" + paymentNo);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        runConcurrently(8, () -> {
            var result = asSystem(() -> paymentConfirmationService.accept(notification, paymentNo));
            if (result.duplicate()) {
                duplicate.incrementAndGet();
            } else if (result.success()) {
                success.incrementAndGet();
            }
            return null;
        });

        assertThat(success.get()).isEqualTo(1);
        assertThat(duplicate.get()).isEqualTo(7);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_event WHERE payment_no = ?", paymentNo))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE aggregate_no = ? AND tenant_id = ?",
                paymentNo, tenant)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_order WHERE payment_no = ? AND status = 'PAID'",
                paymentNo)).isEqualTo(1);
    }

    @Test
    void 发件箱并发抢占不重复投递() throws Exception {
        wipePaymentTables();
        long tenantA = newTenant();
        long tenantB = newTenant();
        List<String> aggregateNos = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String paymentNo = "CONC-OUTBOX-" + i;
            aggregateNos.add(paymentNo);
            long tenant = i % 2 == 0 ? tenantA : tenantB;
            asSystem(() -> {
                jdbc.update("""
                        INSERT INTO t_payment_outbox (tenant_id, event_type, aggregate_no, payload,
                                                      status, next_at)
                        VALUES (?, 'PAYMENT_SUCCEEDED', ?, '{"eventType":"PAYMENT_SUCCEEDED"}',
                                'PENDING', CURRENT_TIMESTAMP(3))
                        """, tenant, paymentNo);
                return null;
            });
        }

        ConcurrentLinkedQueue<Long> claimed = new ConcurrentLinkedQueue<>();
        runConcurrently(4, () -> {
            var rows = paymentOutboxRepository.claimDue(10);
            rows.forEach(row -> claimed.add(row.id()));
            return null;
        });

        List<Long> ids = new ArrayList<>(claimed);
        Set<Long> distinct = new HashSet<>(ids);
        assertThat(distinct).hasSameSizeAs(ids);
        assertThat(ids.size()).isBetween(10, 40);
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE status = 'SENDING'"))
                .isEqualTo(ids.size());

        // 用收集型 listener 投递：每个事件只应被投递一次
        Set<String> deliveredPaymentNos = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger deliveries = new AtomicInteger();
        var dispatcher = new com.wherelee.base.modules.payment.outbox.PaymentOutboxDispatcher(
                paymentOutboxRepository,
                List.of(event -> {
                    deliveredPaymentNos.add(event.paymentNo());
                    deliveries.incrementAndGet();
                }),
                new SimpleMeterRegistry(), 50, 5, 1, 60);
        asSystem(dispatcher::dispatchOnce);
        assertThat(deliveries.get()).isEqualTo(deliveredPaymentNos.size());
        assertThat(countRows("SELECT COUNT(*) FROM t_payment_outbox WHERE status = 'SENT'"))
                .isEqualTo(deliveredPaymentNos.size());
    }

    @Test
    void 邀请接受并发_同一令牌只成功一次() throws Exception {
        long tenant = newTenant();
        seedRole(tenant, "TUSER");
        String rawToken = "CONC-INVITE-TOKEN-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO t_user_invitation (tenant_id, token_hash, username, nickname, role_code,
                                               status, expires_at, created_by)
                VALUES (?, ?, ?, '并发邀请', 'TUSER', 'PENDING',
                        DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR), 1)
                """, tenant, sha256(rawToken), "conc_invited");
        var request = new UserInvitationDtos.AcceptRequest(rawToken, "GoodPass123", "GoodPass123");

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runConcurrently(8, () -> {
            try {
                asTenant(tenant, () -> invitationService.accept(request));
                success.incrementAndGet();
            } catch (BizException e) {
                rejected.incrementAndGet();
            }
            return null;
        });

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(7);
        assertThat(countRows("SELECT COUNT(*) FROM t_user WHERE tenant_id = ? AND username = 'conc_invited'",
                tenant)).isEqualTo(1);
        assertThat(queryOne("SELECT status FROM t_user_invitation WHERE tenant_id = ? AND username = 'conc_invited'",
                String.class, tenant)).isEqualTo("USED");
    }

    @Test
    void 登录节流Redis计数生效_达到阈值即拒绝() {
        clearRedisKeys();
        SecurityProperties properties = new SecurityProperties(List.of(), false, true, 3, 100, 10);
        LoginThrottleService throttle = new LoginThrottleService(redis, properties,
                new SimpleMeterRegistry(), new LocalRateLimiter(),
                new RedisFallbackMetrics(new SimpleMeterRegistry()));

        throttle.check("race_user", "1.2.3.4");
        throttle.recordFailure("race_user", "1.2.3.4");
        throttle.recordFailure("race_user", "1.2.3.4");
        throttle.check("race_user", "1.2.3.4");
        throttle.recordFailure("race_user", "1.2.3.4");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> throttle.check("race_user", "1.2.3.4"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("登录尝试过于频繁");

        // 登录成功后清理，额度恢复
        throttle.clear("race_user", "1.2.3.4");
        throttle.check("race_user", "1.2.3.4");
        clearRedisKeys();
    }
}
