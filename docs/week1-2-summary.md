# 第 1–2 周总结（交接文档）

> **用途**：跨会话/跨阶段的上下文交接。换新对话、隔几周回来、或面试前快速回忆时，
> 读这一份就够，不必翻历史记录。
>
> **状态**：第 1、2 周已全部完成并验证通过。
> **最后更新**：2026-11-06 ｜ **对应提交**：`f66c149`（分支 `main`）
>
> ⚠️ 第 3 周已完成，请接着读 [`docs/week3-summary.md`](week3-summary.md)。
> 另外：本文第 9 节把"第 3 周"写成 RAG，那是当时的粗略估计；实际按 `ja计划.docx`，
> **第 3 周是大模型 API / Prompt / 结构化输出，RAG 在第 5 周**。

---

## 0. 一句话现状

第 1 周搭起可运行的 Spring Boot 后端骨架，第 2 周补齐数据库索引/参数校验/Redis 缓存/JWT 鉴权/多环境配置/容器化，
共 **50 个自动化测试 + 38 项端到端验收 + GitHub Actions 容器化验证**全部通过。
下一步是第 3 周（Spring AI + RAG 知识库）。

---

## 1. 项目基础信息

| 项 | 值 |
|---|---|
| 本地路径 | `D:\a应聘\学习\workbuddy\smartdesk` |
| 计划文档 | `D:\a应聘\学习\ja计划.docx`（8 周求职计划） |
| GitHub | `https://github.com/wy-6/smartdesk`（公开，分支 `main`） |
| 技术栈 | Java 17 · Spring Boot **4.1.2** · Spring Security 7.1.2 · Spring Data Redis 4.1.2 · Jackson 3 · MyBatis-Plus 3.5.17 · JJWT 0.12.6 |
| 数据库 / 缓存 | MySQL 8.0 · Redis 5.0.14.1 |
| 本地端口 | 应用 **18080**（8080 常被占用）· MySQL dev **3307** · Redis dev **6380** |

### 技术栈是锁定的，不要升版本

Boot 4 / Jackson 3 / Spring Data Redis 4 有一批 breaking change 已经踩平（见第 5 节）。
升级会把这些坑重新踩一遍，收益为零。

---

## 2. 第 1 周交付（Spring Boot 后端基础）

| Day | 内容 | 交付 |
|---|---|---|
| 1 | 环境搭建、Maven 骨架 | `pom.xml` 版本锁定、`SmartDeskApplication` |
| 2 | REST 基础、统一响应 `ApiResponse` | `common/ApiResponse`、`HealthController` |
| 3 | 实体 + MyBatis-Plus CRUD | `task` 领域完整分层、`schema.sql` |
| 4 | 全局异常处理 | `GlobalExceptionHandler`、`BusinessException` + errorCode 枚举 |
| 5 | 参数校验 `@Valid` | DTO 校验注解、`MethodArgumentNotValidException` 处理 |
| 6 | 测试入门 | `@WebMvcTest` 切片测试 |
| 7 | 复盘 | `docs/week1-guide.md` |

**关键约定（第 1 周就定下，全程沿用）**
- 统一响应结构：`{success, data, errorCode, errorMessage, timestamp}`
- Controller 保持薄，业务下沉 Service，SQL 留在 Mapper
- 返回前端用 VO 不用实体

---

## 3. 第 2 周交付（数据库、Redis、鉴权与工程能力）

### Day 8 — 索引、EXPLAIN、分页与筛选

- `task` 表建 3 个复合索引：
  - `idx_owner_status(owner_id, status)` — 最常用
  - `idx_owner_due(owner_id, due_date)` — 按截止时间排序
  - `idx_owner_priority(owner_id, priority)` — 按优先级筛选
- 新增 `dto/TaskQuery.java`：`ownerId / status / dueFrom / dueTo / minPriority / pageNum / pageSize`
- `TaskService.query(TaskQuery)` 支持组合筛选 + 分页
- **EXPLAIN 实测对照**（写进 `week2-guide.md`）：
  - `WHERE owner_id=1 AND status='TODO'` → `type=ref, key=idx_owner_status, rows=1`
  - `WHERE status='TODO'`（去掉最左列）→ `type=ALL, key=NULL, rows=5`
  - 这组对照是"最左前缀原则"最直观的证据，面试可直接讲

### Day 9 — DTO / VO / 参数校验 / 全局异常

- 补齐边界校验：非法 ID、空标题、错误状态、越界 priority 全部返回明确错误
- `GlobalExceptionHandler` 新增 3 个 handler：
  - `MethodArgumentTypeMismatchException` — 路径变量类型错误（如 `/tasks/abc`）
  - `MissingServletRequestParameterException` — 缺必填 query 参数
  - `HandlerMethodValidationException` — Boot 4 新增的方法级校验异常
- 错误码统一走 `ErrorCode` 枚举，前端可编程判断

### Day 10 — Redis 缓存（本项目最容易踩坑的一天）

- `config/CacheConfig.java`：`RedisCacheManager` + `GenericJacksonJsonRedisSerializer`
- 防三大问题：
  - **穿透**：`disableCachingNullValues()`（不缓存 null，宁可直接查库）
  - **击穿**：`RedisCacheWriter.lockingRedisCacheWriter(...)` 分布式锁，同一 key 只放一个请求进库
  - **雪崩**：TTL 1800s + **0~60s 随机偏移**
- 写操作 `@CacheEvict(allEntries = true)` —— **删缓存而非更新缓存**
- **缓存 key 含 ownerId**：`taskDetail::{ownerId}:{id}`

> **重要弯路（已记录在 `architecture.md`）**：第一版设计是"权限校验的查询不走缓存"，理由是怕污染。
> 但这导致 `getById`（唯一带缓存的方法）**从来不会被调用** —— 所有对外接口都走 `getByIdForOwner`，
> 缓存形同虚设，Day10 验收直接失败。正解是把 ownerId 放进 key：既享受性能，又完全隔离。

### Day 11 — 登录认证、BCrypt、JWT、Spring Security

- `app_user` 表（`uk_username` 唯一索引、`password_hash`、`enabled`）
- `UserServiceImpl`：BCrypt 哈希（cost=10，故意慢 + 自动加盐）
  - **防用户枚举**：账号不存在与密码错误返回同一条错误消息
  - **防时序攻击**：用户不存在时也跑一次 `encode`，让响应耗时一致
- `security/` 包：
  - `JwtService` — 签发/解析，HS256，`Keys.hmacShaKeyFor` 要求密钥 ≥32 字节
  - `JwtAuthenticationFilter` — `OncePerRequestFilter`，解析 `Authorization: Bearer`
  - `JwtPrincipal` — record `(Long userId, String username)`
  - `CurrentUser` — 静态读取 `SecurityContextHolder`
- `SecurityConfig`：`csrf.disable()`、`STATELESS`、统一 401/403 JSON

**三层越权防护（核心设计，不可动摇）**
1. ownerId 一律取自 JWT，绝不接受请求体/URL 传入的用户标识
2. SQL 层再兜一层：`WHERE id=? AND owner_id=?`，上层漏了也不会返回别人的行
3. 缓存 key 含 ownerId

另外：**越权访问返回 404 而非 403**，不泄露资源存在性。

### Day 12 — 配置、环境变量、日志、Maven profile、Docker

- 配置拆分：`application.yml` + `application-local.yml` / `application-test.yml` / `application-prod.yml`
- 敏感信息全走环境变量；**`prod` profile 敏感项不给默认值 → fail fast**
- 3 个 Maven profile：`local`（默认）/ `dev` / `prod`
- 区分两个概念：**Spring Profile 是运行期**，**Maven Profile 是构建期**
- `.env.example` 提交、`.env` 进 `.gitignore`
- 容器化（详见第 6 节）：
  - `Dockerfile` — 多阶段构建、非 root 用户、HEALTHCHECK、`exec java`
  - `compose.yaml` — mysql/redis/app 三容器，`depends_on: service_healthy`
  - `.dockerignore` — 挡住 `target/`、`.git`，**以及 `.env`（真实密码）**

### Day 13 — 测试（50 个）

测试金字塔，三层各司其职：

| 层 | 文件 | 数量 | 特点 |
|---|---|---|---|
| Service 单元测试 | `TaskServiceImplTest` | 13 | Mockito，含越权防护断言 |
| 用户服务单元测试 | `UserServiceImplTest` | 7 | 真实 BCrypt 编码器（不 mock 加密） |
| JWT 单元测试 | `JwtServiceTest` | 7 | 含篡改、异密钥、过期三类失败路径 |
| Web 切片测试 | `TaskControllerTest` | 14 | `@WebMvcTest` + `@Import(SecurityConfig)` |
| 集成测试 | `TaskIntegrationTest` | 9 | `@SpringBootTest` + **真实 MySQL**，不用 H2 |

> 为什么不用 H2：H2 的 SQL 方言、索引行为、事务隔离都和 MySQL 不一致，
> 用它测"分页/筛选/索引"等于自欺欺人。

### Day 14 — 复盘、架构、面试

- `docs/week2-retrospective.md` — 包结构重构评估（3 个决策 + 刻意保留的 3 处）、3 个关键技术决策、8 题答题骨架
- `docs/architecture.md` — 一页纸架构说明（分层、请求链路、数据模型与索引、缓存策略、认证授权、配置部署、测试策略、决策记录）
- `docs/week2-guide.md` — 15 道必须能回答的面试题

---

## 4. 验收数据（全部实测，非估算）

| 指标 | 结果 |
|---|---|
| `mvn -B test` | **50 个测试全绿**（7 + 13 + 7 + 14 + 9） |
| `python scripts/week2-acceptance.py` | **38 项断言全通过**，连跑两次结果一致 |
| EXPLAIN 对照 | `type=ref`（走索引）vs `type=ALL`（全表），`rows=1` vs `rows=5` |
| 应用启动 | 约 12 秒（本地）/ 3.39 秒（容器内 prod profile） |
| GitHub Actions | **全绿，1 分 50 秒** |

---

## 5. 踩过的坑（高价值，省几小时）

| # | 现象 | 根因 | 解法 |
|---|---|---|---|
| 1 | `4.1.2.RELEASE` 拉不到 | Initializr 输出的版本号在 Maven Central 不存在 | 改成 `4.1.2` |
| 2 | Jackson 3 的 `ObjectMapper` import 报错 | databind 搬到 `tools.jackson` | 改 import；但**注解仍在 `com.fasterxml.jackson.annotation`**，别改 |
| 3 | `RedisCacheWriter.builder()` 编译不过 | Spring Data Redis 4 改为静态工厂 | `lockingRedisCacheWriter(...)`；`BatchStrategies` 在 `cache` 包，常量是 `keys()` 不是 `DEFAULT` |
| 4 | 缓存二次读取 `ClassCastException: LinkedHashMap → Task` | **Redis 4 的 `defaultTyping` 默认关闭**（Jackson 2 时代是 true），类型信息丢失 | `enableDefaultTyping(BasicPolymorphicTypeValidator.builder().allowIfBaseType("com.smartdesk.").build())` |
| 5 | `BindingException: Invalid bound statement: AppUserMapper.selectByUsername` | Mapper 方法**只声明没实现** | 补 `@Select` 注解写 SQL |
| 6 | `Column 'created_at' cannot be null` | 缺 `MetaObjectHandler`，`@TableField(fill=INSERT)` 静默失效（声明与实现是两步） | 新增 `MybatisMetaObjectHandler`，并删掉 Service 里手动 set 时间的代码 |
| 7 | `@WebMvcTest` 14 个测试全报 "No qualifying bean of type JwtService" | 切片加载 `SecurityConfig`/过滤器，但 `JwtService` 不在切片内 | `@MockitoBean private JwtService jwtService;` |
| 8 | `@WebMvcTest` 认证后仍 401 | 切片**不扫描普通 `@Configuration`**，Spring Security 默认配置生效 | `@Import(SecurityConfig.class)` |
| 9 | `@MockBean` 找不到 | Boot 4 改名 | `@MockitoBean`；`@WebMvcTest` 移包到 `org.springframework.boot.webmvc.test.autoconfigure` |
| 10 | 验收脚本第二次跑必崩 | 脚本无条件注册 `alice`/`bob` → 撞 `USERNAME_EXISTS`，4 项断言连锁失败 | `ensure_account()`（注册失败退回登录，统一走登录拿 token）+ `purge_tasks()`（分页清空历史任务） |

**第 10 条是最值钱的一条经验**：验收脚本本身也是代码，**连跑两次结果必须一致才算合格**。
一次性脚本进 CI 流水线，第二次跑就红。

其他非代码坑：
- `mvn clean` 失败 → 运行中的应用占着 jar（文件锁），先停应用
- `TRUNCATE TABLE task` 失败 → 外键约束，改按序 `DELETE FROM task; DELETE FROM app_user;`
- 后台进程被 shell 退出杀掉 → `(... &)` / `nohup` 都不行，要用受管的 background task

---

## 6. Docker 问题：诊断与闭环

### 诊断结论（实测，不是猜的）

障碍只有 3 层，**硬件不是问题**：

1. `wsl.exe` 被 WorkBuddy 安全策略的程序黑名单封禁 → **唯一硬障碍**，Docker Desktop 的 Linux 引擎必须依赖 WSL2
2. Docker Desktop 根本没装（`C:\Program Files\Docker` 不存在，PATH 里只有孤立 CLI，且缺 compose 插件）
3. Docker Hub 不通（`registry-1.docker.io` 返回 000）

好消息：CPU 虚拟化已开启 ✅、Windows 11 家庭版完全支持 WSL2（不需要 Hyper-V）、`github.com` / `ghcr.io` 可达 ✅。

### 纯静态审查抓出 4 个「上机必炸」的 bug（已全修）

| # | Bug | 后果 |
|---|---|---|
| 1 | HEALTHCHECK 用 `curl`，但 `eclipse-temurin:17-jre-jammy` 不自带 | 容器永远 unhealthy → `depends_on: service_healthy` 永远等不到 |
| 2 | prod 日志写 `/var/log/smartdesk/`，该目录不存在且非 root 不可写 | **应用启动即失败**（最致命）→ 改 `LOG_FILE=/app/logs/app.log` + `mkdir` + `chown` |
| 3 | ENTRYPOINT 缺 `exec` | `docker stop` 的 SIGTERM 传不到 JVM，永远被 SIGKILL 强杀，优雅停机失效 |
| 4 | 健康检查端口写死 18080 | 改 `SERVER_PORT` 就失配 → 跟随环境变量 |

另外补了 `.dockerignore` —— 没有它 `target/`（几百 MB）和 **`.env`（真实密码）** 都会进构建上下文。

### 三条解决路径

| 路径 | 内容 | 状态 |
|---|---|---|
| **B 云端 CI** | GitHub Linux runner 真跑 `docker build` → `compose up -d --wait` → 6 步冒烟 → 优雅停机验证 | ✅ **已验证通过** |
| **C 离线静态校验** | hadolint（0 告警）+ compose CLI `config --quiet`（exit 0）+ 渲染核对变量插值 | ✅ 已完成 |
| **A 本机 Docker** | 移除 `wsl.exe` 程序黑名单 → 启用 WSL2 → 装 Docker Desktop（`desktop.docker.com`）→ 配镜像加速 | 📋 步骤见 `docs/docker-setup.md`，**已降级为可选** |

### CI 验证到的硬证据（日志原文）

| 验证点 | 日志 |
|---|---|
| 测试 | `Tests run: 41, Failures: 0, Errors: 0`（9 个 MySQL 集成测试排除） |
| 三容器健康 | `Container smartdesk-mysql Healthy` / `smartdesk-redis Healthy` / **`smartdesk-app Healthy`** |
| 应用启动 | `profile is active: "prod"` + `Started SmartDeskApplication in 3.391 seconds` |
| 接口冒烟 | `=== 冒烟测试全部通过 ===`（健康检查→401→注册201→登录→建任务→查任务） |
| 优雅停机 | `app 容器停机耗时 0s（<10s 说明收到了 SIGTERM 正常退出）` |

**`smartdesk-app Healthy` 这一行同时证明了三件事**：curl 装上了、日志目录可写、prod profile 能正常跑。
停机 0s 证明 `exec` 让 SIGTERM 直达 JVM。也就是说 4 个 bug 的修复全部有了真实容器的验证。

---

## 7. 不可动摇的工程约定

1. **数据边界只来自登录态**：ownerId 一律取自 JWT，绝不接受请求体/URL 传入的用户标识
2. **SQL 层再兜一层**：`WHERE id=? AND owner_id=?`，即使上层漏了也不会返回别人的行
3. **缓存 key 必须含全部影响结果的条件**（含 ownerId），否则缓存污染
4. **写操作删缓存**（`@CacheEvict(allEntries=true)`），不更新缓存
5. **返回前端用 VO 不用实体**：实体是黑名单（加字段易漏 `@JsonIgnore`），VO 是白名单
6. **返回 404 而非 403**：不泄露资源存在性
7. **密钥走环境变量**，`prod` profile 敏感项不给默认值（fail fast）
8. **验收脚本必须可重复运行**：连跑两次结果一致才算合格

---

## 8. 常用命令速查

```bash
cd D:/a应聘/学习/workbuddy/smartdesk

# 启动依赖（当前均已停止）
bash scripts/start-mysql-dev.sh start
bash scripts/start-redis-dev.sh start

# 构建与测试
mvn -B test                    # 50 个测试
mvn -B test -Dtest='!*IntegrationTest' -DfailIfNoTests=false   # 跳过需 MySQL 的集成测试，41 个

# 起服务（18080）
MYSQL_PORT=3307 REDIS_PORT=6380 java -jar target/smartdesk-0.1.0-SNAPSHOT.jar --server.port=18080

# 端到端验收（幂等，可重复跑）
python scripts/week2-acceptance.py

# 停依赖
bash scripts/start-redis-dev.sh stop
bash scripts/start-mysql-dev.sh stop

# 复用 compose CLI 做静态校验（无需 daemon）
C:/Users/86181/.workbuddy/docker-tools/docker-compose.exe -f compose.yaml config --quiet
```

---

## 9. 遗留事项与下一步

### 遗留（第 3 周顺手清理）

- `TaskService.getById()` 和 `list()` 已无生产调用方 → 删掉
- `docs/api.md`、`docs/er.md` 仍标注「待补充」
- 提交身份是占位符 `smartdesk <smartdesk@example.com>`（真实邮箱未暴露，但不计入 GitHub 贡献图；
  要改只对**之后**的提交生效，历史 8 个提交已用占位身份写死）

### 第 3 周：Spring AI + RAG 知识库

计划原文的逐日任务（`ja计划.docx` 第 3 周）：

- **Day 15**：Spring AI 入门、ChatClient、模型接入
- **Day 16**：Prompt 工程、结构化输出
- **Day 17**：向量数据库与 Embedding
- **Day 18**：RAG 检索增强生成（切分、召回、重排）
- **Day 19**：Tool Calling（让模型调用我们已有的任务接口）
- **Day 20**：会话记忆、流式响应
- **Day 21**：周复盘

**开工提醒**：新建表（如 `knowledge_doc` / `kb_chunk`）后，**先写越权测试再写业务代码** ——
第 2 周的三层防护约定同样适用，别等到最后补。

### 开工第一步

```bash
cd D:/a应聘/学习/workbuddy/smartdesk
bash scripts/start-mysql-dev.sh start
bash scripts/start-redis-dev.sh start
```

MySQL 数据目录完整保留（`C:\Users\86181\.workbuddy\mysql-dev\data`），下次启动直接恢复；
Redis 刻意不持久化（`save ""` + `appendonly no`），它只存缓存，真实数据源是 MySQL。

---

## 10. 相关文档索引

| 文档 | 内容 |
|---|---|
| `docs/week1-guide.md` | 第 1 周逐日任务 |
| `docs/week2-guide.md` | 第 2 周逐日任务、10 个坑、15 道面试题 |
| `docs/week2-retrospective.md` | 第 2 周复盘：包结构评估、技术决策、答题骨架 |
| `docs/architecture.md` | 一页纸架构说明 |
| `docs/docker-setup.md` | Docker 障碍诊断与三条解决路径 |
| `README.md` | 启动步骤、接口一览、错误码对照、开发规范 |
