package com.wherelee.chatry.credits;

import com.wherelee.chatry.chat.ChatProperties;
import com.wherelee.chatry.ledger.FundLedgerService;
import com.wherelee.base.modules.contracts.llm.LlmUsage;
import com.wherelee.base.modules.contracts.metering.BalanceView;
import com.wherelee.base.modules.contracts.metering.MeteringAdminOperations;
import com.wherelee.base.modules.contracts.metering.MeteringOperations;
import com.wherelee.base.modules.contracts.metering.RechargeCommand;
import com.wherelee.base.modules.contracts.metering.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

/**
 * 额度服务：v1 的「额度」就是 atry 计量账户里的余额。
 *
 * <p>账户首次使用时用幂等充值赠送初始额度（bizNo 固定），之后所有消耗都由
 * atry-llm 内部按 token 预扣/结算；这里只负责开户、成本展示口径与余额查询。</p>
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);
    private static final long APP_ID = 0L;
    private static final String INPUT_METER = "llm.input_tokens";
    private static final String OUTPUT_METER = "llm.output_tokens";
    private static final String CACHED_READ_METER = "llm.cached_read_tokens";

    private final MeteringOperations metering;
    private final MeteringAdminOperations meteringAdmin;
    private final CreditProperties properties;
    private final ChatProperties chatProperties;
    private final FundLedgerService fundLedger;

    public CreditService(MeteringOperations metering, MeteringAdminOperations meteringAdmin,
                         CreditProperties properties, ChatProperties chatProperties,
                         FundLedgerService fundLedger) {
        this.metering = metering;
        this.meteringAdmin = meteringAdmin;
        this.properties = properties;
        this.chatProperties = chatProperties;
        this.fundLedger = fundLedger;
    }

    public SubjectRef subject(long tenantId, long userId) {
        return new SubjectRef(tenantId, APP_ID, userId);
    }

    /**
     * 确保账户存在并已发放新手额度；已发放过则直接返回当前余额。
     *
     * <p>并发首访时两次充值会撞账本 biz_no 唯一键，捕获后回读余额即可，
     * 不会重复赠送。</p>
     */
    public ChatBalanceView ensureAccount(long tenantId, long userId) {
        SubjectRef subject = subject(tenantId, userId);
        try {
            meteringAdmin.recharge(new RechargeCommand(subject, properties.initialGrant(),
                    properties.currency(), "chatry-grant:" + tenantId + ":" + userId));
        } catch (DuplicateKeyException ignored) {
            // 另一线程已完成首次赠送
        }
        try {
            fundLedger.postInitialGrant(tenantId, userId, properties.initialGrant(),
                    "chatry-grant:" + tenantId + ":" + userId);
        } catch (RuntimeException e) {
            log.warn("新手额度资金分录失败 tenantId={} userId={}：{}", tenantId, userId,
                    e.getClass().getSimpleName());
        }
        return balance(tenantId, userId);
    }

    public ChatBalanceView balance(long tenantId, long userId) {
        return toView(metering.queryBalance(subject(tenantId, userId)));
    }

    /** 额度页：余额 + 按当前默认模型的单价估算还能聊几次。 */
    public CreditsView credits(long tenantId, long userId) {
        ChatBalanceView balance = ensureAccount(tenantId, userId);
        BigDecimal perChat = perChatCost(chatProperties.defaultModel(), Instant.now());
        long estimatedChats = perChat.signum() <= 0
                ? 0
                : balance.balance().divide(perChat, 0, RoundingMode.DOWN).longValue();
        return new CreditsView(balance.balance(), balance.frozen(), balance.currency(),
                properties.initialGrant(), estimatedChats);
    }

    /** 与 atry-llm 结算口径一致的实际成本（非缓存输入 + 输出 + 缓存命中）。 */
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

    public static long micros(BigDecimal cost) {
        if (cost == null || cost.signum() <= 0) {
            return 0L;
        }
        return cost.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private BigDecimal perChatCost(String model, Instant at) {
        Map<String, String> dimensions = Map.of("model", model);
        return price(INPUT_METER, properties.estimateInputTokens(), at, dimensions)
                .add(price(OUTPUT_METER, properties.estimateOutputTokens(), at, dimensions));
    }

    private BigDecimal price(String meterKey, long quantity, Instant at, Map<String, String> dimensions) {
        if (quantity <= 0) {
            return BigDecimal.ZERO.setScale(6);
        }
        return metering.estimateCost(meterKey, BigDecimal.valueOf(quantity), at, dimensions);
    }

    public ChatBalanceView toView(BalanceView balance) {
        return new ChatBalanceView(balance.balance(), balance.frozen(), balance.currency());
    }
}
