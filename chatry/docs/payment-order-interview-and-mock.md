# 支付/订单面试追问地图 & Mock 支付能做到什么程度

> 整理时间：2026-11-21。面向 Java 后端面试（支付/订单/交易方向），结合本项目 chatry + atry 的实现。

## 一句话结论

**Mock 支付可以做得非常完善——只要把 Mock 当成「渠道替身」而不是「跳过支付」。**
支付单状态机、幂等、验签、金额校验、异步回调、掉单补偿、退款、对账、双分录账本，这些业务能力
都可以在 Mock 渠道下真实跑通；Mock 不能替代的只有真实资金清算、渠道风控/3DS、拒付和合规。
面试官关心的通常不是「有没有真钱」，而是「渠道不可靠时你的状态机、幂等和账务是否可靠」。

---

## 一、面试官会怎么问：六组问题 + 追问链

### 1. 设计一个支付 API

**开场问题**：你如何设计一个支付接口？

**回答骨架**：
- 接口幂等：客户端生成 `idempotency_key`，服务端唯一约束落库，重试返回第一次结果。
- 支付单状态机：`CREATED → PAYING → PAID / FAILED / CLOSED / EXPIRED / REFUNDING / REFUNDED`。
- 金额与业务单分离：支付单只认金额/币种/业务单号，不直接改订单。
- 回调异步确认：验签、校验金额/商户/订单，写支付事件，发件箱通知业务。
- 兜底：主动查单 + 日终对账 + 告警。

**追问链（层层拷打）**：
1. 网络超时，客户端重试同一请求，会扣两次吗？
2. 幂等键放 Redis，两个实例同时未命中怎么办？
3. 渠道已经扣款成功，你本地写库失败/进程崩了怎么办？
4. 渠道回调重复、延迟、乱序怎么办？
5. 幂等键过期后重试怎么办？
6. 渠道根本不支持幂等键怎么办？

**标准答法要点**：
- 幂等不是「Redis 先查再写」，而是「唯一键 + 状态 CAS + 渠道幂等键」三层。
- 本地事务和消息用 transactional outbox，避免「库成功、消息没发」。
- 外部副作用后本地失败，靠「支付单状态 + 渠道查单」恢复，不能假设重试一定安全。

参考：[Stripe Idempotent requests](https://docs.stripe.com/api/idempotent_requests.md)、
[微信支付掉单处理](https://pay.weixin.qq.com/doc/v3/merchant/4012075249.md)。

---

### 2. 下单与库存：什么时候扣库存？

**开场问题**：下单扣库存还是支付扣库存？怎么防超卖？

**追问链**：
1. 先扣库存，订单创建失败怎么办？
2. 支付回调重复执行，会不会重复扣库存/重复发货？
3. 支付成功回调撞上「超时关单」，谁赢？
4. Redis 扣了库存，落库失败怎么办？
5. 多商品扣减怎么避免死锁？
6. 热点商品库存怎么扛？

**答法要点**：
- 下单预占（available--, frozen++），支付成功确认扣减，超时释放；每一步带状态和幂等键。
- 防超卖：`UPDATE stock SET available=available-? WHERE sku=? AND available>=?` 数据库原子条件更新；
  Redis + Lua 只能做前置拦截，最终以 DB 为准。
- 状态流转用 CAS（`WHERE status='PAYING'`），影响行数为 0 就放弃，不能靠 if 判断。
- 支付成功撞关单：支付单/订单状态机规定唯一赢家；关闭后收到成功回调进「已收款待退款」。
- 多商品按 SKU 排序扣减，避免死锁。
- 热点库存：分桶库存、队列串行化、Redis 预扣 + DB 最终扣减，但必须能对账。

参考：常见订单/库存面试题里的「预占/释放/支付确认」状态表；微信支付回调与关单实践。

---

### 3. 支付回调：验签、幂等、掉单

**开场问题**：支付回调怎么做？

**追问链**：
1. 怎么确认回调是渠道发的？金额被篡改怎么办？
2. 同一笔回调重复 3 次会加 3 次额度吗？
3. 回调处理到一半失败，渠道重试，你怎么保证不重复？
4. 回调一直没来，用户说付了钱，怎么办？
5. 回调先返回 success 再处理，行不行？
6. 回调里的订单号找不到/金额不一致，怎么处理？

**答法要点**：
- 验签（RSA2/HMAC）→ 校验 `app_id/商户号/订单号/金额/币种` → 事件唯一键（渠道流水号）→
  支付单状态 CAS → 写 outbox → 业务消费幂等。
- 先落库处理成功再返回渠道约定响应；处理失败不能返回 success，否则渠道不再重试。
- 掉单：发起支付后登记补偿任务，按 5s/30s/1m/3m/5m/10m 退避查单；T+1 对账兜底。
- 金额不一致：拒绝、告警、人工介入，绝不能按回调金额直接入账。
- 关闭/过期支付单收到成功回调：不直接履约，进入退款/人工队列。

参考：[微信支付掉单与对账方案](https://pay.weixin.qq.com/doc/v3/merchant/4012075249.md)、
[支付宝异步通知处理](https://opendocs.alipay.com/open/64/103776)。

---

### 4. 订单、支付、额度三库一致性

**开场问题**：下单、支付、加额度在不同服务/库，怎么保证一致？

**追问链**：
1. 为什么不用 2PC/XA？
2. 本地消息表怎么保证消息一定发出去？
3. 消息重复/丢失/乱序怎么办？
4. TCC 的空回滚、悬挂怎么处理？
5. Saga 补偿失败怎么办？
6. 最终一致期间用户看到什么？

**答法要点**：
- 优先最终一致：本地事务 + outbox（或事务消息），消费者幂等，失败重试 + 死信 + 对账。
- 资金/库存核心链路可用 TCC/Saga，但要设计补偿、空回滚、悬挂、幂等。
- 2PC 阻塞、协调者单点、跨渠道不支持，互联网交易少用。
- 对账是最后防线：支付渠道账单 vs 支付单 vs 订单 vs 额度账本，差异告警 + 人工修复。

参考：[Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html)、
[Saga](https://microservices.io/patterns/data/saga.html)。

---

### 5. 金额、优惠、退款、账务

**开场问题**：金额用什么类型？优惠券/赠送额度怎么记账？退款怎么处理？

**追问链**：
1. 为什么不能用 double？金额单位是什么？
2. 部分退款、退款后赠送额度是否退回？
3. 用户已经消费了额度，还能退吗？
4. 退款重复回调怎么办？
5. 手续费/渠道费怎么记？
6. 怎么证明账是平的？

**答法要点**：
- 金额用 `long` 分或 `BigDecimal`，禁止 `double`；币种显式存。
- 预付费额度是平台负债：收款时「借现金/清算，贷用户额度负债」；消费时「借负债，贷收入」；
  赠送额度由营销费用承担或冲减收入。
- 退款：先退渠道，再扣回未消费额度；已消费部分按业务规则；退款单独立幂等键。
- 对账：用户额度余额之和 vs 渠道已收款 vs 收入/退款，逐日核对，差异表 + 告警。

参考：Modern Treasury 钱包双分录账本；Stripe customer balance（credit 与现金是两套账）。

---

### 6. 高并发、稳定性、故障处理

**追问链**：
1. 大促时支付回调洪峰怎么扛？
2. 渠道超时/限流/熔断怎么处理？
3. 数据库连接池被打满怎么办？
4. 消息积压/死信怎么处理？
5. 怎么发现「支付成功但没发货」？
6. 出了资损怎么定位/修复？

**答法要点**：
- 回调入口限流、验签前置、快速落库、异步履约；渠道调用超时 + 退避重试 + 熔断。
- 慢 SQL/连接池监控；消息积压看消费速率和死信；关键指标：支付成功率、回调延迟、掉单率、对账差异。
- 资损排查顺序：渠道账单 → 支付事件 → 支付单 → 业务单 → 履约账本；每步有 traceId/业务单号。

---

## 二、Mock 支付能做到什么程度

### 2.1 能真实跑通（建议都做）

| 能力 | Mock 能否 | 怎么模拟 |
|---|---|---|
| 支付单/订单状态机 | ✅ | Mock 网关驱动 `CREATED→PAYING→PAID/CLOSED/REFUNDED`，业务代码不特判 |
| 请求幂等 | ✅ | 客户端 `idempotency_key` + 服务端唯一约束；重复请求返回第一次结果 |
| 回调验签 | ✅ | Mock 用 HMAC/RSA 测试密钥签名，服务端走真实验签逻辑 |
| 金额/商户/订单校验 | ✅ | 构造金额错、订单号错、appId 错的回调 |
| 重复/延迟/乱序回调 | ✅ | 定时器/队列重放，注入 duplicate/delay/reorder |
| 回调丢失 + 主动查单 | ✅ | Mock 查单接口返回支付结果；补偿任务真实跑 |
| 超时关单 vs 支付成功 | ✅ | 控制时钟/延迟，制造 CAS 竞争 |
| 退款/部分退款 | ✅ | Mock 退款接口 + 退款单幂等 + 额度回退 |
| 对账 | ✅ | Mock 生成渠道账单（CSV/接口），与本地逐笔核对 |
| 故障注入 | ✅ | 500/超时/签名错/金额错/重复通知开关 |
| 双分录账本/余额守恒 | ✅ | 真实记账，不依赖渠道 |
| 并发/压测 | ✅ | Mock 延迟可控，压支付回调/履约 |

### 2.2 不能真正替代（要诚实说）

| 能力 | 说明 |
|---|---|
| 真实资金清算/到账 | Mock 不产生银行/渠道资金流，不能验证结算周期、备付金 |
| 渠道风控/3DS/实名/限额 | 只能用测试用例模拟返回码，无法覆盖真实策略 |
| 真实证书/密钥轮换/签名差异 | 可模拟算法，但渠道证书体系和字段差异要 sandbox 验证 |
| 拒付/chargeback/争议 | 真实卡组织流程无法 mock |
| 税务/合规/PCI | 与代码逻辑无关，mock 不覆盖 |
| 渠道对账文件格式差异 | 可造样例，但生产格式要以渠道文档为准 |

### 2.3 面试官追问「你这不是真支付吧？」怎么答

> 对，Mock 是渠道替身，不是跳过支付。支付域的状态机、幂等、金额校验、回调验签、outbox、
> 履约幂等、退款、对账和账本都是真的；生产只替换 `PaymentGateway` 实现和密钥，业务代码不变。
> Mock 的价值是能稳定复现渠道的失败模式（超时、重复、乱序、掉单、金额错），这些才是资损高发点。

参考：Stripe test mode 与支付宝沙箱都是同一思路——测试环境模拟渠道，业务链路走真实代码
（[Stripe Testing](https://docs.stripe.com/testing.md)、[支付宝沙箱](https://opendocs.alipay.com/open/09dfv2)）。

---

## 三、一套「能扛住追问」的 Mock 支付架构

```text
Client
  │ 1. create order (idempotency_key)
  ▼
Order Service ── 本地事务：订单 + outbox(OrderCreated)
  │ 2. create payment (bizNo, amount, idempotency_key)
  ▼
Payment Service ── 支付单 + 状态机 + 幂等键
  │ 3. call PaymentGateway
  ▼
Mock Gateway (fake PSP)
  ├─ 接受支付：生成渠道流水，延迟 N 秒
  ├─ 回调：签名 + 金额 + 事件ID，可重复/延迟/乱序
  ├─ 查单：返回当前状态
  ├─ 退款：退款单 + 幂等
  └─ 对账单：按日生成 CSV/JSON
  │ 4. callback
  ▼
Payment Service: 验签 → 金额/订单校验 → event 唯一键 → 支付单 CAS → outbox(PaymentSucceeded)
  │ 5. event
  ▼
Order/Fulfillment: 消费幂等（bizNo=mall:orderNo）→ 订单 PAID → 额度入账（双分录）
  │
  ├─ 补偿任务：未收到回调 → 查单
  └─ 日终对账：渠道账单 vs 支付单 vs 订单 vs 账本
```

Mock 网关必须支持「剧本」：成功、失败、超时、重复回调、延迟回调、金额错、签名错、关单后回调、
退款成功/失败。每个剧本都有自动化测试。

---

## 四、对照本项目（chatry + atry）

### 已经有的

- 支付单/订单分离，`PaymentConfirmationService` 只改支付单、写 outbox，业务侧消费事件。
- 回调金额校验；`t_payment_event(provider,event_key)` 唯一键；重复回调幂等。
- 履约 `bizNo=mall:orderNo` 幂等，重复事件不会重复加额度。
- 订单超时关闭 + 库存回补；支付单状态 `CREATED/PAYING/PAID`。
- 订单限购唯一键、库存条件扣减、分布式锁（支付发起）。

### 面试会被追问穿的缺口

1. ~~`mock-pay` 无生产守卫~~：P6 已加 `provider=mock + mockEnabled + 非 prod` 守卫。
2. ~~支付确认忽略 `markPaid` 影响行数~~：P6 已改为状态 CAS，关闭/过期回调拒绝且不写发件箱。
3. 没有资金侧账本：只有额度账本，无法证明「收了多少现金、发了多少额度」（P7）。
4. Mock 没有签名、查单、退款、对账、故障注入，只覆盖 happy path（P9）。
5. ~~额度显示成 `¥余额`~~：P6 已改「充值额度 / 到账额度 / 支付金额」。

### 建议补齐顺序

1. **P6.1 安全/状态**：mock 接口守卫、`markPaid` 行数检查、关闭单回调不履约、测试。
2. **P6.2 账本**：资金总账 + 双分录 + 对账；额度子账保留。
3. **P6.3 Mock 网关完善**：签名、查单、退款、账单、故障剧本；Mock 只挂 dev/test。
4. **P6.4 面试叙事**：把「状态机 + 幂等三层 + outbox + 对账 + 双分录」讲成一条线。

---

## 五、30 秒面试回答模板

> 我们把支付渠道抽象成 `PaymentGateway`，Mock 只是其中一个实现。下单先生成业务单和支付单，
> 支付单有独立状态机；回调走验签、金额/商户/订单校验、事件唯一键、状态 CAS，然后写 outbox，
> 业务侧幂等消费。回调丢了有主动查单和 T+1 对账兜底；额度是预付费负债，收款和消费都走双分录。
> Mock 用来稳定复现超时、重复、乱序、掉单、金额错这些故障，生产切换真实网关并禁用 Mock。
> 这样面试官问「重试/重复/掉单/对账/退款」，每一步都有对应的机制和数据可以讲。

---

## 参考链接

- Stripe Idempotent requests: <https://docs.stripe.com/api/idempotent_requests.md>
- Stripe PaymentIntent lifecycle: <https://docs.stripe.com/payments/paymentintents/lifecycle>
- Stripe Testing: <https://docs.stripe.com/testing.md>
- 微信支付掉单与对账: <https://pay.weixin.qq.com/doc/v3/merchant/4012075249.md>
- 支付宝异步通知处理: <https://opendocs.alipay.com/open/64/103776>
- 支付宝沙箱: <https://opendocs.alipay.com/open/09dfv2>
- Transactional outbox: <https://microservices.io/patterns/data/transactional-outbox.html>
- Saga: <https://microservices.io/patterns/data/saga.html>
- Modern Treasury 钱包双分录: <https://www.moderntreasury.com/journal/accounting-for-developers-part-ii>
- 支付幂等面试题（社区整理）: <https://github.com/JsonViewerMusk/interview-advanced-java-20250916/blob/main/docs/distributed-system/distributed-system-idempotency.md>
