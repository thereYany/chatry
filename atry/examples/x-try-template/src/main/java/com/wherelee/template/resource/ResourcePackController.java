package com.wherelee.template.resource;

import com.wherelee.base.modules.payment.PaymentConfirmationService;
import com.wherelee.base.modules.payment.PaymentNotification;
import com.wherelee.base.modules.payment.outbox.PaymentOutboxDispatcher;
import com.wherelee.base.modules.contracts.payment.PaymentOperations;
import com.wherelee.base.modules.platform.aop.PermissionCodes;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 示例垂直域：资料包。支付/回调/履约事件全部复用 atry 内核。 */
@RestController
@RequestMapping("/api/resource-packs")
public class ResourcePackController {

    private final JdbcTemplate jdbc;
    private final PaymentOperations paymentOperations;
    private final PaymentOutboxDispatcher outboxDispatcher;
    private final PaymentConfirmationService confirmationService;

    public ResourcePackController(JdbcTemplate jdbc, PaymentOperations paymentOperations,
                                  PaymentOutboxDispatcher outboxDispatcher,
                                  PaymentConfirmationService confirmationService) {
        this.jdbc = jdbc;
        this.paymentOperations = paymentOperations;
        this.outboxDispatcher = outboxDispatcher;
        this.confirmationService = confirmationService;
    }

    @GetMapping
    @RequirePermission(PermissionCodes.ADMIN_USER_MANAGE)
    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT id, title, description, price_cents AS priceCents FROM t_resource_pack "
                        + "WHERE tenant_id = 0 AND status = 'ON_SALE' ORDER BY id");
    }

    @PostMapping("/{packId}/orders")
    @Transactional
    @RequirePermission(PermissionCodes.ADMIN_USER_MANAGE)
    public Map<String, Object> createOrder(@PathVariable Long packId,
                                           @RequestAttribute("userId") Long userId) {
        Map<String, Object> pack = jdbc.queryForMap(
                "SELECT price_cents, title FROM t_resource_pack WHERE id = ? AND tenant_id = 0", packId);
        long amount = ((Number) pack.get("price_cents")).longValue();
        String orderNo = "RP" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("""
                INSERT INTO t_resource_pack_order (tenant_id, order_no, pack_id, user_id, amount_cents, status)
                VALUES (0, ?, ?, ?, ?, 'CREATED')
                """, orderNo, packId, userId, amount);
        PaymentOperations.InitiateResult payment = paymentOperations.initiate(
                new PaymentOperations.InitiateCommand(null, "RESOURCE_PACK", orderNo, amount, "CNY",
                        String.valueOf(pack.get("title")), LocalDateTime.now().plusMinutes(15)));
        jdbc.update("UPDATE t_resource_pack_order SET status = 'PAYING', payment_no = ? WHERE order_no = ?",
                payment.paymentNo(), orderNo);
        return Map.of("orderNo", orderNo, "paymentNo", payment.paymentNo(),
                "cashierUrl", String.valueOf(payment.cashierUrl()), "status", payment.status());
    }

    @GetMapping("/orders/{orderNo}")
    @RequirePermission(PermissionCodes.ADMIN_USER_MANAGE)
    public Map<String, Object> order(@PathVariable String orderNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_no AS orderNo, status, payment_no AS paymentNo FROM t_resource_pack_order "
                        + "WHERE order_no = ? AND tenant_id = 0", orderNo);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        return rows.get(0);
    }

    /** 仅 Mock 渠道下用于本地验证：模拟支付成功并触发事件投递。 */
    @PostMapping("/orders/{orderNo}/mock-pay")
    @RequirePermission(PermissionCodes.PAYMENT_MOCK)
    public Map<String, Object> mockPay(@PathVariable String orderNo) {
        PaymentOperations.PaymentView payment = paymentOperations.findByBiz("RESOURCE_PACK", orderNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "支付单不存在"));
        confirmationService.accept(new PaymentNotification("MOCK", "DEV:" + orderNo + ":PAID", orderNo,
                "PAYMENT_SUCCESS", payment.amountCents(), payment.providerTradeNo(), null));
        outboxDispatcher.dispatchOnce();
        return Map.of("orderNo", orderNo, "paymentNo", payment.paymentNo(),
                "paymentStatus", paymentOperations.findByBiz("RESOURCE_PACK", orderNo)
                        .map(PaymentOperations.PaymentView::status).orElse("UNKNOWN"));
    }

    @GetMapping("/grants/me")
    @RequirePermission(PermissionCodes.ADMIN_USER_MANAGE)
    public List<Map<String, Object>> myGrants(@RequestAttribute("userId") Long userId) {
        return jdbc.queryForList("""
                SELECT e.pack_id AS packId, p.title FROM t_resource_pack_grant e
                JOIN t_resource_pack p ON p.id = e.pack_id
                WHERE e.tenant_id = 0 AND e.user_id = ? ORDER BY e.id
                """, userId);
    }
}
