-- Mock 渠道故障剧本：按支付单指定本次模拟要触发的场景
CREATE TABLE t_payment_mock_scenario
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT       NOT NULL DEFAULT 0,
    payment_no VARCHAR(40)  NOT NULL,
    scenario   VARCHAR(40)  NOT NULL COMMENT 'SUCCESS/FAILED/DUPLICATE/DELAYED/AMOUNT_MISMATCH/BAD_SIGNATURE/AFTER_CLOSE/QUERY_ONLY',
    delay_ms   INT          NOT NULL DEFAULT 0,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_mock_scenario_payment (payment_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Mock 支付故障剧本（仅测试环境）';
