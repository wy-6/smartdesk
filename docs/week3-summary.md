# 第 3 周总结（交接文档）

> **用途**：跨会话交接。换新对话、隔几周回来、面试前回忆，读这一份就够。
>
> **状态**：第 1、2、3 周全部完成并验证通过。
> **最后更新**：2026-11-06 ｜ 对应提交见文末

---

## 0. 一句话现状

在第 2 周的后端骨架上接入了大模型能力：**双 Provider（离线 mock / OpenAI 兼容）+ 四类业务场景
（问答、摘要、分类、任务草稿）+ 结构化输出校验 + 超时重试 + 调用日志与指标 + SSE 流式**。
**105 个自动化测试 + 55 项端到端验收**全部通过，验收脚本可重复运行。

下一步是**第 4 周：Tool Calling**（让模型调用我们已有的任务接口）。

---

## 1. 本周交付

### 1.1 分层

```
AiController      收参数 / 取登录态 / 包响应        （不知道模型存在）
   │
AiService         Prompt 组装 → 调用 → 解析 → 校验 → 重试 → 记录
   │
ChatClient        Spring AI 门面（第 5 周在这里挂 RAG Advisor，第 6 周挂记忆 Advisor）
   │
ChatModel         MockChatModel / OpenAiCompatibleChatModel
   │
OpenAiChatTransport   JDK HttpClient（同步 + SSE）
```

**依赖方向是单向的**：换 Provider 只换 Bean，业务代码零改动。

### 1.2 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/chat` | 问答，返回回答 + Token/耗时/traceId |
| POST | `/api/ai/chat/stream` | SSE 流式问答（`delta` → `done`/`error`） |
| POST | `/api/ai/summarize` | 摘要（带字数上限） |
| POST | `/api/ai/classify` | 分类（返回值必须在白名单枚举内） |
| POST | `/api/ai/task-draft` | 自然语言 → 任务草稿，**不落库** |
| GET | `/api/ai/calls` | 我的调用记录（分页） |
| GET | `/api/ai/calls/{id}` | 我的某条记录（别人的返回 404） |

### 1.3 新表 `ai_call_log`

字段：`owner_id / trace_id / scene / provider / model / prompt_tokens / completion_tokens /
total_tokens / latency_ms / attempts / success / error_code / error_message / created_at`

索引：`idx_owner_created(owner_id, created_at)`、`idx_trace(trace_id)`

> 不存 Prompt / 回答全文 —— 业务文本可能含敏感信息，只存元数据。

---

## 2. 关键设计决策（面试可直接用）

| # | 决策 | 理由 |
|---|---|---|
| 1 | 只引 `spring-ai-client-chat`，不引 openai starter | starter 启动强制要 Key，破坏离线 mock；且把业务焊死在厂商 SDK |
| 2 | 默认 mock，配 `AI_PROVIDER=openai` 才用真实模型 | 测试可重复、CI 不烧钱、故障可注入、无 Key 可开发 |
| 3 | 模型输出当不可信输入：解析 → 校验 → 修复重试（≤1 次）→ 422 | 缺字段/类型错/越界/幻觉日期都不能进业务 |
| 4 | 任务草稿不落库，接口返回 200 | 模型永远不能直接产生副作用；确认后创建放第 6 周 |
| 5 | 错误码分 6 类，各自映射 HTTP（504/429/503/422/400/503） | 前端要按错误码分流，全 500 等于没设计 |
| 6 | 重试只针对可重试错误，指数退避 + 抖动，最多 3 次 | 无上限重试会把抖动放大成雪崩 |
| 7 | 流式不重试 | 已输出的内容无法撤回，重放会重复 |
| 8 | 调用记录写库 + Micrometer 指标（tag 用 scene，不用 userId） | 成本可归因、问题可追溯、指标基数可控 |
| 9 | 记录失败绝不影响主流程 | 审计/统计可降级，鉴权/落库不能降级 |
| 10 | SSE 放行 `DispatcherType.ASYNC/ERROR` | 异步二次派发没有 SecurityContext，否则报 "response already committed" |

---

## 3. 验收数据（全部实测）

| 指标 | 结果 |
|---|---|
| `mvn -B test` | **105 个全绿**（第 2 周 50 + 本周 55） |
| `python scripts/week3-acceptance.py` | **55 项全通过**，连跑两次结果一致 |
| 应用启动（local + mock） | 约 4 秒内 health=UP |
| fail-fast | `AI_PROVIDER=openai` 且无 Key → 启动失败（日志 "Application run failed"） |
| 指标 | `ai.calls` COUNT=28，tag 覆盖 4 个 scene / 2 种 outcome；`ai.tokens` COUNT=2280 |
| 索引实测 | 28 行 → `type=ALL`；5028 行 → `type=index, key=idx_owner_created, Backward index scan` |

### 索引这组对照怎么讲

小表全表扫描是优化器的正确选择，不是索引失效。要证明索引有效，必须给数据量：

```sql
-- 5000 行时
EXPLAIN SELECT id, trace_id, scene FROM ai_call_log
 WHERE owner_id=1 ORDER BY created_at DESC LIMIT 20;
-- key=idx_owner_created, rows=20, Extra=Using where; Backward index scan
```

`Backward index scan` 说明索引同时满足了过滤和倒序，没有 filesort。

---

## 4. 本周踩的坑（高价值）

| # | 现象 | 根因 | 解法 |
|---|---|---|---|
| 1 | 引 openai starter 后不配 Key 起不来 | 自动装配强校验 | 只引 client-chat，自实现 ChatModel |
| 2 | 兼容服务多返回字段 → 反序列化失败 | 默认严格模式 | DTO 加 `@JsonIgnoreProperties(ignoreUnknown=true)` |
| 3 | SSE 测试报 "response is already committed" | ASYNC 派发无认证上下文 | 放行 `DispatcherType.ASYNC/ERROR` |
| 4 | 用户输入含 `{}` 被模板引擎吃掉 | ChatClient 默认 StTemplateRenderer | 用 `Prompt` + `Message` 直接传文本 |
| 5 | 模型输出带 ```json 围栏解析失败 | 模型不守"只输出 JSON" | 剥围栏 + 截取 `{}` |
| 6 | 28 行时 EXPLAIN `type=ALL` | 表太小，优化器选全扫 | 灌 5000 行复测 |
| 7 | 加外键后 schema 脚本报错 | DROP 顺序必须子表先 | `ai_call_log → task → app_user` |
| 8 | 同一 traceId 多条失败记录 | 修复重试每次调用都记一条 | 统计区分 `count(*)` 与 `count(distinct trace_id)` |

---

## 5. 工程约定（在原有 8 条上新增 4 条）

原有 8 条不变（数据边界来自登录态、SQL 兜底、缓存 key 含 ownerId、写删缓存、
VO 不返实体、404 不 403、密钥走环境变量、验收脚本可重复）。

本周新增：

9. **模型输出是不可信输入**：解析 → 校验 → 才允许进业务
10. **模型不能直接产生副作用**：写操作必须先出草稿，用户确认后才执行
11. **调用必须可观测**：每次模型调用都有 traceId、Token、耗时、attempts、错误码
12. **流式接口的错误用事件表达**，不能再改 HTTP 状态码

---

## 6. 常用命令

```bash
cd D:/a应聘/学习/workbuddy/smartdesk

# 依赖（当前：MySQL 3307 / Redis 6380 已启动）
bash scripts/start-mysql-dev.sh start
bash scripts/start-redis-dev.sh start

# 测试
mvn -B test

# 起服务
mvn -B -q package -DskipTests
MYSQL_PORT=3307 REDIS_PORT=6380 AI_PROVIDER=mock \
  java -jar target/smartdesk-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=18080

# 验收
python scripts/week3-acceptance.py

# 建表（改了 schema 之后）
MYSQL="C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
"$MYSQL" --defaults-extra-file="C:/Users/86181/.workbuddy/mysql-dev/client.cnf" < src/main/resources/db/schema.sql
"$MYSQL" --defaults-extra-file="C:/Users/86181/.workbuddy/mysql-dev/client.cnf" -D smartdesk_test < src/main/resources/db/schema-test.sql
```

> 客户端凭据放在 `C:/Users/86181/.workbuddy/mysql-dev/client.cnf`（不在仓库里），
> 避免命令行出现密码明文。

---

## 7. 下一步：第 4 周 Tool Calling

计划原文（`ja计划.docx` 第 4 周）：

| Day | 内容 | 交付 |
|---|---|---|
| 22 | Tool Calling 原理、时序图 | 用户 → 模型 → 工具 → 结果 → 模型 的时序图 |
| 23 | `@Tool` 定义与注册 | 只读工具 `getTask` / `listTasks` |
| 24 | 工具参数校验、用户上下文 | **userId 只能来自认证上下文，绝不信任模型传入** |
| 25 | 有副作用的工具、确认机制 | `createTask` + 确认流程 |
| 26 | 工具调用循环、上限、异常恢复 | 最大调用次数、超时、工具报错不崩 |
| 27 | 多轮会话、上下文保存 | 会话表 + 用户隔离 |
| 28 | 复盘与演示 | "查询逾期任务并创建草稿" 现场演示 |

### 开工提醒

- 工具方法是**新的攻击面**：模型可以诱导调用任何已注册工具。
  参数里的 `userId` 一律忽略，从 `SecurityContext` 取。
- 第 4 周会新增 `chat_session` / `chat_message`（或类似）表 → **先写越权测试**。
- `ChatClient.prompt().tools(...)` 与 `@Tool` 注解可直接复用本周的 `ChatClient` Bean。
- 已注册工具数量要设上限，工具调用循环要有最大轮数，否则模型可能来回调用烧钱。

---

## 8. 遗留事项

| 项 | 状态 | 说明 |
|---|---|---|
| `.env.example` 没进仓库 | ✅ 本周修复 | `.gitignore` 的 `.env.*` 把它一起忽略了；已加 `!.env.example`。另外模板里两行键名被写坏（`MYSQL_MIECZCJN` / `REDIS_ZWELSOWR`），已改回 `MYSQL_PASSWORD` / `REDIS_DATABASE` |
| `TaskService.getById()` / `list()` 无生产调用方 | ⏳ 暂留 | `getById` 是唯一 `@Cacheable` 的演示（删了缓存就没法讲了）；`list` 与 `query` 重叠。等第 4 周工具调用落地后再决定删哪个 |
| `docs/api.md`、`docs/er.md` | ⏳ 待补 | 接口清单已散落在 README 与 architecture，第 7 周统一整理 |
| 真实模型未实测 | ⚠️ 必做 | 代码路径已实现且有错误映射，但本机没有 API Key。**正式投递前必须用真实 Key 跑一遍 chat / task-draft / stream** |
| 流式 Token 用量是估算值 | 📌 已知限制 | 上游流式默认不返回 usage，按 2 字符 ≈ 1 Token 估算，文档已注明 |
| 调用记录会无限增长 | 📌 后续 | 第 7 周加清理策略（按天分区或定时删除 N 天前数据） |

---

## 9. 相关文档
| 文档 | 内容 |
|---|---|
| `docs/week1-2-summary.md` | 第 1–2 周交接 |
| `docs/week2-guide.md` | 索引/缓存/鉴权/容器化的坑与面试题 |
| `docs/week3-guide.md` | **本周逐日任务、8 个坑、10 道面试题** |
| `docs/architecture.md` | 一页纸架构（已补 AI 层） |
| `README.md` | 启动步骤、接口一览、配置说明 |
