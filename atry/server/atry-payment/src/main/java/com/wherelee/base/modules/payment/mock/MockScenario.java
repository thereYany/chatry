package com.wherelee.base.modules.payment.mock;

/**
 * Mock 渠道故障剧本（仅测试/演示环境）。
 *
 * <p>每个场景对应一种真实渠道下会出现的异常，用来验证支付域的状态机、幂等与对账，
 * 而不是绕过它们。</p>
 */
public enum MockScenario {

    /** 正常支付成功：签名回调走真实验签 → 确认。 */
    SUCCESS,
    /** 渠道回报支付失败。 */
    FAILED,
    /** 同一回调重复投递两次。 */
    DUPLICATE,
    /** 延迟 N 毫秒后回调（模拟异步通知晚到）。 */
    DELAYED,
    /** 回调金额与支付单不一致（必须拒绝）。 */
    AMOUNT_MISMATCH,
    /** 回调签名错误（必须拒绝）。 */
    BAD_SIGNATURE,
    /** 订单已关闭后才收到成功回调（不得履约）。 */
    AFTER_CLOSE,
    /** 只更新渠道侧事实、不回调：用于验证主动查单兜底。 */
    QUERY_ONLY
}
