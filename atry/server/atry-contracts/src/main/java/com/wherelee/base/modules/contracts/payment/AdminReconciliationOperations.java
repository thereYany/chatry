package com.wherelee.base.modules.contracts.payment;

import com.wherelee.base.modules.platform.common.PageResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 运营端资金对账契约。
 *
 * <p>operations 只依赖这个接口，不直接接触对账库表与渠道账单解析实现。</p>
 */
public interface AdminReconciliationOperations {

    /** 执行一次对账；同一渠道同一账期可重复执行，结果幂等覆盖。 */
    ReconSummary run(String channel, LocalDate billDate);

    PageResult<DiffView> pageDiffs(String status, int page, int size);

    /** 人工处理差异：HANDLED（已处理）或 IGNORED（确认忽略）。 */
    void handle(Long diffId, Long operatorId, String status);

    record ReconSummary(String taskNo, String channel, LocalDate billDate,
                        int totalCount, int matchedCount, int diffCount,
                        Map<String, Integer> diffByType) {
    }

    record DiffView(Long id, String taskNo, String paymentNo, String bizNo, String diffType,
                    Long expectedAmount, Long actualAmount, String detail, String status,
                    Long handledBy, LocalDateTime handledAt, LocalDateTime createdAt) {
    }
}
