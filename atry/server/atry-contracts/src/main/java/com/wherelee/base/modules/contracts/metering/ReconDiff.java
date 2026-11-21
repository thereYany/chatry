package com.wherelee.base.modules.contracts.metering;

/** 对账差异（expected/actual 用字符串，便于跨类型比较与展示）。 */
public record ReconDiff(String type, String ref, String expected, String actual, String detail) {
}
