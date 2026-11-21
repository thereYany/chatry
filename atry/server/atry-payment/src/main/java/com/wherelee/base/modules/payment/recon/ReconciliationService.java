package com.wherelee.base.modules.payment.recon;

import com.wherelee.base.modules.platform.common.BizException;
import com.wherelee.base.modules.platform.common.PageResult;
import com.wherelee.base.modules.platform.tracing.TraceSpans;
import com.wherelee.base.modules.contracts.payment.AdminReconciliationOperations;
import com.wherelee.base.modules.payment.recon.ReconRepository.LocalPayment;
import com.wherelee.base.modules.payment.recon.ReconRepository.ReconDiffView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 资金对账：我方支付单 vs 渠道账单，逐笔比对并落差异。
 *
 * <p>对账是资金正确性的最后防线。设计上有三条纪律：</p>
 * <ol>
 *   <li>差异只入库、不自动补偿——金额不一致必须人工处理；</li>
 *   <li>同一渠道同一账期可重复跑，结果幂等（重跑覆盖差异）；</li>
 *   <li>任何"通知成功"都不等于账平了，只有这里比对通过才算。</li>
 * </ol>
 */
@Slf4j
@Service
public class ReconciliationService implements AdminReconciliationOperations {

    private static final String DIFF_LOCAL_ONLY = "LOCAL_ONLY";
    private static final String DIFF_PROVIDER_ONLY = "PROVIDER_ONLY";
    private static final String DIFF_AMOUNT = "AMOUNT";
    private static final String DIFF_STATUS = "STATUS";

    private final List<ChannelBillProvider> providers;
    private final ReconRepository repository;
    private final TraceSpans traceSpans;

    public ReconciliationService(List<ChannelBillProvider> providers,
                                 ReconRepository repository,
                                 TraceSpans traceSpans) {
        this.providers = providers;
        this.repository = repository;
        this.traceSpans = traceSpans;
    }

    @Override
    @Transactional
    public ReconSummary run(String channel, LocalDate billDate) {
        String normalizedChannel = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
        ChannelBillProvider provider = providers.stream()
                .filter(value -> value.supports(normalizedChannel))
                .findFirst()
                .orElseThrow(() -> BizException.badRequest("该渠道未接入对账数据源：" + normalizedChannel));
        LocalDate date = billDate == null ? LocalDate.now().minusDays(1) : billDate;

        return traceSpans.trace("recon.run", TraceSpans.tags(
                "recon.channel", normalizedChannel, "recon.billDate", date.toString()),
                () -> runInternal(normalizedChannel, date, provider));
    }

    private ReconSummary runInternal(String channel, LocalDate date, ChannelBillProvider provider) {
        List<ChannelBillLine> bill = provider.fetch(channel, date);
        repository.upsertBill(channel, date, provider.sourceKey(channel, date),
                checksum(bill), bill.size());
        String taskNo = repository.beginTask(channel, date);

        Map<String, ChannelBillLine> remaining = new LinkedHashMap<>();
        for (ChannelBillLine line : bill) {
            remaining.put(line.providerTradeNo(), line);
        }

        List<LocalPayment> localPayments = repository.findLocalPaid(channel, date);
        Map<String, Integer> diffByType = new LinkedHashMap<>();
        int matched = 0;
        int diffCount = 0;
        for (LocalPayment local : localPayments) {
            String tradeNo = local.providerTradeNo();
            if (tradeNo == null || tradeNo.isBlank()) {
                repository.insertDiff(taskNo, local.paymentNo(), null, DIFF_LOCAL_ONLY,
                        local.amountCents(), null, "我方已支付但缺少渠道流水号");
                diffCount++;
                diffByType.merge(DIFF_LOCAL_ONLY, 1, Integer::sum);
                continue;
            }
            ChannelBillLine line = remaining.remove(tradeNo);
            if (line == null) {
                repository.insertDiff(taskNo, local.paymentNo(), null, DIFF_LOCAL_ONLY,
                        local.amountCents(), null, "渠道账单中不存在该流水号 " + tradeNo);
                diffCount++;
                diffByType.merge(DIFF_LOCAL_ONLY, 1, Integer::sum);
                continue;
            }
            if (line.amountCents() != local.amountCents()) {
                repository.insertDiff(taskNo, local.paymentNo(), null, DIFF_AMOUNT,
                        local.amountCents(), line.amountCents(), "金额不一致");
                diffCount++;
                diffByType.merge(DIFF_AMOUNT, 1, Integer::sum);
                continue;
            }
            if (!ChannelBillLine.STATUS_PAID.equals(line.status())) {
                repository.insertDiff(taskNo, local.paymentNo(), null, DIFF_STATUS,
                        local.amountCents(), line.amountCents(),
                        "我方支付成功但渠道状态为 " + line.status());
                diffCount++;
                diffByType.merge(DIFF_STATUS, 1, Integer::sum);
                continue;
            }
            matched++;
        }

        for (ChannelBillLine line : remaining.values()) {
            repository.insertDiff(taskNo, null, null, DIFF_PROVIDER_ONLY,
                    null, line.amountCents(), "渠道有单而本地无支付单 " + line.providerTradeNo());
            diffCount++;
            diffByType.merge(DIFF_PROVIDER_ONLY, 1, Integer::sum);
        }

        int total = localPayments.size() + remaining.size();
        repository.finishTask(taskNo, total, matched, diffCount);
        if (diffCount > 0) {
            log.warn("对账存在差异 taskNo={} channel={} date={} total={} matched={} diffs={} {}",
                    taskNo, channel, date, total, matched, diffCount, diffByType);
        } else {
            log.info("对账通过 taskNo={} channel={} date={} total={}", taskNo, channel, date, total);
        }
        return new ReconSummary(taskNo, channel, date, total, matched, diffCount, diffByType);
    }

    @Override
    public PageResult<DiffView> pageDiffs(String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        int total = repository.countDiffs(status);
        if (total == 0) {
            return new PageResult<>(List.of(), 0, safePage, safeSize);
        }
        List<DiffView> records = repository
                .pageDiffs(status, (safePage - 1) * safeSize, safeSize)
                .stream().map(ReconciliationService::toView).toList();
        return new PageResult<>(records, total, safePage, safeSize);
    }

    @Override
    @Transactional
    public void handle(Long diffId, Long operatorId, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!"HANDLED".equals(normalized) && !"IGNORED".equals(normalized)) {
            throw BizException.badRequest("差异处理状态只支持 HANDLED 或 IGNORED");
        }
        int changed = repository.handleDiff(diffId, operatorId, normalized);
        if (changed == 0) {
            throw BizException.badRequest("差异不存在或已处理");
        }
    }

    private static DiffView toView(ReconDiffView view) {
        return new DiffView(view.id(), view.taskNo(), view.paymentNo(), view.bizNo(),
                view.diffType(), view.expectedAmount(), view.actualAmount(), view.detail(),
                view.status(), view.handledBy(), view.handledAt(), view.createdAt());
    }

    private static String checksum(List<ChannelBillLine> bill) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> lines = new ArrayList<>();
            for (ChannelBillLine line : bill) {
                lines.add(line.providerTradeNo() + "," + line.amountCents() + "," + line.status());
            }
            lines.sort(String::compareTo);
            byte[] hash = digest.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return null;
        }
    }
}
