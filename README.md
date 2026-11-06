# SmartDesk

[![docker-verify](https://github.com/wy-6/smartdesk/actions/workflows/docker-verify.yml/badge.svg)](https://github.com/wy-6/smartdesk/actions/workflows/docker-verify.yml)

> 企业知识库与任务智能助手 · Java 后端 · Spring AI · RAG · Tool Calling · 工程化部署

面向企业内部知识查询和任务管理的 AI 应用。用户可以上传文档、询问业务问题、
查看答案引用来源，并通过自然语言查询或操作任务。

**当前进度：第 1 周（Spring Boot 后端基础）、第 2 周（数据库 / Redis / 鉴权 / 工程化）、
第 3 周（大模型 API / Prompt / 结构化输出 / SSE）已完成。**

| 周次 | 主题 | 状态 |
|------|------|------|
| 第 1 周 | Spring Boot 后端基础、CRUD、统一响应与异常 | ✅ 已完成 |
| 第 2 周 | 索引与分页、参数校验、Redis 缓存、JWT 鉴权、多环境配置、测试体系 | ✅ 已完成 |
| 第 3 周 | Spring AI ChatClient、Prompt 工程、结构化输出、超时重试、SSE 流式、调用可观测 | ✅ 已完成 |
| 第 4 周 | Tool Calling（让模型调用任务接口） | ⏳ 未开始 |
| 第 5 周 | RAG 知识库 | ⏳ 未开始 |

---

## 技术栈

| 模块 | 选型 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 后端 | Spring Boot / Spring MVC | 4.1.2 |
| 安全 | Spring Security + JJWT | 7.1.2 / 0.12.6 |
| 数据库 | MySQL + MyBatis-Plus | 8.0 / 3.5.17 |
| 缓存 | Redis（Spring Data Redis） | 5.0+ / 4.1.2 |
| Agent | Spring AI（只引 `spring-ai-client-chat`） | 2.0.1 |
| 模型接入 | 自实现双 Provider：离线 mock / OpenAI 兼容协议（JDK HttpClient） | — |
| 工程化 | Maven / Git / JUnit 5 / Mockito / Testcontainers / Docker | — |

---

## 快速开始

### 1. 环境要求

- JDK 17+（本机 `JAVA_HOME=D:\JAVA\jdk`）
- Maven 3.9+ 或直接用项目自带的 `./mvnw`
- MySQL 8.0+（本机已装，端口 3306）

### 2. 建库建表

本机 MySQL 8.0.46 已装在 **3306**，但 root 密码未知。
项目额外准备了一个**专用 dev 实例（3307）**，不动你原有的 MySQL：

```bash
# 启动 dev 实例（首次会自动初始化数据目录 + 建库建表）
bash scripts/start-mysql-dev.sh start

# 查状态
bash scripts/start-mysql-dev.sh status
```

dev 实例连接信息：`127.0.0.1:3307`，用户 `root`，密码 `smartdesk_root`，库 `smartdesk`。

如果你之后找回或重置了本机 3306 的密码，也可以直接用它（无需改代码）：

```bash
# 方式 A：用本机 MySQL
mysql -h 127.0.0.1 -P 3306 -uroot -p < src/main/resources/db/schema.sql

# 方式 B：用 Docker Compose 起一套独立实例（端口 3307）
docker compose up -d mysql
```

### 3. 启动 Redis（第 2 周新增，缓存需要）

本机没有装 Redis。项目用了一个 Windows 版 Redis（5.0.14.1），跑在 **6380**
（避开可能已被占用的 6379）：

```bash
bash scripts/start-redis-dev.sh start     # 启动
bash scripts/start-redis-dev.sh status    # 看状态
bash scripts/start-redis-dev.sh keys      # 看缓存 key（排查缓存问题）
bash scripts/start-redis-dev.sh stop      # 停止
```

连接信息：`127.0.0.1:6380`，密码 `smartdesk_root`。

> Redis 二进制来自 Maven Central 的 `com.github.codemonstur:embedded-redis` jar
> （GitHub release 下载被沙箱阻断了）。很多"嵌入式中间件"的 Maven 包
> 都直接打包了各平台二进制，这是个通用技巧。

### 4. 配置连接（不要把密码写进代码或提交到仓库）

```bash
# Windows CMD
set MYSQL_PORT=3307
set MYSQL_PASSWORD=smartdesk_root
set REDIS_PORT=6380
set REDIS_PASSWORD=smartdesk_root

# Windows PowerShell
$env:MYSQL_PORT="3307"; $env:MYSQL_PASSWORD="smartdesk_root"
$env:REDIS_PORT="6380"; $env:REDIS_PASSWORD="smartdesk_root"

# Linux / macOS
export MYSQL_PORT=3307
export MYSQL_PASSWORD=smartdesk_root
export REDIS_PORT=6380
export REDIS_PASSWORD=smartdesk_root
```

> 更推荐用 `.env` 文件（模板见 `.env.example`）：
> ```bash
> cp .env.example .env
> # 编辑 .env 填上你的配置
> ```
> `.env` 已在 `.gitignore` 里，不会被提交。

可选环境变量：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYSQL_HOST` | 127.0.0.1 | 数据库地址 |
| `MYSQL_PORT` | 3306 | 数据库端口（用 dev 实例填 3307） |
| `MYSQL_DATABASE` | smartdesk | 库名 |
| `MYSQL_USER` | root | 用户名 |
| `MYSQL_PASSWORD` | 空 | 密码 |
| `SERVER_PORT` | 8080 | 应用端口 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 127.0.0.1 / 6379 / 空 | 缓存连接（第 2 周启用） |
| `JWT_SECRET` | 本地有默认值 | JWT 签名密钥，**至少 32 字节**，生产必须覆盖 |
| `JWT_EXPIRATION_SECONDS` | 7200 | token 有效期 |
| `SPRING_PROFILES_ACTIVE` | local | 运行期 profile：`local` / `test` / `prod` |

> 生产环境（`prod` profile）的数据库密码、JWT 密钥等**没有默认值**，缺失时启动直接失败 —— 这是刻意的 fail-fast，好过带着默认密钥上线。

### 5. 构建与运行

```bash
# 构建
mvn -B clean package

# 运行（8080 被占用时用 --server.port=18080）
java -jar target/smartdesk-0.1.0-SNAPSHOT.jar --server.port=18080

# 或开发模式
mvn spring-boot:run
```

### 6. 验证

> 第 2 周起，任务接口全部需要 JWT。先注册/登录拿 token，再带上 `Authorization` 头调用。

```bash
# 1) 健康检查（免鉴权）
curl http://localhost:18080/api/health

# 2) 注册（免鉴权，返回 201）
curl -X POST http://localhost:18080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_user","password":"Passw0rd2026","nickname":"演示"}'

# 3) 登录拿 token（免鉴权）
TOKEN=$(curl -s -X POST http://localhost:18080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_user","password":"Passw0rd2026"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

# 4) 创建任务（需鉴权）
curl -X POST http://localhost:18080/api/tasks \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"写周报","description":"整理本周进展","priority":3,"dueDate":"2026-12-31T18:00:00"}'

# 5) 查询列表（支持 status / minPriority / dueFrom / dueTo / page / pageSize）
curl "http://localhost:18080/api/tasks?status=TODO&page=1&pageSize=10" \
  -H "Authorization: Bearer $TOKEN"

# 6) 参数校验失败 → 400 + 统一错误结构
curl -i -X POST http://localhost:18080/api/tasks \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"","priority":9,"dueDate":"2026-12-31T18:00:00"}'

# 7) 不带 token → 401（认证失败）
curl -i http://localhost:18080/api/tasks
```

### 7. 测试

```bash
mvn -B test
# 期望：Tests run: 105, Failures: 0, Errors: 0
```

测试分布：

| 测试类 | 数量 | 类型 |
|--------|------|------|
| `JwtServiceTest` | 7 | 单元测试（签发 / 解析 / 篡改 / 过期 / 异密钥） |
| `UserServiceImplTest` | 7 | 单元测试（BCrypt 哈希 / 用户枚举防护） |
| `TaskServiceImplTest` | 13 | 单元测试（CRUD / 越权防护，Mockito） |
| `TaskControllerTest` | 14 | Web 切片测试（`@WebMvcTest` + MockMvc） |
| `TaskIntegrationTest` | 9 | 集成测试（`@SpringBootTest` + 真实 MySQL） |
| `TaskDraftParserTest` | 14 | 结构化输出解析与校验（Week3） |
| `MockChatModelTest` | 7 | Mock Provider 场景输出 / 故障注入 / 流式一致性 |
| `AiInvokerTest` | 5 | 重试次数、不可重试错误、退避增长 |
| `AiServiceImplTest` | 9 | 四场景 + 修复重试 + 流式回调 |
| `PromptCasesTest` | 1（15 用例） | Prompt 回归评测 |
| `AiControllerTest` | 11 | 401/400、504/422 映射、SSE 事件 |
| `AiIntegrationTest` | 8 | 真实 MySQL、越权隔离、落库、SSE |

端到端验收脚本（均可重复运行）：

```bash
python scripts/week2-acceptance.py   # 38 项：Day8–Day12
python scripts/week3-acceptance.py   # 55 项：Day15–Day20（AI 全链路）
```

### 8. 持续集成（GitHub Actions）

`.github/workflows/docker-verify.yml` 在 GitHub 的 Linux runner 上做**真实的容器化验证**：

1. 跑 88 个单元/切片测试（排除需要本地 MySQL 的 17 个集成测试）
2. `docker build` 构建镜像 —— 验证 Dockerfile 每条指令
3. `docker compose up -d --wait` —— 等 mysql / redis / app 三个容器**全部健康**
4. 冒烟测试：健康检查 → 未登录 401 → 注册 201 → 登录拿 token → 建任务 → 查任务
5. 优雅停机验证：`docker compose stop app` 必须在 10 秒内退出
   （验证 ENTRYPOINT 里 `exec` 让 SIGTERM 直达 JVM）
6. 无论成败都导出容器日志为 artifact

> 为什么放在云端跑：本机 Docker Desktop 起不来（`wsl.exe` 被安全策略封禁），
> 详见 [Docker 环境方案](docs/docker-setup.md)。CI 的绿勾等价于"镜像能构建、编排能起来、接口能跑通"。

---

## 接口一览

**免鉴权（permitAll）**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| POST | `/api/auth/register` | 注册，返回 201 + 用户信息（不含密码） |
| POST | `/api/auth/login` | 登录，返回 JWT + 用户信息 |
| GET | `/actuator/health` | 存活探针（Docker HEALTHCHECK 用） |

**需鉴权（`Authorization: Bearer <token>`）**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/tasks` | 创建任务 |
| GET | `/api/tasks` | 任务列表：`status` / `minPriority` / `dueFrom` / `dueTo` / `page` / `pageSize` |
| GET | `/api/tasks/{id}` | 查询单个任务（非本人 → 404） |
| PUT | `/api/tasks/{id}/status?status=TODO` | 更新任务状态 |
| DELETE | `/api/tasks/{id}` | 删除任务（非本人 → 404） |

> 所有任务接口都以登录态里的 `ownerId` 作为数据边界，请求体不接受 `ownerId`，改 URL 里的 id 也读不到别人的数据。

**AI 接口（Week3，需鉴权）**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ai/chat` | 问答，返回回答 + Token / 耗时 / traceId |
| POST | `/api/ai/chat/stream` | SSE 流式问答（`delta` → `done` / `error` 事件） |
| POST | `/api/ai/summarize` | 摘要（`text` + `maxChars`） |
| POST | `/api/ai/classify` | 分类，返回值限定在 `BUG/FEATURE/BILLING/ACCOUNT/OTHER` |
| POST | `/api/ai/task-draft` | 自然语言 → 任务草稿，**不落库** |
| GET | `/api/ai/calls` | 我的调用记录（分页） |
| GET | `/api/ai/calls/{id}` | 我的某条记录（非本人 → 404） |

AI 能力默认走**离线 mock Provider**，不需要任何 API Key 就能跑通全链路：

```bash
# 默认：mock（规则引擎，可故障注入，测试与演示用）
AI_PROVIDER=mock java -jar target/smartdesk-0.1.0-SNAPSHOT.jar

# 真实模型：任何 OpenAI 兼容服务（OpenAI / DeepSeek / 通义兼容模式 / 本地 vLLM）
AI_PROVIDER=openai OPENAI_API_KEY=sk-xxx \
AI_BASE_URL=https://api.deepseek.com/v1 AI_MODEL=deepseek-chat \
  java -jar target/smartdesk-0.1.0-SNAPSHOT.jar
```

> `AI_PROVIDER=openai` 但没给 Key 时应用会**拒绝启动**（fail fast），
> 不会等第一个用户提问才 500。切换模型不改一行业务代码。

统一响应结构：

```json
{
  "success": true,
  "data": { },
  "errorCode": null,
  "errorMessage": null,
  "timestamp": "2026-11-02T16:00:00"
}
```

错误码约定：

| HTTP | errorCode | 场景 |
|------|-----------|------|
| 400 | `VALIDATION_ERROR` / `BAD_REQUEST` | 参数校验失败、非法枚举值、缺少必填参数 |
| 401 | `UNAUTHORIZED` | 未携带 token、token 过期或签名错误 |
| 403 | `FORBIDDEN` | 已认证但无权限 |
| 404 | `NOT_FOUND` | 资源不存在（含「别人的任务」「别人的调用记录」——不泄露存在性） |
| 500 | `INTERNAL_ERROR` | 未预期异常 |
| 504 | `AI_TIMEOUT` | 模型响应超时（可重试，已自动重试到上限） |
| 429 | `AI_RATE_LIMITED` | 上游限流（可退避重试） |
| 503 | `AI_UPSTREAM_ERROR` | 上游 5xx / 不可达 |
| 422 | `AI_INVALID_OUTPUT` | 模型输出不合法（重试无用，需用户改写输入） |
| 503 | `AI_NOT_CONFIGURED` | 选了 openai 但 Key 无效/未配置 |

---

## 文档

- [**第 1–2 周总结（交接文档）**](docs/week1-2-summary.md) —— 后端基础、验收数据、10 个坑
- [**第 3 周总结（交接文档）**](docs/week3-summary.md) —— AI 能力交付、关键决策、验收数据、下一步 Tool Calling
- [第一周执行手册](docs/week1-guide.md) —— 逐日任务、验收命令、面试题
- [第二周执行手册](docs/week2-guide.md) —— Day8–Day14 逐日任务、10 个踩坑、必须能回答的面试题
- [第二周复盘](docs/week2-retrospective.md) —— 包结构重构评估、三个关键技术决策、限时面试答题骨架
- [第三周执行手册](docs/week3-guide.md) —— Day15–Day21 逐日任务、8 个踩坑、10 道 AI 应用面试题
- [系统架构说明](docs/architecture.md) —— 一页纸架构图、请求链路、缓存与鉴权设计、AI 能力层、决策记录
- [Docker 环境方案](docs/docker-setup.md) —— 本机障碍诊断、云端 CI 验证、已修复的 4 个容器级 bug
- [接口文档](docs/api.md) —— 待补充
- [ER 图](docs/er.md) —— 待补充

---

## 目录结构

```
smartdesk/
├── pom.xml                    # 版本锁定中心 + local/dev/prod 三个 Maven profile
├── compose.yaml               # MySQL + Redis 本地编排
├── Dockerfile                 # 多阶段构建，非 root 用户 + curl + exec + HEALTHCHECK
├── .dockerignore              # 收敛构建上下文（绝不让 .env 进去）
├── .env.example               # 环境变量模板（真实 .env 已被 .gitignore 忽略）
├── .github/workflows/
│   └── docker-verify.yml      # 云端构建镜像 + compose 编排 + 冒烟测试 + 优雅停机验证
├── docs/
│   ├── week1-guide.md         # 第一周执行手册
│   ├── week2-guide.md         # 第二周执行手册
│   ├── week2-retrospective.md # 第二周复盘
│   ├── week3-guide.md         # 第三周执行手册（AI 能力）
│   ├── week1-2-summary.md     # 第 1–2 周交接
│   ├── week3-summary.md       # 第 3 周交接
│   ├── architecture.md        # 系统架构说明
│   └── docker-setup.md        # Docker 环境方案（障碍诊断 + 三条路径）
├── scripts/
│   ├── start-redis-dev.sh     # Redis dev 实例 start/stop/status/keys
│   ├── week2-acceptance.py    # 38 项端到端验收
│   └── week3-acceptance.py    # 55 项 AI 链路验收
└── src/
    ├── main/java/com/smartdesk/
    │   ├── SmartDeskApplication.java
    │   ├── ai/                # Week3：config/model/prompt/provider/service/controller
    │   ├── common/            # 统一响应、全局异常处理、健康检查、业务异常
    │   ├── config/            # Security / Cache / MyBatis-Plus / MetaObjectHandler
    │   ├── security/          # JwtService / JwtAuthenticationFilter / CurrentUser
    │   ├── task/              # task 领域：entity/dto/mapper/service/controller
    │   └── user/              # user 领域：entity/dto/mapper/service/controller
    ├── main/resources/
    │   ├── application.yml          # 公共配置 + 变量占位
    │   ├── application-local.yml    # 本地开发
    │   ├── application-test.yml     # 测试
    │   ├── application-prod.yml     # 部署（敏感项无默认值，fail fast）
    │   └── db/
    │       ├── schema.sql           # 建库建表
    │       └── schema-test.sql      # 测试库建表
    └── test/java/com/smartdesk/
```

---

## 开发规范

1. **不提交密钥**。`.env` / `application-local.yml` 已在 `.gitignore` 中。
2. **提交信息格式**：`<type>(dayN): <描述>`，如 `feat(day3): add task status update endpoint`
3. **先自己尝试 20~30 分钟再求助**。AI 生成的代码必须能解释、能运行、能测试。
4. **Controller 保持薄**。业务逻辑下沉到 Service，SQL 留在 Mapper。
5. **数据边界只能来自登录态**。任何涉及"属于谁"的判断，ownerId 一律取自 JWT，绝不接受来自请求体或 URL 的用户标识。
6. **改数据就清缓存**。写操作统一 `@CacheEvict(allEntries = true)`，不要"更新缓存"——并发写时先删后写更安全。
7. **测试要覆盖失败路径**。每个业务方法至少有一条"应该失败"的断言，不能只测 happy path。
