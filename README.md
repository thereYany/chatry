# chatry

在 [atry](https://github.com/thereYany/atry) 内核之上做的**对话 AI 应用**：与 DeepSeek 网页版/豆包同形态——登录、多轮对话、流式输出、按用量扣额度；后续接商城售卖额度与秒杀。

当前进度：**M0 + M1 完成**（骨架、对话/额度闭环）。M2 商城、M3 秒杀、M4 React 前端待做。

## 架构

```
chatry-app                     宿主 + 垂直业务（本仓库唯一模块）
 ├─ chat/                      会话、消息、多轮上下文、SSE 对话编排
 ├─ credits/                   额度 = atry 计量账户余额（新手赠送、成本口径、余额查询）
 └─ bootstrap/                 管理员/演示账号种子
        │ 只依赖 contracts
        ▼
atry 内核（Maven 依赖 atry-parent:0.1.0）
 atry-llm        LLM 治理：超时/重试/熔断/限流/预算守卫/流式/调用记录/outbox
 atry-metering   计量计费：预扣 → 结算/释放、不可变账本、定价、账单
 atry-identity   JWT 登录、RBAC、账号
 atry-platform   租户 SQL 拦截、AOP、告警、追踪
```

垂直域不复制内核逻辑：对话里没有超时/重试/计费代码，全部走 `LlmOperations` 契约；额度不建第二套账本，直接复用 `t_metering_*`。

## 数据

- `t_chat_conversation` / `t_chat_message`（`V900`，V9xx 段避开内核版本）
- 内核表：`t_user` 等身份表、`t_metering_account/ledger/reservation`、`t_llm_call_record`
- 每条助手消息落库 `input_tokens/output_tokens/cost_micros/status`，与账本逐笔对得上

## 快速开始

前置：JDK 21、Maven、MySQL 8、Redis 7。

```bash
# 1) 数据库（一次）
sudo mysql -e "CREATE DATABASE IF NOT EXISTS chatry_dev CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'chat'@'localhost' IDENTIFIED BY 'chatpass';
GRANT ALL PRIVILEGES ON chatry_dev.* TO 'chat'@'localhost'; FLUSH PRIVILEGES;"

# 2) 构建 + 启动（默认 mock 模型，不需要任何 key）
mvn -B -DskipTests package
scripts/dev-run.sh

# 3) 端到端冒烟
scripts/smoke.sh
```

- 应用：http://127.0.0.1:8084 ，健康：`/api/health`（或 9092 `/actuator/health`）
- 演示账号：`demo / demo123456`；管理员：`admin / admin12345`（首次登录强制改密）
- 接真实模型：`DEEPSEEK_ENABLED=true DEEPSEEK_API_KEY=... CHATRY_CHAT_MODEL=deepseek-flash`

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录（内核） |
| GET | `/api/chat/credits` | 额度：余额/冻结/新手赠送/估算可聊次数 |
| POST | `/api/chat/conversations` | 新建会话 |
| GET | `/api/chat/conversations` | 会话列表 |
| GET | `/api/chat/conversations/{id}` | 会话详情 |
| GET | `/api/chat/conversations/{id}/messages` | 历史消息 |
| DELETE | `/api/chat/conversations/{id}` | 软删除会话 |
| POST | `/api/chat/conversations/{id}/messages` | 同步对话（JSON：内容/用量/成本/余额） |
| POST | `/api/chat/conversations/{id}/messages/stream` | SSE：`delta` / `done` / `error` |

SSE 示例：

```bash
curl -sN -X POST localhost:8084/api/chat/conversations/1/messages/stream \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"你好"}'
```

## 已验证的证据（2026-11-20 本机）

- 多轮上下文：连续三轮输入 token 3 → 11 → 38，历史确实拼进了请求。
- 结算一致：账本 `settle` 金额 = 消息 `cost_micros`（83/27/54 微元），余额逐笔下降且等于 `balance_after`。
- 断连释放：0.2s 掐断 SSE 后预扣 `RELEASED`、余额不变、已生成部分按 `CANCELLED` 落库不计费。
- 用户隔离：他人会话返回 404。
- 单测：9 个用例全绿（上下文截断、取消落库、成本换算等）。

## 设计取舍

- **额度即余额**：商城售卖/秒杀 = 幂等充值（`chatry-grant:*` 之外新增业务单号），不造独立点数账本；对账、退款、并发防超卖全部复用内核。
- **断连不计费**：与 atry 现有语义一致（按 CANCELLED 释放预扣）；若产品要求「已生成即计费」，需在 sink 层累计部分 usage，属于 M2 之后的产品决策。
- **上下文预算**：最近 20 条 + 12000 字符，保证最后一轮用户消息必进上下文。
- **请求号**：`chat` + 32 位 UUID，共 36 字符，满足内核 `t_metering_reservation.reservation_id VARCHAR(40)`（`llm:` 前缀后不溢出）。

## 已知限制

- 默认 mock provider（本地全链路无需 key）；真实 DeepSeek 只建议本机开。
- 单租户（tenant_id=0）部署假设；多租户按 atry 的既有结论暂不启用。
- 额度有效期、退款、发票不在 M1 范围。
- 前端未开始（M4 将用 React + Vite）。
