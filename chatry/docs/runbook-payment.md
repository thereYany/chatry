# 支付运行手册（chatry）

> 面向这套 mock 支付系统自身的运维动作。所有操作都基于现有接口/任务，不手工改余额。
> 指标与告警见 `atry/observability/`，页面在 `/admin/*`。

## 0. 先看哪里

| 想知道 | 看什么 |
|---|---|
| 钱和额度对不对 | `/admin/finance`：负债−计量、清算−已付、借贷不平 |
| 有没有卡单/积压 | Grafana「卡单 / 关单后待退款」「退款积压」「发件箱积压」 |
| 谁改了什么 | `/admin/audit`（管理操作）+ `/admin/auth-events`（认证事件） |
| 单笔订单/退款详情 | `t_mall_order`、`t_refund`、`t_payment_order`、`t_payment_event`、`t_fund_ledger_transaction` |

## 1. 支付单停留 PAYING（卡单）

**现象**：`chatry_payment_stuck > 0`；用户说付了钱但额度没到。

1. 查支付尝试的渠道事实：
   `SELECT status, provider_status, provider_amount_cents FROM t_payment_attempt WHERE payment_no = ?`
2. 渠道事实为 `SUCCESS`：触发主动查单，让支付域自行结算（不要手工改支付单）：
   `POST /api/mock/payments/{paymentNo}/simulate` 之前先确认支付单还处于 PAYING。
3. 渠道事实不是 SUCCESS：说明用户其实没付成功，让用户重新支付；超过 15 分钟订单会被关单任务关闭并回补库存。
4. 处理完观察指标回落；若长时间不回落，检查回调日志 `payment.callback.*` 与发件箱积压。

## 2. 重复回调 / 金额不一致 / 关单后回调

这三种都**不应该**产生履约，系统会拒绝并落事件：

- 重复回调：`t_payment_event` 只有一条，返回「重复支付事件已忽略」；如果出现两条，说明事件唯一键失效，立即查 `uk_payment_event_key`。
- 金额不一致：`PAYMENT_AMOUNT_MISMATCH` 告警与 `t_alert_log` 记录，支付单保持原状态；**不要**按回调金额改单，核对渠道账单。
- 关单后回调：支付单 `CLOSED` 拒绝确认；如果钱确实到了，渠道会再对账，按「已收款待退款」处理（见第 3 节）。

可用 Mock 剧本复现：`POST /api/mock/payments/{paymentNo}/scenario` + `/simulate`，详见 `observability/README.md`。

## 3. 关单后收到支付（PAID_AFTER_CLOSE）

**现象**：`chatry_paid_after_close > 0` 持续超过 1 分钟。

1. 该状态表示「钱到账、额度未发」，资金账本记的是 `REFUND_PAYABLE`（待退款负债），**不能**手工加额度。
2. 自动任务 `PaidAfterCloseRefundJob` 每 60 秒扫描并全额退款；先看日志 `mall.refund.auto_requested`。
3. 若任务失败：查 `t_refund` 状态与 `error_message`，修因后等 `RefundRetryJob` 重试，或用
   `POST /api/admin/mall/orders/{orderNo}/refund` 手动发起。
4. 退款成功订单转 `REFUNDED`，资金分录冲掉待退款负债。

## 4. 退款积压 / 重试耗尽

- `chatry_refund_pending` 高：看 `t_refund.status` 与 `next_retry_at`；常规失败会指数退避重试。
- `chatry_refund_exhausted > 0`：重试次数耗尽，**必须人工处理**——确认渠道侧是否已退款，再决定：
  - 渠道已退：补一张成功记录（走退款重放，不要改账本分录）；
  - 渠道未退：线下转账后，用管理员退款接口重试或记录人工补偿单。
- 参数：`app.payment.refund-max-attempts`、`refund-retry-base-seconds`。

## 5. 账实差异

`/admin/finance` 三项校验：

| 校验 | 含义 | 常见原因 |
|---|---|---|
| 负债 − 计量余额 | 资金账本的额度负债与计量账户余额 | 消费分录 sweep 未跑、退款扣额度失败 |
| 清算 − 已付订单 | 收到的钱与已支付订单金额 | 关单后收款未记负债、退款分录缺失 |
| 借贷不平 | 单笔交易借贷不等 | 代码 bug（不应出现，出现即阻断） |

处理顺序：

1. 先看差异出现的时间点，定位对应业务单（`t_fund_ledger_transaction.biz_no`）。
2. 消费分录缺失：等 `FundLedgerUsageJob`（30 秒）补记，或手动触发一次后复查。
3. 退款相关差异：确认 `t_refund.status = SUCCEEDED` 且订单 `refunded_cents` 已累加。
4. **禁止直接 UPDATE 余额或分录**；修复只能是「补一笔冲正交易」，并且要有审计记录。

## 6. 发件箱积压 / 死信

- `chatry_outbox_pending` 高：`t_payment_outbox.status in (PENDING,SENDING)`；检查投递任务日志与监听器异常。
- `payment.outbox.dead` 增加：超过重试上限，业务侧未履约；按 `t_payment_outbox.payload` 找到订单，人工触发履约或退款。
- 投递是 at-least-once：业务侧必须幂等（当前履约用 `bizNo=mall:orderNo`，退款用退款单号）。

## 7. 演示/测试用的故障注入

只在非生产可用（`/admin/mock` 或接口）：

```bash
# 重复回调
curl -X POST /api/mock/payments/$PNO/scenario -d '{"scenario":"DUPLICATE"}'
curl -X POST /api/mock/payments/$PNO/simulate
# 金额不一致
curl -X POST /api/mock/payments/$PNO/scenario -d '{"scenario":"AMOUNT_MISMATCH"}'
# 关单后回调（先关闭支付单）
curl -X POST /api/mock/payments/$PNO/scenario -d '{"scenario":"AFTER_CLOSE"}'
# 延迟回调
curl -X POST /api/mock/payments/$PNO/scenario -d '{"scenario":"DELAYED","delayMs":2000}'
# 只写渠道事实、不回调（验证查单兜底）
curl -X POST /api/mock/payments/$PNO/scenario -d '{"scenario":"QUERY_ONLY"}'
```

## 8. 已知限制（照实说明）

- 延迟回调用进程内线程池，重启会丢；真实渠道由渠道侧保证，演示场景重启后需重新触发。
- Mock 账单只覆盖支付流水，未含退款流水（待办 #72）。
- 告警只发到日志/Alertmanager 占位，未接外发通道。
- 自动退款任务当前只扫默认租户（0），多租户需扩展。
