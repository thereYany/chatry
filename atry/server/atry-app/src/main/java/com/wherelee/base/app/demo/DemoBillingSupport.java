package com.wherelee.base.app.demo;

import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.RechargeCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 演示用计费辅助：账户自动开户、成本估算、余额查询。
 *
 * <p>演示账户通过「幂等充值」种子：bizNo = {@code demo-seed:<tenant>:<user>}，
 * 重复调用不会重复入账；因此重启后余额保持，而演示数据被清空时会自动重新开户。</p>
 */
@Service
public class DemoBillingSupport {

    private static final String INPUT_METER = "llm.input_tokens";
    private static final String OUTPUT_METER = "llm.output_tokens";
    private static final String CACHED_READ_METER = "llm.cached_read_tokens";

    private final MeteringOperations metering;
    private final MeteringAdminOperations meteringAdmin;
    private final AtryDemoProperties properties;

    public DemoBillingSupport(MeteringOperations metering, MeteringAdminOperations meteringAdmin,
                              AtryDemoProperties properties) {
        this.metering = metering;
        this.meteringAdmin = meteringAdmin;
        this.properties = properties;
    }

    /** 演示主体：appId 固定 0（演示宿主没有多应用维度），用户来自登录态。 */
    public SubjectRef subject(long tenantId, long userId) {
        return new SubjectRef(tenantId, 0, userId);
    }

    /**
     * 确保演示账户存在并已完成种子充值；已存在时返回当前余额（不重复入账）。
     *
     * <p>并发下两次首访可能同时尝试开户，由账本 biz_no 唯一键兜底，捕获后回读余额。</p>
     */
    public BalanceView ensureAccount(SubjectRef subject) {
        String bizNo = "demo-seed:" + subject.tenantId() + ":" + subject.userId();
        try {
            return meteringAdmin.recharge(new RechargeCommand(subject, properties.initialBalance(),
                    properties.currency(), bizNo));
        } catch (DuplicateKeyException e) {
            return metering.queryBalance(subject);
        }
    }

    /** 本次调用的实际成本：与 LlmService 结算口径一致（非缓存输入 + 输出 + 缓存命中）。 */
    public BigDecimal cost(LlmUsage usage, String model, Instant at) {
        if (usage == null) {
            return BigDecimal.ZERO;
        }
        long nonCachedInput = Math.max(0, usage.inputTokens() - usage.cachedReadTokens());
        Map<String, String> dimensions = Map.of("model", model);
        return price(INPUT_METER, nonCachedInput, at, dimensions)
                .add(price(OUTPUT_METER, usage.outputTokens(), at, dimensions))
                .add(price(CACHED_READ_METER, usage.cachedReadTokens(), at, dimensions));
    }

    public DemoChatResponse.Balance toView(BalanceView balance) {
        return new DemoChatResponse.Balance(balance.balance(), balance.frozen(), balance.currency());
    }

    private BigDecimal price(String meterKey, long quantity, Instant at, Map<String, String> dimensions) {
        if (quantity <= 0) {
            return BigDecimal.ZERO.setScale(6);
        }
        return metering.estimateCost(meterKey, BigDecimal.valueOf(quantity), at, dimensions);
    }
}
