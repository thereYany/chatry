# chatry

对话 AI 产品 + AI 内核，**单仓工作区**：一次克隆即可构建、运行、改内核。

```text
chatry/                  ← 本仓库（工作区根）
├── atry/                # 通用 AI 内核：平台/身份/支付/计量/LLM 治理
└── chatry/              # 垂直产品：对话、额度商城、秒杀、管理后台
```

## 为什么放一个仓

- **克隆一次就能跑**：内核与产品版本严格对应，不需要先装内核；
- **改动一次提交**：跨内核和产品的改动（比如新增一个工具调用接口）在一个 PR 里完成；
- **CI 一次跑完**：内核测试 + 集成测试 + 产品测试 + 前端构建 + 镜像扫描。

历史上 atry 曾是独立仓库（`thereYany/atry`，保留为内核历史归档，冻结在 0.12.0）；
从 0.12.0 起统一在本仓库演进。

## 快速开始（本地）

前置：JDK 21、Maven 3.9+、Node 20+、MySQL 8、Redis 7。

```bash
# 1) 构建并安装内核到本地仓库
mvn -B -DskipTests install -f atry/server/pom.xml

# 2) 跑产品测试 / 打包
mvn -B test -f chatry/pom.xml
mvn -B -DskipTests package -f chatry/pom.xml

# 3) 前端构建（产物会复制到 chatry-app 的 static 目录）
bash chatry/scripts/build-web.sh

# 4) 启动（示例环境变量见 chatry/README.md）
java -jar chatry/chatry-app/target/chatry-app-*-exec.jar
```

## CI

`.github/workflows/ci.yml` 五个 job：

| job | 内容 |
|---|---|
| `kernel` | 数据边界门禁、Flyway 纪律、内核单测、集成测试（MySQL + Redis） |
| `app` | 安装内核 → 产品单测 → 打包 → 上传 jar |
| `web` | `npm ci` + `vite build` |
| `docs` | 关键文档存在性门禁 |
| `image` | Docker 构建 + Trivy CRITICAL 扫描 |

Dependabot 只跟 patch/minor，大版本需要单独评估（`.github/dependabot.yml`）。

## 文档

- 支付路线图（P6–P10，已完成）：[chatry/docs/payment-roadmap-p6-p10.md](chatry/docs/payment-roadmap-p6-p10.md)
- 支付运行手册：[chatry/docs/runbook-payment.md](chatry/docs/runbook-payment.md)
- 支付演示脚本：[chatry/docs/demo-payment-5min.md](chatry/docs/demo-payment-5min.md)
- AI 能力层调研与 P11–P14 规划：[chatry/docs/ai-layer-research-and-plan.md](chatry/docs/ai-layer-research-and-plan.md)
- 待办与阶段状态：[atry-待办清单-2026-11-21.md](atry-待办清单-2026-11-21.md)
- 内核说明：[atry/README.md](atry/README.md)；产品说明：[chatry/README.md](chatry/README.md)

## 版本

当前工作区版本 **0.12.0**：内核 0.12.0 + 产品 0.12.0。
