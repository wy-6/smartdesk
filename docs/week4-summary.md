# 第 4 周交接总结：Tool Calling

> **状态**：Day 22–28 全部完成并验证通过。
> **最后更新**：2026-11-06 ｜ **对应提交**：见 `git log --oneline -5`
> **新会话从这份开始读**；逐日细节见 [week4-guide.md](week4-guide.md)。

---

## 1. 一句话结论

模型现在能读写数据库了，所以本周一半的代码量花在**安全边界**和**刹车**上，而不是功能上。

新增 `com.smartdesk.ai.tool` 包与 `AgentLoop`，4 个工具、3 张新表、9 个新接口、51 个新测试、46 项端到端验收。

---

## 2. 交付物

### 2.1 代码

| 层 | 类 | 职责 |
|---|---|---|
| 工具声明 | `tool/TaskTools` | 4 个业务工具（3 读 + 1 写） |
| 工具注册 | `tool/ToolRegistry` | 扫描 `@Tool`、生成 schema、参数转换、反射执行 |
| 身份上下文 | `tool/AgentToolContext` | **唯一身份来源**，不出现在 schema 里 |
| 副作用标记 | `tool/SideEffect` | 标记必须走确认的工具 |
| 循环 | `service/AgentLoop` | 模型↔工具循环 + 四个刹车 |
| 工具执行 | `service/ToolExecutionService` | 指标 + 审计（`ai_tool_call_log`） |
| 确认 | `service/ConfirmationService` | Redis 草稿，Lua 原子取删 |
| 会话 | `service/ChatSessionService` | 会话/消息持久化，历史回灌 |
| 门面 | `service/AgentService` | chat + confirm + cancel |
| Provider | `provider/ToolAwareChatModel` | "支持工具"的能力接口 |
| Mock | `provider/MockChatModel` | 剧本 + 自然语言路由 + 死循环注入 |
| OpenAI | `provider/OpenAiCompatibleChatModel` | `tools` 入参、`tool_calls` 解析、tool 消息回填 |

### 2.2 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/agent` | 无状态 Agent 对话（带工具） |
| GET | `/api/ai/tools` | 工具列表与 schema（可直接验证没有 userId） |
| POST | `/api/ai/confirmations/{id}/confirm` | 确认草稿 → 落库 |
| DELETE | `/api/ai/confirmations/{id}` | 放弃草稿 |
| GET | `/api/ai/tool-calls` | 工具调用审计（按 ownerId 隔离） |
| POST | `/api/ai/sessions` | 创建会话 |
| GET | `/api/ai/sessions` | 我的会话列表 |
| GET | `/api/ai/sessions/{id}/messages` | 会话消息 |
| POST | `/api/ai/sessions/{id}/messages` | 发消息（触发 Agent） |
| DELETE | `/api/ai/sessions/{id}` | 删除会话（级联删消息） |

### 2.3 数据表

```sql
ai_tool_call_log   工具审计：trace_id / tool_name / arguments / result_summary / privilege_stripped
                   KEY idx_owner_created(owner_id, created_at), idx_trace(trace_id)

chat_session       id / owner_id / title / created_at / updated_at
                   KEY idx_owner_updated(owner_id, updated_at)

chat_message       id / session_id / owner_id / role / content / tool_calls / created_at
                   KEY idx_session_id(session_id, id), idx_owner(owner_id)
```

`chat_message.owner_id` 是从 session 表**冗余**过来的 —— 延续第 2 周"SQL 层再兜一层"的做法。

---

## 3. 验收数据

| 项 | 结果 |
|---|---|
| `mvn -B test` | **156 个全绿**（W3 的 105 + 本周 51） |
| `python scripts/week4-acceptance.py` | **46/46**，连跑两次结果一致 |
| 工具 schema 泄露检查 | 4 个工具的 properties 里均无 `userId`/`ownerId` |
| 确认幂等 | 重复确认 404，任务数不变 |
| 越权 | 跨用户查任务/读会话/确认草稿 全部 404 |
| 死循环 | `[[FAIL:LOOP]]` → 422，且只调 2 次模型（熔断早于轮数上限） |

测试分布（Week4 部分 51 个）：

| 测试类 | 数量 | 重点 |
|---|---|---|
| `ToolRegistryTest` | 13 | schema 不含身份字段、参数转换、异常脱敏 |
| `TaskToolsTest` | 10 | `verify(taskService, never()).create(...)` 证明没写库 |
| `AgentLoopTest` | 8 | 四个刹车、截断补响应、工具失败不崩 |
| `MockChatModelToolTest` | 8 | tool_calls 协议、路由优先级、结果总结 |
| `AgentIntegrationTest` | 12 | 真实 MySQL：越权、确认幂等、会话隔离、审计 |

---

## 4. 三个关键决策

### 4.1 自建循环，不用 `DefaultToolCallingManager`

框架能跑通"基础版"，但轮数、单轮工具数、超时预算都写死在内部流程里。
而这三件事恰恰是生产出问题时唯一能调的旋钮。

自己写约 200 行，换来：可测（`AgentLoopTest` 用队列驱动的 StubModel）、可控（四个刹车都是配置项）、可观测（每轮都能落库打点）。

### 4.2 身份从"参数"挪到"上下文"

```java
// ❌ 方法签名毫无异常，但 userId 成了模型可填的参数
public Object listTasks(Long userId, String status)

// ✅ schema 里根本没有身份字段
public Object listTasks(String status, AgentToolContext context)
```

三层防线：schema 里没有 → 执行时剥离黑名单字段 → SQL 再 `WHERE owner_id=?`。

**这是本周最值得在面试里讲的一点**：这类漏洞在 code review 时看不出来，只有"身份不由调用方提供"这个原则能根治。

### 4.3 写操作两阶段确认

```
工具生成草稿（Redis, TTL 5min）→ 返回 confirmationId → 用户点确认 → 落库
```

为什么不能靠 Prompt 让模型问一句"你确定吗"？因为 Prompt 是软约束：可能被注入绕过、可能忘了问、可能自问自答。

确认必须是**确定性的 HTTP 调用**。

草稿用 **Lua 原子 GET+DEL**：防双击导致一条草稿创建两个任务；且兼容 Redis 5.0.14（没有 `GETDEL`）。

取舍：取到草稿但落库失败时草稿已消失，需重新发起 —— **写操作宁可少做一次，不可多做一次**。

---

## 5. 遗留事项

| # | 事项 | 影响 | 建议做法 |
|---|---|---|---|
| 1 | 真实模型仍未实测 | 中 | 有 Key 后跑一遍 `agent` / `sessions` / 确认链路。重点看真实 `tool_calls` 的 `arguments` 格式差异 |
| 2 | 流式接口不支持工具 | 低 | 当前 `/chat/stream` 无工具；要做需要自定义 SSE 事件（tool_start/tool_result/delta/done） |
| 3 | 失败重试会产生重复 user 消息 | 低 | 需要客户端消息 id 做幂等 |
| 4 | 确认后落库失败无法重试 | 低 | 若要强可用，给 task 表加 `source_confirmation_id` 唯一索引 |
| 5 | 工具参数只做了类型校验，没做长度校验 | 低 | `title` 已在工具里校验；其他工具按需补 |
| 6 | 会话没有上下文窗口裁剪（只按条数） | 中 | 长对话应按 token 裁剪，不是按条数 |

---

## 6. 第 5 周开工提醒：RAG

计划原文（第 5–6 周：RAG 与完整 Agent 应用）：

| 内容 | 要点 |
|---|---|
| 文档上传、解析、切分 | 先支持 txt/md/pdf；切分按语义边界，别按固定字符数硬切 |
| 向量化 | 需要 embedding 模型。Mock 模式下用确定性向量（只验证链路，不验证检索质量） |
| 向量存储 | MySQL 存向量 + 应用层算余弦？还是引入 pgvector/Milvus？**先想清楚数据量与检索方式，再选型** |
| 检索 + 引用 | 回答必须带来源；证据不足要明确说"查不到"——这是 RAG 与纯 LLM 的区别 |
| 与工具调用结合 | 把"知识库检索"做成第 5 个工具 `searchKnowledge`，复用本周的 `ToolProvider` |

**三条硬提醒**：

1. **新表先写越权测试**（第 2 周起的规矩）。知识库文档同样按 ownerId 隔离
2. **RAG 的召回率必须能测**。准备 20 条「问题 → 期望文档」的用例，每次改切分/模型都跑一遍。没有评测集的 RAG 是玄学
3. **拒答比回答更重要**。检索分数低于阈值就返回"没有找到相关资料"，不要硬编。这条要写进 Prompt，也要写进测试

---

## 7. 本地环境

```bash
# 依赖
bash scripts/start-mysql-dev.sh start     # 3307
bash scripts/start-redis-dev.sh start     # 6380

# 建表（含 3 张新表）
mysql --defaults-extra-file=~/.workbuddy/mysql-dev/client.cnf < src/main/resources/db/schema.sql

# 跑起来
mvn -B package -DskipTests
AI_PROVIDER=mock java -jar target/smartdesk-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=18080

# 验收
python scripts/week4-acceptance.py
```

当前 MySQL 3307 / Redis 6380 / 应用 18080 均在运行，provider=mock。
