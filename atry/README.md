# atry

[![ci](https://github.com/thereYany/atry/actions/workflows/ci.yml/badge.svg)](https://github.com/thereYany/atry/actions/workflows/ci.yml)

**AI 应用基座**：与业务无关的 Java 21 / Spring Boot 4.1.2 模块化单体，把大模型调用治理、计量计费、身份/权限、支付与运营可靠性做成可复用的内核能力，业务方只写自己的垂直域。

> 仓库有意不保留设计文档（旧文档会误导后来的维护者），**以代码与本 README 为准**；历史文档可在 git 历史中查阅。

## 模块

```
atry-app            宿主应用：演示端点 + 运营接口（只装配内核）
 ├─ atry-llm        LLM 调用治理：多 provider 路由、四层超时、重试/熔断、多维限流、预算守卫、流式调用、调用记录与 outbox
 ├─ atry-metering   计量计费：预扣 → 结算/释放 → 不可变账本；定价（分 token 类型）、充值、对账、账单、过期回收
 ├─ atry-identity   身份：JWT、RBAC、邀请注册、登录节流、改密/会话失效
 ├─ atry-payment    支付：支付单状态机、渠道抽象（支付宝/模拟）、回调 + 主动查单兜底、发件箱、对账、退款数据与告警
 ├─ atry-contracts  契约：业务只依赖这里的接口（LlmOperations / MeteringOperations / PaymentOperations / 身份）
 └─ atry-platform   平台 starter：多租户 SQL 拦截、AOP（幂等/审计/限流/计量）、告警、对象存储、追踪、健康检查
examples/x-try-template  垂直接入模板：支付履约事件监听的最小示例
```

依赖方向由 ArchUnit + Spring Modulith 门禁强制：`platform` 不依赖业务模块，四个业务模块之间只通过 `contracts` 交互，内核不依赖宿主。

## 技术栈

Java 21 · Spring Boot 4.1.2 · MySQL 8（Flyway）· Redis 7 · JdbcTemplate/MyBatis · Resilience4j · Micrometer + OpenTelemetry · Docker Compose

## 质量与证据

| 项 | 数值 |
|---|---|
| 测试 | **488 用例**：单元 + 架构门禁 414（`mvn test`），集成 71（`-Pintegration`，4 skip；其中 1 个 Testcontainers 用例无 Docker 时跳过），垂直模板 4 |
| 覆盖率 | JaCoCo 行 **70.8%** / 分支 67.4%（`atry-llm` 行覆盖 89.6%） |
| 并发 | 32 线程对余额 10.000000 并发预扣 3.000000：**恰好 3 成功、29 拒绝**，冻结 9.000000，无超卖 |
| 多实例 | Redis 共享令牌桶：A 实例用满额度后 B 实例立即拒绝；回调 / 充值 / 邀请 / 发件箱并发幂等 |
| CI | data-boundary 棘轮（跨模块表访问只降不升）+ backend（单测 / 集成 / JaCoCo） |

## 可观测性与错误契约（0.2.0+）

**日志**（`logback-spring.xml`，默认文本 / `prod` profile JSON）：

- 每行带 `traceId / userId / tenantId`（MDC）；消息统一走 `%maskedMsg` 脱敏
- 三份文件：`logs/<app>.log`（应用）、`-error.log`（仅 ERROR）、`-access.log`（访问日志）
- 异步写盘（queueSize 4096，不静默丢日志）；滚动 20MB/14 天/1GB
- 脱敏规则：密码/令牌/密钥整段 `******`，手机号/身份证/邮箱打码；JSON 输出额外用 MaskingJsonGeneratorDecorator

**访问日志**（`AccessLogFilter`）：

```
access method=POST path=/api/chat/x status=200 latencyMs=67 userId=2 tenantId=0 ip=1.2.3.4 slow=false traceId=... ua="..."
```

只记 path 不记 query；健康检查/静态资源跳过；SSE 等异步请求在 `onComplete` 记录**完整流时长**。
开关：`LOG_ACCESS_ENABLED`（默认 true）、`ACCESS_LOG_SLOW_MS`（慢请求标记阈值，默认 1000ms）。

**统一错误体**（`GlobalExceptionHandler`）：

```json
{"code":"VALIDATION_FAILED","message":"用户名不能为空","traceId":"...","path":"/api/auth/register",
 "timestamp":"2026-11-21T...Z","details":[{"field":"username","message":"用户名不能为空"}]}
```

错误码目录见 `ErrorCodes`（只增不改）；未知异常/数据库异常只返回兜底文案，堆栈与 SQL 只进日志。

**慢 SQL**：`SLOW_SQL_ENABLED=true`（默认关）+ `SLOW_SQL_THRESHOLD_MS=200`；只记录超阈值语句并脱敏，不带参数值。

**OpenAPI**：`/v3/api-docs`、`/swagger-ui.html`（`SPRINGDOC_ENABLED`，生产 profile 默认关）。

## 注册与认证（0.3.0）

- `POST /api/auth/register`：用户名 + 密码 + 昵称 + 邮箱（可选），成功直接返回 token（免二次登录）
- **不接验证码/短信/邮件**（按产品约束）：防刷靠 `@RateLimit(dimension=IP)`（默认每 IP 每小时 10 次）、
  用户名/邮箱租户内唯一键、注册总开关；邮箱只做格式校验与唯一性，不验证真实性
- 开关与默认角色：`app.auth.registration-enabled`、`app.auth.default-role`（默认 USER）
- 登录/注册/登出/失败都写 `t_auth_event`（user_id/username/event_type/success/ip/ua/detail），
  审计写入失败只告警、不影响认证主流程；失败详情只记分类（如 `BAD_CREDENTIALS`），不记密码/令牌
- 登录节流沿用账号+IP 双维度（Redis + 本地降级）；注册限流走统一 `@RateLimit` 切面
- **会话管理（0.4.0）**：登录/注册签发带 `sid` 的 token 并落 `t_user_session`；Redis 活跃标记 + DB 兜底，
  支持 `GET /api/auth/sessions`（当前设备标记）、`DELETE /api/auth/sessions/{sid}`（单会话踢下线）、
  `POST /api/auth/logout`（吊销当前会话）。多实例即时生效；无 sid 的旧 token 仅做用户状态/版本校验。
  每日清理过期/已吊销会话（保留 30 天，可配）。

**数据库迁移约定**：内核迁移固定 `V1xx–V5xx`，垂直应用使用 `V9xx`。已有垂直库升级内核时，
新内核迁移版本号小于已执行的垂直线，需要 `spring.flyway.out-of-order=true`（当前 chatry 如此）；
P4 会补「版本段分配 + 乱序禁止」的校验脚本与 CI 门禁。
## 权限与平台能力（0.5.0）

- **RBAC 多实例失效**：Caffeine 本地缓存 + Redis 频道 `atry:rbac:invalidate` 广播；改角色/授权后所有实例即时失效（实测两实例 60ms 内生效），Redis 不可用时最迟 2 分钟 TTL 兜底。
- **`@DistributedLock`**：Redis `SET NX PX` + Lua 比对 token 解锁，支持 `waitMs/leaseMs/SpEL key`；Redis 故障 fail-closed（503）。已用于支付发起入口。
- **切面顺序**：`AspectOrders` 常量 + 反射测试：Measured(-1100) → Permission(-1000) → RateLimit(-900) → Idempotent(-800) → DistributedLock(-700) → Audit(100) → Metering(500)。
- **限流配置化**：注解默认值可被 `app.rate-limit.<key>.limit` / `.window-seconds` 覆盖；命中打 `atry.rate_limit.blocked` 指标并发布 `RateLimitExceededEvent`（注册 429 会补写 `REGISTER_THROTTLED` 审计）。
- **管理接口下沉**：`/api/admin/users`、`/api/admin/roles`、`/api/admin/permissions`（含分配角色、重置密码）进入 identity 内核，垂直应用直接复用。

## 持久层试点与工程纪律（0.6.0）

**MyBatis 试点（只迁移复杂读，写/事务仍是 JdbcTemplate）**
- `/api/admin/users` 读路径改为 MyBatis XML（`AccountQueryMapper`）：动态过滤 + 分页，SQL 显式带 `tenant_id`。
- `MybatisTenantGuard`：MyBatis 不走 `TenantAwareJdbcTemplate`，因此 Mapper SQL 缺少 `tenant_id` 时 **fail-fast** 拒绝；系统作用域（定时任务/回调）跳过。
- A/B 记录：旧实现 88 行动态拼 SQL → 新 mapper XML 43 行 + Service 55 行；单表索引查询，100 次请求 p95 ≈ 35ms（本机），无性能回退；结论：**复杂读用 MyBatis，写/事务不迁移**。

**Flyway 纪律**
- 版本段：identity 1xx / payment 2xx / platform 3xx / llm 4xx / metering 5xx；`tools/flyway_discipline.py` 校验文件名、段归属、全局唯一与已发布校验和（`docs/generated/flyway-checksums.json`）。
- CI 的 data-boundary job 增加 `--verify`；修改历史迁移或新增未登记迁移会直接失败。

**其它**
- Testcontainers：`TestcontainersAuthIntegrationTest` 用 MySQL 8 + Redis 7 容器跑注册全链路；无 Docker 自动跳过。
- 审计保留：`t_admin_audit_log` / `t_auth_event` 按 `app.audit.retention-days`（默认 180 天）分批清理，补 `created_at` 索引；cron 默认 03:20。
- JSON 日志脱敏回归：`LogbackMaskingConfigTest` 直接解析 logback 配置里的正则，防止配置笔误泄露凭据。


## 运维收口（0.7.0）

- **审计统一检索**：`GET /api/admin/audit-logs`（管理操作）与 `GET /api/admin/auth-events`（认证事件），
  需要 `admin:audit:view` 权限；按租户隔离，动作/操作人/事件/用户名/结果/日期可组合，分页返回。
  管理操作写入现在显式带 `tenant_id`（修复多租户串号隐患）。
- **可观测看板**：Grafana `atry overview` 增加限流命中、分布式锁、LLM token、`atry.method` p95 面板；
  Prometheus 增加 6 条告警规则（backend down / 5xx / p95 / 堆压 / 锁失败 / 限流飙升），见 `observability/README.md`。
- **CI 镜像与依赖**：`image` job 用 `server/Dockerfile` 构建镜像并用 Trivy 扫 CRITICAL（只报可修复）；
  Dependabot 周更 Maven / GitHub Actions / Docker。


## 支付交易收口（0.8.0）

- 支付单状态 CAS：`markPaid/markFailed` 只允许从 `CREATED/PAYING` 迁移，影响行数为 0 时拒绝；
  关闭/过期支付单收到成功回调不再写发件箱、不触发履约，落失败事件并告警。
- 重复成功事件（支付单已 PAID）按幂等处理，不再重复发 `PAYMENT_SUCCEEDED`。
- 新增 `PaymentStatus` 词表；关闭/过期后的迟到支付进入人工/退款处置（P8 接自动退款）。
- 支付发起继续使用分布式锁；回调金额校验、事件唯一键、履约 bizNo 幂等保持不变。
- 测试：单元 413、集成 71（4 skip），新增关闭支付单回调与已支付重复事件用例。


## 资金账本与对账（0.9.0，chatry 侧）

- 新增 `t_fund_ledger_account/transaction/entry/meta/check`：资产（清算）、负债（用户额度）、收入、
  营销费用、期初权益五类账户；每笔交易借贷必须相等，事件键唯一幂等。
- 记账规则：充值「借 PSP_CLEARING（实付）+ 借 PROMO_EXPENSE（赠送）／贷 CREDIT_LIABILITY（到账）」；
  消费「借 CREDIT_LIABILITY／贷 REVENUE」；新手赠送「借 PROMO_EXPENSE／贷 CREDIT_LIABILITY」。
- 历史数据用 `OPENING_EQUITY` 一次性期初回填（额度余额 + 已支付订单），不从凭空余额开始。
- 消费分录在对话结算后即时补记，另有 30s sweep 兜底；日终对账快照落到 `t_fund_ledger_check`。
- 管理端 `/api/admin/fund-ledger/summary`（权限 `admin:finance:view`）：额度负债 vs 计量余额、
  清算 vs 已付订单、借贷不平数。
- 实测：负债 222.990262 = 计量余额 222.990262；清算 139.86 = 已付订单 139.86；借贷不平 0 笔。


## 退款与售后逆向（0.10.0）

- `RefundOperations` 契约 + `RefundService` 实现：金额不可超退（已退+本次 ≤ 支付单金额）、
  `refundNo` 幂等、渠道 `out_request_no` 复用退款单号；支持部分退款（V201 去掉「一单只能退一次」唯一键）。
- 失败退避重试：`FAILED` + `next_retry_at`（指数退避），`RefundRetryJob` 跨租户重试，
  超过上限留在 FAILED 供人工处理，不静默丢单。
- 成功后写 `REFUND_SUCCEEDED` 发件箱事件，业务侧消费完成额度扣回/订单状态/资金分录；
  全额退款时支付单转 `REFUNDED`。
- 新增 `MeteringAdminOperations.adjust`：允许负向调整且可配置「允许扣到 0」，供退款扣回额度复用。
- 测试：单元 421、集成 71；退款用例覆盖超退拒绝、未支付拒绝、幂等、成功事件、失败重试。


## Mock 渠道工程化（0.11.0）

Mock 不再是「一个确认按钮」，而是一个可控的假 PSP：

- **真实回调链路**：`MockCashierService` 生成渠道流水 → 用 `MockSignatureService`（HMAC-SHA256）签名
  → 回调入口验签 → `PaymentCallbackService.acceptVerified`。签名错、金额错会被真实拒绝。
- **故障剧本**（`t_payment_mock_scenario`，V202）：`SUCCESS` / `FAILED` / `DUPLICATE` / `DELAYED` /
  `AMOUNT_MISMATCH` / `BAD_SIGNATURE` / `AFTER_CLOSE` / `QUERY_ONLY`（只写渠道事实不回调，验证查单兜底）。
- **开发台接口**：`POST /api/mock/payments/{paymentNo}/scenario` 设剧本、
  `POST .../simulate` 触发、`GET /api/mock/payments/scenarios` 列场景；
  `@ConditionalOnProperty(mock-enabled=true)` + 非 prod 双重保护，生产不注册。
- **退款剧本**：`POST /api/mock/payments/refunds/{refundNo}/fail-once` 让退款失败一次，驱动退避重试验证。
- 集成测试 `MockScenarioIntegrationTest` 8 个用例覆盖全部场景。


## 支付可观测与运行手册（0.12.0）

- **运营指标**（chatry `PaymentOpsMetrics`，每 30s 刷新）：`chatry_payment_stuck`（PAYING 超时）、
  `chatry_refund_pending`、`chatry_refund_exhausted`、`chatry_paid_after_close`、`chatry_outbox_pending`、
  `chatry_ledger_unbalanced`、`chatry_ledger_liability_diff`、`chatry_ledger_cash_diff`。
- **看板**：`atry overview` 增加退款积压、卡单/关单后待退款、账本健康、发件箱积压 4 个面板。
- **告警**：新增 7 条支付运营告警（账本不平/账实差异为 critical；卡单、退款积压、重试耗尽、
  关单后待处理、发件箱积压为 warning）。
- **运行手册**：`chatry/docs/runbook-payment.md`，覆盖卡单、重复回调/金额错、关单后支付、退款积压、
  账实差异、发件箱积压与故障注入；明确「只允许冲正、不改余额」。
- **演示脚本**：`chatry/docs/demo-payment-5min.md`，6 幕演示 + 面试问答卡片。
- 修复：chatry 之前关闭了主动查单任务（`PAYMENT_QUERY_ENABLED:false`），导致过期支付单永不关闭、
  查单兜底失效；已改为默认开启。同时修正查单对「渠道金额不一致」的误报（此前每轮打「已结算」），
  现在记 `payment_query_rejected` 指标与明确告警日志。


## 快速开始

前置：JDK 21、Maven 3.9、MySQL 8、Redis 7。

```bash
cd server
mvn -B -DskipTests install

DB_URL='jdbc:mysql://127.0.0.1:3306/atry_dev?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true' \
DB_USER=root DB_PASSWORD=root REDIS_HOST=127.0.0.1 \
java -jar atry-app/target/atry-app-0.12.0-exec.jar
```

默认端口 8084（管理端 9091），Flyway 启动时自动建表并创建管理员 `admin`（密码见启动日志或 `SEED_ADMIN_PASSWORD`，首次登录强制改密）。默认使用 **mock LLM provider**，无需任何外部 key 即可跑通全链路。

演示（mock 模型）：

```bash
# 登录拿 token → 同步调用 → 查余额
curl -s -X POST localhost:8084/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<初始密码>"}'
curl -s -X POST localhost:8084/api/demo/llm/chat -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' -d '{"message":"你好"}'
curl -s localhost:8084/api/demo/metering/balance -H "Authorization: Bearer <token>"
# SSE 流式：POST /api/demo/llm/stream（delta / done / error 事件）
```

接入真实 DeepSeek：`DEEPSEEK_ENABLED=true DEEPSEEK_API_KEY=...`（默认 `deepseek-flash`；不配置则用 mock）。

## 测试

```bash
cd server
mvn -B test                 # 单元 + 架构门禁（不需要外部依赖）
# 集成测试需要真实 MySQL / Redis；本地库用 atry_it，Redis 用 db15
IT_DB_URL='jdbc:mysql://127.0.0.1:3306/atry_it?allowPublicKeyRetrieval=true&useSSL=false&allowMultiQueries=true' \
IT_DB_USER=<user> IT_DB_PASSWORD=<pass> IT_REDIS_DATABASE=15 \
mvn -B -Pintegration test
mvn -B -q jacoco:report     # 覆盖率报告：各模块 target/site/jacoco/index.html
```

CI 的集成测试环境变量约定见 `.github/workflows/ci.yml`。

## 已知限制

- **多租户暂未启用**：租户隔离链路（SQL 改写 / 系统作用域）已实现并有单测，但登录、邀请、支付唯一键等仍按单租户部署假设；3 个多租户用例以 `@Disabled` 标注为已知限制，不宣称生产可用。
- **支付**：默认 mock 渠道用于演示；支付宝渠道已接入协议与验签/对账，但需要商户资质与真实密钥；退款目前只有数据模型、查询与告警，退款执行链路未实现。
- **演示与合规**：项目仅作个人技术演示，不公开运营；真实模型调用仅在本地/临时演示时开启，公开演示使用 mock provider。
- 未做：多实例 outbox 抢占/分片、周期额度（Entitlement）、账单税务/多币种、真实 provider 高并发压测。

## License

个人项目，未声明开源许可证；如需引用请联系仓库作者。
