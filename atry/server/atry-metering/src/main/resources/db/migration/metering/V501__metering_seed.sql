-- P4 基础种子：默认 token 计量器 + 人民币价格书（后续由运营配置覆盖/新增版本）
INSERT INTO t_metering_meter (meter_key, event_name, aggregation, unit, status)
VALUES ('llm.tokens', 'llm.call.completed', 'SUM', 'token', 'ACTIVE');

INSERT INTO t_metering_price_book (book_key, currency, version, effective_from, status)
VALUES ('default', 'CNY', 1, '2026-01-01 00:00:00.000', 'ACTIVE');

INSERT INTO t_metering_rate_card (book_id, meter_key, unit_price, currency, rounding)
SELECT id, 'llm.tokens', 0.000001, 'CNY', 'CEIL'
FROM t_metering_price_book
WHERE book_key = 'default' AND version = 1;
