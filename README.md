# SmartDesk

> 企业知识库与任务智能助手 · Java 后端 · Spring AI · RAG · Tool Calling · 工程化部署

面向企业内部知识查询和任务管理的 AI 应用。用户可以上传文档、询问业务问题、
查看答案引用来源，并通过自然语言查询或操作任务。

**当前进度：第 1 周（Spring Boot 后端基础）已完成。**

---

## 技术栈

| 模块 | 选型 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 后端 | Spring Boot / Spring MVC | 4.1.2 |
| 数据库 | MySQL + MyBatis-Plus | 8.0 / 3.5.17 |
| 缓存 | Redis | 7 |
| Agent | Spring AI | 2.0.1（第 3 周启用） |
| 工程化 | Maven / Git / JUnit 5 / Mockito / Docker | — |

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

### 3. 配置连接（不要把密码写进代码或提交到仓库）

```bash
# Windows CMD
set MYSQL_PORT=3307
set MYSQL_PASSWORD=smartdesk_root

# Windows PowerShell
$env:MYSQL_PORT="3307"; $env:MYSQL_PASSWORD="smartdesk_root"

# Linux / macOS
export MYSQL_PORT=3307
export MYSQL_PASSWORD=smartdesk_root
```

可选环境变量：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYSQL_HOST` | 127.0.0.1 | 数据库地址 |
| `MYSQL_PORT` | 3306 | 数据库端口（用 compose 时填 3307） |
| `MYSQL_DATABASE` | smartdesk | 库名 |
| `MYSQL_USER` | root | 用户名 |
| `MYSQL_PASSWORD` | 空 | 密码 |
| `SERVER_PORT` | 8080 | 应用端口 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | — | 第 2 周启用 |

### 4. 构建与运行

```bash
# 构建
mvn -B clean package

# 运行（8080 被占用时用 --server.port=18080）
java -jar target/smartdesk-0.1.0-SNAPSHOT.jar --server.port=18080

# 或开发模式
mvn spring-boot:run
```

### 5. 验证

```bash
# 健康检查
curl http://localhost:18080/api/health

# 创建任务
curl -X POST http://localhost:18080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"title":"写周报","description":"整理本周进展","priority":3,"dueDate":"2026-12-31T18:00:00"}'

# 查询列表
curl http://localhost:18080/api/tasks

# 参数校验失败 → 400 + 统一错误结构
curl -i -X POST http://localhost:18080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"title":"","priority":9,"dueDate":"2026-12-31T18:00:00"}'
```

### 6. 测试

```bash
mvn -B test
# 期望：Tests run: 18, Failures: 0, Errors: 0
```

---

## 接口一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| POST | `/api/tasks` | 创建任务 |
| GET | `/api/tasks` | 任务列表（支持 `status` / `page` / `pageSize`） |
| GET | `/api/tasks/{id}` | 查询单个任务 |
| PUT | `/api/tasks/{id}/status` | 更新任务状态 |
| DELETE | `/api/tasks/{id}` | 删除任务 |

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

---

## 文档

- [第一周执行手册](docs/week1-guide.md) —— 逐日任务、验收命令、面试题
- [接口文档](docs/api.md) —— 待补充
- [ER 图](docs/er.md) —— 待补充

---

## 目录结构

```
smartdesk/
├── pom.xml                    # 版本锁定中心
├── compose.yaml               # MySQL + Redis 本地编排
├── docs/week1-guide.md        # 第一周执行手册
└── src/
    ├── main/java/com/smartdesk/
    │   ├── SmartDeskApplication.java
    │   ├── common/            # 统一响应、异常处理、健康检查
    │   ├── task/              # task 领域：entity/dto/mapper/service/controller
    │   └── config/            # MyBatis-Plus 配置
    ├── main/resources/
    │   ├── application.yml
    │   └── db/schema.sql
    └── test/java/com/smartdesk/
```

---

## 开发规范

1. **不提交密钥**。`.env` / `application-local.yml` 已在 `.gitignore` 中。
2. **提交信息格式**：`<type>(dayN): <描述>`，如 `feat(day3): add task status update endpoint`
3. **先自己尝试 20~30 分钟再求助**。AI 生成的代码必须能解释、能运行、能测试。
4. **Controller 保持薄**。业务逻辑下沉到 Service，SQL 留在 Mapper。
