package com.wherelee.base.modules.metering;

import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.ReleaseCommand;
import com.wherelee.base.modules.contracts.metering.ReservationView;
import com.wherelee.base.modules.contracts.metering.ReserveCommand;
import com.wherelee.base.modules.contracts.metering.SettleCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.contracts.metering.UsageCommand;
import com.wherelee.base.modules.contracts.metering.UsageQuery;
import com.wherelee.base.modules.contracts.metering.UsageView;
import com.wherelee.base.modules.metering.store.MeteringStore;
import com.wherelee.base.modules.platform.common.BizException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * P4：通用计量计费核心。
 *
 * <p>recordUsage 幂等入事件 → 按 occurredAt 价格版本计价 → Charge；
 * reserve/settle/release 用行锁 + 账户 frozen 防并发超卖；账本不可变、余额由流水派生。</p>
 */
@Service
public class MeteringService implements MeteringOperations {

    private static final String CURRENCY_FALLBACK = "CNY";

    private final MeteringStore store;

    public MeteringService(MeteringStore store) {
        this.store = store;
    }

    @Override
    @Transactional
    public void recordUsage(UsageCommand command) {
        validateUsage(command);
        if (!store.insertUsage(command)) {
            return; // 幂等：重复 eventId 不重复入账
        }
        String model = command.dimensions() == null ? null : command.dimensions().get("model");
        MeteringStore.PriceRow price = store.findPrice(command.meterKey(), command.occurredAt(), model)
                .orElseThrow(() -> new BizException(HttpStatus.CONFLICT,
                        "没有生效价格：meter=" + command.meterKey()));
        BigDecimal amount = command.quantity().multiply(price.unitPrice()).setScale(6, RoundingMode.CEILING);
        String snapshot = "{\"unitPrice\":" + price.unitPrice().toPlainString()
                + ",\"currency\":\"" + price.currency() + "\",\"priceVersion\":" + price.priceVersion()
                + ",\"rateCardId\":" + price.rateCardId() + "}";
        long chargeId = store.insertCharge(command.eventId(), command.meterKey(), command.quantity(),
                amount, price.currency(), price.priceVersion(), price.rateCardId(), snapshot);
        store.markEventRated(command.eventId(), chargeId);
    }

    @Override
    public BigDecimal estimateCost(String meterKey, BigDecimal quantity, Instant occurredAt) {
        return estimateCost(meterKey, quantity, occurredAt, Map.of());
    }

    @Override
    public BigDecimal estimateCost(String meterKey, BigDecimal quantity, Instant occurredAt,
                                   Map<String, String> dimensions) {
        if (meterKey == null || meterKey.isBlank() || quantity == null || occurredAt == null) {
            throw BizException.badRequest("estimateCost 参数不完整");
        }
        String model = dimensions == null ? null : dimensions.get("model");
        MeteringStore.PriceRow price = store.findPrice(meterKey, occurredAt, model)
                .orElseThrow(() -> new BizException(HttpStatus.CONFLICT, "没有生效价格：meter=" + meterKey));
        return quantity.multiply(price.unitPrice()).setScale(6, RoundingMode.CEILING);
    }

    @Override
    @Transactional
    public ReservationView reserve(ReserveCommand command) {
        validateReserve(command);
        MeteringStore.AccountRow account = store.findAccount(command.subject(), command.currency(), true)
                .orElseThrow(() -> new BizException(HttpStatus.PAYMENT_REQUIRED, "账户不存在"));
        if (!"ACTIVE".equals(account.status())) {
            throw new BizException(HttpStatus.PAYMENT_REQUIRED, "账户不可用：" + account.status());
        }
        BigDecimal available = account.balance().subtract(account.frozen());
        if (available.compareTo(command.amount()) < 0) {
            throw new BizException(HttpStatus.PAYMENT_REQUIRED, "可用额度不足");
        }
        store.addFrozen(account.id(), command.amount());
        store.insertReservation(command.reservationId(), account.id(), command.amount(),
                command.currency(), command.expiresAt(), command.bizRef());
        return new ReservationView(command.reservationId(), "PENDING", command.amount(), command.currency());
    }

    @Override
    @Transactional
    public void settle(SettleCommand command) {
        if (command.reservationId() == null || command.reservationId().isBlank()) {
            throw BizException.badRequest("reservationId 不能为空");
        }
        BigDecimal actual = command.actualAmount() == null ? BigDecimal.ZERO : command.actualAmount();
        if (actual.signum() < 0) {
            throw BizException.badRequest("actualAmount 不能为负");
        }
        MeteringStore.ReservationRow reservation = store.findReservationForUpdate(command.reservationId())
                .orElseThrow(() -> new BizException(HttpStatus.NOT_FOUND, "预扣记录不存在"));
        if ("SETTLED".equals(reservation.status())) {
            return; // 幂等
        }
        if (!"PENDING".equals(reservation.status())) {
            throw new BizException(HttpStatus.CONFLICT, "预扣状态不可结算：" + reservation.status());
        }
        MeteringStore.AccountRow account = store.findAccountById(reservation.accountId(), true)
                .orElseThrow(() -> new BizException(HttpStatus.NOT_FOUND, "账户不存在"));
        if (!store.settleAccount(account.id(), reservation.amount(), actual)) {
            throw new BizException(HttpStatus.PAYMENT_REQUIRED, "结算失败：余额/冻结不足");
        }
        BigDecimal balanceAfter = account.balance().subtract(actual);
        store.insertLedger(account.id(), "DEBIT", actual, reservation.currency(),
                "SETTLE", "settle:" + reservation.reservationId(), balanceAfter);
        store.markReservation(reservation.reservationId(), "SETTLED");
    }

    @Override
    @Transactional
    public void release(ReleaseCommand command) {
        if (command.reservationId() == null || command.reservationId().isBlank()) {
            throw BizException.badRequest("reservationId 不能为空");
        }
        MeteringStore.ReservationRow reservation = store.findReservationForUpdate(command.reservationId())
                .orElseThrow(() -> new BizException(HttpStatus.NOT_FOUND, "预扣记录不存在"));
        if ("RELEASED".equals(reservation.status()) || "EXPIRED".equals(reservation.status())) {
            return; // 幂等
        }
        if (!"PENDING".equals(reservation.status())) {
            throw new BizException(HttpStatus.CONFLICT, "预扣状态不可释放：" + reservation.status());
        }
        store.releaseAccount(reservation.accountId(), reservation.amount());
        store.markReservation(reservation.reservationId(), "RELEASED");
    }

    @Override
    public BalanceView queryBalance(SubjectRef subject) {
        validateSubject(subject);
        return store.findAnyAccount(subject)
                .map(account -> new BalanceView(subject, account.balance(), account.frozen(), account.currency()))
                .orElseGet(() -> new BalanceView(subject, BigDecimal.ZERO, BigDecimal.ZERO, CURRENCY_FALLBACK));
    }

    @Override
    public List<UsageView> queryUsage(UsageQuery query) {
        if (query == null) {
            throw BizException.badRequest("查询条件不能为空");
        }
        validateSubject(query.subject());
        Instant from = query.from() == null ? Instant.EPOCH : query.from();
        Instant to = query.to() == null ? Instant.now() : query.to();
        int limit = query.limit() <= 0 ? 100 : Math.min(query.limit(), 500);
        return store.queryUsage(query.subject(), from, to, limit);
    }

    private void validateUsage(UsageCommand command) {
        if (command == null || command.eventId() == null || command.eventId().isBlank()) {
            throw BizException.badRequest("eventId 不能为空");
        }
        if (command.meterKey() == null || command.meterKey().isBlank()) {
            throw BizException.badRequest("meterKey 不能为空");
        }
        if (command.quantity() == null || command.quantity().signum() < 0) {
            throw BizException.badRequest("quantity 不能为负");
        }
        validateSubject(command.subject());
        if (command.occurredAt() == null) {
            throw BizException.badRequest("occurredAt 不能为空");
        }
    }

    private void validateReserve(ReserveCommand command) {
        if (command == null || command.reservationId() == null || command.reservationId().isBlank()) {
            throw BizException.badRequest("reservationId 不能为空");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw BizException.badRequest("预扣金额必须大于 0");
        }
        if (command.currency() == null || command.currency().isBlank()) {
            throw BizException.badRequest("currency 不能为空");
        }
        if (command.expiresAt() == null || !command.expiresAt().isAfter(Instant.now())) {
            throw BizException.badRequest("expiresAt 必须晚于当前时间");
        }
        validateSubject(command.subject());
    }

    private void validateSubject(SubjectRef subject) {
        if (subject == null) {
            throw BizException.badRequest("subject 不能为空");
        }
    }
}
