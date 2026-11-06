# 第 2 周复盘：数据库、Redis、鉴权与后端工程能力

> 对应计划 Day 14。这份文档回答三个问题：
> **这周做了什么**、**包结构重构成什么样了、为什么**、**面试时怎么讲**。

---

## 一、本周交付总览

| Day | 主题 | 核心产出 | 验收结果 |
|-----|------|----------|----------|
| 8 | 索引 / EXPLAIN / 分页 / 事务 | 3 个复合索引 + `TaskQuery` 条件查询 | `type=ref, key=idx_owner_status` ✅ |
| 9 | DTO/VO / 参数校验 / 全局异常 | `TaskQuery`、`UserResponse`、3 个新异常处理器 | 非法 ID / 空标题 / 错误状态全部明确报错 ✅ |
| 10 | Redis 缓存 | `CacheConfig` + `taskDetail::{ownerId}:{id}` | 读写一致性验证通过 ✅ |
| 11 | JWT + Spring Security | 登录/注册 + 三层越权防护 | 改 URL id 读不到别人的数据 ✅ |
| 12 | 配置 / 环境变量 / Docker | 4 套 profile + `.env.example` + Dockerfile | 生产配置 fail-fast ✅ |
| 13 | 测试体系 | 50 个测试（单元 / 切片 / 集成） | `Tests run: 50, Failures: 0` ✅ |
| 14 | 复盘 / 架构 / 面试 | 本文档 + `architecture.md` | 38 项端到端验收全通过 ✅ |

**硬指标**：50 个测试全绿 + 38 项端到端验收全过（且连续跑两次结果一致）。

> **验收脚本踩的第 5 个坑：脚本自己不能重复运行**
>
> 第一版脚本直接调注册接口建 `alice` / `bob`。第一次跑全绿，第二次跑必然崩：
> 用户名已存在 → `USERNAME_EXISTS` → 后续所有断言连锁失败。
> 根因是**验收脚本假设了数据库是一次性的干净状态**。
>
> 修法：`ensure_account()` 先试注册，失败就退回登录；再 `purge_tasks()` 清掉这两个账号
> 的历史任务，让"N 个任务"这类精确计数断言在重复运行时依然成立。
>
> **教训**：验收脚本本身也是代码，也要有测试。判断标准很简单 ——
> **连跑两次，结果必须一致**。做不到就说明它依赖了状态。
> 这个习惯在 CI 里尤其重要：一次性的脚本在流水线里第二次跑就红。

---

## 二、包结构重构评估

### 重构前（第 1 周）

```
com.smartdesk
├── common/          # 统一响应、异常
├── config/          # MyBatis-Plus
├── task/
│   ├── controller/
│   ├── dto/
│   ├── entity/
│   ├── mapper/
│   └── service/
└── SmartDeskApplication.java
```

### 重构后（第 2 周）

```
com.smartdesk
├── common/          # ApiResponse / GlobalExceptionHandler / 业务异常 / HealthController
├── config/          # SecurityConfig / CacheConfig / MybatisPlusConfig / MybatisMetaObjectHandler
├── security/        # JwtService / JwtAuthenticationFilter / JwtPrincipal / CurrentUser
├── task/            # controller / dto / entity / mapper / service
├── user/            # controller / dto / entity / mapper / service   ← 新增领域
└── SmartDeskApplication.java
```

### 三个关键决策

**决策 1：`security/` 独立成包，不塞进 `user/`**

鉴权是**横切关注点**，将来会有第二个受保护资源（知识库文档），它不属于 `user` 领域。
如果把 `JwtService` 放进 `user/`，第二个领域就得反向依赖 `user/`，包之间产生环。
`security/` 是"面向接口而非面向功能"的依赖方向。

**决策 2：`dto/` 在领域包内，不上提到顶层 `dto/`**

`task/dto/` 和 `user/dto/` 各自闭环。放顶层 `dto/` 会让 DTO 变成一锅粥，
改一个任务字段要翻遍整个 `dto/` 目录。判断标准很简单：**DTO 只服务于一个领域，就放在那个领域里**。

**决策 3：`Task` 实体不直接返回给前端**

实体是**持久化模型**，`TaskResponse` 是**视图模型**，两者分离（VO 模式）。

原因有三：
1. `ownerId` 不该暴露给前端 —— 前端没有理由知道内部主键是"谁的"
2. 以后实体加敏感字段（软删除标记、内部备注），忘记加 `@JsonIgnore` 就直接泄漏
3. `TaskStatus` 是枚举，序列化后是 `"TODO"`；`TaskResponse` 额外给了 `statusDescription`，
   前端可以直接展示，不用自己维护一份状态字典

> **这周踩的坑（也是决策 3 的代价）**：`AppUser` 实体上原本漏了 `@JsonIgnore`，
> 密码哈希差点被返回。补了 `@JsonIgnore` 之后我更确信：
> **返回给前端的应该是专门的 VO，而不是实体**。VO 是"白名单"，实体是"黑名单"，
> 天然容易漏。`UserResponse` 就是这么加的，`passwordHash` 从头到尾就没进过 VO。

### 没有重构的地方（刻意保留）

| 保留项 | 原因 |
|--------|------|
| `TaskService.getById(Long)` | 只有测试在用，对外接口全走 `getByIdForOwner`。等第 3 周没有别的调用方就删掉 |
| `TaskService.list(ownerId, status, page, pageSize)` | 已被 `query(TaskQuery)` 取代，但 `count()` 还在用它。合并时一起改 |
| `common` 包里的 `ApiResponse` | 已经是通用工具，再抽一层 `core/` 属于过度设计 |

> **面试怎么讲**："我按领域分包，`dto` 放领域内而不是集中放顶层；
> `security` 独立因为它是横切关注点。返回给前端一律用 VO 而不用实体，
> 因为实体是黑名单——加字段时容易忘记加 `@JsonIgnore` 导致泄漏，
> 这周我就踩过一次密码哈希差点返回的坑。"

---

## 三、这周最值钱的三个技术决策

### 1. 缓存 key 里放 ownerId（而不是"权限查询不走缓存"）

**第一版设计**："权限校验的查询不走缓存"，理由是怕缓存被污染。

**结果**：`getById`（唯一带缓存的方法）**从来不会被调用** —— 所有对外接口都走
`getByIdForOwner`，缓存形同虚设，Day 10 验收直接失败。

**最终解法**：把 ownerId 放进 key，`taskDetail::{ownerId}:{id}`。

```java
@Cacheable(cacheNames = TASK_CACHE, key = "#ownerId + ':' + #id", unless = "#result == null")
public Task getByIdForOwner(Long id, Long ownerId) { ... }
```

> **这才是通用规律**：缓存 key 必须包含**所有会影响结果的条件**。
> 少一个条件 = 缓存污染（拿到别人的数据）或缓存失效（永远命中不了）。
> 这比"绕开缓存"优雅得多 —— 后者是性能问题的补丁，前者才是设计。

### 2. 写操作"删缓存"而不是"更新缓存"

```java
@CacheEvict(cacheNames = TASK_CACHE, allEntries = true)
```

**为什么不是"更新缓存"？**

考虑并发：请求 A 更新任务为 `DOING`，请求 B 同时更新为 `DONE`。
如果先写缓存再写数据库，两个请求的缓存写入顺序和数据库写入顺序**可能相反**，
最终缓存里是 `DOING`，数据库里是 `DONE` —— 不一致。

而"先写数据库，再删缓存"：
- 数据库是唯一真相源
- 缓存不存在或过期时，下次读会从数据库重建
- 代价只是短暂的一次缓存未命中（性能损失），而不是**返回错误数据**（正确性损失）

> **面试怎么讲**："缓存和数据库双写的一致性问题，本质上是在用'性能'换'正确性'。
> 我选了一致性：先落库再删缓存，缓存失效期间只是回源多查一次，
> 绝不会返回脏数据。如果业务能接受短暂不一致，反过来先删缓存再落库也行，
> 前提是数据库写失败要补偿。"

### 3. 三层越权防护（不是一层）

| 层 | 做法 | 挡住什么 |
|----|------|----------|
| Controller | ownerId **只**从 `CurrentUser.getUserId()` 取，请求体不接受 ownerId | 防止"用户主动传入别人的 id" |
| Service / SQL | `WHERE id = ? AND owner_id = ?` | 即使上层漏了，SQL 层也不会返回别人的行 |
| Cache | key 包含 ownerId | 即使缓存逻辑写错，也不会命中别人的缓存 |

> **为什么不用 AOP / 拦截器做？**
> 拦截器能做鉴权，但做不了"数据归属校验"——归属是**行级**的，不是路径级的。
> `/api/tasks/{id}` 这个路径本身不携带归属信息，归属只有在查到那一行之后才知道。
> 所以行级校验必须在 SQL 层，用 `WHERE owner_id = ?` 天然表达。

---

## 四、本周踩的坑（复盘版）

完整版在 `week2-guide.md` 第 1 节，这里只记**教训**：

| # | 现象 | 根因 | 教训 |
|---|------|------|------|
| 1 | 缓存读第二次 500 `ClassCastException` | Spring Data Redis 4 的 `defaultTyping` 默认从 true 改成 false | **升级大版本必须先读 release notes**，序列化行为这种"静默变更"最难查 |
| 2 | `BindingException: Invalid bound statement` | Mapper 接口声明了方法但没写 `@Select` 实现 | MyBatis 的注解式 Mapper，声明 ≠ 实现 |
| 3 | `Column 'created_at' cannot be null` | 缺 `MetaObjectHandler`，`@TableField(fill=INSERT)` 静默失效 | **声明 + 实现两步**，Spring Boot 4 不会因为缺 Handler 报错，只会静默不填 |
| 4 | `@WebMvcTest` 报 "No qualifying bean of type 'JwtService'" | 切片不加载普通 `@Component` | 切片测试需要显式 `@MockitoBean` 补齐外部依赖 |
| 5 | 认证后仍返回 401 | `@WebMvcTest` 不加载普通 `@Configuration`，安全配置没生效 | 要 `@Import(SecurityConfig.class)` |
| 6 | `RedisCacheWriter.builder()` 编译不过 | Boot 4 改成了静态工厂方法 | 同上，看文档 |
| 7 | 验收脚本第二次跑就红 | 脚本假设数据库是干净的，账号已存在导致 `USERNAME_EXISTS` | **验收脚本必须可重复运行**，连跑两次结果一致 |

**最值得记的一条是 #1**：它没有任何报错提示，缓存第一次写成功、第二次读就炸，
而且报错信息（`LinkedHashMap cannot be cast to Task`）完全指向不了根因。
**遇到"类型转换失败但逻辑没变"的怪问题，先怀疑序列化配置。**

---

## 五、限时面试（30 分钟，不查资料）

> 完整 15 题在 `week2-guide.md` 第 8 节。这里是"必须能流畅答出"的核心 8 题 + 答题骨架。

### Q1：认证和授权有什么区别？

**骨架**：认证 = **你是谁**（验证 JWT 签名、解出 userId）；授权 = **你能做什么**（这条数据是不是你的）。
本项目里 `JwtAuthenticationFilter` 只做认证，把 `JwtPrincipal` 塞进 SecurityContext；
真正的授权在 Service 层的 `WHERE owner_id = ?`。

### Q2：JWT 为什么不能放敏感信息？

**骨架**：JWT 是**签名**不是**加密** —— payload 只是 Base64URL 编码，谁拿到 token 都能解开看内容。
签名只能保证"内容没被改"，不能保证"别人看不到"。所以只放 userId、username 这类非敏感标识。

### Q3：怎么防止用户改 URL 里的 id 越权？

**骨架**：三层（ownerId 只从登录态取 / SQL 带 `owner_id` 条件 / 缓存 key 含 ownerId）。
**返回 404 而不是 403** —— 403 等于告诉攻击者"这个 id 确实存在但你没权限"，反而泄露了存在性。

### Q4：缓存穿透、击穿、雪崩分别是什么，怎么解决？

**骨架**：
- **穿透** = 查不存在的 key，每次都打到 DB → 不缓存 null（本项目 `disableCachingNullValues`）
- **击穿** = 某个热点 key 过期瞬间，大量请求同时打 DB → 互斥锁重建（本项目 `lockingRedisCacheWriter`）
- **雪崩** = 大批 key 同时过期 → TTL 加随机偏移（本项目 1800s + 0~60s 随机）

### Q5：为什么写操作是"删缓存"不是"更新缓存"？

**骨架**：并发写时"先更新缓存"的顺序可能和数据库写入顺序相反，导致缓存与 DB 不一致。
"先落库再删缓存"最多导致一次缓存未命中（性能损失），不会返回脏数据（正确性损失）。

### Q6：Spring Profile 和 Maven Profile 的区别？

**骨架**：Maven profile 是**构建期**（决定打什么包、跳过什么测试、选什么依赖）；
Spring profile 是**运行期**（决定读哪个配置文件、连哪个数据库）。
`pom.xml` 里那三个 profile 是构建工具的；`application-{local,test,prod}.yml` 是框架的。

### Q7：为什么生产环境的配置项不写默认值？

**骨架**：默认值是为了本地方便，但生产环境忘了配的时候，有默认值 = **带着一个所有人都知道的默认密钥上线**。
没有默认值 = 启动直接失败（fail fast）—— 早失败好过带病上线。

### Q8：为什么不用 H2 做集成测试？

**骨架**：H2 和 MySQL 的 SQL 方言、类型行为、索引实现、并发语义都有差异，
在 H2 上通过的测试到 MySQL 上可能就挂。**测试环境要尽量贴近生产**。
本项目的集成测试直接连真实 MySQL（`smartdesk_test` 库），用 `schema-test.sql` 建表。

---

## 六、下周（第 3 周）准备

| 项 | 说明 |
|----|------|
| **Spring AI 接入** | 引入 Spring AI 2.0.1，先跑通一个 ChatClient 调用 |
| **知识库雏形** | 用 MySQL 存"文档片段"表，第 3 周后半段接向量检索 |
| **RAG 链路** | 用户提问 → 检索片段 → 拼 prompt → 调用大模型 → 返回答案 |
| **保留第 2 周能力** | 文档属于某个用户，检索和展示同样要走 ownerId 隔离 —— **越权防护不能因为是新功能就放松** |
| **技术债** | `TaskService.getById` / `list()` 已无生产调用方，第 3 周清理 |

> **下周最重要的提醒**：第 2 周刚建立的"数据边界只来自登录态"这条规则，
> 在加新功能时最容易破。一旦文档表出来，`DocumentMapper` 里少写一个
> `owner_id` 条件就是数据泄漏。**新表建出来先写测试，越权用例必须先写。**
