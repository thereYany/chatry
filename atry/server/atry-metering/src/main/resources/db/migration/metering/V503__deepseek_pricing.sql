-- P5b：按 token 类型分计量 + 模型维度价格（DeepSeek 官方人民币价，单位 CNY / 1M tokens）
INSERT INTO t_metering_meter (meter_key, event_name, aggregation, unit, status) VALUES
 ('llm.input_tokens', 'llm.call.completed', 'SUM', 'token', 'ACTIVE'),
 ('llm.output_tokens', 'llm.call.completed', 'SUM', 'token', 'ACTIVE'),
 ('llm.cached_read_tokens', 'llm.call.completed', 'SUM', 'token', 'ACTIVE');

-- 默认价（mock/未配置模型）：便于本地演示，不代表真实厂商价
INSERT INTO t_metering_rate_card (book_id, meter_key, subject_selector, unit_price, currency, rounding)
SELECT id, 'llm.input_tokens', NULL, 0.000001, 'CNY', 'CEIL' FROM t_metering_price_book WHERE book_key = 'default' AND version = 1;
INSERT INTO t_metering_rate_card (book_id, meter_key, subject_selector, unit_price, currency, rounding)
SELECT id, 'llm.output_tokens', NULL, 0.000001, 'CNY', 'CEIL' FROM t_metering_price_book WHERE book_key = 'default' AND version = 1;
INSERT INTO t_metering_rate_card (book_id, meter_key, subject_selector, unit_price, currency, rounding)
SELECT id, 'llm.cached_read_tokens', NULL, 0.000001, 'CNY', 'CEIL' FROM t_metering_price_book WHERE book_key = 'default' AND version = 1;

-- DeepSeek V4.1 Flash 官方价（标准时段，2026-11 查询；优惠时段后续用价格维度扩展）
-- 输入 cache miss 2 元/M、缓存命中 0.04 元/M、输出 8 元/M
INSERT INTO t_metering_rate_card (book_id, meter_key, subject_selector, unit_price, currency, rounding)
SELECT id, 'llm.input_tokens', 'deepseek-flash', 0.000002, 'CNY', 'CEIL' FROM t_metering_price_book WHERE book_key = 'default' AND version = 1;
INSERT INTO t_metering_rate_card (book_id, meter_key, subject_selector, unit_price, currency, rounding)
SELECT id, 'llm.output_tokens', 'deepseek-flash', 0.000008, 'CNY', 'CEIL' FROM t_metering_price_book WHERE book_key = 'default' AND version = 1;
INSERT INTO t_metering_rate_card (book_id, meter_key, subject_selector, unit_price, currency, rounding)
SELECT id, 'llm.cached_read_tokens', 'deepseek-flash', 0.00000004, 'CNY', 'CEIL' FROM t_metering_price_book WHERE book_key = 'default' AND version = 1;
