# 商城支付与额度账务审查（2026-11-21）

> **2026-11-21 P6 更新**：mock 生产守卫、支付/订单状态 CAS、关闭单回调拒绝、重复支付幂等、
> 充值语义已落地；资金账本（P7）、退款（P8）、Mock 签名回调（P9）仍未做。

## 结论

**余额增加不是符号写反，而是产品语义和账务模型的问题。**

当前商城卖的是「额度包」：支付人民币，到账的是可用于对话的额度（1 额度 ≈ ¥1 的 token 成本），
所以支付成功后额度余额上升是符合当前实现的；实测买 ¥9.99 的包，额度从 ¥154.991368 变成
¥166.991368（+¥12.00，含 ¥2.01 赠送），账本是一笔 `CREDIT 12.000000`。

但用户看到的是：页面上写着「购买 / 余额 / ¥」，点完支付余额反而变大，且没有任何「现金侧」记录。
这会让人合理地认为系统在凭空造钱。**这是真问题，只是不在乘法/符号那一层。**

## 复现证据

```text
购买前 /api/chat/credits: balance=154.991368
订单: amountCents=999, creditCents=1200, status=PAYING
模拟支付后: balance=166.991368, status=PAID
t_mall_order: amount_cents=999, credit_cents=1200, status=PAID
t_payment_order: amount_cents=999, status=PAID, channel=MOCK
t_metering_ledger: direction=CREDIT, amount=12.000000,
                   biz_no=mall:M2611210358428BD623, balance_after=166.991368
```

- 账户是 `t_metering_account.balance`（计量账户），没有独立的「现金钱包」账户。
- 首次使用赠送 ¥5.00；商城包价格 999 分、到账 1200 分，属于「充值 + 赠送」。
- 对话消耗走 `DEBIT` 的 `SETTLE` 账本，所以余额会随使用下降。

## 真正需要修的问题

### 1. 语义与单位混用（用户感知的直接原因）

- 前端把额度余额显示为 `¥154.99`（`App.jsx`、`Mall.jsx`），商城写「购买」「当前额度」；
  用户自然会把它当成现金余额，认为「购买」应该扣钱。
- 商品卡同时显示到账额度和价格，却没有「支付金额 / 到账额度 / 赠送」的明确拆分
  （`Mall.jsx` 的 `sku-credit` 与 `sku-price`）。
- 支付成功提示是「额度已到账，当前余额 …」，更像现金账户变动。

### 2. 只有额度账本，没有资金侧账本

- `t_metering_ledger` 记录的是额度增减；支付单 `t_payment_order` 只表示渠道状态。
- 没有记录「收到多少钱、这笔钱对应多少额度负债、赠送部分记到哪里」，因此无法回答
  「平台收了 ¥999，发了 ¥1200 额度，差额是什么」。
- 行业做法是双分录：收到钱时 `借：现金/清算账户`、`贷：客户额度负债`；消费时
  `借：客户额度负债`、`贷：收入`。赠送额度要么由营销费用承担，要么减少收入。
  参考 Modern Treasury 的 wallet ledger 说明：用户余额是平台的负债，所有用户余额之和
  应与平台实际持有的现金对得上。

### 3. `mock-pay` 没有生产守卫（安全洞，优先级最高）

- `MallController` 的 `POST /api/mall/orders/{orderNo}/mock-pay` 没有任何
  `@Profile`/配置判断；`MallService.mockPay()` 直接构造 `provider="MOCK"` 的支付成功通知。
- `PaymentSafetyGuard` 只在启动时禁止「生产 + mock 渠道/mockEnabled」，但如果生产配置成
  `provider=alipay, mockEnabled=false`，应用能正常启动，这个接口仍然存在；任何登录用户
  都能把自己的订单标记为已支付并拿到额度。
- 修复：接口必须校验 `app.payment.provider=mock && app.payment.mock-enabled=true`，
  并建议只在 dev/test profile 暴露；生产直接 404/403，同时加集成测试。

### 4. 支付确认不校验「支付单是否还允许支付」

- `PaymentConfirmationService.confirm()` 调用 `paymentOrderRepository.markPaid(...)` 后
  忽略返回值；而 `markPaid` 的 SQL 只更新 `CREATED/PAYING/PAID`。
- 如果支付单已 `CLOSED/EXPIRED`，更新为 0 行，但代码仍会写 `PAYMENT_SUCCEEDED` 发件箱，
  履约监听器仍会给用户加额度，导致「支付单是关闭的，额度却到账」。
- 修复：检查 `markPaid` 影响行数；状态不允许时记录事件失败/告警并拒绝履约，或明确走
  「已收款但订单关闭 → 自动退款/人工处理」分支。

### 5. 已有的正确部分（不要改坏）

- 支付确认校验了金额：`notification.amountCents() != payment.amountCents()` 会拒绝并告警。
- 回调事件有 `(provider, event_key)` 唯一约束，重复通知会走幂等分支。
- 履约充值以 `bizNo=mall:orderNo` 幂等，重复发件箱不会重复加额度。
- 订单/支付单有基本的 `CREATED/PAYING/PAID/CLOSED` 状态。

## 行业调研要点

1. **额度不是现金，充值不是消费**
   - SolvaPay 把 credit 定义为 prepaid wallet unit（预付钱包单位），top-up 是「增加额度」；
     meter 负责计量使用量，额度和计量是两层。商城应叫「充值额度」，不是「购买」。
     <https://docs.solvapay.com/plans/glossary>
   - Stripe 的 customer balance 也是「credit balance」：正向交易是消耗已有 credit，
     负向/调整才是授予 credit；它和银行卡收款是两套账。
     <https://docs.stripe.com/billing/customer/balance>

2. **钱包/额度必须有双分录账本**
   - Modern Treasury 的 wallet 账本示例：Cash 是借方资产，User Wallet 是贷方负债；
     每笔充值至少「借现金、贷用户余额」，并要求所有用户余额之和等于平台现金。
     <https://www.moderntreasury.com/journal/accounting-for-developers-part-ii>
   - 预付钱包余额通常先记 deferred revenue/liability，消费时再确认收入
     （零售/出行公司的钱包余额披露也是这个口径）。

3. **支付回调要验签、验金额、幂等、按状态机处理**
   - 支付宝商户处理注意事项：先验签、确认通知来源，再判断是否已处理过同一笔通知；
     不能只根据 `trade_status` 改单。
     <https://opendocs.alipay.com/open/64/103776>
   - Stripe 幂等键：客户端生成唯一 key，重试返回第一次的结果；参数不一致会报错。
     <https://docs.stripe.com/api/idempotent_requests.md>
   - Stripe PaymentIntent 生命周期：`requires_payment_method → processing → succeeded`，
     成功后才可履约，失败可重试；退款走 Refunds。
     <https://docs.stripe.com/payments/paymentintents/lifecycle>

4. **Mock 只能存在于测试/开发路径**
   - 行业做法是 mock 走与生产相同的状态变更路径，但注入只允许在 staging/测试环境；
     生产环境不暴露可确认支付的入口。
     <https://docs.fluz.app/zh-hant/Simulate-Virtual-Card-Transactions>（示例：模拟交易仅限预备环境）

## 建议的目标模型（P6）

推荐继续「额度」方向（与 chat AI 的预付费模式一致），不要引入第二套现金钱包；
但要把「充值」和「消费」讲清楚，并把资金侧账本补上。

### P6.1 止血（低风险，先做）

1. `mock-pay` 增加配置守卫：仅 `provider=mock && mockEnabled` 且非 prod 可用；补集成测试。
2. 支付确认检查 `markPaid` 影响行数；关闭/过期支付单不再触发履约，记告警。
3. 前端改语义：
   - 「购买」→「充值额度」；顶部「余额」→「当前额度」；
   - 商品卡显示 `支付 ¥9.99 · 到账 12.00 额度（含赠送 ¥2.01）`；
   - 支付成功显示 `额度 +12.00（支付 ¥9.99）`，不再把总额叫「余额」。
4. 增加订单/支付一致性测试：金额不一致、重复回调、关闭后回调、mock 关闭时调用。

### P6.2 双分录账本（真正补齐账务）

新增 `t_ledger_transaction` + `t_ledger_entry`（或复用 atry metering 的账本，但必须加资金账户）：

- 账户：`CASH/PSP_CLEARING`（借）、`USER_CREDIT_LIABILITY`（贷）、`REVENUE`（贷）、
  `MARKETING_EXPENSE`（借，赠送部分）。
- 支付成功：借 `PSP_CLEARING 9.99`，贷 `USER_CREDIT_LIABILITY 12.00`，借 `MARKETING_EXPENSE 2.01`
  （或按业务选择：赠送不进负债、直接费用化）。
- 对话消费：借 `USER_CREDIT_LIABILITY`，贷 `REVENUE`。
- 对账：`sum(用户额度余额)` 与 `PSP_CLEARING/CASH`、渠道账单三方对账；差异告警。
- 迁移时保留现有 `t_metering_ledger` 作为额度子账，新增总账做资金侧，避免一次改崩。

### P6.3 生命周期与售后

- 支付单状态机：`CREATED → PAYING → PAID/CLOSED/EXPIRED/REFUNDING/REFUNDED`；
  关闭后的成功回调进入「已收款待退款」而不是直接履约。
- 退款/退额度：退款成功后扣回未消费额度，已消费部分按规则处理。
- 渠道对账、异常告警、人工补偿入口。

## 验收标准

- 买 ¥9.99 的包，页面明确显示「支付 ¥9.99 / 到账 ¥12.00（赠送 ¥2.01）」，余额增加不再被误读为现金。
- 生产配置 `provider=alipay` 时，`mock-pay` 返回 404/403；测试覆盖。
- 关闭/过期支付单收到成功通知：不履约、告警、进入退款/人工队列。
- 双分录每笔 transaction 借贷相等；`用户额度余额 = 已收款 - 已消费 - 退款` 可对账。
- 重复回调、金额不一致、mock 开关关闭、退款幂等全部有测试。

## 参考

- SolvaPay credits glossary: <https://docs.solvapay.com/plans/glossary>
- Modern Treasury, Accounting for Developers Part II: <https://www.moderntreasury.com/journal/accounting-for-developers-part-ii>
- Stripe customer balance: <https://docs.stripe.com/billing/customer/balance>
- Stripe idempotent requests: <https://docs.stripe.com/api/idempotent_requests.md>
- Stripe PaymentIntent lifecycle: <https://docs.stripe.com/payments/paymentintents/lifecycle>
- 支付宝商户处理注意事项: <https://opendocs.alipay.com/open/64/103776>
- Fluz 模拟交易仅限预备环境: <https://docs.fluz.app/zh-hant/Simulate-Virtual-Card-Transactions>
