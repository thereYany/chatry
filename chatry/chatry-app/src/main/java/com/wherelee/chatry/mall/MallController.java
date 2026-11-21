package com.wherelee.chatry.mall;

import com.wherelee.base.modules.platform.aop.Idempotent;
import com.wherelee.base.modules.platform.common.BizException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 商城（用户侧）：额度包列表、下单、模拟支付、我的订单。 */
@RestController
@RequestMapping("/api/mall")
public class MallController {

    private final MallService mall;

    public MallController(MallService mall) {
        this.mall = mall;
    }

    @GetMapping("/skus")
    public List<SkuView> skus(@RequestAttribute("tenantId") long tenantId) {
        return mall.listOnSale(tenantId);
    }

    @GetMapping("/orders")
    public List<OrderView> orders(@RequestAttribute("tenantId") long tenantId,
                                  @RequestAttribute("userId") long userId) {
        return mall.myOrders(tenantId, userId);
    }

    @PostMapping("/orders")
    @Idempotent(key = "mall:createOrder", windowSeconds = 3)
    public CreateOrderResponse createOrder(@RequestAttribute("tenantId") long tenantId,
                                           @RequestAttribute("userId") long userId,
                                           @Valid @RequestBody CreateOrderBody body) {
        return mall.createOrder(tenantId, userId, body.skuId());
    }

    @PostMapping("/orders/{orderNo}/mock-pay")
    @Idempotent(key = "mall:mockPay", windowSeconds = 5)
    public PayResponse mockPay(@RequestAttribute("tenantId") long tenantId,
                               @RequestAttribute("userId") long userId,
                               @PathVariable String orderNo) {
        return mall.mockPay(tenantId, userId, orderNo);
    }

    public record CreateOrderBody(@NotNull Long skuId) {
        public CreateOrderBody {
            if (skuId == null || skuId <= 0) {
                throw BizException.badRequest("skuId 非法");
            }
        }
    }
}
