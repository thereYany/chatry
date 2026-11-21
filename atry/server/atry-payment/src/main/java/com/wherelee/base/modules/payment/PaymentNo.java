package com.wherelee.base.modules.payment;

/**
 * 支付单号生成规则：{@code P + 业务单号}。
 *
 * <p>确定性生成保证「同一业务单只有一个支付单」，重复发起是幂等的；
 * 正式多业务方接入后改由序列/雪花生成，届时需要一次性迁移。</p>
 */
public final class PaymentNo {

    private static final String PREFIX = "P";

    private PaymentNo() {
    }

    public static String of(String bizNo) {
        return PREFIX + bizNo;
    }

    /** 新垂直域使用的支付单号（非确定性；幂等由 (app,bizType,bizNo) 唯一键保证）。 */
    public static String issue() {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return PREFIX + System.currentTimeMillis() + suffix;
    }
}
