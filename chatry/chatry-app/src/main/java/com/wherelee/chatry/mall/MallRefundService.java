package com.wherelee.chatry.mall;

import com.wherelee.base.modules.contracts.payment.RefundOperations;
import com.wherelee.base.modules.contracts.metering.AdjustCommand;
import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.chatry.credits.ChatBalanceView;
import com.wherelee.chatry.credits.CreditService;
import com.wherelee.chatry.ledger.FundLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * 退款与售后逆向（商品侧）：可退金额计算、发起退款、退款成功后的额度扣回与订单状态。
 *
 * <p>可退金额口径（与用户可理解的规则一致）：</p>
 * <ul>
 *   <li>{@code PAID_AFTER_CLOSE}（钱到账、额度没发）：可退全部实付；</li>
 *   <li>{@code PAID}：只能退「未消费额度」折算的实付金额 = 未消费额度 × 实付 / 到账额度；</li>
 *   <li>已退金额从可退金额里扣掉，不能超退。</li>
 * </ul>
 *
 * <p>退款成功后：扣回对应额度（允许扣到 0）、写资金账本反向分录、订单标记全额/部分退款。
 * 幂等：退款单号由订单号派生，重复事件按退款单号去重。</p>
 */
@Service
public class MallRefundService {

    private static final Logger log = LoggerFactory.getLogger(MallRefundService.class);
    private static final String CURRENCY = "CNY";

    private final MallOrderRepository orders;
    private final RefundOperations refunds;
    private final MeteringAdminOperations meteringAdmin;
    private final CreditService credits;
    private final FundLedgerService fundLedger;

    public MallRefundService(MallOrderRepository orders, RefundOperations refunds,
                             MeteringAdminOperations meteringAdmin, CreditService credits,
                             FundLedgerService fundLedger) {
        this.orders = orders;
        this.refunds = refunds;
        this.meteringAdmin = meteringAdmin;
        this.credits = credits;
        this.fundLedger = fundLedger;
    }

    /** 可退金额（分）。 */
    public long refundableCents(long tenantId, MallOrder order) {
        if (MallOrder.STATUS_PAID_AFTER_CLOSE.equals(order.status())) {
            return Math.max(0, order.amountCents() - order.refundedCents());
        }
        if (!MallOrder.STATUS_PAID.equals(order.status())) {
            return 0L;
        }
        ChatBalanceView balance = credits.balance(tenantId, order.userId());
        BigDecimal unconsumed = balance.balance().max(BigDecimal.ZERO)
                .min(BigDecimal.valueOf(order.creditCents(), 2));
        if (order.creditCents() <= 0) {
            return 0L;
        }
        // 直接在「分」上算：可退 = 实付 × 未消费额度 / 到账额度，向下取整避免超退
        long refundable = BigDecimal.valueOf(order.amountCents()).multiply(unconsumed)
                .divide(BigDecimal.valueOf(order.creditCents(), 2), 0, RoundingMode.DOWN)
                .longValue();
        return Math.max(0, Math.min(refundable, order.amountCents() - order.refundedCents()));
    }

    /** 管理员发起退款；返回退款单号。 */
    public RefundOperations.RefundView requestRefund(long tenantId, String orderNo, Long amountCents,
                                                     String reason, Long operatorId) {
        MallOrder order = orders.findByNo(tenantId, orderNo)
                .orElseThrow(() -> BizException.notFound("订单不存在"));
        if (order.paymentNo() == null || order.paymentNo().isBlank()) {
            throw new BizException(HttpStatus.CONFLICT, "订单没有支付单，不能退款");
        }
        long refundable = refundableCents(tenantId, order);
        if (refundable <= 0) {
            throw new BizException(HttpStatus.CONFLICT, "该订单当前没有可退金额");
        }
        long amount = amountCents == null ? refundable : amountCents;
        if (amount <= 0 || amount > refundable) {
            throw BizException.badRequest("退款金额需在 1 ~ " + refundable + " 分之间");
        }
        String refundNo = "MR" + orderNo + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        if (MallOrder.STATUS_PAID_AFTER_CLOSE.equals(order.status())) {
            // 保留 PAID_AFTER_CLOSE：退款成功时据此判断「额度从未发放，不需扣回」
            orders.attachRefundNo(tenantId, orderNo, refundNo);
        } else {
            orders.markRefunding(tenantId, orderNo, refundNo);
        }
        RefundOperations.RefundView view = refunds.request(new RefundOperations.RefundCommand(
                refundNo, order.paymentNo(), amount, reason == null || reason.isBlank()
                ? "管理员退款" : reason, operatorId));
        log.info("mall.refund.requested tenantId={} orderNo={} refundNo={} amountCents={} status={}",
                tenantId, orderNo, view.refundNo(), amount, view.status());
        return view;
    }

    /** 退款成功（由 REFUND_SUCCEEDED 事件驱动）：扣回额度 + 记账 + 订单状态。 */
    public void onRefundSucceeded(long tenantId, String orderNo, long amountCents, String refundNo) {
        MallOrder order = orders.findByNo(tenantId, orderNo).orElse(null);
        if (order == null) {
            log.warn("mall.refund.order_missing tenantId={} orderNo={}", tenantId, orderNo);
            return;
        }
        long deductCreditCents = creditToDeduct(order, amountCents);
        if (deductCreditCents > 0) {
            meterApp(tenantId, order.userId(), deductCreditCents, refundNo);
            fundLedger.postRefund(tenantId, order.userId(), amountCents, order.creditCents(),
                    deductCreditCents, refundNo);
        } else {
            fundLedger.postRefundNoCredit(tenantId, amountCents, refundNo);
        }
        int updated = orders.addRefunded(tenantId, orderNo, amountCents, refundNo);
        if (updated == 0) {
            log.warn("mall.refund.order_update_missing tenantId={} orderNo={}", tenantId, orderNo);
        }
        log.info("mall.refund.succeeded tenantId={} orderNo={} amountCents={} deductCreditCents={} refundNo={}",
                tenantId, orderNo, amountCents, deductCreditCents, refundNo);
    }

    /** 退款失败：订单回到可重试状态，记录退款单号。 */
    public void onRefundFailed(long tenantId, String orderNo, String refundNo, String message) {
        orders.markRefundFailed(tenantId, orderNo, refundNo);
        log.warn("mall.refund.failed tenantId={} orderNo={} refundNo={} message={}",
                tenantId, orderNo, refundNo, message);
    }

    /** 待自动退款订单（钱已到、额度未发）。 */
    public List<MallOrder> pendingAfterCloseRefunds(long tenantId, int limit) {
        return orders.listByStatus(tenantId, MallOrder.STATUS_PAID_AFTER_CLOSE, limit);
    }

    private long creditToDeduct(MallOrder order, long refundAmountCents) {
        if (MallOrder.STATUS_PAID_AFTER_CLOSE.equals(order.status()) || order.creditCents() <= 0) {
            return 0L;
        }
        long refundedTotal = order.refundedCents() + refundAmountCents;
        long credit = BigDecimal.valueOf(order.creditCents())
                .multiply(BigDecimal.valueOf(refundedTotal))
                .divide(BigDecimal.valueOf(order.amountCents()), 0, RoundingMode.DOWN)
                .longValue();
        long alreadyDeducted = order.refundedCents() * order.creditCents() / order.amountCents();
        return Math.max(0, credit - alreadyDeducted);
    }

    private void meterApp(long tenantId, long userId, long deductCents, String refundNo) {
        BigDecimal amount = BigDecimal.valueOf(deductCents, 2).negate();
        meteringAdmin.adjust(new AdjustCommand(
                new SubjectRef(tenantId, 0L, userId), amount, CURRENCY,
                "mall-refund:" + refundNo, true, "REFUND_DEDUCT"));
    }
}
