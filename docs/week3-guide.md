# 第 3 周执行手册：大模型 API、Prompt 与结构化输出

> 对应 `ja计划.docx` 第 3 周（Day 15–21）。
> 本周不写 RAG、不写 Tool Calling —— 那是第 4、5 周的事。
> 本周只回答一个问题：**怎么把一个大模型，接成一个有契约、有错误码、可观测、可测试的后端能力。**

---

## 0. 本周结论（先看这个）

| 项 | 结果 |
|---|---|
| 真实模型调用 | ✅ 已实现 OpenAI 兼容协议（JDK HttpClient，同步 + SSE 流式），配 `AI_PROVIDER=openai` + Key 即生效 |
| 离线可跑 | ✅ 默认 `mock` Provider，规则引擎 + 故障注入，`git clone` 后无需任何 Key 即可跑通全链路 |
| 自动化测试 | **105 个全绿**（第 2 周 50 个 + 本周 55 个） |
| 端到端验收 | **55 项断言全通过**，连跑两次结果一致 |
| 结构化输出 | ✅ 解析 → 校验 → 修复重试（有上限）→ 仍不合法返回 422 |
| 可观测 | ✅ `ai.calls` / `ai.latency` / `ai.tokens` 指标 + `ai_call_log` 落库（含 traceId） |
| 流式输出 | ✅ SSE，`delta` → `done` / `error` 事件协议 |

---

## 1. 本周的架构判断（最重要的一节）

### 1.1 为什么不用 `spring-ai-starter-model-openai`

Spring AI 的 starter 会自动装配 `OpenAiChatModel`，看着省事，但它有两个硬伤：

1. **启动即要求 API Key**。没配 Key 应用直接起不来 —— 而我们的默认场景是"离线 mock"，
   CI、单元测试、面试官 clone 下来跑，全都不该需要 Key。
2. **把业务焊死在某个厂商 SDK 上**。想换 DeepSeek / 通义兼容模式 / 本地 Ollama，得改依赖。

所以最终只引 `spring-ai-client-chat`（提供 `ChatClient` / `ChatModel` / `Prompt` 抽象），
自己实现两个 `ChatModel`：

```
AiService ──依赖──> ChatClient（Spring AI 接口）
                        │
                 ChatModel（我们自己实现）
                   ├── MockChatModel         离线规则引擎 + 故障注入
                   └── OpenAiCompatibleChatModel ──> OpenAiChatTransport（JDK HttpClient）
```

**面试讲法**：这是适配器模式。上层只认 `ChatClient`，换 Provider 是换 Bean，不是改业务。
"模型无关"不是口号，是这条依赖箭头方向决定的。

### 1.2 为什么默认必须有一个 Mock Provider

不是为了偷懒，是四个工程需求：

| 需求 | 真实模型 | Mock |
|---|---|---|
| 测试可重复 | ❌ 有随机性，同一输入两次不同 | ✅ 输入决定输出 |
| CI 不烧钱 | ❌ 每次跑测试都是真账单 | ✅ 免费 |
| 故障可注入 | ❌ 超时/429/5xx 无法稳定复现 | ✅ 标记触发 |
| 无 Key 可开发 | ❌ 起步就卡住 | ✅ 直接跑 |

这个 mock 不是"随机假数据"，而是按 Prompt 场景分发的**规则引擎**：

- 摘要 → 按系统提示词里的字数上限截断
- 分类 → 关键词映射到白名单枚举
- 任务提取 → 解析"明天/后天/紧急/重要/日期"，输出合法 JSON
- 故障注入 → 消息里带 `[[FAIL:TIMEOUT]]` / `[[FAIL:RATE_LIMIT]]` / `[[FAIL:UPSTREAM]]` / `[[FAIL:INVALID_JSON]]`

> 关键取舍：mock 的行为要和真实模型**在契约层面一致**（都能被解析、都能触发同样的错误码），
> 但绝不追求文案一致。否则接上真模型，测试全红。

---

## 2. Day 15：LLM 基础与一次裸 HTTP 调用

### 学习要点

| 概念 | 一句话解释 | 工程含义 |
|---|---|---|
| Token | 模型计费和计长的最小单位 | 输入输出都收费，所以接口要限长度 |
| 上下文窗口 | 一次请求能塞进去的最大 Token 数 | 超了就被截断或报错，RAG 要控制召回量 |
| System / User 消息 | 角色约束 vs 本次任务 | 约束放 System，数据放 User，别混 |
| Temperature | 采样随机性 | 结构化输出要低（0~0.3），创意任务才调高 |
| `finish_reason` | 模型为什么停下 | `length` 说明被截断，输出一定不完整 |

### 落地：`OpenAiChatTransport`

用 `java.net.http.HttpClient` 而不是 RestTemplate / WebClient：

- JDK 自带，零依赖
- 支持 `BodyHandlers.ofInputStream()`，流式读取不用额外框架
- 连接超时和整请求超时能分开设

```java
// 连接超时 5s（TCP 握手）
HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
// 整请求超时 20s
HttpRequest.newBuilder(uri).timeout(properties.getRequestTimeout())
```

**为什么两层超时都必要**：只设总超时，DNS 卡住也会占线程；只设连接超时，
服务端"接住了但永不返回"同样能耗死连接池。

---

## 3. Day 16：Spring AI ChatClient 与 AiService 分层

### 分层

```
AiController   收参数、取登录态、包响应          ← 不知道模型存在
AiService      Prompt、解析、校验、重试、记录     ← 唯一的 AI 业务层
ChatClient     Spring AI 门面
ChatModel      mock / openai 两个实现
```

Controller 里出现任何 Prompt 或 HTTP 调用，都说明分层错了。

### 一个容易忽略的坑：模板渲染会吃掉花括号

```java
chatClient.prompt().system(sys).user(userText)   // ❌ userText 里的 {} 会被当占位符
```

Spring AI 默认用 `StTemplateRenderer`，`{}` 是变量占位符。用户输入里出现 JSON、
代码片段、正则（太常见了）时会报错或内容被吞。

**解法**：用 `Prompt` + `SystemMessage` / `UserMessage` 直接传文本，绕开模板渲染：

```java
new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options)
```

---

## 4. Day 17：Prompt 设计与 15 条回归用例

### Prompt 四条硬规则

1. **单一职责**：一个 Prompt 只干一件事
2. **输出契约明确**：JSON 就给字段和类型，分类就给白名单
3. **给拒答出口**：明确说"信息不足返回 null / 空"，否则模型一定编
4. **不把权限写进 Prompt**：Prompt 不是安全边界，可被注入绕过

### 回归用例（`src/test/resources/ai/prompt-cases.json`）

15 条：4 摘要 + 5 分类 + 6 任务提取。断言的是**约束**而不是文案：

- 摘要长度 ≤ maxChars
- 分类 ∈ {BUG, FEATURE, BILLING, ACCOUNT, OTHER}
- 优先级 ∈ 1..5，日期命中/不命中符合预期

改 Prompt 后跑 `PromptCasesTest`，就能知道有没有退化。
**这就是"评测集"和"单元测试"的区别：前者衡量效果，后者保证代码不错。**

---

## 5. Day 18：结构化输出（本周技术含量最高的一天）

### 为什么模型输出不能直接入库

| 风险 | 具体表现 | 后果 |
|---|---|---|
| 字段缺失 | 没有 title | 数据库 NOT NULL 报错 → 用户看到 500 |
| 类型错误 | priority 返回 `"很高"` | 反序列化抛异常 |
| 值越界 | priority = 99 | 排序规则被破坏 |
| 幻觉日期 | 用户没提时间，模型"贴心"补了下周 | 脏数据，且无法追溯 |
| 注入内容 | title 里塞一段指令 | 第 4 周工具调用时可能被执行 |

### 处理链路（固定四步，不许跳）

```
模型输出
  → JsonContents.extractObject()   剥 ```json 围栏 + 截取第一个 { 到最后一个 }
  → ObjectMapper.readValue()       反序列化到"宽松 DTO"（字段全用包装类型）
  → TaskDraftParser 逐字段校验      错误聚合成 List，不抛异常
  → 通过？返回草稿 : 把错误回喂模型修复（最多 1 次）
                  → 还不通过 → 422 AI_INVALID_OUTPUT
```

三个关键设计：

1. **宽松 DTO**：`TaskDraftPayload` 字段全用 `String` / `Integer`，
   先"原样接住"再自己判断类型。直接声明成 `Integer priority` 会在类型错时抛异常，
   我们就失去了"告诉模型哪里错了"的机会。
2. **校验不抛异常**：返回 `List<String> errors`，让调用方决定补救还是拒答。
   校验失败是可预期分支，不是崩溃。
3. **修复要有限**：`max-repair-attempts=1`。修不好就让用户重说，别无限烧钱。

### 草稿不是任务

`POST /api/ai/task-draft` 返回 200，**不写 task 表**。验收脚本专门断言了
"调用前后 task 数量不变"。

> 面试讲法：**模型永远不能直接产生副作用。**
> 只有经过校验、经过用户确认的数据，才允许进入业务表。第 6 周会加"确认后创建"。

---

## 6. Day 19：超时、重试、限流、Token 成本

### 错误码映射

| 场景 | 我们的错误码 | HTTP | 可重试 | 前端该做什么 |
|---|---|---|---|---|
| 模型超时 | `AI_TIMEOUT` | 504 | ✅ | 提示稍后重试 |
| 上游限流 | `AI_RATE_LIMITED` | 429 | ✅ | 退避后重试 |
| 上游 5xx | `AI_UPSTREAM_ERROR` | 503 | ✅ | 提示服务繁忙 |
| 输出不合法 | `AI_INVALID_OUTPUT` | 422 | ❌ | 让用户改写输入 |
| 请求参数错 | `AI_INVALID_REQUEST` | 400 | ❌ | 修参数 |
| 鉴权失败/未配置 | `AI_NOT_CONFIGURED` | 503 | ❌ | 运维介入 |

**为什么不能统一 500**：前端要按错误码分流。422 重试一万次也没用，
504 不重试就是白白失败。

### 重试策略

```
最多 3 次（含首次）
只重试可重试错误码
退避 = base * 2^(n-1) + random(0, base/2)
```

**为什么必须加抖动**：100 个请求同时被限流，不加抖动会在同一毫秒重试，
第二轮依然限流 —— 重试风暴。

**流式不能重试**：已经吐了一半，重放会让用户看到重复文本。
流式的错误处理是"发 error 事件并结束"，不是"再来一次"。

### 成本与可观测

- 指标：`ai.calls{scene,provider,outcome}`、`ai.latency{scene}`、`ai.tokens{scene,type}`
- 落库：`ai_call_log`（owner_id / trace_id / scene / tokens / latency / attempts / success / error_code）

**为什么用 tag 不用指标名**：指标名爆炸会让 Prometheus 基数失控；
tag 既能聚合也能下钻。同理：**绝不要把 userId 做成 tag**。

**为什么不存 Prompt 全文和回答全文**：业务文本可能含手机号、客户名单，
写进调用日志等于把敏感数据复制一份。只存元数据才合规。

---

## 7. Day 20：SSE 流式输出

### 协议

```
event: delta   data: {"text":"增量片段"}
event: done    data: {"content":"完整回答","meta":{...}}
event: error   data: {"code":"AI_TIMEOUT","message":"..."}
```

### 三个必须踩过才知道的坑

1. **异步线程里没有 SecurityContext**
   ```java
   Long userId = CurrentUser.getUserId();  // ✅ 必须在进入异步之前
   aiService.streamChat(userId, ...);
   ```
   在回调里调 `CurrentUser.getUserId()` 会拿到 null。

2. **流开始后不能改状态码**
   SSE 的响应头在第一个字节发出时就固定了。后面出错只能发 `error` 事件。
   > 这是本周真实踩到的 bug：异步派发（`DispatcherType.ASYNC`）会再走一遍
   > 安全过滤器链，此时没有认证信息 → 抛 AccessDenied → 报
   > `Unable to handle the Spring Security Exception because the response is already committed`。
   > 解法：`SecurityConfig` 里放行 `DispatcherType.ASYNC, DispatcherType.ERROR`。
   > 首次 REQUEST 派发已经认证过，放行是安全的。

3. **阻塞读必须离开 Tomcat 工作线程**
   ```java
   stream.subscribeOn(Schedulers.boundedElastic())
   ```
   否则并发一上来，工作线程全被"等模型吐字"占死。

### SSE vs WebSocket

| | SSE | WebSocket |
|---|---|---|
| 方向 | 服务端 → 客户端单向 | 双向 |
| 协议 | 纯 HTTP | 需要升级握手 |
| 自动重连 | 浏览器内置 | 要自己实现 |
| 适用 | 模型逐字输出、通知推送 | 聊天室、协同编辑 |

模型回答是单向推送，SSE 足够，别为了"高级"上 WebSocket。

---

## 8. Day 21：复盘

### 本周踩的坑（都是真踩的，不是抄的）

| # | 现象 | 根因 | 解法 |
|---|---|---|---|
| 1 | 引入 openai starter 后不配 Key 无法启动 | starter 自动装配强校验 api-key | 只引 `spring-ai-client-chat`，自实现 ChatModel |
| 2 | 兼容服务多返回字段导致反序列化失败 | 默认严格模式 | DTO 全部加 `@JsonIgnoreProperties(ignoreUnknown = true)` |
| 3 | SSE 集成测试报 "response is already committed" | 异步二次派发没有 SecurityContext | 放行 `DispatcherType.ASYNC/ERROR` |
| 4 | 用户输入含 `{}` 时模型收到残缺内容 | ChatClient 模板渲染 | 改用 `Prompt` + `Message` 直接传文本 |
| 5 | 模型输出带 ```json 围栏，直接解析报错 | 模型不遵守"只输出 JSON" | 剥围栏 + 截取 `{}`，再解析 |
| 6 | 28 行数据时 EXPLAIN 显示 `type=ALL` | 表太小，优化器主动选全表扫描 | 灌 5000 行复测，才看到 `idx_owner_created` |
| 7 | 加 `ai_call_log` 后跑 schema 脚本报外键错误 | DROP 顺序 parents-first | 先子表后父表 |
| 8 | 修复重试导致同一 traceId 多条失败记录 | 每次模型调用都记一条 | 统计时区分 `count(*)` 与 `count(distinct trace_id)` |

> 第 6 条最值得记住：**讲索引必须给出数据量条件。**
> "我加了索引所以快"是错的；"28 行时优化器选全表扫描，5000 行时走
> `idx_owner_created` 且 Backward index scan 无 filesort"才是证据。

### 索引实测（真实输出）

```sql
-- 28 行时
EXPLAIN SELECT id, trace_id, scene FROM ai_call_log WHERE owner_id=1 ORDER BY created_at DESC LIMIT 20;
-- type=ALL, key=NULL, rows=28, Extra=Using where; Using filesort

-- 插入 5000 行后（ANALYZE TABLE 之后）
-- type=index, key=idx_owner_created, rows=20, Extra=Using where; Backward index scan
```

`Backward index scan` 说明索引同时满足了"过滤 owner_id"和"按 created_at 倒序"，
没有额外 filesort。这就是复合索引 `(owner_id, created_at)` 的设计目的。

### 10 个必须能答的面试题

1. 为什么模型输出不能直接写数据库？你会怎么设计校验链路？
2. 模型返回的不是合法 JSON，怎么处理？为什么不无限重试？
3. 超时、重试、限流怎么设计？哪些错误可重试，哪些绝不能重试？
4. 怎么控制模型调用成本？Token 用量怎么观测和归因到人？
5. `ChatClient` 相比直接发 HTTP 请求多了什么？为什么值得这层抽象？
6. SSE 和 WebSocket 的区别？Spring MVC 里怎么实现 SSE？坑在哪？
7. 为什么把"只能查自己的数据"写进 Prompt 是不安全的？
8. Temperature 对结构化输出有什么影响？为什么结构化场景要调低？
9. 如何做 Prompt 回归评测？断言什么、不断言什么？
10. 怎么做到换模型不改业务代码？依赖方向应该怎么设计？

### 答题骨架（第 3 题示例）

> 先分类：错误分"可恢复"和"不可恢复"。超时、429、5xx 可恢复；
> 400、422 不可恢复，重试只会放大成本。
> 再定上限：最多 3 次，指数退避 + 随机抖动，避免重试风暴。
> 最后可观测：每次调用记 attempts、耗时、错误码，指标按 scene 打 tag。
> 流式场景不重试 —— 已经输出的内容无法撤回，重放会造成重复。

---

## 9. 本周产出清单

### 代码

```
src/main/java/com/smartdesk/ai/
├── config/       AiProperties、AiConfig（Provider 选择 + fail-fast）
├── model/        AiScene、AiErrorCode、AiException、AiChatResult、TaskCategory
├── prompt/       PromptTemplates（版本化 Prompt 库）
├── provider/     MockChatModel、OpenAiCompatibleChatModel、OpenAiChatTransport、dto/
├── service/      AiService(Impl)、AiInvoker、AiMetrics、AiCallLogService、
│                 TaskDraftParser、JsonContents
├── entity/       AiCallLog
├── mapper/       AiCallLogMapper
├── controller/   AiController（含 SSE）、AiCallLogController
└── dto/          请求/响应 record
```

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/chat` | 问答 |
| POST | `/api/ai/chat/stream` | 流式问答（SSE） |
| POST | `/api/ai/summarize` | 摘要 |
| POST | `/api/ai/classify` | 分类 |
| POST | `/api/ai/task-draft` | 自然语言 → 任务草稿（不落库） |
| GET | `/api/ai/calls` | 我的调用记录（分页） |
| GET | `/api/ai/calls/{id}` | 我的某条记录（别人的 404） |

### 测试（55 个新增）

| 文件 | 数量 | 覆盖 |
|---|---|---|
| `TaskDraftParserTest` | 14 | JSON 抠取、字段校验、越界、非法日期 |
| `MockChatModelTest` | 7 | 三场景输出、故障注入、流式一致性 |
| `AiInvokerTest` | 5 | 重试次数、不可重试、退避增长 |
| `AiServiceImplTest` | 9 | 四场景、超时、修复重试、流式回调 |
| `PromptCasesTest` | 1（15 用例） | Prompt 回归评测 |
| `AiControllerTest` | 11 | 401/400、504/422 映射、SSE 事件 |
| `AiIntegrationTest` | 8 | 真实 MySQL、越权隔离、落库、SSE |

---

## 10. 常用命令

```bash
cd D:/a应聘/学习/workbuddy/smartdesk

# 依赖
bash scripts/start-mysql-dev.sh start
bash scripts/start-redis-dev.sh start

# 测试（105 个）
mvn -B test

# 只跑 AI 相关
mvn -B test -Dtest='*Ai*Test,TaskDraftParserTest,MockChatModelTest,PromptCasesTest'

# 起服务（mock，离线）
mvn -B -q package -DskipTests
MYSQL_PORT=3307 REDIS_PORT=6380 AI_PROVIDER=mock \
  java -jar target/smartdesk-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=18080

# 端到端验收（幂等）
python scripts/week3-acceptance.py

# 切换真实模型（任何 OpenAI 兼容服务）
AI_PROVIDER=openai OPENAI_API_KEY=sk-xxx AI_BASE_URL=https://api.deepseek.com/v1 AI_MODEL=deepseek-chat \
  java -jar target/smartdesk-0.1.0-SNAPSHOT.jar --spring.profiles.active=local --server.port=18080
```

> 注意：`AI_PROVIDER=openai` 但 `OPENAI_API_KEY` 为空时，应用会**拒绝启动**并给出明确提示。
> 这是第 2 周定下的 fail-fast 约定，本周继续沿用。
