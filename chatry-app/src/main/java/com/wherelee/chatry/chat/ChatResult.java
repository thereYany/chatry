package com.wherelee.chatry.chat;

import com.wherelee.chatry.credits.ChatBalanceView;

import java.math.BigDecimal;

/**
 * 一次对话的结果（同步接口返回体，也是 SSE done 事件的载荷）。
 *
 * @param messageId    助手消息 id
 * @param content      完整回复
 * @param usage        token 用量
 * @param cost         本次成本（CNY）
 * @param currency     币种
 * @param balance      结算后的余额
 * @param provider     实际 provider（流式时可能为空）
 * @param model        请求模型
 * @param finishReason 结束原因
 * @param latencyMs    端到端耗时
 */
public record ChatResult(long messageId, String content, ChatUsageView usage, BigDecimal cost,
                         String currency, ChatBalanceView balance, String provider, String model,
                         String finishReason, long latencyMs) {
}
