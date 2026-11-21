package com.wherelee.base.modules.payment.mock;

import com.wherelee.base.modules.payment.PaymentConfirmationService;
import com.wherelee.base.modules.payment.RefundService;
import com.wherelee.base.modules.platform.aop.PermissionCodes;
import com.wherelee.base.modules.platform.aop.RequirePermission;
import com.wherelee.base.modules.platform.common.BizException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Mock 渠道开发台：只在非生产 + 启用 Mock 时注册。
 *
 * <p>用途是「按剧本触发渠道行为」，不是「跳过支付」：所有回调都经过真实验签与确认链路。</p>
 */
@RestController
@RequestMapping("/api/mock/payments")
@ConditionalOnProperty(name = "app.payment.mock-enabled", havingValue = "true")
public class MockChannelController {

    private final MockCashierService cashier;
    private final MockScenarioRepository scenarios;
    private final RefundService refundService;
    private final Environment environment;

    public MockChannelController(MockCashierService cashier, MockScenarioRepository scenarios,
                                 RefundService refundService, Environment environment) {
        this.cashier = cashier;
        this.scenarios = scenarios;
        this.refundService = refundService;
        this.environment = environment;
    }

    /** 触发一次指定场景；未设置剧本时按 SUCCESS 处理。 */
    @PostMapping("/{paymentNo}/simulate")
    public SimulateResponse simulate(@PathVariable String paymentNo) {
        requireNonProd();
        PaymentConfirmationService.ConfirmationResult result = cashier.simulate(paymentNo);
        return new SimulateResponse(paymentNo, result == null ? "SCHEDULED" : result.status(),
                result == null ? null : result.message());
    }

    /** 设置/覆盖剧本（delayMs 仅 DELAYED 使用）。 */
    @PostMapping("/{paymentNo}/scenario")
    public void setScenario(@PathVariable String paymentNo,
                            @RequestBody ScenarioBody body) {
        requireNonProd();
        MockScenario scenario;
        try {
            scenario = MockScenario.valueOf(body.scenario().trim().toUpperCase());
        } catch (RuntimeException e) {
            throw BizException.badRequest("未知场景：" + body.scenario());
        }
        scenarios.save(paymentNo, scenario, body.delayMs() == null ? 0 : body.delayMs());
    }

    @GetMapping("/scenarios")
    public List<String> scenarios() {
        requireNonProd();
        return Arrays.stream(MockScenario.values()).map(Enum::name).toList();
    }

    /** 让退款按「渠道失败一次、重试成功」执行，用于验证退款重试。 */
    @PostMapping("/refunds/{refundNo}/fail-once")
    public void failRefundOnce(@PathVariable String refundNo) {
        requireNonProd();
        refundService.failNextAttempt(refundNo, "Mock 渠道退款失败（剧本）");
    }

    private void requireNonProd() {
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw BizException.notFound("Mock 渠道仅测试环境可用");
        }
    }

    public record ScenarioBody(String scenario, Integer delayMs) {
    }

    public record SimulateResponse(String paymentNo, String status, String message) {
    }
}
