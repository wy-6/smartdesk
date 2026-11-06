# SmartDesk

> 企业知识库与任务智能助手 · Java 后端 · Spring AI · RAG · Tool Calling · 工程化部署

面向企业内部知识查询和任务管理的 AI 应用。用户可以上传文档、询问业务问题、
查看答案引用来源，并通过自然语言查询或操作任务。

**当前进度：第 1 周（Spring Boot 后端基础）、第 2 周（数据库 / Redis / 鉴权 / 工程化）已完成。**

| 周次 | 主题 | 状态 |
|------|------|------|
| 第 1 周 | Spring Boot 后端基础、CRUD、统一响应与异常 | ✅ 已完成 |
| 第 2 周 | 索引与分页、参数校验、Redis 缓存、JWT 鉴权、多环境配置、测试体系 | ✅ 已完成 |
| 第 3 周 | Spring AI + RAG 知识库 | ⏳ 未开始 |

---

## 技术栈

| 模块 | 选型 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 后端 | Spring Boot / Spring MVC | 4.1.2 |
| 安全 | Spring Security + JJWT | 7.1.2 / 0.12.6 |
| 数据库 | MySQL + MyBatis-Plus | 8.0 / 3.5.17 |
| 缓存 | Redis（Spring Data Redis） | 5.0+ / 4.1.2 |
| Agent | Spring AI | 2.0.1（第 3 周启用） |
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
# 期望：Tests run: 50, Failures: 0, Errors: 0
```

第 2 周测试分布：

| 测试类 | 数量 | 类型 |
|--------|------|------|
| `JwtServiceTest` | 7 | 单元测试（签发 / 解析 / 篡改 / 过期 / 异密钥） |
| `UserServiceImplTest` | 7 | 单元测试（BCrypt 哈希 / 用户枚举防护） |
| `TaskServiceImplTest` | 13 | 单元测试（CRUD / 越权防护，Mockito） |
| `TaskControllerTest` | 14 | Web 切片测试（`@WebMvcTest` + MockMvc） |
| `TaskIntegrationTest` | 9 | 集成测试（`@SpringBootTest` + 真实 MySQL） |

端到端验收脚本（覆盖 Day8–Day12 共 36 项断言）：

```bash
python scripts/week2-acceptance.py
```

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
| 404 | `NOT_FOUND` | 资源不存在（含「别人的任务」——不泄露存在性） |
| 500 | `INTERNAL_ERROR` | 未预期异常 |

---

## 文档

- [第一周执行手册](docs/week1-guide.md) —— 逐日任务、验收命令、面试题
- [第二周执行手册](docs/week2-guide.md) —— Day8–Day14 逐日任务、10 个踩坑、必须能回答的面试题
- [第二周复盘](docs/week2-retrospective.md) —— 包结构重构评估、三个关键技术决策、限时面试答题骨架
- [系统架构说明](docs/architecture.md) —— 一页纸架构图、请求链路、缓存与鉴权设计、决策记录
- [接口文档](docs/api.md) —— 待补充
- [ER 图](docs/er.md) —— 待补充

---

## 目录结构

```
smartdesk/
├── pom.xml                    # 版本锁定中心 + local/dev/prod 三个 Maven profile
├── compose.yaml               # MySQL + Redis 本地编排
├── Dockerfile                 # 多阶段构建，非 root 用户 + HEALTHCHECK
├── .env.example               # 环境变量模板（真实 .env 已被 .gitignore 忽略）
├── docs/
│   ├── week1-guide.md         # 第一周执行手册
│   ├── week2-guide.md         # 第二周执行手册
│   └── architecture.md        # 系统架构说明
├── scripts/
│   ├── start-redis-dev.sh     # Redis dev 实例 start/stop/status/keys
│   └── week2-acceptance.py    # 36 项端到端验收
└── src/
    ├── main/java/com/smartdesk/
    │   ├── SmartDeskApplication.java
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
