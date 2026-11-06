# SmartDesk 第 5 周交接总结（RAG 知识库）

> 新会话先读这份，再读 `docs/week5-guide.md`（执行手册）与 `docs/week5-eval-result.md`（评测数据）。

---

## 1. 本周做了什么

**从"能聊天"到"能基于文档回答"，且每个结论都能指回原文。**

计划书第 5 周的验收门槛是：

> 不接受只有一个聊天接口、没有独立检索流程、没有来源引用的实现。
> 你需要能够看到检索结果，并用测试集解释它为什么有效或无效。

本周的交付就是按这条门槛设计的：

| 环节 | 交付 | 位置 |
|---|---|---|
| 索引 | 导入 → 解析 → 切片 → 向量化 → 落库 | `knowledge/service/KnowledgeService` |
| 检索 | **独立接口**，不经过模型，返回片段/来源/分块 ID/分数 | `POST /api/knowledge/search` |
| 问答 | 检索 → 组装资料 → 生成 → 引用对齐 → 拒答 | `POST /api/knowledge/ask` |
| 评测 | 30 题 × 3 种配置，报告自动生成进版本库 | `RagEvaluationTest` + `docs/week5-eval-result.md` |

---

## 2. 验收数据（实测）

| 项目 | 结果 |
|---|---|
| `mvn -B test` | **195 个全绿**（156 原有 + 39 新增） |
| `python scripts/week5-acceptance.py` | **51/51**，连跑两次结果一致 |
| 前几周回归 | week2 38 + week3 55 + week4 46 全过 |
| 检索评测（配置 A） | hit@1 83.3% / hit@5 90.0% / 引用错误数 **0** |
| 检索评测（配置 C） | hit@1 76.7% / hit@5 **96.7%** / 引用错误数 **0** |

新增测试类：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `ChunkerTest` | 6 | 切片大小、重叠、标点边界、尾巴合并、偏移还原 |
| `MockEmbeddingModelTest` | 6 | 确定性、归一化、相关性可分辨、批量 |
| `MySqlKnowledgeVectorStoreTest` | 8 | **越权红线**、SQL 带 owner_id、阈值、排序、维度不匹配 |
| `KnowledgeIntegrationTest` | 18 | **4 个越权用例** + 索引 + 检索 + 引用 + 重复导入 + 异常输入 |
| `RagEvaluationTest` | 1 | 30 题 × 3 配置对比 + 报告生成 |

---

## 3. 三个关键决策

### 3.1 向量存储：实现 Spring AI 的 `VectorStore` 接口，但自己写 MySQL 版

不选官方 `SimpleVectorStore` 的原因只有一个，但它很关键：
**它的过滤在内存里做**。本项目从第 1 周起就定了"过滤必须下沉到 SQL"，
知识库是多租户数据，隔离必须落在 SQL 的 `WHERE owner_id = ?` 上。

不选 Milvus/Qdrant/pgvector 的原因是本机 Docker 不可用（wsl 被封）。

实现 Spring AI 官方接口的收益：将来换 pgvector 只需要换实现类，检索代码一行不改。

### 3.2 引用由检索结果生成，不由模型生成

模型的 `[1]` 只是"声称"用了第 1 条资料。我们的 `citations` 来自真实检索结果，
编号与资料区块严格对齐。模型如果编了 `[99]`，会被记进 `unsupportedMarkers`
（**评测里这个数恒为 0**，三条配置都是）。

### 3.3 检索不到就不调模型

`hits.isEmpty()` → 直接返回拒答话术，不发起模型调用。省钱 + 防幻觉。

---

## 4. 评测发现（值得在面试里讲）

评测集：10 份制度文档 / 30 道问题，每题带期望关键词。

| 配置 | chunk | overlap | top-k | hit@1 | hit@k | 引用正确率 | 引用错误数 |
|---|---|---|---|---|---|---|---|
| A（默认） | 400 | 80 | 5 | 83.3% | 90.0% | 90.0% | 0 |
| B（大切片） | 800 | 0 | 3 | 83.3% | 90.0% | 90.0% | 0 |
| C（小切片） | 150 | 30 | 5 | 76.7% | **96.7%** | **96.7%** | 0 |

**结论**：
1. 小切片召回更高、精度略低 —— 经典召回/精度权衡
2. 失败的 3 道题**全部**来自"整份文档装进一个 chunk"的短文档：
   向量把「密码策略 + 漏洞修复 + 数据分级」平均成了一个方向
3. 保留默认 400/80：长文档切太碎会丢上下文且成本更高；
   生产上应按文档类型分配置（制度类 150~200，技术手册 400~600）

---

## 5. 遗留事项

| # | 事项 | 影响 | 建议做法 |
|---|---|---|---|
| 1 | mock 嵌入只懂词面，不懂语义 | 中 | 换真实 embedding（百炼 `text-embedding-v3`）后 hit@1 会明显上升；配置已留好（`RAG_EMBEDDING_PROVIDER=openai`） |
| 2 | 没有混合检索（向量 + BM25） | 中 | 错误码、金额、编号这类精确匹配，向量反而不如关键词；生产环境应加 BM25 通道 |
| 3 | 精确检索不是 ANN | 中 | 候选集全量读出算余弦；万级 chunk 内可接受，再大要换 HNSW/IVF 索引 |
| 4 | 检索是全量扫该用户的 chunk | 低 | 已按 owner 过滤 + `LIMIT 5000`；用户文档变多要加预筛 |
| 5 | 文档没有版本与更新机制 | 低 | 重复导入按哈希拦截，但"改了一个字重新导入"会变成两份；要做 diff/版本 |
| 6 | 扫描件 PDF 需要 OCR | 低 | 当前明确报错提示，不会静默产生空索引 |
| 7 | 真实模型未实测 RAG | 低 | 真实 Key 只跑过第 4 周的 Agent 链路；RAG 的引用格式在真实模型上还需验一次 |

---

## 6. 第 6 周开工提醒：Agent + RAG 整合

计划原文第 6 周（Day36-42）：把知识库问答、工具调用、会话管理、任务业务整合成完整应用。

三个演示场景：

- **场景 A 知识问答**："公司的报销期限是什么？" → 检索制度 → 回答期限 + 真实来源；找不到就说无法确认
- **场景 B 业务数据查询**："列出我还没完成的任务，按优先级排序。" → Agent 调任务工具 → 基于真实数据库作答
- **场景 C 知识与行动结合**："根据项目交付规范，帮我生成一个上线前检查任务。"
  → 先检索规范，再生成任务草稿，确认后创建，返回任务 ID

**开工前必须注意的三件事**：

1. **场景 C 的路由**：哪些问题走知识检索、哪些走业务工具，要显式设计。
   建议用**意图分流**：出现"制度/规范/流程/规定/怎么申请"等词走检索，
   "我的/我的任务/待办"走工具，模糊的走"先检索后工具"。
2. **检索结果要能变成工具参数**：场景 C 里规范里的"优先级"要能映射成 `createTask` 的参数，
   这正好复用第 4 周的 `createTask` 草稿 + 两阶段确认。
3. **上下文窗口裁剪**（Day38）：现在会话按条数裁剪（20 条），不是按 token。
   接入 RAG 后上下文会明显变长，必须改成按 token 裁剪。

---

## 7. 环境与运行

```bash
# 中间件
bash scripts/start-mysql-dev.sh start    # MySQL 3307
bash scripts/start-redis-dev.sh start    # Redis 6380

# 应用（mock provider，离线可跑）
bash scripts/start-app-local.sh          # 从 .env 注入变量，18080
# 或者手动：
AI_PROVIDER=mock MYSQL_PORT=3307 REDIS_PORT=6380 \
  java -jar target/smartdesk-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=18080

# 验收
python scripts/week5-acceptance.py
```

**建表**：测试库会在测试上下文启动时自动重建（`spring.sql.init.mode=always`）；
dev 库手工执行 `src/main/resources/db/schema.sql`（用 mysql 客户端时记得
`--default-character-set=utf8mb4`，否则中文字符串默认值会报错）。

**切换真实 embedding**（可选）：
```bash
RAG_EMBEDDING_PROVIDER=openai
RAG_EMBEDDING_MODEL=text-embedding-v3
RAG_EMBEDDING_DIMENSIONS=1024
```
注意 DeepSeek 没有 embedding 接口，要用百炼的 Key。
