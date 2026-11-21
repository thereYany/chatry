package com.wherelee.chatry.mall;

import com.wherelee.base.modules.contracts.payment.RefundOperations;
import com.wherelee.base.modules.platform.aop.AdminAudit;
import com.wherelee.base.modules.platform.aop.PermissionCodes;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端：退款发起与退款记录。 */
@RestController
@RequestMapping("/api/admin")
public class AdminRefundController {

    private final MallRefundService refunds;
    private final MallOrderRepository orders;
    private final RefundOperations refundOperations;

    public AdminRefundController(MallRefundService refunds, MallOrderRepository orders,
                                 RefundOperations refundOperations) {
        this.refunds = refunds;
        this.orders = orders;
        this.refundOperations = refundOperations;
    }

    /** 可退款订单（含可退金额，便于运营确认）。 */
    @GetMapping("/mall/refunds/candidates")
    @RequirePermission(PermissionCodes.ADMIN_REFUND_MANAGE)
    public List<RefundCandidate> candidates(@RequestAttribute("tenantId") long tenantId,
                                           @RequestParam(defaultValue = "20") int size) {
        int limit = Math.max(1, Math.min(size, 50));
        return orders.listRefundable(tenantId, limit).stream()
                .map(order -> new RefundCandidate(order.orderNo(), order.skuTitle(),
                        order.amountCents(), order.creditCents(), order.refundedCents(),
                        order.status(), order.paymentNo(),
                        refunds.refundableCents(tenantId, order)))
                .toList();
    }

    @PostMapping("/mall/orders/{orderNo}/refund")
    @RequirePermission(PermissionCodes.ADMIN_REFUND_MANAGE)
    @AdminAudit(action = "ORDER_REFUND", targetType = "ORDER")
    public RefundOperations.RefundView refund(@RequestAttribute("tenantId") long tenantId,
                                              @RequestAttribute("userId") long operatorId,
                                              @PathVariable String orderNo,
                                              @Valid @RequestBody RefundBody body) {
        return refunds.requestRefund(tenantId, orderNo, body.amountCents(),
                body.reason(), operatorId);
    }

    /** 某支付单下的退款记录（含处理中/失败/成功）。 */
    @GetMapping("/mall/payments/{paymentNo}/refunds")
    @RequirePermission(PermissionCodes.ADMIN_REFUND_MANAGE)
    public List<RefundOperations.RefundView> refundRecords(@PathVariable String paymentNo) {
        return refundOperations.listByPayment(paymentNo);
    }

    public record RefundBody(@Positive(message = "退款金额必须大于 0") Long amountCents,
                             @Size(max = 200, message = "退款原因过长") String reason) {
    }

    public record RefundCandidate(String orderNo, String skuTitle, long amountCents,
                                  long creditCents, long refundedCents, String status,
                                  String paymentNo, long refundableCents) {
    }
}
