# 支付系统 5 分钟演示脚本（chatry）

> 目标：用一套 mock 渠道，把「正常支付 → 重复回调 → 金额不一致 → 关单后回调 → 退款 → 账本与对账」讲完。
> 全部操作都有界面或接口，不需要改代码、不需要真实资金。

## 准备（30 秒）

1. 打开 `https://<host>/chatry/`，用 `demo / demo123456` 登录。
2. 浏览器另开一个窗口用 `admin / admin12345` 登录（管理页）。
3. 准备一个终端，后面用来调 Mock 剧本接口（需要 admin token）。

## 第 1 幕：正常充值（60 秒）

1. demo 进入「额度商城」，点「立即充值」→「确认模拟支付」。
2. 界面提示：`充值成功：支付 ¥9.99，到账 12.00 额度（含赠送 ¥2.01）`。
3. 讲点：
   - 额度是预付费负债，不是现金余额；
   - 支付成功 = 渠道签名回调 → 验签 → 金额校验 → 支付单状态 CAS → 发件箱 → 履约（幂等 `mall:orderNo`）。

## 第 2 幕：重复回调（60 秒）

1. 后台找到一个 `PAYING` 支付单，或新下一单。
2. `POST /api/mock/payments/{paymentNo}/scenario` body `{"scenario":"DUPLICATE"}`，再 `POST .../simulate`。
3. 返回：`重复支付事件已忽略`。
4. 讲点：事件表 `(provider, event_key)` 唯一键 + 支付单状态 CAS，重复回调不会重复加额度；
   发件箱只有一条 `PAYMENT_SUCCEEDED`。

## 第 3 幕：金额不一致（45 秒）

1. 新下一单，设剧本 `AMOUNT_MISMATCH`，触发。
2. 返回：`支付金额不一致`，订单仍是待支付。
3. 讲点：回调金额与支付单金额不等时拒绝、落失败事件、告警，绝不按回调金额入账。

## 第 4 幕：关单后回调（45 秒）

1. 新下一单，等超时或直接把支付单置 `CLOSED`（演示用）。
2. 设剧本 `AFTER_CLOSE`，触发。
3. 返回：`支付单状态 CLOSED 不允许确认支付`，发件箱 0 条。
4. 讲点：钱到了但订单已关 → 不履约；真实场景进入「已收款待退款」，由自动退款任务处理。

## 第 5 幕：退款与账本（60 秒）

1. admin 打开 `/admin/refund`，选一笔已支付订单，看「可退金额」= 未消费额度折算。
2. 发起退款 → 订单变 `REFUNDED`，额度被扣回，界面余额回到退款前。
3. 打开 `/admin/finance`：
   - 负债 − 计量 = 0；
   - 清算 − 已付订单 = 0；
   - 借贷不平 = 0；
   - 最近交易里能看到 `refund:*` 分录：借额度负债 ＝ 贷清算 + 贷营销费用。
4. 讲点：退款不是「把余额改回去」，而是正常反向分录；佣金/赠送按比例冲回。

## 第 6 幕：账本与对账总结（30 秒）

1. 指着 `/admin/finance` 的三个 0 说：
   - 每笔交易借贷相等；
   - 用户额度之和等于负债；
   - 收到的钱等于已支付订单金额。
2. 收尾一句话：
   > Mock 是渠道替身，不是跳过支付。状态机、幂等、验签、金额校验、发件箱、履约幂等、退款、账本、对账都是真的；
   > 生产只替换网关实现与密钥。

## 面试问答卡片

| 追问 | 我们的实现 | 在哪看 |
|---|---|---|
| 重试会扣两次吗 | 请求幂等 + 支付单状态 CAS + 履约 bizNo 幂等 | `PaymentConfirmationService`、`MallFulfillmentService` |
| 两个实例同时回调 | 事件唯一键 + 状态 CAS + 分布式锁 | `t_payment_event`、`PaymentFacade` |
| 支付成功但订单已关闭 | 拒绝确认，进「已收款待退款」，自动退款 | `MallFulfillmentService`、`PaidAfterCloseRefundJob` |
| 回调丢了怎么办 | 主动查单 + 补偿任务 | `PaymentQueryService`、`PaymentQueryJob` |
| 钱和额度怎么对上 | 双分录账本 + 三项校验 | `FundLedgerService`、`/admin/finance` |
| 退款能重复退吗 | 退款单号幂等 + 可退金额上限 | `RefundService` |
| 退款失败怎么办 | 指数退避重试 + 耗尽告警 + 人工 | `RefundRetryJob`、`/admin/refund` |
| 怎么发现资损 | 指标 + 告警 + 日终对账快照 | `PaymentOpsMetrics`、Grafana、`t_fund_ledger_check` |
| 怎么防止生产误用 mock | 非 prod + mockEnabled 双守卫，端点不注册 | `MockPaymentGuard`、`MockChannelController` |
