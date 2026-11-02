# SmartDesk 第一周执行手册

> 目标：从「会写 Java 程序」到「能独立搭出一个分层的后端 REST API」。
> 本周验收线：**不看教程，能自己创建 Controller、调用 Service、把数据写进 MySQL。**

---

## 0. 开始之前：本周环境基线（已替你踩完坑）

这是本机实测通过的环境组合，**请直接使用，不要自行升级或降级**：

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17.0.8 | `JAVA_HOME=D:\JAVA\jdk` |
| Maven | 4.0.0-alpha-10 | 已配置阿里云镜像 |
| Spring Boot | **4.1.2** | 不是 3.x，原因见下 |
| Spring AI | 2.0.1 | 第 3 周用，BOM 已预先引入 |
| MyBatis-Plus | 3.5.17 | 必须用 `boot4` 专用 starter |
| MySQL | 8.0.46 | 本机已装，端口 3306 |

### 本周踩过的 5 个坑（省你 2~3 天）

**坑 1：Spring Initializr 生成的版本号在 Maven 上不存在**

`start.spring.io` 现在给出的 Boot 版本 id 形如 `4.1.2.RELEASE`，
但 Maven Central 上真实的坐标是 `4.1.2`（没有 `.RELEASE` 后缀）。
直接用 Initializr 的 pom 会报：

```
Non-resolvable parent POM ... Could not find artifact
org.springframework.boot:spring-boot-starter-parent:pom:4.1.2.RELEASE
```

→ **本项目 pom.xml 已修正为 `4.1.2`，照抄即可。**

**坑 2：Spring Boot 4 已经不是"3.x 的小升级"**

| 变化 | 影响 |
|------|------|
| `spring-boot-starter-web` → `spring-boot-starter-webmvc` | 依赖名变了 |
| Jackson 2 → **Jackson 3**，包名 `com.fasterxml.jackson` → `tools.jackson` | 序列化细节变了 |
| `@MockBean` 被删除 | 改用 `@MockitoBean` |
| `@SpringBootTest` 不再自动提供 MockMvc | 需 `@AutoConfigureMockMvc` |
| `@WebMvcTest` 所在包变了 | `org.springframework.boot.webmvc.test.autoconfigure` |
| Undertow 被移除（不支持 Servlet 6.1） | 只能用 Tomcat/Jetty |

**坑 3：Jackson 3 的日期配置路径变了**

Boot 3 的写法 `spring.jackson.serialization.write-dates-as-timestamps` 在 Boot 4 会**直接启动失败**：

```
No enum constant tools.jackson.databind.SerializationFeature.write-dates-as-timestamps
```

因为 Jackson 3 把这个开关从 `SerializationFeature` 移到了 `DateTimeFeature`。
正确路径是 `spring.jackson.datatype.datetime.write-dates-as-timestamps`。
→ **application.yml 里已用正确写法。**

**坑 4：兜底异常处理器会把 404 变成 500**

`@ExceptionHandler(Exception.class)` 会吃掉框架自己抛的 `NoResourceFoundException`，
导致访问不存在的 URL 返回 500 而不是 404。
→ **GlobalExceptionHandler 里已单独处理这两个异常类。**

**坑 5：MyBatis-Plus 的 starter 有 Boot 4 专用版**

用 `mybatis-plus-spring-boot3-starter` 在 Boot 4 下会报：

```
No qualifying bean of type 'TaskMapper' available
```

因为 boot3 starter 的自动配置类根本没被加载。
→ **必须用 `mybatis-plus-spring-boot4-starter`（3.5.15+）。**

---

## 1. 每天的固定节奏（7 小时有效学习时间）

| 时间 | 任务 | 当天必须留下的产出 |
|------|------|-------------------|
| 09:00–10:30 | 看官方文档 | 一页知识笔记 |
| 10:45–12:00 | 跟做最小示例 | 一个可运行示例 |
| 13:30–16:30 | 独立编码主项目 | 一个可验收功能 |
| 16:45–17:30 | 测试、修 Bug、提交 Git | 测试结果与提交记录 |
| 19:00–20:00 | 复习面试题、复盘 | 3～5 个问答或一段口头讲解 |

**铁律：先自己尝试 20～30 分钟，再查文档或向 AI 求助。**
AI 生成的代码必须满足三个条件：**能解释、能运行、能测试**。
解释不出来就等于没写。

---

## 2. 逐日任务

### Day 1 · 从 Java 基础进入 Spring Boot

**学习任务**
- 复习：类与接口、集合（List/Map）、异常处理、泛型基本用法
- 认识 HTTP / JSON / REST：
  - HTTP 方法语义：GET 查、POST 增、PUT 改、DELETE 删
  - 状态码：200 成功、400 参数错、401 未登录、403 没权限、404 不存在、500 服务器炸
  - JSON 与 Java 对象的互转（Jackson）
- 装好工具链并确认版本

**动手任务**
1. 建立 `smartdesk` Git 仓库（本目录已建好，直接 `git init`）
2. 写三个类：
   - `TaskStatus`（枚举：TODO / IN_PROGRESS / DONE / CANCELLED）
   - `Task`（实体：id、title、description、status、priority、ownerId、dueDate、createdAt、updatedAt）
   - `TaskService`（接口：create / getById / list / updateStatus / delete / count）
3. 提交第一次代码

**本项目对应文件（已写好，可对照学习后自己重写一遍）**
```
src/main/java/com/smartdesk/task/entity/TaskStatus.java
src/main/java/com/smartdesk/task/entity/Task.java
src/main/java/com/smartdesk/task/service/TaskService.java
```

**当日验收**
```bash
cd D:/a应聘/学习/workbuddy/smartdesk
git init && git add -A && git commit -m "chore: init smartdesk project (Day1)"
mvn -B -DskipTests compile     # 必须 BUILD SUCCESS
```

**必须能回答（晚上复盘用）**
1. `Task` 和 `TaskStatus` 为什么要分成两个类？枚举相比字符串常量好在哪？
2. 为什么 Service 要定义成接口而不是直接写实现类？
3. `TaskService` 接口里为什么用 `TaskStatus` 而不是 `String`？

---

### Day 2 · Spring Boot 启动机制与依赖注入

**学习任务**
- 项目结构：`src/main/java` / `src/main/resources` / `src/test/java`
- `@SpringBootApplication` 展开是什么
  - `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`
- **自动配置（AutoConfiguration）**：Spring Boot 根据 classpath 上有哪些 jar，
  自动决定启动什么组件。启动日志里 `ConditionEvaluationReport` 会告诉你"为什么装配/为什么不装配"
- **IoC 与依赖注入**：容器创建 Bean 并注入依赖
- **Bean 的生命周期与作用域**（singleton 默认 / prototype / request）
- **构造器注入 vs 字段注入**

**动手任务**
1. 启动应用，确认能跑起来
2. 实现 `GET /api/health`
3. 给 `TaskService` 写实现类 `TaskServiceImpl`，用**构造器注入**
4. 观察启动日志，找到 Tomcat 端口、context path、启动耗时

**本项目对应文件**
```
src/main/java/com/smartdesk/SmartDeskApplication.java
src/main/java/com/smartdesk/common/HealthController.java
src/main/java/com/smartdesk/task/service/TaskServiceImpl.java
```

**当日验收**
```bash
mvn -B -DskipTests package
java -jar target/smartdesk-0.1.0-SNAPSHOT.jar --server.port=18080
# 另开一个终端
curl http://localhost:18080/api/health
# 期望：{"data":{"status":"UP",...},"success":true,...}
```

> ⚠️ **端口提示**：本机 8080 可能被占用，统一用 `--server.port=18080`。
> 也可以 `set SERVER_PORT=18080`（Windows）后直接 `mvn spring-boot:run`。

**必须能回答**
1. `@Component` / `@Service` / `@Repository` / `@Controller` 有什么区别？为什么 Service 用 `@Service`？
2. 构造器注入相比字段注入有什么优势？（提示：能解释的点至少有 4 个）
3. `SpringApplication.run()` 到底做了什么？容器是谁创建的？
4. 自动配置的"自动"体现在哪？它是怎么知道要装配 DataSource 的？

---

### Day 3 · Spring MVC 与 REST 接口

**学习任务**
- `@RestController` = `@Controller` + `@ResponseBody`
- 映射注解：`@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping`
- **四种参数来源**（本项目全部用到了）：
  | 注解 | 来源 | 例子 |
  |------|------|------|
  | `@PathVariable` | 路径 | `/api/tasks/{id}` |
  | `@RequestParam` | 查询串 | `?status=TODO&page=1` |
  | `@RequestBody` | 请求体 | JSON |
  | `@RequestHeader` | 请求头 | `Authorization` |
- 请求处理流程：请求 → DispatcherServlet → HandlerMapping → HandlerAdapter → Controller → 返回值 → HttpMessageConverter → JSON
- `ResponseEntity` vs 直接返回对象
- 用 Postman / curl 验证正常请求与非法请求

**动手任务**
1. 实现 `TaskController` 的创建、查询、状态更新接口
2. 统一响应结构 `ApiResponse<T>`
3. 用 curl 测正常请求 + 非法请求（空标题、非法状态值、错误 JSON）

**本项目对应文件**
```
src/main/java/com/smartdesk/task/controller/TaskController.java
src/main/java/com/smartdesk/task/dto/TaskResponse.java
src/main/java/com/smartdesk/common/ApiResponse.java
```

**当日验收命令**
```bash
# 创建（正常）
curl -X POST http://localhost:18080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"title":"写周报","description":"整理进展","priority":3,"dueDate":"2026-12-31T18:00:00"}'

# 创建（非法：标题为空 + 优先级越界）→ 期望 400
curl -i -X POST http://localhost:18080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"title":"","priority":9,"dueDate":"2026-12-31T18:00:00"}'

# 查询列表
curl http://localhost:18080/api/tasks

# 非法状态值 → 期望 400 INVALID_STATUS
curl -i "http://localhost:18080/api/tasks?status=NOT_A_STATUS"

# 不存在的接口 → 期望 404（不是 500！）
curl -i http://localhost:18080/api/nonexistent
```

**必须能回答**
1. `@RequestBody` 的对象是怎么从 JSON 变出来的？谁负责？
2. 为什么 `TaskController` 里不直接 new `TaskServiceImpl`？
3. HTTP 的 PUT 和 POST 有什么区别？为什么更新状态用 PUT？
4. 为什么"查询不存在的任务"返回 404 而不是 200 + null？

---

### Day 4 · 分层、DTO、参数校验与统一异常处理

**学习任务**
- **三层职责划分**（这是面试高频）：
  | 层 | 职责 | 不该做什么 |
  |----|------|-----------|
  | Controller | 接收请求、参数校验、调用 Service、包装响应 | 不写业务判断、不碰 SQL |
  | Service | 业务规则、事务边界、编排数据操作 | 不知道 HTTP 的存在 |
  | Mapper | 与数据库交互 | 不写业务逻辑 |
- **为什么用 DTO 而不是直接用 Entity**
  1. 接口契约与存储结构解耦（加字段不会意外暴露）
  2. 入参可控（防止"前端传什么存什么"）
  3. 校验语义分离（Entity 约束 = 存储约束，DTO 约束 = 接口约束）
- **参数校验**：`@Valid` / `@NotBlank` / `@NotNull` / `@Size` / `@Min` / `@Max`
- **统一异常处理**：`@RestControllerAdvice` + `@ExceptionHandler`
  - 为什么要统一：不统一的话每个 Controller 都要 try-catch，且异常栈会泄漏内部信息
  - 哪些异常该单独处理、哪些走兜底
- **HTTP 状态码语义**：400 / 404 / 500 各自对应什么

**动手任务**
1. 写 `TaskCreateRequest` DTO，带校验注解
2. 写 `GlobalExceptionHandler`，覆盖：
   - `MethodArgumentNotValidException` → 400 VALIDATION_ERROR
   - `HttpMessageNotReadableException` → 400 MALFORMED_REQUEST
   - `BusinessException` → 400
   - `ResourceNotFoundException` → 404
   - `NoResourceFoundException` / `NoHandlerFoundException` → 404（**别让兜底把 404 变成 500**）
   - `Exception` → 500 INTERNAL_ERROR（真实堆栈只进日志）
3. 跑通上面 Day 3 的全部 curl 验收命令

**本项目对应文件**
```
src/main/java/com/smartdesk/task/dto/TaskCreateRequest.java
src/main/java/com/smartdesk/common/GlobalExceptionHandler.java
src/main/java/com/smartdesk/common/BusinessException.java
src/main/java/com/smartdesk/common/ResourceNotFoundException.java
```

**必须能回答**
1. 为什么要引入 DTO？举一个"实体直接当入参"会出问题的具体例子。
2. 兜底的 `Exception.class` 处理器为什么必须返回通用文案而不是 `ex.getMessage()`？
3. `@Transactional` 应该标在 Controller 还是 Service？为什么？
4. 什么时候该抛业务异常、什么时候该抛运行时异常？举两个例子。

---

### Day 5 · MySQL 与 MyBatis-Plus

**学习任务**
- MySQL 基础：库/表/行/列、主键、常用类型
- **索引**：B+ 树直觉、聚簇索引与二级索引、最左前缀、为什么索引不是越多越好
- 常用 SQL：`SELECT/INSERT/UPDATE/DELETE`、`WHERE`、`ORDER BY`、`LIMIT`、`COUNT`
- **事务**：ACID、隔离级别、Spring 的 `@Transactional` 传播与回滚
  - 常见坑：同类内部方法调用不走代理 → 事务不生效；异常被 catch 住 → 不回滚
- **MyBatis-Plus**：核心价值是"单表 CRUD 零 SQL"
  - `BaseMapper<T>` 白拿全套单表方法
  - `LambdaQueryWrapper` 类型安全（字段名写错编译期就报错）
  - `Page<T>` + `PaginationInnerInterceptor` 分页
  - `@TableName` / `@TableId` / `@TableField` / `@EnumValue`

**动手任务**
1. 建库建表（用 `src/main/resources/db/schema.sql`）
2. 接入 MyBatis-Plus，完成增删改查
3. 验证：**重启应用后数据仍在**（这是 Day 5 的核心验收点）

**本项目对应文件**
```
src/main/resources/db/schema.sql
src/main/java/com/smartdesk/task/mapper/TaskMapper.java
src/main/java/com/smartdesk/config/MybatisPlusConfig.java
src/main/resources/application.yml
```

**当日验收**
```bash
# 建库建表
mysql -uroot -p < src/main/resources/db/schema.sql

# 启动应用
set MYSQL_PASSWORD=你的密码
java -jar target/smartdesk-0.1.0-SNAPSHOT.jar --server.port=18080

# 造数据
curl -X POST http://localhost:18080/api/tasks -H "Content-Type: application/json" \
  -d '{"title":"写周报","priority":3,"dueDate":"2026-12-31T18:00:00"}'

# 查库确认真的落库了
mysql -uroot -p -e "SELECT id,title,status,priority FROM smartdesk.task;"

# 重启应用，再查接口 → 数据还在
```

**必须能回答**
1. 为什么 `task` 表要建 `idx_owner_id` 和 `idx_owner_status` 两个索引？分别对应什么查询？
2. `EXPLAIN` 看什么字段能判断索引有没有生效？
3. `@Transactional` 标在 Service 上，为什么同类内部方法调用会导致事务失效？
4. MyBatis-Plus 的 `LambdaQueryWrapper` 相比字符串拼接条件，优势是什么？
5. 什么是逻辑删除？知识库场景为什么更适合逻辑删除？

---

### Day 6 · Maven、Git 分支、JUnit、日志与调试

**学习任务**
- **Maven 依赖管理**：`dependencyManagement` vs `dependencies` 的区别（BOM 是什么）
- **Git 分支策略**：`main` 保护 + `develop` + `feature/xxx`
- **JUnit 5**：`@Test` / `@DisplayName` / `@BeforeEach` / `assertThat`
- **Mockito**：`@Mock` / `@InjectMocks` / `when().thenReturn()` / `verify()`
  - Mockito 4+ 严格模式：桩数据没用上会报错（这是好事，逼你写有意义的测试）
- **分层测试策略**：
  | 测试类型 | 注解 | 是否启动容器 | 是否连数据库 | 速度 |
  |---------|------|------------|------------|------|
  | 单元测试 | 无 + Mockito | 否 | 否 | 毫秒 |
  | Web 层测试 | `@WebMvcTest` | 是（仅 Web 切片） | 否 | 秒 |
  | 集成测试 | `@SpringBootTest` | 是 | 是 | 十秒级 |
- **日志**：为什么用 SLF4J 而不用 `System.out.println`；日志级别怎么选

**动手任务**
1. 给 `TaskService` 写 **≥5 个单元测试**（本项目已提供 9 个，含边界场景）
2. 给 `TaskController` 写 Web 层测试
3. 整理接口文档、ER 图、启动说明

**本项目对应文件**
```
src/test/java/com/smartdesk/task/service/TaskServiceImplTest.java      # 9 个单元测试
src/test/java/com/smartdesk/task/controller/TaskControllerTest.java   # 9 个 Web 测试
```

**当日验收**
```bash
mvn -B test
# 期望：Tests run: 18, Failures: 0, Errors: 0
```

**必须能回答**
1. 单元测试、Web 层测试、集成测试分别测什么？各自的成本？
2. 为什么 Controller 用 `@WebMvcTest` 而不是 `@SpringBootTest`？
3. Mockito 的 `verify()` 断言的是什么？如果不写 `verify` 会漏掉什么问题？
4. 为什么不用 `System.out.println` 调试？生产环境日志怎么做分级？

---

### Day 7 · 轻复盘 + 限时练习（不学新框架）

**学习任务**
- 补漏：本周没搞懂的点
- 不学新东西，不加新依赖

**动手任务**
1. **限时 60 分钟**：从空项目重做一个简单查询接口（不看本手册）
2. 录 2 分钟演示视频
3. 写下 **10 个你不会的问题**（这 10 个就是第 2 周的学习清单）

**当日验收**
- 能从零建一个 Spring Boot 项目 + 一个 Controller + 一个 Service + 连接数据库
- 能口头讲清：请求从进来到返回经历了什么

**本周总验收（对应计划里的"第 1 周验收门槛"）**

> 如果你还不能不看教程独立创建 Controller、调用 Service 并将数据写入 MySQL，
> 就不要进入 Agent 开发。用 1～2 天补齐这一环节，比在后面返工便宜得多。

```bash
# 全绿
mvn -B clean test
# 应用能起
mvn -B -DskipTests package && java -jar target/smartdesk-0.1.0-SNAPSHOT.jar --server.port=18080
# 接口全通
curl http://localhost:18080/api/health
curl http://localhost:18080/api/tasks
```

---

## 3. 本周参考代码地图

```
smartdesk/
├── pom.xml                                # 版本锁定：Boot 4.1.2 / Spring AI 2.0.1 / MP 3.5.17
├── docs/week1-guide.md                    # 本文件
└── src/
    ├── main/java/com/smartdesk/
    │   ├── SmartDeskApplication.java      # Day2 入口，@SpringBootApplication
    │   ├── common/
    │   │   ├── ApiResponse.java           # Day4 统一响应体
    │   │   ├── GlobalExceptionHandler.java# Day4 统一异常处理
    │   │   ├── BusinessException.java     # Day4 业务异常
    │   │   ├── ResourceNotFoundException.java
    │   │   └── HealthController.java      # Day2 健康检查
    │   ├── task/
    │   │   ├── entity/
    │   │   │   ├── Task.java              # Day1 实体
    │   │   │   └── TaskStatus.java        # Day1 枚举
    │   │   ├── dto/
    │   │   │   ├── TaskCreateRequest.java # Day4 入参 + 校验
    │   │   │   └── TaskResponse.java      # Day3 出参
    │   │   ├── mapper/TaskMapper.java     # Day5 MyBatis-Plus
    │   │   ├── service/
    │   │   │   ├── TaskService.java       # Day1 接口
    │   │   │   └── TaskServiceImpl.java   # Day2 实现（构造器注入）
    │   │   └── controller/TaskController.java # Day3 REST 接口
    │   └── config/MybatisPlusConfig.java  # Day5 分页插件
    ├── main/resources/
    │   ├── application.yml                # 配置（全部走环境变量，不硬编码密钥）
    │   └── db/schema.sql                  # Day5 建表脚本
    └── test/java/com/smartdesk/
        ├── task/service/TaskServiceImplTest.java    # Day6 单元测试 ×9
        └── task/controller/TaskControllerTest.java  # Day6 Web 测试 ×9
```

---

## 4. 本周面试题速记（每天 19:00–20:00 用）

1. Spring Boot 自动配置的原理是什么？怎么知道某个自动配置为什么没生效？
2. IoC 和 DI 的区别是什么？Spring 是怎么实现 DI 的？
3. 构造器注入相比 setter 注入/字段注入的优势？
4. `@SpringBootApplication` 包含哪三个注解？
5. Spring MVC 处理一个请求的完整流程？
6. `@RequestBody` / `@RequestParam` / `@PathVariable` / `@RequestHeader` 的区别？
7. 为什么 Controller 不该直接操作 Mapper？
8. DTO 和 Entity 为什么要分开？
9. `@RestControllerAdvice` 的作用？兜底 handler 有什么风险？
10. 什么情况下该返回 400，什么情况下 404，什么情况下 500？
11. 索引的原理是什么？什么是最左前缀？
12. 事务的 ACID 是什么？`@Transactional` 什么时候会失效？
13. MyBatis-Plus 相比原生 MyBatis 优势在哪？什么场景下还要写 XML？
14. 单元测试 / 集成测试的区别？Mockito 在测什么？
15. `dependencyManagement` 和 `dependencies` 的区别？BOM 是什么作用？

---

## 5. 每天提交规范

```bash
git checkout -b feature/day1-task-domain
git add -A
git commit -m "feat(day1): add Task entity, TaskStatus enum and TaskService interface"
git checkout main && git merge --no-ff feature/day1-task-domain
```

提交信息格式：`<type>(dayN): <描述>`
type 取值：`feat` / `fix` / `refactor` / `test` / `docs` / `chore`
