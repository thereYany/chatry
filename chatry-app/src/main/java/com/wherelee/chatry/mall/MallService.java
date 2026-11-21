package com.wherelee.chatry.mall;

import com.wherelee.base.modules.contracts.payment.PaymentOperations;
import com.wherelee.base.modules.payment.PaymentStatus;
import com.wherelee.base.modules.payment.mock.MockCashierService;
import com.wherelee.base.modules.payment.PaymentConfirmationService;
import com.wherelee.base.modules.payment.PaymentNotification;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxDispatcher;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.tenant.TenantContext;
import com.wherelee.chatry.credits.ChatBalanceView;
import com.wherelee.chatry.credits.CreditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 商城：额度包购买 + 秒杀。
 *
 * <p>职责边界：商品/订单/库存是垂直域；支付单与回调、发件箱、履约事件、额度入账全部复用内核。
 * 支付成功事件由 {@link MallFulfillmentListener} 消费，充值幂等（bizNo = mall:orderNo）。</p>
 */
@Service
public class MallService {

    private static final Logger log = LoggerFactory.getLogger(MallService.class);

    public static final String BIZ_TYPE = "MALL_CREDIT";
    private static final String STATUS_ON_SALE = "ON_SALE";
    private static final String STATUS_OFF_SHELF = "OFF_SHELF";
    private static final DateTimeFormatter ORDER_TIME =
            DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(ZoneId.systemDefault());

    private final MallSkuRepository skus;
    private final MallOrderRepository orders;
    private final PaymentOperations payment;
    private final PaymentConfirmationService confirmation;
    private final PaymentOutboxDispatcher outbox;
    private final CreditService credits;
    private final MockPaymentGuard mockPaymentGuard;
    private final MockCashierService mockCashier;
    private final MallProperties properties;

    public MallService(MallSkuRepository skus, MallOrderRepository orders, PaymentOperations payment,
                       PaymentConfirmationService confirmation, PaymentOutboxDispatcher outbox,
                       CreditService credits, MockPaymentGuard mockPaymentGuard,
                       MockCashierService mockCashier, MallProperties properties) {
        this.skus = skus;
        this.orders = orders;
        this.payment = payment;
        this.confirmation = confirmation;
        this.outbox = outbox;
        this.credits = credits;
        this.mockPaymentGuard = mockPaymentGuard;
        this.mockCashier = mockCashier;
        this.properties = properties;
    }

    // ---------- 用户侧 ----------

    public List<SkuView> listOnSale(long tenantId) {
        Instant now = Instant.now();
        return skus.listOnSale(tenantId).stream().map(sku -> SkuView.from(sku, now)).toList();
    }

    public List<OrderView> myOrders(long tenantId, long userId) {
        return orders.listByUser(tenantId, userId, 50).stream().map(OrderView::from).toList();
    }

    /**
     * 下单：校验商品/秒杀窗口/限购 → 条件扣库存 → 建订单 → 创建支付单。
     *
     * <p>秒杀与限购 1 的商品用 limit_key 唯一键挡并发重复下单；库存扣减失败即售罄。</p>
     */
    public CreateOrderResponse createOrder(long tenantId, long userId, long skuId) {
        Instant now = Instant.now();
        MallSku sku = skus.findById(tenantId, skuId)
                .orElseThrow(() -> BizException.notFound("商品不存在"));
        if (!STATUS_ON_SALE.equals(sku.status())) {
            throw BizException.badRequest("商品已下架");
        }
        boolean flash = sku.flashSale();
        if (flash) {
            boolean inWindow = sku.flashStartAt() != null && sku.flashEndAt() != null
                    && !now.isBefore(sku.flashStartAt()) && now.isBefore(sku.flashEndAt());
            if (!inWindow) {
                throw BizException.badRequest("秒杀未开始或已结束");
            }
            if (!sku.unlimitedStock() && sku.stock() <= 0) {
                throw new BizException(HttpStatus.CONFLICT, "已售罄");
            }
        }
        long priceCents = sku.effectivePriceCents(now);
        int limit = sku.perUserLimit();
        if (limit > 1 && orders.countActive(tenantId, userId, skuId) >= limit) {
            throw BizException.badRequest("已达每人限购数量");
        }
        if (!sku.unlimitedStock() && skus.decrementStock(tenantId, skuId) == 0) {
            throw new BizException(HttpStatus.CONFLICT, "已售罄");
        }

        String orderNo = newOrderNo();
        String limitKey = limit == 1 ? ((flash ? "flash:" : "limit:") + skuId + ":" + userId) : null;
        try {
            orders.insert(tenantId, orderNo, userId, skuId, sku.title(), priceCents,
                    sku.creditCents(), flash, limitKey,
                    Instant.now().plusSeconds(properties.orderExpireMinutes() * 60L));
        } catch (DuplicateKeyException e) {
            if (!sku.unlimitedStock()) {
                skus.restoreStock(tenantId, skuId);
            }
            throw new BizException(HttpStatus.CONFLICT, "已达限购数量");
        }

        try {
            PaymentOperations.InitiateResult initiated = payment.initiate(new PaymentOperations.InitiateCommand(
                    null, BIZ_TYPE, orderNo, priceCents, "CNY", sku.title(),
                    LocalDateTime.now().plusMinutes(properties.orderExpireMinutes())));
            orders.markPaying(tenantId, orderNo, initiated.paymentNo());
            log.info("mall.order.created tenantId={} userId={} orderNo={} skuId={} flash={} amountCents={}",
                    tenantId, userId, orderNo, skuId, flash, priceCents);
            return new CreateOrderResponse(orderNo, initiated.paymentNo(), initiated.cashierUrl(),
                    "PAYING", priceCents, sku.creditCents(), flash);
        } catch (RuntimeException e) {
            orders.closeIfOpen(tenantId, orderNo);
            if (!sku.unlimitedStock()) {
                skus.restoreStock(tenantId, skuId);
            }
            throw e;
        }
    }

    /**
     * 模拟支付（仅 mock 渠道演示用）：确认支付成功 → 投递发件箱 → 履约监听器幂等充值。
     *
     * <p>真实渠道下由异步回调/主动查单触发，本接口只用于本地演示。</p>
     */
    public PayResponse mockPay(long tenantId, long userId, String orderNo) {
        mockPaymentGuard.requireEnabled();
        MallOrder order = orders.findByNo(tenantId, orderNo)
                .filter(found -> found.userId() == userId)
                .orElseThrow(() -> BizException.notFound("订单不存在"));
        if (MallOrder.STATUS_CLOSED.equals(order.status())
                || MallOrder.STATUS_PAID_AFTER_CLOSE.equals(order.status())) {
            throw new BizException(HttpStatus.CONFLICT, "订单已关闭，不能支付");
        }
        BigDecimal balance = credits.balance(tenantId, userId).balance();
        if (MallOrder.STATUS_PAID.equals(order.status())) {
            return new PayResponse(orderNo, order.status(), balance);
        }
        PaymentOperations.PaymentView pay = payment.findByBiz(BIZ_TYPE, orderNo)
                .orElseThrow(() -> BizException.notFound("支付单不存在"));
        if (PaymentStatus.PAID.equals(pay.status())) {
            // 支付单已成功但订单尚未履约（例如上一次 outbox 尚未处理），先推动一次再回读。
            outbox.dispatchOnce();
            MallOrder latest = orders.findByNo(tenantId, orderNo)
                    .orElseThrow(() -> BizException.notFound("订单不存在"));
            return new PayResponse(orderNo, latest.status(), credits.balance(tenantId, userId).balance());
        }
        if (!PaymentStatus.CREATED.equals(pay.status()) && !PaymentStatus.PAYING.equals(pay.status())) {
            throw new BizException(HttpStatus.CONFLICT, "支付单状态 " + pay.status() + " 不允许支付");
        }
        // 走 Mock 收银台：生成渠道流水 → 签名 → 回调入口验签 → 真实确认链路
        var confirmationResult = mockCashier.simulate(pay.paymentNo());
        if (confirmationResult == null) {
            // 延迟场景：回调稍后投递，当前仍是待支付
            return new PayResponse(orderNo, orders.findByNo(tenantId, orderNo)
                    .map(MallOrder::status).orElse("PAYING"), credits.balance(tenantId, userId).balance());
        }
        if (!confirmationResult.success()) {
            throw new BizException(HttpStatus.CONFLICT, confirmationResult.message());
        }
        outbox.dispatchOnce();
        MallOrder latest = orders.findByNo(tenantId, orderNo)
                .orElseThrow(() -> BizException.notFound("订单不存在"));
        if (!MallOrder.STATUS_PAID.equals(latest.status())
                && !MallOrder.STATUS_PAID_AFTER_CLOSE.equals(latest.status())) {
            throw new BizException(HttpStatus.CONFLICT, "订单状态异常：" + latest.status());
        }
        return new PayResponse(orderNo, latest.status(), credits.balance(tenantId, userId).balance());
    }

    // ---------- 管理端 ----------

    public List<SkuView> listAll(long tenantId) {
        Instant now = Instant.now();
        return skus.listAll(tenantId).stream().map(sku -> SkuView.from(sku, now)).toList();
    }

    public SkuView createSku(long tenantId, CreateSkuRequest request) {
        validate(request);
        boolean flash = Boolean.TRUE.equals(request.flashSale());
        MallSku sku = new MallSku(0, tenantId, request.code().trim(), request.title().trim(),
                request.description(), request.priceCents(), request.creditCents(),
                request.stock() == null ? -1 : request.stock(),
                request.perUserLimit() == null ? 0 : request.perUserLimit(),
                flash, flash ? request.flashPriceCents() : null,
                flash ? request.flashStartAt() : null,
                flash ? request.flashEndAt() : null,
                STATUS_ON_SALE, request.sort() == null ? 50 : request.sort());
        long id;
        try {
            id = skus.insert(sku);
        } catch (DuplicateKeyException e) {
            throw BizException.badRequest("商品编码已存在");
        }
        log.info("mall.sku.created tenantId={} id={} code={} flash={}", tenantId, id, sku.code(), flash);
        return SkuView.from(skus.findById(tenantId, id)
                .orElseThrow(() -> new IllegalStateException("SKU 创建后读取失败")), Instant.now());
    }

    public SkuView updateStatus(long tenantId, long id, String status) {
        String target = status == null ? "" : status.trim().toUpperCase();
        if (!STATUS_ON_SALE.equals(target) && !STATUS_OFF_SHELF.equals(target)) {
            throw BizException.badRequest("状态只支持 ON_SALE / OFF_SHELF");
        }
        if (skus.updateStatus(tenantId, id, target) == 0) {
            throw BizException.notFound("商品不存在");
        }
        return SkuView.from(skus.findById(tenantId, id)
                .orElseThrow(() -> new IllegalStateException("SKU 更新后读取失败")), Instant.now());
    }

    // ---------- 后台任务 ----------

    /**
     * 关闭过期未支付订单并回补库存（系统作用域跨租户扫描）。
     *
     * @return 本次关闭的订单数
     */
    public int closeExpiredOrders() {
        Instant now = Instant.now();
        List<MallOrderRepository.ExpiredRow> expired =
                TenantContext.runAsSystem(() -> orders.findExpiredAcrossTenants(now, 100));
        int closed = 0;
        for (MallOrderRepository.ExpiredRow row : expired) {
            long previous = TenantContext.current();
            TenantContext.set(row.tenantId());
            try {
                if (orders.closeIfOpen(row.tenantId(), row.orderNo()) > 0) {
                    skus.findById(row.tenantId(), row.skuId()).ifPresent(sku -> {
                        if (!sku.unlimitedStock()) {
                            skus.restoreStock(row.tenantId(), sku.id());
                        }
                    });
                    closed++;
                }
            } finally {
                TenantContext.set(previous);
            }
        }
        if (closed > 0) {
            log.info("mall.order.expired_closed count={}", closed);
        }
        return closed;
    }

    // ---------- 内部 ----------

    private void validate(CreateSkuRequest request) {
        if (request == null || request.code() == null || request.code().isBlank()) {
            throw BizException.badRequest("商品编码不能为空");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw BizException.badRequest("商品标题不能为空");
        }
        if (request.priceCents() == null || request.priceCents() < 0) {
            throw BizException.badRequest("原价必须 >= 0");
        }
        if (request.creditCents() == null || request.creditCents() <= 0) {
            throw BizException.badRequest("到账额度必须 > 0");
        }
        if (request.stock() != null && request.stock() < -1) {
            throw BizException.badRequest("库存必须 >= -1（-1 表示不限量）");
        }
        if (request.perUserLimit() != null && request.perUserLimit() < 0) {
            throw BizException.badRequest("限购数量必须 >= 0");
        }
        if (Boolean.TRUE.equals(request.flashSale())) {
            if (request.flashPriceCents() == null || request.flashPriceCents() <= 0) {
                throw BizException.badRequest("秒杀价必须 > 0");
            }
            if (request.flashStartAt() == null || request.flashEndAt() == null
                    || !request.flashStartAt().isBefore(request.flashEndAt())) {
                throw BizException.badRequest("秒杀时间窗非法");
            }
        }
    }

    private String newOrderNo() {
        return "M" + ORDER_TIME.format(Instant.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}
