# SmartDesk 系统架构说明

> 一页纸讲清楚 SmartDesk 后端的架构。第 2 周结束时的核心产出。
>
> **面试用法**：讲项目时按这个顺序讲 —— 分层 → 请求链路 → 数据模型 → 缓存 → 认证 → 部署。

---

## 1. 分层结构与职责边界

```
┌─────────────────────────────────────────────────────────┐
│  Controller 层    com.smartdesk.*.controller               │
│  ───────────────────────────────────────────────────      │
│  职责：参数绑定与校验 → 取当前用户 → 调 Service → 包装响应  │
│  禁止：业务逻辑、SQL、缓存操作                              │
└──────────────────────────┬──────────────────────────────┘
                           │ DTO / VO
┌──────────────────────────▼──────────────────────────────┐
│  Service 层       com.smartdesk.*.service                 │
│  ───────────────────────────────────────────────────      │
│  职责：业务规则、事务边界（@Transactional）、缓存编排        │
│  禁止：出现 @RequestBody/@PathVariable、直接拼 SQL          │
└──────────────────────────┬──────────────────────────────┘
                           │ Entity / Query
┌──────────────────────────▼──────────────────────────────┐
│  Mapper 层        com.smartdesk.*.mapper                  │
│  ───────────────────────────────────────────────────      │
│  职责：数据访问。继承 MyBatis-Plus BaseMapper 得白拿 CRUD   │
│  禁止：业务判断                                            │
└──────────────────────────┬──────────────────────────────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
     ┌─────────────────┐      ┌─────────────────┐
     │   MySQL 8.0     │      │   Redis 5.0     │
     │   端口 3307      │      │   端口 6380      │
     │   InnoDB/utf8mb4│      │   缓存任务详情    │
     └─────────────────┘      └─────────────────┘
```

**横切关注点**（不属于任何一层，但被所有层使用）：

| 组件 | 位置 | 职责 |
|------|------|------|
| 全局异常处理 | `common/GlobalExceptionHandler` | 把异常转成统一 JSON + 正确状态码 |
| 统一响应体 | `common/ApiResponse` | 前端只需处理一种格式 |
| 安全配置 | `config/SecurityConfig` | 过滤器链、URL 授权规则、401/403 响应 |
| 缓存配置 | `config/CacheConfig` | 序列化、TTL、防雪崩/击穿 |
| 当前用户 | `security/CurrentUser` | 从 SecurityContext 读登录态 |

**核心原则**：依赖方向永远是**单向向下**（Controller → Service → Mapper）。
Service 不知道 HTTP 的存在，Mapper 不知道业务的存在。

---

## 2. 请求链路

### 2.1 已认证请求

```
HTTP 请求
   ↓
[Servlet 容器]
   ↓
[JwtAuthenticationFilter]  ← 从 Authorization: Bearer xxx 取 Token
   │  解析成功 → 构造 JwtPrincipal(userId, username)
   │  放入 SecurityContextHolder
   ↓
[AuthorizationFilter]  ← 检查 authorizeHttpRequests 规则
   │  未登录 → 401（统一 JSON）
   │  已登录 → 放行
   ↓
[TaskController]
   │  1. @Valid 校验 DTO → 失败则抛 MethodArgumentNotValidException
   │  2. CurrentUser.getUserId()  ← ★ ownerId 只从这里来
   │  3. 调 Service
   ↓
[TaskServiceImpl]
   │  1. @Cacheable → 命中直接返回（方法体不执行）
   │  2. 未命中 → LambdaQueryWrapper 组装动态 SQL
   │  3. @Transactional 包裹
   ↓
[TaskMapper] → MyBatis-Plus → MySQL
   ↓
[ResponseEntity<ApiResponse<TaskResponse>>] → JSON
```

### 2.2 登录请求

```
POST /api/auth/login {username, password}
   ↓
[SecurityConfig] permitAll 放行
   ↓
[AuthController.login]
   ↓
[UserServiceImpl.authenticate]
   │  1. selectByUsername 查用户
   │  2. 用户不存在 → 也跑一次 encode（防时序攻击）→ 返回 null
   │  3. 密码错误 → 返回 null
   │  4. 校验通过 → 返回用户
   ↓
[JwtService.issueToken] → HS256 签名
   ↓
{ token, userId, username, nickname, expiresIn }
```

---

## 3. 数据模型与索引设计

### 3.1 表结构

```
app_user                          task
├─ id            PK               ├─ id            PK
├─ username      UK (uk_username) ├─ title
├─ password_hash (BCrypt)         ├─ description
├─ nickname                       ├─ status        (TODO/IN_PROGRESS/DONE/CANCELLED)
├─ enabled                        ├─ priority      (1-5)
├─ created_at                     ├─ owner_id      FK → app_user.id
└─ updated_at                     ├─ due_date
                                  ├─ created_at
                                  └─ updated_at
```

### 3.2 索引设计（Day8）

| 索引 | 列 | 服务的查询场景 |
|------|-----|--------------|
| `idx_owner_status` | (owner_id, status) | 我的待办 / 我的已完成 |
| `idx_owner_due` | (owner_id, due_date) | 查我近期的任务、找出已过期 |
| `idx_owner_priority` | (owner_id, priority) | 我的任务按优先级排序 |

**设计原则**：
1. 所有索引以 `owner_id` 开头 —— 它是每个查询的必带条件，也是数据隔离的根基
2. 遵循最左前缀 —— `(owner_id, status)` 自动覆盖 `WHERE owner_id = ?`
3. 不建冗余单列索引 —— `idx_owner_id` 被 `idx_owner_status` 的最左前缀覆盖
4. 索引有代价：占空间 + 拖慢写入 + 可能选错

### 3.3 EXPLAIN 验证

```sql
EXPLAIN SELECT id, title FROM task WHERE owner_id = 1 AND status = 'TODO';
-- key  = idx_owner_status   ← 走索引
-- type = ref                ← 范围查找
-- rows = 1                  ← 预估扫描行数小

EXPLAIN SELECT id, title FROM task WHERE status = 'TODO';
-- key  = NULL               ← 索引失效（全表扫描）
-- type = ALL
```

---

## 4. 缓存策略与失效时机

### 4.1 Cache-Aside 模式

```
读：查 Redis → 命中直接返回
             → 未命中查 MySQL → 回填 Redis（TTL 30min + 随机 60s）

写：更新 MySQL → 删除 Redis 缓存（@CacheEvict）
```

**为什么写是"删"不是"更新"**：
- 更新缓存可能和并发写产生顺序错乱
- 删除是幂等的，下次读时重建
- 删失败有兜底（重试队列、定时对账）

**为什么"先更新 DB 再删缓存"**：
- 反过来（先删缓存再更新 DB），并发读会穿透到 DB 拿到旧值并**重新写回缓存**，脏数据留更久

### 4.2 三个缓存问题的处理

| 问题 | 本项目的处理 |
|------|-------------|
| 穿透 | `disableCachingNullValues()` —— null 不缓存；`unless = "#result == null"` |
| 击穿 | `lockingRedisCacheWriter` —— 分布式锁保证同 key 写入原子性 |
| 雪崩 | TTL = 1800s + 随机 0~60s 偏移，打散集体过期 |

### 4.3 缓存污染的防范（关键设计）

**缓存 key 必须包含所有影响结果的条件。**

`getByIdForOwner(id, ownerId)` 的缓存 key 是 `taskDetail::{ownerId}:{id}`：

| 用户 | 查询 | 缓存 key | 结果 |
|------|------|---------|------|
| alice (id=1) | task#1 | `taskDetail::1:1` | 缓存 alice 的视角 |
| bob (id=2) | task#1 | `taskDetail::2:1` | **不同的 key**，命中不了 alice 的缓存 |

如果只用 `id` 做 key，用户 A 查 task#1 会把结果缓存起来，
用户 B 再查 task#1 就会**命中缓存拿到 A 的数据** —— 这就是缓存污染，
是数据隔离场景下最危险的缓存 bug。

**这里踩过一个弯路，值得记录：**

第一版设计是"权限校验的查询不走缓存"，理由是怕污染。但这导致
`getById`（唯一带缓存的方法）**从来不会被调用** —— 所有对外接口都走
`getByIdForOwner`，缓存形同虚设，Day10 验收直接失败。

正确解法就是**把 ownerId 放进 key**：既享受缓存性能，又完全隔离。

> 面试标准答法：「缓存 key 的维度必须覆盖所有影响返回值的条件。
> 少一个维度，就等于允许不同维度共享同一份结果。」

**写操作清缓存用 `allEntries = true`**：

因为 key 里带了 ownerId，同一任务在不同用户下是不同的 key。
而 update/delete 时无法枚举所有 ownerId 变体，所以清空整个 `taskDetail`
缓存 —— 简单且绝对安全。高并发系统会用 Redis SCAN 按模式精确删除。

---

## 5. 认证与授权链路

### 5.1 概念区分

| | 认证 Authentication | 授权 Authorization |
|---|---|---|
| 回答 | 你是谁？ | 你能做什么？ |
| 实现 | JWT 令牌 | ownerId 过滤 + URL 规则 |
| 本项目 | `JwtService` / `JwtAuthenticationFilter` | `CurrentUser` + Service 层 SQL 过滤 |

### 5.2 密码安全

```
注册：rawPassword → BCrypt.encode() → 存 password_hash
登录：passwordEncoder.matches(raw, hash) → 自动按 hash 里的 salt 和 cost 复算比对
```

- BCrypt 相比 MD5/SHA 的两个优势：**故意慢**（cost=10）+ **自动加盐**
- 同一密码两次注册哈希不同（测试用例 `register_samePasswordTwice_shouldProduceDifferentHashes` 验证了这点）

### 5.3 三层越权防护

| 层 | 措施 | 作用 |
|----|------|------|
| Controller | ownerId 只从 `CurrentUser.getUserId()` 取，**绝不从请求参数取** | 杜绝"改参数就能看别人数据" |
| Service | SQL 层 `WHERE id = ? AND owner_id = ?`，不是查出来再判断 | 别人的数据根本不会发到应用层 |
| 缓存 | 缓存 key 含 ownerId（`taskDetail::{ownerId}:{id}`） | 兼顾缓存性能与数据隔离 |

### 5.4 状态码选择

| 场景 | 状态码 | 理由 |
|------|--------|------|
| 未登录 | 401 | 需要认证 |
| 已登录但权限不足（角色层面） | 403 | 身份确认但无权 |
| **访问他人资源** | **404** | 返回 403 等于告诉攻击者"这个 id 存在"，是信息泄露 |

### 5.5 用户枚举防护

登录失败时，无论"用户不存在"还是"密码错误"，返回完全相同的响应：

```json
{ "success": false, "errorCode": "BAD_CREDENTIALS", "errorMessage": "用户名或密码错误" }
```

集成测试 `login_withWrongPassword_shouldReturn401` 断言了两种情况的消息完全一致。

---

## 6. 配置与部署

### 6.1 配置分层

```
application.yml            基础配置，所有环境共享，敏感信息走环境变量
├── application-local.yml  本地：DEBUG 日志 + SQL 打印 + 详细健康检查
├── application-test.yml   测试：WARN 日志 + 独立 Redis db(1)
└── application-prod.yml   生产：敏感配置【无默认值】，未配置则启动失败
```

**prod profile 的关键设计**：

```yaml
password: ${MYSQL_PASSWORD}      # ✅ 不给默认值 —— fail fast
password: ${MYSQL_PASSWORD:}     # ❌ 给默认值 —— 生产裸奔
```

**Spring Profile（运行期）vs Maven Profile（构建期）**：

| | 生效时机 | 决定什么 | 激活方式 |
|---|---------|---------|---------|
| Spring Profile | 运行期 | 读哪份配置 | `--spring.profiles.active=prod` |
| Maven Profile | 构建期 | 打包什么、跑不跑测试 | `-P prod` |

### 6.2 环境变量

`.env.example` 是模板（可提交），`.env` 是真实配置（已 gitignore）。

```bash
cp .env.example .env
```

### 6.3 Docker 编排

```yaml
mysql (3307)  ──┐
                ├──> app (18080)   ← 等依赖 service_healthy 才启动
redis (6380)  ──┘
```

- **多阶段构建**：编译用 Maven 镜像，运行用 JRE 镜像，最终镜像小、攻击面小
- **非 root 用户**：容器逃逸时危害更小
- **HEALTHCHECK**：让编排平台知道容器是否就绪
- **`depends_on: condition: service_healthy`**：等依赖**真正健康**，而不是"容器启动了"
- **命名卷**：数据持久化，`docker compose down` 不删，`down -v` 才删

---

## 7. 测试策略

```
src/test/java/com/smartdesk/
├── security/JwtServiceTest.java            7 个 —— JWT 签发/篡改/过期/异密钥
├── task/service/TaskServiceImplTest.java   13 个 —— Service 逻辑（mock Mapper）
├── task/controller/TaskControllerTest.java 14 个 —— Web 切片（mock Service）
├── user/service/UserServiceImplTest.java    7 个 —— BCrypt / 用户枚举防护
└── task/TaskIntegrationTest.java            8 个 —— 真实 MySQL 全链路
```

**分层原则**：

| 类型 | 注解 | 加载什么 | 隔离什么 |
|------|------|---------|---------|
| 单元测试 | `@ExtendWith(MockitoExtension)` | 无 | Mock 掉 Mapper |
| Web 切片 | `@WebMvcTest` | Web 层 + Security | Mock 掉 Service |
| 集成测试 | `@SpringBootTest` | 全量 | 用真实 MySQL |

**为什么用真实 MySQL 不用 H2**：SQL 方言、类型精度、执行计划都有差异，
H2 会造成"本地测试全绿、生产一跑就炸"的假象。

**切片测试的坑**：加了 `spring-security-test` 后，`@WebMvcTest` 会加载
`SecurityConfig` 和 `JwtAuthenticationFilter`，但 `JwtService` 是普通
`@Component` 不在切片内 —— 必须用 `@MockitoBean` 补上。

---

## 8. 关键技术决策记录

| 决策 | 选择 | 理由 | 代价 |
|------|------|------|------|
| Spring Boot 版本 | 4.1.2 | Spring AI 2.0 要求 Boot 4.0+ | Initializr 输出的 `.RELEASE` 后缀在 Maven Central 不存在，要手改 |
| MyBatis-Plus starter | `mybatis-plus-spring-boot4-starter` | boot3 版在 Boot 4 下不装配 Mapper | 换 starter 时容易踩 |
| Jackson | Boot 4 默认的 Jackson 3 | 无需额外配置 | databind 在 `tools.jackson` 包，注解还在 `com.fasterxml.jackson.annotation` |
| Redis 序列化 | `GenericJacksonJsonRedisSerializer` | JSON 可读、安全，避免 JDK 序列化漏洞 | 类名去掉了 "2"，旧教程会写错 |
| 缓存策略 | Cache-Aside + 删缓存 | 幂等，避免并发写顺序错乱 | 存在短暂不一致窗口 |
| ownerId 传递 | Controller 显式传给 Service | Service 不与 Security 耦合，单元测试简单 | 方法签名啰嗦 |
| 跨用户访问 | 返回 404 而非 403 | 不泄露资源存在性 | 排障时不如 403 直观 |
| 测试数据库 | 真实 MySQL | 避免 H2 造成假绿 | 测试依赖本地 MySQL |
