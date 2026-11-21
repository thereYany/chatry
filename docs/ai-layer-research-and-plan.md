# AI 能力层：市场与招聘调研 + 设计方案（P11 起）

> 调研时间 2026-11-21。目标：让「对话式 AI」升级成「可交付、可评测、能干活」的 AI 应用，
> 同时对齐当前市场上大模型应用 / AI Agent 岗位的真实要求。
> 本文只做调研与方案，不含实现。

## 一、结论先说

1. **只做「聊天 + 流式 + 计费」已经不够。** 2026 年的岗位要求已经收敛成四件套：
   **RAG、Tool Calling、上下文工程、评测**；Prompt 工程师岗位在萎缩，被「Agent 应用工程师 + 上下文工程」吸收。
2. **Java 不是劣势，反而是差异点。** 市场上大量候选人只会 Python 调 API；能把 LLM 接到
   **可靠性契约、计量账本、权限审计、幂等事务**里的人很少。我们已有的 atry 内核（重试/熔断/预算/计量/发件箱）
   正好是这部分。
3. **我们的缺口也很明确**：没有 system prompt/人格、没有工具调用、没有 RAG、没有结构化输出、
   没有记忆压缩、没有评测集、没有内容安全。这些是 P11–P14 要补的。
4. **不要为了追框架而换掉内核。** 有实测研究显示 Java 框架在失败契约上覆盖不全
   （Spring AI 2.0.1 72/77、LangChain4j 1.21 47/77，且都把「没有结束标记的流」当完整答案）；
   我们已有可靠性层，应保留并对外提供标准协议（OpenAI 风格 tool calling + MCP），而不是重写成框架应用。

## 二、市场与招聘调研

### 2.1 岗位硬性要求（2026）

| 方向 | 高频硬性要求 | 说明 |
|---|---|---|
| LLM 应用开发 | Prompt 设计、多轮对话管理、Tool/Function Calling、RAG + 向量库 | 中型厂/传统 IT 转 AI 的主流入门岗 |
| AI Agent 工程师 | Agent 编排（LangGraph/自研）、工具调用、记忆、规划、MCP | 与上一条高度重叠，要求更偏「能端到端交付」 |
| 平台/基础设施 | 模型接入、限流/重试/降级、token 成本、可观测、私有化部署 | Java 后端的主场，和 atry 现有能力重合度最高 |
| 算法岗 | 微调、推理加速、GPU | 与我们方向不同，不追 |

市场报告的关键判断（[AI Agent 岗位 JD 调研](https://github.com/harrisliangsu/ai-agent-engineer-handbook/blob/main/interview-prep/jd-requirements.md)）：
- 3–5 年经验普遍 25–60K·14/15 薪；
- 硬性要求高度趋同：**Python + LangChain/LangGraph + RAG + Tool Calling + 向量库 + Docker/K8s**；
- **Prompt 工程师独立岗位明显萎缩（约 -70%）**，被「Agent 应用工程师 / 上下文工程」吸收。

对应到我们：Java 栈没问题，但**RAG、Tool Calling、Agent 编排、评测**这四块必须有可演示的落地，
否则简历上只能写「调过 API」。

### 2.2 面试怎么问（高频追问链）

**RAG**（几乎必考，链路长、好追问）：
- 为什么需要 RAG？和长上下文、微调怎么选？
- 怎么分块？chunk size / overlap 怎么定？
- 纯向量检索有什么问题？为什么要混合检索 + RRF？
- 为什么要 Rerank？Bi-Encoder 和 Cross-Encoder 区别？
- 怎么评测？Recall@k、MRR、Faithfulness 分别测什么？
- 幻觉怎么处理？

来源：[RAG 面试题汇总](https://github.com/youngyangyang04/llm-master/blob/main/docs/interview/llm/rag_interview.md)、
[RAG 完整链路](https://blog.csdn.net/thesky123456/article/details/163340694)。

**Tool Calling / Agent**：
- 工具调用失败了怎么处理？（分类 → 是否 retryable → 是否幂等/有副作用 → 降级 → 记录 trace）
- 模型选了错工具/参数不对怎么办？
- Agent 怎么防止越权？写操作要不要人工确认？
- 怎么做评测：工具选择准确率、参数准确率、轨迹质量、错误恢复率。
- 上线看什么：P95 延迟、工具错误率、重试次数、每任务 token 成本、人工接管率。

来源：[工具调用失败处理](https://github.com/szylover/knowledge-library/blob/main/ai-agent-book/chapter-22-tooling-chain-interview-accelerator.md)、
[工具执行可靠性](https://github.com/cdavid817/agent-interview/blob/main/docs/02-capabilities/tools-skills-mcp/reliability.md)。

**上下文工程**：
- 上下文窗口不是越大越好：Lost in the Middle、成本、延迟；
- 怎么决定「给模型看什么、按什么顺序、带什么记忆、花多少 token」；
- 长对话怎么压缩（摘要 + 最近 N 轮 + 关键事实）。

**评测**（区分「做过」和「真做过」的关键）：
- Golden Set 怎么建？（生产日志分层采样、人工构造、失败回流）
- 规则评测 / LLM-as-Judge / 人工怎么分工？Judge 有哪几种偏差？
- RAG 为什么要分检索层和生成层两段评？
- 离线评测 → Trace 回放 → 线上灰度怎么串？

来源：[AI 应用评测体系（JavaGuide）](https://github.com/snailclimb/javaguide/blob/main/docs/ai/llm-basis/llm-evaluation.md)。

**Java 生态**：
- Spring AI 2.0 / LangChain4j 1.21 都支持 tool calling、MCP、向量库；
- 但框架的失败处理未必符合生产契约（见下文），面试官会追问「为什么自研那一层」。
- MCP 已成为事实标准（2026 年捐给 Linux Foundation），Java 侧主流框架都支持，
  暴露工具给外部 Agent 是加分项。

### 2.3 面试官真正在筛什么

结合调研和我们自己的面试经验，分三层：

1. **会不会用**：能调 API、能流式、能算 token —— 这是门槛，不加分。
2. **能不能工程化**：重试/熔断/超时/预算/降级/可观测/成本核算 —— 大部分候选人缺。
3. **能不能证明有效**：评测集、指标、灰度、badcase 闭环 —— 极少有人能讲清楚。

我们已有的 P1–P10 把第 2 层做得很扎实（甚至超出一般业务团队），
第 3 层只做了成本和延迟，缺质量维度；第 1 层缺 RAG/工具/结构化输出。
所以后续重点应该是 **第 1 层补齐 + 第 3 层建立**，而不是继续堆第 2 层。

## 三、现状盘点（atry + chatry）

### 3.1 已有的（优势，讲得出来）

| 能力 | 实现 |
|---|---|
| 多 Provider 抽象 | `LlmOperations` + `LlmProviderRegistry`（DeepSeek / Mock） |
| 同步 + 流式 | `LlmService.invoke/stream`，增量回调、可取消、断开不计费 |
| 可靠性 | 重试预算、熔断、限流、超时/截止时间、错误分类 `LlmErrorCategory` |
| 成本与计量 | `t_llm_call_record`、`atry.llm.cost/tokens/ttft` 指标、按 token 预扣结算 |
| 预算守卫 | `@BudgetGuard` 切面（REJECT/DEGRADE） |
| 异步记账 | `t_llm_outbox` 投递调用记录 |
| 可观测 | traceId、调用日志、Prometheus 指标、审计 |
| 对话存储 | 会话/消息表、上下文按条数+字符预算截断 |

### 3.2 缺的（P11–P14 要补）

| 缺口 | 影响 | 对应面试题 |
|---|---|---|
| 没有 system prompt / 人格与指令管理 | 输出不可控，无法做产品化 | Prompt 工程、指令遵循 |
| 没有 tool/function calling | AI 只能聊天，不能干活 | 工具调用、Agent |
| 没有结构化输出（JSON Schema 约束） | 无法把 LLM 接到业务动作 | 结构化输出、格式遵循 |
| 没有 RAG | 不能基于私有知识回答 | RAG 全链路 |
| 没有记忆压缩（长对话摘要） | 长对话丢失早期信息 | 上下文工程 |
| 没有评测集与质量指标 | 无法证明改动有效 | 评测体系 |
| 没有内容安全/注入防护 | 越权、Prompt 注入风险 | 安全与合规 |
| 没有模型路由/降级策略 | 单一模型，成本与质量无法权衡 | 模型选型、成本优化 |
| 没有 trace 回放/调试台 | badcase 无法定位 | 可观测与根因 |

## 四、设计方案

### 4.1 分层

```text
chatry 应用层（垂直业务）
  ├─ 人格/场景提示词（版本化）
  ├─ 工具集：账户/订单/额度/退款（复用现有模块契约）
  ├─ RAG：知识库（产品文档、帮助中心、用户上传）
  ├─ 记忆：长对话摘要 + 事实抽取
  └─ 评测集与演示
        │
atry AI 内核层（通用）
  ├─ Prompt 注册与版本（模板 + 变量 + 灰度）
  ├─ 工具调用协议（JSON Schema → provider tool_calls → 执行 → 回填）
  ├─ 结构化输出（schema 校验 + 重试修复）
  ├─ 上下文编排（预算分配：系统/历史/检索/工具结果）
  ├─ 安全护栏（注入检测、脱敏、输出审核）
  ├─ 评测挂点（trace 记录、judge 调用、指标）
  └─ 已有：Provider/流式/重试/熔断/预算/计量/outbox
```

原则：**内核只管「怎么可靠地调模型、怎么把工具/检索接进来」，不认识任何垂直语义**；
工具的具体实现、知识库内容、人格文案都在 chatry。

### 4.2 关键设计取舍

**① 工具调用：内核定义协议，应用注册工具**

- 内核提供 `ToolRegistry` + `ToolDefinition(name, description, JSON Schema)`，执行前做参数校验；
- 模型返回 tool_calls → 内核执行（带超时、权限校验、幂等键）→ 结果作为 tool 消息回填 → 继续对话；
- 写操作（下单/退款）必须：**权限校验 + 幂等键 + 审计 + 可选人工确认**，不能由模型直接执行；
- 失败处理按调研的四步：分类 → 是否可重试/幂等 → 降级（缓存/只读/兜底答案/转人工）→ 记录 trace。

**② RAG：先用 MySQL + 向量列，不引入重型组件**

- 文档表 + chunk 表 + embedding 列（MySQL 8 无原生向量索引时用召回后精确计算，或接 pgvector/Redis Search 作为演进）；
- 分块：按标题层级 + 固定 token 窗口 + overlap；表格/代码单独策略；
- 检索：关键词（MySQL FULLTEXT/BM25 近似）+ 向量，**RRF 融合**，再可选 rerank（先用 LLM 做轻量重排）；
- 生成：把 top-k 段落注入，要求「只基于资料回答 + 引用编号」，低于阈值直接回答「未找到」；
- 评测：Recall@k / MRR（检索层）、Faithfulness / Relevancy（生成层）。

**③ 上下文工程：预算化编排**

- 给一次请求分配 token 预算：系统提示（固定）→ 最近 N 轮（必保）→ 检索片段（按分数）→ 摘要（压缩）→ 工具结果；
- 超预算时按优先级裁剪，并记录「被裁剪了什么」（可解释）；
- 长对话用滚动摘要 + 关键事实（预算、偏好、未完成事项）。

**④ 评测：先规则，再 Judge，最后人工**

- Golden Set 从真实对话 + 人工构造（含对抗样本）起步，20–50 条即可开始；
- 规则评测优先（JSON 合法、字段完整、引用存在、工具调用正确）；
- 开放式质量用 LLM-as-Judge（给 rubric 和参考答案，注意位置/冗长/自我强化偏差）；
- 指标落库，和 prompt 版本、模型版本、代码 commit 绑定；CI 里跑核心回归集。

**⑤ 为什么不自研框架替换**

引用实测：Java 框架在 15 类失败场景下的可靠性契约覆盖不足，且都会把「没有结束标记的流」当完整答案
（[arXiv:2610.25480](https://arxiv.org/abs/2610.25480)）。我们已经有重试/熔断/预算/流终止判断，
应保留并以标准协议（OpenAI tool calling 格式、MCP）对外，而不是重写成框架应用。
面试回答：「框架负责抽象，可靠性必须自己兜底，否则线上会出现半截答案按成功计费。」

## 五、阶段规划（P11–P14）

> 按既定纪律：一个 P 是一个完整交付阶段，不再拆小阶段。

### P11 — 提示词、结构化输出与工具调用（AI 能「按格式、按指令干活」）

**交付**
- Prompt 注册与版本：`t_ai_prompt`（code/version/template/variables/status），聊天默认人格走配置；
- 上下文编排升级：系统提示 + 预算分配 + 裁剪记录；
- 结构化输出：JSON Schema 校验 + 失败重试一次 + 解析降级；
- 工具调用协议：`ToolRegistry`、Schema 校验、执行超时、权限与审计、幂等键；
- chatry 首批工具（只读）：查额度、查订单、查退款状态（复用现有契约，天然带权限与审计）；
- 前端：对话中展示工具调用过程（「正在查询订单…」+ 结果卡片）。

**验收**：模型能按 JSON Schema 返回结构化结果；能调用只读工具并正确回填；工具失败有降级与 trace；
prompt 改版本后可回滚。

**面试价值**：Prompt 工程、function calling、工具失败处理四步、权限与幂等。

### P12 — RAG 知识库（AI 能「基于私有资料回答并给引用」）

**交付**
- 文档接入：TXT/MD/PDF 解析（PDF 用现成解析库）→ 清洗 → 分块 → embedding；
- 存储：`t_ai_document` / `t_ai_chunk`（含向量、元数据、权限域）；
- 检索：关键词 + 向量混合，RRF 融合；可选 LLM 轻量 rerank；
- 生成：引用编号、无依据时拒答、低于阈值兜底；
- 管理端：上传/重建索引/查看检索命中；
- 评测：内置 20–50 条 RAG Golden Set，输出 Recall@k / MRR / Faithfulness（规则 + Judge）。

**验收**：上传文档后可问答并给出引用；同一问题改检索策略能在评测集上看到指标变化；
无资料时明确说「没找到」而不是编。

**面试价值**：RAG 全链路、分块、混合检索、rerank、幻觉处理、检索/生成分段评测。

### P13 — Agent 与记忆（AI 能「多步完成任务」）

**交付**
- Agent 循环：意图 → 计划 → 调工具 → 观察 → 再决策（上限轮次、超时、预算）；
- 写操作：下单/充值需二次确认（前端确认卡片），执行带幂等键与审计；
- 失败恢复：工具超时/限流/业务冲突的降级与重规划；
- 记忆：长对话滚动摘要 + 用户事实（偏好、额度、未完成事项）；
- 安全：Prompt 注入检测、工具参数白名单、越权拦截；
- 演示：一句话完成「查额度 → 选额度包 → 下单 → 确认支付」。

**验收**：多步任务成功率可测；写操作必须人工确认且可审计；注入样本被拦截。

**面试价值**：Agent 编排、工具选择/参数准确率、错误恢复、越权防护、记忆。

### P14 — 评测与质量可观测（能证明「改得更好」）

**交付**
- 评测 Harness：Golden Set 管理、跑批、规则评分器 + LLM-as-Judge、结果落库；
- Trace 记录与回放：一次请求的 prompt 版本、检索片段、工具调用、模型输出全量存档；
- 质量看板：任务完成率、工具错误率、拒答率、Faithfulness、P95 延迟、每任务成本；
- 告警：质量指标下降、工具错误率飙升、拒答率异常；
- CI：核心回归集（规则类）跑在 PR，完整集跑定时任务；
- badcase 闭环：线上失败 → 分类 → 归因 → 回流评测集。

**验收**：能回答「这次改 Prompt/换模型/调检索，效果是变好还是变差」；
告警能触发；badcase 能定位到层（检索/提示词/模型/工具）。

**面试价值**：评测体系、LLM-as-Judge、离线/回放/灰度、badcase 归因。

### 依赖与顺序

```text
P11（提示词/结构化/工具）──► P12（RAG）──► P13（Agent/记忆）──► P14（评测）
```

P11 是地基（工具协议 + 结构化输出），P12 的 RAG 可以作为「一个工具」接入，
P13 才需要多步编排；P14 反过来给前三者提供回归保障。

## 六、差异化：把已有资产变成 AI 的「手和账本」

这是本项目相对普通「聊天机器人项目」最大的不同，也是面试最能讲的地方：

| 已有资产 | 在 AI 场景里的价值 |
|---|---|
| 支付/订单/额度模块 | 工具调用的真实副作用，不是玩具接口 |
| 幂等 + 状态机 | 模型重试/重复调用不会重复扣款 |
| RBAC + 审计 | 工具调用可鉴权、可追溯 |
| 双分录账本 | 每次 AI 触发的消费都有账可查 |
| 计量与预算守卫 | 按租户/用户限成本，防止刷爆 |
| 发件箱 | 工具副作用与事件最终一致 |

一句话总结：「别人做的是 Demo，我们做的是带账本、带权限、带幂等的 AI 应用。」

## 七、风险与边界（照实说）

- **RAG 效果依赖语料质量**：没有真实知识库时，用产品文档/帮助中心自建，别假装企业级；
- **向量检索在 MySQL 上的性能有限**：先单机验证，量大再评估 pgvector/专用向量库；
- **LLM-as-Judge 有偏差**：必须人工抽样校准，不能当唯一门禁；
- **Agent 写操作风险**：默认只读，写操作人工确认 + 幂等 + 审计；
- **成本**：评测与 Judge 也烧 token，需要预算控制（复用 `@BudgetGuard`）。

## 八、参考

- [AI Agent 岗位 JD 调研（2026-05）](https://github.com/harrisliangsu/ai-agent-engineer-handbook/blob/main/interview-prep/jd-requirements.md)
- [JavaGuide：AI 应用评测体系](https://github.com/snailclimb/javaguide/blob/main/docs/ai/llm-basis/llm-evaluation.md)
- [RAG 大厂面试题汇总](https://github.com/youngyangyang04/llm-master/blob/main/docs/interview/llm/rag_interview.md)
- [RAG 从原理到落地：分块、检索、重排与评估](https://blog.csdn.net/thesky123456/article/details/163340694)
- [工具执行可靠性（异常分类/重试/降级）](https://github.com/cdavid817/agent-interview/blob/main/docs/02-capabilities/tools-skills-mcp/reliability.md)
- [工具调用失败四步回答法](https://github.com/szylover/knowledge-library/blob/main/ai-agent-book/chapter-22-tooling-chain-interview-accelerator.md)
- [arXiv:2610.25480 Java LLM 框架可靠性对比](https://arxiv.org/abs/2610.25480)
- [MCP 规范](https://modelcontextprotocol.io/specification/2026-07-28/index)
