# SmartDesk 第 2 周执行手册

> **本周主题：数据库、Redis、鉴权与后端工程能力**
>
> 目标：让任务系统成为**可以复用的业务后端**，为第 3-4 周的 Agent 开发提供真实、受控的工具。
>
> **本周验收门槛（计划原文）**：
> - CRUD、分页、筛选和用户权限可用
> - 关键业务测试通过
> - 数据库和 Redis 能按配置启动
> - 能解释索引、事务、缓存失效和认证授权的区别
>
> 如果这四条有任意一条做不到，**不要进入第 3 周**。

---

## 0. 本周实测环境（已跑通，不用你再踩坑）

| 组件 | 版本 | 位置 | 说明 |
|------|------|------|------|
| JDK | 17.0.8 | `D:\JAVA\jdk` | |
| Maven | 4.0.0-alpha-10 | `D:\JAVA\maven` | 阿里云镜像可用 |
| Spring Boot | **4.1.2** | pom.xml | 不是 3.x |
| Spring Security | **7.x** | 由 Boot 托管 | Boot 4 对应 Security 7 |
| JJWT | 0.12.6 | pom.xml | API 与 0.11 有 breaking change |
| MyBatis-Plus | 3.5.17 | `mybatis-plus-spring-boot4-starter` | 必须 boot4 版 |
| Spring Data Redis | 4.1.2 | 由 Boot 托管 | **API 与 3.x 差别很大** |
| MySQL | 8.0.46 | 本机 3306 | root 密码未知，**别动它** |
| MySQL dev | 8.0.46 | **3307** | `C:\Users\86181\.workbuddy\mysql-dev` |
| Redis | 5.0.14.1 | **6380** | `C:\Users\86181\.workbuddy\redis-win` |

### 启动依赖（每次开工第一件事）

```bash
cd D:/a应聘/学习/workbuddy/smartdesk

# 启动 MySQL dev 实例
bash scripts/start-mysql-dev.sh start

# 启动 Redis dev 实例
bash scripts/start-redis-dev.sh start

# 查状态 / 停掉
bash scripts/start-redis-dev.sh status
bash scripts/start-redis-dev.sh stop
```

> 两个脚本都支持 `start` / `stop` / `status`；Redis 脚本另外有 `keys` 子命令，
> 可以直接列出缓存里的 key，用来观察 `@Cacheable` 到底有没有生效。
>
> **Redis 为什么在 6380 不是 6379？** 本机可能已有别的 Redis 占用 6379，避开冲突。
>
> **Redis 二进制从哪来？** GitHub 的 release 下载被沙箱阻断了，最终从 Maven Central 的
> `com.github.codemonstur:embedded-redis:1.4.3` jar 里提取的
> `redis-server-5.0.14.1-windows-amd64.exe`。这是个通用技巧 ——
> 很多"嵌入式数据库/中间件"的 Maven 包里都直接打包了各平台的二进制。

---

## 1. 本周踩过的 10 个坑（Boot 4 / Spring Data Redis 4 的 breaking change）

### 坑 1：Spring Data Redis 4 的序列化器换名字了

```java
// ❌ 老写法（Jackson 2 时代）
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

// ✅ Boot 4 / Spring Data Redis 4 的写法
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
```

名字里的 "Jackson2" 是历史命名残留。现在 Spring Data Redis 4 全面迁移到 Jackson 3，
新类是 `GenericJacksonJsonRedisSerializer`，内部用 `tools.jackson.databind.ObjectMapper`。

**规律**：Spring Data Redis 4 里凡是涉及 Jackson 的类，都去掉了 "2"。

### 坑 2：Jackson 3 的 ObjectMapper 在 `tools.jackson` 包

```java
// ❌
import com.fasterxml.jackson.databind.ObjectMapper;
// ✅
import tools.jackson.databind.ObjectMapper;
```

**但注解不用改**：`com.fasterxml.jackson.annotation.JsonIgnore` 等注解
仍然在 `com.fasterxml.jackson.annotation` 包下。Jackson 3 只搬了
databind / core，annotations 保持在原位。

> 这是第 1 周坑的延续 —— 记住一句话：
> **Jackson 3：注解还在老地方，databind 和 core 搬到了 tools.jackson。**

### 坑 3：`RedisCacheWriter` 不再是"接口 + builder()"

```java
// ❌ 老写法
RedisCacheWriter.builder(connectionFactory).ttl(ttlFunction).build();

// ✅ 新写法：静态工厂方法
RedisCacheWriter cacheWriter = RedisCacheWriter.lockingRedisCacheWriter(
        connectionFactory,
        Duration.ofSeconds(5),   // 锁等待超时
        ttlFunction,             // 每个 key 的 TTL
        BatchStrategies.keys()); // 注意是方法不是字段
```

`BatchStrategies` 里也是 `keys()` / `scan(int)` 两个**静态方法**，
没有 `DEFAULT` 常量。

### 坑 4：`BatchStrategy` 在 `cache` 包，不在 `serializer` 包

```java
import org.springframework.data.redis.cache.BatchStrategies;  // ✅
import org.springframework.data.redis.serializer.BatchStrategy; // ❌
```

### 坑 5：`@Cacheable` 的 `unless` 表达式用 `#result`

```java
@Cacheable(cacheNames = "taskDetail", key = "#id", unless = "#result == null")
```

`#result` 是方法返回值，`#p0`/`#a0` 是参数。写错 SpEL 表达式会在**运行时**才报错，
编译期发现不了。

### 坑 6：`@ExceptionHandler` 的 import 在 Boot 4 挪了位置

```java
// 新增的三个 handler，import 容易写错：
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
```

> `HandlerMethodValidationException` 是 Spring 6.1+ 的方法级参数校验异常，
> 老的 `ConstraintViolationException` 已经不适用了。

### 坑 7：Mapper 方法只声明不实现 → BindingException

```java
public interface AppUserMapper extends BaseMapper<AppUser> {
    AppUser selectByUsername(String username);   // ← 只声明，没实现！
}
```

**启动不报错，调用时才炸**：

```
BindingException: Invalid bound statement (not found):
  com.smartdesk.user.mapper.AppUserMapper.selectByUsername
```

这是"静默失效"里最难查的一种 —— 编译通过、启动通过、单元测试也过
（因为 Mapper 被 mock 了），只有真连数据库才暴露。

修法：加 `@Select` 注解补上 SQL。

```java
@Select("SELECT id, username, password_hash, nickname, enabled, created_at, updated_at "
      + "FROM app_user WHERE username = #{username}")
AppUser selectByUsername(String username);
```

> **教训：Mapper 接口的每个自定义方法，都要有对应的实现（注解或 XML）。**

### 坑 8：缺 MetaObjectHandler → created_at 插入 null

`@TableField(fill = FieldFill.INSERT)` 只是**声明**，真正干活需要一个
`MetaObjectHandler` Bean。项目里第 1 周没写这个 Bean。

- `task` 表没报错 —— 因为建表时 `created_at DEFAULT CURRENT_TIMESTAMP`，数据库兜住了
- `app_user` 表报错了 —— 因为 MyBatis-Plus 生成的 INSERT **显式包含 created_at 列并传 null**，
  显式传 null 会覆盖数据库默认值：

```
SQLIntegrityConstraintViolationException: Column 'created_at' cannot be null
```

修法：新增 `config/MybatisMetaObjectHandler.java`：

```java
@Component
public class MybatisMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
```

> **面试可讲**：MyBatis-Plus 的自动填充是"声明 + 实现"两步，只做声明不会有任何效果
> —— 不报错，但也不生效。而数据库默认值和 ORM 自动填充是两套机制，
> ORM 显式传 null 时会覆盖 DB 默认值。

### 坑 9：`@WebMvcTest` 不加载普通 `@Configuration`

```java
@WebMvcTest(controllers = { TaskController.class, HealthController.class })
@Import(SecurityConfig.class)     // ← 必须显式 import
class TaskControllerTest { }
```

`@WebMvcTest` 的组件扫描范围很窄，普通的 `@Configuration` 类（比如 `SecurityConfig`）
默认**不会**被扫到。不 import 的话：

- Spring Security 的默认配置生效（表单登录 + httpBasic），不是我们设计的 JWT 方案
- 我们精心写的 `authenticationEntryPoint`（统一 JSON 401 响应）根本没生效

另外切片里还缺 `JwtService`（它是普通 `@Component`），必须补：

```java
@MockitoBean
private JwtService jwtService;
```

> **规律**：切片测试报 "No qualifying bean" 时，先想"这个 Bean 属于哪个切片"，
> 再决定是 mock 它还是把它 import 进切片。

### 坑 10：Spring Data Redis 4 的 defaultTyping 默认关闭

```java
GenericJacksonJsonRedisSerializer.builder()
        .enableDefaultTyping(BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType("com.smartdesk.")   // 安全护栏：只允许自己项目的类
                .build())
        .build();
```

**Jackson 2 时代 `defaultTyping` 默认是 true，Jackson 3 / Spring Data Redis 4 改成 false。**

不开的后果 —— 缓存能写进去，但读出来炸：

```
ClassCastException: class java.util.LinkedHashMap cannot be cast to class com.smartdesk.task.entity.Task
```

因为没有类型信息，反序列化回来是 `LinkedHashMap`，调 `getTitle()` 直接失败。

**为什么默认关闭？** 多态类型信息是反序列化漏洞的常见来源 ——
攻击者若能往缓存写恶意 JSON，可能触发任意类实例化。Jackson 3 默认关掉是更安全的默认。
所以开启时**必须配 `typeValidator`** 限定可反序列化的包范围。

> **这个坑只有真正跑一遍缓存才暴露** —— 单元测试全绿，集成测试全绿，
> 只有"读两次同一份缓存"的端到端验收才会炸。

---

## 2. Day 8：MySQL 索引、EXPLAIN、分页、事务与隔离级别

### 学习任务

1. **索引的原理**：B+ 树为什么适合做索引
   - 叶子节点串成链表 → 范围查询快
   - 非叶子节点只存键 → 树更矮 → 磁盘 IO 次数少
   - 关键结论：**为什么数据库索引通常是 B+ 树而不是红黑树**：
     B+ 树是多路平衡树（一个节点能挂几百个分支），所以树高极低（3-4 层），
     千万级数据也只需要 3-4 次 IO；红黑树是二叉的，树高会到几十层。

2. **最左前缀原则**：`(owner_id, status)` 能覆盖哪些查询
   - `WHERE owner_id = 1` ✅（最左前缀）
   - `WHERE owner_id = 1 AND status = 'TODO'` ✅
   - `WHERE status = 'TODO'` ❌（跳过了最左列，索引失效）

3. **EXPLAIN 怎么看**
   - `type`：从好到差 `const > ref > range > index > ALL`
   - `key`：实际使用的索引
   - `rows`：预估扫描行数
   - `Extra`：`Using filesort` / `Using temporary` 是危险信号

4. **事务与隔离级别**（面试高频）

   | 隔离级别 | 脏读 | 不可重复读 | 幻读 |
   |---------|------|-----------|------|
   | Read Uncommitted | ✓ | ✓ | ✓ |
   | Read Committed | ✗ | ✓ | ✓ |
   | Repeatable Read | ✗ | ✗ | ✓ |
   | Serializable | ✗ | ✗ | ✗ |

   - **MySQL 默认是 Repeatable Read**（比标准更严，因为 MVCC + 间隙锁）
   - 面试标准答案：**RR 下的幻读靠间隙锁解决**；快照读（普通 SELECT）
     看不到新插入的行，当前读（SELECT ... FOR UPDATE / UPDATE / DELETE）
     会看到并加间隙锁阻塞。
   - 三个并发问题：脏读（读到未提交）、不可重复读（同一事务两次读同一行值变了）、
     幻读（同一事务两次读同一范围，行数变了）

### 编码任务

**已完成，代码位置**：

| 文件 | 内容 |
|------|------|
| `src/main/resources/db/schema.sql` | 3 个复合索引 + app_user 表 + 外键 |
| `src/main/java/com/smartdesk/task/dto/TaskQuery.java` | 查询条件对象 |
| `TaskServiceImpl.query()` | 多条件动态查询 |

### 当日验收

```bash
# 1) 确认索引存在
mysql -h 127.0.0.1 -P 3307 -uroot -p -e "SHOW INDEX FROM smartdesk.task;"

# 2) 造数据
mysql -h 127.0.0.1 -P 3307 -uroot -p smartdesk -e "
INSERT INTO app_user (username, password_hash, nickname) VALUES
  ('alice', '\$2a\$10\$abcdefghijklmnopqrstuv', '爱丽丝'),
  ('bob',   '\$2a\$10\$abcdefghijklmnopqrstuv', '鲍勃');
INSERT INTO task (title, status, priority, owner_id, due_date) VALUES
  ('写周报','TODO',5,1,'2026-12-31 18:00:00'),
  ('联调测试','IN_PROGRESS',3,1,'2026-12-20 18:00:00'),
  ('部署文档','TODO',4,2,'2026-12-25 18:00:00');"

# 3) EXPLAIN 验证走索引（期望 key=idx_owner_status, type=ref）
mysql -h 127.0.0.1 -P 3307 -uroot -p smartdesk -e "
EXPLAIN SELECT id, title FROM task WHERE owner_id = 1 AND status = 'TODO';"

# 4) 对比：没有索引前缀的查询（期望 key=NULL, type=ALL）
mysql -h 127.0.0.1 -P 3307 -uroot -p smartdesk -e "
EXPLAIN SELECT id, title FROM task WHERE status = 'TODO';"
```

**必须能回答**：
- 为什么 `(owner_id, status)` 能加速 `WHERE owner_id = 1`？
- 索引越多越好吗？为什么？
- `type = ALL` 意味着什么？`Using filesort` 意味着什么？
- MySQL 默认隔离级别是什么？RR 下幻读怎么解决？
- 什么情况下索引会失效？（答出 3 个以上：函数、隐式类型转换、LIKE '%x'、OR 连接非索引列）

---

## 3. Day 9：DTO、VO、参数校验、全局异常、统一响应

### 学习任务

1. **DTO vs Entity vs VO**

   | | Entity | DTO | VO |
   |---|--------|-----|-----|
   | 面向 | 数据库 | 前端入参 | 前端出参 |
   | 字段 | 技术字段 | 接口需要 | 接口需要 |
   | 能否含敏感字段 | 可能含 | 可能含 | **绝不能含** |
   | 例子 | `AppUser`（含 passwordHash） | `LoginRequest` | `UserResponse` |

2. **校验注解清单**（本项目实际用到的）

   | 注解 | 作用 |
   |------|------|
   | `@NotBlank` | 不能为 null 且不能全空白 |
   | `@NotNull` | 不能为 null |
   | `@Size(min,max)` | 长度范围 |
   | `@Pattern(regexp)` | 正则匹配 |
   | `@Min` / `@Max` | 数值范围 |
   | `@Email` | 邮箱格式 |

3. **异常分类与状态码**

   | 异常 | 状态码 | errorCode |
   |------|--------|-----------|
   | `MethodArgumentNotValidException` | 400 | VALIDATION_ERROR |
   | `HttpMessageNotReadableException` | 400 | MALFORMED_REQUEST |
   | `MethodArgumentTypeMismatchException` | 400 | INVALID_PARAMETER |
   | `MissingServletRequestParameterException` | 400 | MISSING_PARAMETER |
   | `HandlerMethodValidationException` | 400 | VALIDATION_ERROR |
   | `BusinessException` | 400 | 业务自定义 |
   | `ResourceNotFoundException` | 404 | NOT_FOUND |
   | `NoResourceFoundException` | 404 | NOT_FOUND |
   | 未认证 | 401 | UNAUTHORIZED |
   | 权限不足 | 403 | FORBIDDEN |
   | `Exception`（兜底） | 500 | INTERNAL_ERROR |

### 编码任务

**已完成**：

- `GlobalExceptionHandler` 新增 3 个 handler（类型不匹配 / 缺参数 / 方法级校验）
- `UserResponse`（VO）—— 不含 passwordHash
- `LoginRequest` / `RegisterRequest` —— 完整校验注解
- `AppUser.passwordHash` 加 `@JsonIgnore` —— 双保险

### 当日验收

```bash
# 1) 校验失败
curl -X POST http://localhost:18080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"a","password":"123"}'
# 期望：400，errorCode=VALIDATION_ERROR，消息说明哪几个字段有问题

# 2) 密码哈希绝不返回
curl -X POST http://localhost:18080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"test123456"}'
# 期望：201，响应体里没有 passwordHash 字段

# 3) 类型不匹配
curl http://localhost:18080/api/tasks/abc
# 期望：400 INVALID_PARAMETER（不是 500！）

# 4) 非法枚举
curl "http://localhost:18080/api/tasks?status=NOT_A_STATUS"
# 期望：400 INVALID_STATUS
```

**必须能回答**：
- 为什么实体不能直接当响应体返回？
- `@Valid` 和 `@Validated` 的区别？
- 为什么兜底 handler 要单独处理 404？（第 1 周踩过）
- 401 和 403 的区别？

---

## 4. Day 10：Redis 数据类型、过期时间、缓存命中与失效

### 学习任务

1. **Redis 常用数据结构**

   | 类型 | 用途 | 本项目用在哪 |
   |------|------|------------|
   | String | 缓存对象、计数器 | 任务详情 JSON |
   | Hash | 存储对象字段 | 任务详情（字段级更新） |
   | List | 队列、时间线 | 消息队列（后续） |
   | Set | 去重、标签 | 任务标签 |
   | ZSet | 带分数的排序 | 优先级排序 |
   | String + JSON | 通用缓存 | **本项目采用** |

2. **过期机制**：惰性删除 + 定期删除
   - 惰性：访问到 key 时检查是否过期，过了就删
   - 定期：随机采样若干 key 检查并删除
   - 两者结合，兼顾 CPU 和内存

3. **三大缓存问题（面试必答）**

   | 问题 | 现象 | 解决 |
   |------|------|------|
   | **穿透** | 查不存在的 key，每次都打到 DB | 缓存空值 / 布隆过滤器 / 参数校验 |
   | **击穿** | 热点 key 过期瞬间，大量请求同时打到 DB | 互斥锁 / 逻辑过期 / 热点永不过期 |
   | **雪崩** | 大量 key 同时过期，DB 被打挂 | TTL 加随机偏移 / 多级缓存 / 限流降级 |

4. **Cache-Aside 模式**（本项目采用）

   ```
   读：查缓存 → 未命中查 DB → 回填缓存
   写：更新 DB → 删除缓存（不是更新缓存）
   ```

   **为什么写操作是"删缓存"而不是"更新缓存"？**
   - 更新缓存可能和并发写产生顺序错乱（后写的先更新缓存）
   - 删除缓存是"下次读时重建"，天然幂等
   - 删缓存失败还有兜底（消息队列重试、定时任务对账）

5. **写操作的两个顺序**
   - **先更新 DB，再删缓存**（本项目）：并发读可能短暂读到旧值，但不会长期不一致
   - 先删缓存，再更新 DB：并发读会穿透到 DB 拿到旧值并**重新写回缓存**，
     导致缓存里留了更久的脏数据 —— 更糟

### 编码任务

**已完成**：

| 文件 | 内容 |
|------|------|
| `config/CacheConfig.java` | JSON 序列化 + TTL + 随机偏移防雪崩 + 分布式锁防击穿 |
| `TaskServiceImpl.getById()` | `@Cacheable(key="#id", unless="#result == null")` |
| `TaskServiceImpl.updateStatus()` / `delete()` | `@CacheEvict(key="#id")` |

### 当日验收

```bash
# 1) 第一次查询：走 DB，缓存写入
curl http://localhost:18080/api/tasks/1

# 2) 用 redis-cli 确认缓存里有数据
#    （本机没有 redis-cli，用 python 或看应用日志）
python -c "
import socket
s=socket.create_connection(('127.0.0.1',6380),timeout=5)
s.sendall(b'*2\r\n\$4\r\nAUTH\r\n\$14\r\nsmartdesk_root\r\n'); s.recv(100)
s.sendall(b'*2\r\n\$4\r\nKEYS\r\n\$1\r\n*\r\n'); print(s.recv(4000).decode())"

# 3) 改任务状态，观察缓存被清（@CacheEvict）
curl -X PUT "http://localhost:18080/api/tasks/1/status?status=DONE"

# 4) 再次查，确认读到新值
curl http://localhost:18080/api/tasks/1
```

**必须能回答**：
- 缓存穿透/击穿/雪崩的区别和解决方案
- 为什么更新策略是"删缓存"而不是"更新缓存"？
- 先删缓存还是先更新 DB？为什么？
- `@Cacheable` 的 key 怎么设计？如果 key 少了一个过滤条件会怎样？
  （答：会缓存污染 —— 用户 A 的数据被用户 B 读到。本项目的做法是把
  ownerId 放进 key：`taskDetail::{ownerId}:{id}`，这样既享受缓存性能又完全隔离。
  踩过的弯路：第一版设计是"权限校验查询不走缓存"，结果缓存永远用不上，
  Day10 验收直接失败 —— 这个弯路值得在面试时讲）
- Redis 是单线程吗？为什么单线程还这么快？
  （答：Redis 6 起用多线程处理网络 IO，命令执行仍是单线程；
  单线程避免了锁竞争和上下文切换）

---

## 5. Day 11：登录认证、密码哈希、JWT、Spring Security

### 学习任务

1. **认证 vs 授权**
   - **认证（Authentication）**：你是谁？→ 登录、验证身份
   - **授权（Authorization）**：你能做什么？→ 角色、权限、数据归属
   - 本项目：认证 = JWT 令牌；授权 = ownerId 过滤

2. **密码哈希：为什么必须用 BCrypt**
   - ❌ MD5/SHA1：为"快速摘要"设计，可被彩虹表秒破，无盐时相同密码哈希相同
   - ❌ 裸 SHA256 + 固定盐：比 MD5 好，但仍然快
   - ✅ **BCrypt**：故意设计得慢（cost=10），暴力破解成本被放大上千倍；
     **自动生成随机盐并拼进哈希串**，同一密码每人哈希都不同

   ```java
   // Spring Security 内置
   @Bean
   public PasswordEncoder passwordEncoder() {
       return new BCryptPasswordEncoder();  // 默认 strength=10
   }
   ```

3. **JWT 结构**：`header.payload.signature`
   - **JWT 是签名不是加密** —— payload 是 Base64URL 编码，不是加密！
     所以 JWT 里绝不能放密码、身份证号
   - 作用：证明"这个 Token 是我签发的，且没被篡改"
   - HS256（对称）vs RS256（非对称）：单服务用 HS256，
     微服务（签发方和验证方分离）用 RS256

4. **无状态 vs 有状态**
   - Session：服务端存会话，重启即失效，需要共享存储
   - JWT：服务端不存，每次靠 Token 自证，适合微服务和前后端分离
   - **JWT 的代价**：无法即时撤销（要撤销得加黑名单或短有效期 + 刷新令牌）

5. **Spring Security 的过滤器链**

   ```
   请求 → SecurityFilterChain
            ├─ JwtAuthenticationFilter（解析 Token，设置 SecurityContext）
            ├─ AuthorizationFilter（检查 authorizeHttpRequests 规则）
            └─ Controller
   ```

   关键：**认证过滤器只"标记身份"不做拦截**，拦截由授权规则决定。

### 编码任务

**已完成**：

| 文件 | 内容 |
|------|------|
| `security/JwtService.java` | 签发 + 解析 + 校验 |
| `security/JwtAuthenticationFilter.java` | 解析 Authorization 头 |
| `security/JwtPrincipal.java` | 认证主体（record） |
| `security/CurrentUser.java` | 当前用户上下文 |
| `config/SecurityConfig.java` | SecurityFilterChain + 401/403 统一响应 |
| `user/service/UserServiceImpl.java` | BCrypt 哈希 + 防用户枚举 + 防时序攻击 |
| `user/entity/AppUser.java` | passwordHash 加 `@JsonIgnore` |
| `task/controller/TaskController.java` | **所有接口取 CurrentUser，ownerId 永远来自登录态** |

### 当日验收

```bash
B=http://localhost:18080

# 1) 未登录访问受保护接口 → 401
curl -i $B/api/tasks | head -1
# 期望：HTTP/1.1 401

# 2) 注册两个用户
curl -X POST $B/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice123456"}'
curl -X POST $B/api/auth/register -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"bob123456"}'

# 3) 登录拿 token
TOKEN=$(curl -s -X POST $B/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice123456"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
echo "TOKEN=$TOKEN"

# 4) 带 token 访问 → 200
curl -s -o /dev/null -w "%{http_code}\n" $B/api/tasks -H "Authorization: Bearer $TOKEN"

# 5) ★ 越权测试：alice 看不到 bob 的任务
BOB_ID=$(curl -s -X POST $B/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"bob123456"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['userId'])")
curl -s -X POST $B/api/tasks -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"title\":\"alice的任务\",\"priority\":3}" | python -c "import sys,json;print('alice task id =',json.load(sys.stdin)['data']['id'])"

# alice 用 bob 的 userId 去查 → 404（不是 403！不泄露"存在但无权"的信息）
curl -s -o /dev/null -w "bob 的任务被 alice 访问 → HTTP %{http_code}\n" \
  $B/api/tasks/999 -H "Authorization: Bearer $TOKEN"

# 6) 伪造 token → 401
curl -s -o /dev/null -w "伪造 token → HTTP %{http_code}\n" \
  $B/api/tasks -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.fake.signature"

# 7) 密码错误 → 401，且提示统一（不区分"用户不存在"）
curl -s -X POST $B/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"wrongpassword"}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['errorCode'],'|',d['errorMessage'])"

# 8) 不存在的用户 → 同样的提示
curl -s -X POST $B/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"nobody","password":"whatever123"}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['errorCode'],'|',d['errorMessage'])"
```

**必须能回答**：
- 认证和授权的区别？
- 为什么不能用 MD5 存密码？BCrypt 的两个关键设计是什么？
- JWT 能加密用户信息吗？（不能，只是 Base64 编码）
- 无状态认证的代价是什么？怎么解决？
- **怎么防止用户改 URL 里的 id 读取别人的数据？**
  （答：三层 —— ①ownerId 只从登录态取，绝不从请求参数取；
  ②SQL 层过滤 `WHERE id=? AND owner_id=?`，不是查出来再判断；
  ③缓存 key 带 ownerId（`taskDetail::{ownerId}:{id}`），杜绝缓存污染）
- 401 vs 403 vs 404，跨用户访问该返回哪个？
  （答：本项目返回 404。返回 403 等于告诉攻击者"这个 id 确实存在"，
  是信息泄露。但注意：403 在"同一用户无权限访问自己可见的资源类型"时更合适）

---

## 6. Day 12：配置文件、环境变量、日志、Maven profile、Docker 基础

### 学习任务

1. **Spring Profile vs Maven Profile**

   | | Spring Profile | Maven Profile |
   |---|----------------|---------------|
   | 生效时机 | **运行期** | **构建期** |
   | 决定什么 | 读哪份配置 | 打包什么、跑不跑测试 |
   | 激活方式 | `--spring.profiles.active=prod` | `-P prod` |

   本项目两者同名是故意的，方便记忆。

2. **配置优先级**（从高到低）
   ```
   命令行参数 > 环境变量 > profile 专属配置 > application.yml > jar 包内默认
   ```
   Spring Boot 2.4+ 改用 `config/application.yml` 的新格式。

3. **敏感信息的三种处理**

   ```yaml
   # ① 带默认值（开发方便，生产要小心）
   password: ${MYSQL_PASSWORD:}
   # ② 不带默认值（生产用 —— 没配就启动失败，fail fast）
   password: ${MYSQL_PASSWORD}
   # ③ 从文件/配置中心读
   password: ${MYSQL_PASSWORD}   # 由 Docker/K8s 注入
   ```

   **本项目 prod profile 用的是 ②**，这是生产环境该有的样子。

4. **Docker 基础**
   - 多阶段构建：编译用 Maven 镜像，运行用 JRE 镜像，最终镜像小、攻击面小
   - 非 root 用户运行容器
   - `HEALTHCHECK` 让编排平台知道容器是否就绪
   - `depends_on: condition: service_healthy` —— 等依赖**真正健康**而不是"启动了"
   - 命名卷（volume）持久化数据

5. **日志**
   - 分类：ERROR（要有人管）/ WARN（要关注）/ INFO（关键业务）/ DEBUG（排查用）
   - **生产环境不要打 INFO 以下的日志** —— 量大、可能泄露业务数据
   - **绝不打印密码、令牌**到日志

### 编码任务

**已完成**：

| 文件 | 内容 |
|------|------|
| `application.yml` | 全部敏感信息走环境变量 |
| `application-local.yml` | 本地：DEBUG 日志 + 详细健康检查 |
| `application-test.yml` | 测试：WARN 日志 + 独立 Redis db |
| `application-prod.yml` | 生产：JWT_SECRET 等**无默认值**（fail fast） |
| `.env.example` | 环境变量模板（.env 本身被 gitignore） |
| `Dockerfile` | 多阶段构建 + 非 root + HEALTHCHECK |
| `compose.yaml` | mysql + redis + app，healthcheck 编排 |
| `pom.xml` | 3 个 Maven profile：local / dev / prod |

### 当日验收

```bash
# 1) 确认 .env 被 gitignore（不该进仓库）
cd D:/a应聘/学习/workbuddy/smartdesk
git check-ignore -v .env || echo "⚠️ .env 未被忽略！"

# 2) 确认代码里没有硬编码密码
grep -rn "smartdesk_root" src/main/java/ || echo "✅ 代码里没有硬编码密码"

# 3) 切换 profile 启动，验证配置生效
SPRING_PROFILES_ACTIVE=local java -jar target/smartdesk-0.1.0-SNAPSHOT.jar --server.port=18080
# 观察日志级别和 SQL 输出

# 4) Docker（如果本机 Docker 可用）
docker compose config    # 校验 compose 文件语法
docker compose build     # 构建镜像
```

**必须能回答**：
- Spring Profile 和 Maven Profile 的区别？
- 生产环境为什么不给配置项写默认值？
- Docker 多阶段构建解决什么问题？
- 容器里为什么要用非 root 用户？
- `depends_on` 加 `condition: service_healthy` 解决什么问题？

---

## 7. Day 13：JUnit、MockMvc、Service 单元测试、数据库集成测试

### 学习任务

1. **测试金字塔**

   ```
        /\        E2E（少，慢，脆）
       /  \       集成测试（用真 DB）
      /    \      单元测试（多，快，稳）
     /______\
   ```

2. **三种测试的区别**

   | | 单元测试 | 集成测试（@SpringBootTest） | 切片测试（@WebMvcTest） |
   |---|---------|---------------------------|----------------------|
   | 加载容器 | 否 | 全量 | 只加载 Web 层 |
   | 真实 DB | 否 | 是 | 否 |
   | 速度 | 毫秒 | 秒 | 毫秒 |
   | 用途 | 业务逻辑 | SQL / 事务 / 装配 | 路由 + 序列化 + 校验 |

3. **为什么不用 H2 代替 MySQL**
   - SQL 方言差异（`LIMIT`、`ON DUPLICATE KEY UPDATE`、函数）
   - 类型差异（JSON、DECIMAL、日期精度）
   - 索引和执行计划不同
   - **"本地测试全绿，生产一跑就炸"** 是典型的假测试

### 编码任务

**已完成**：

| 文件 | 覆盖内容 |
|------|---------|
| `task/service/TaskServiceImplTest.java` | Service 逻辑（mock Mapper） |
| `task/controller/TaskControllerTest.java` | Web 切片（mock Service） |
| `user/service/UserServiceImplTest.java` | BCrypt 哈希、用户枚举防护 |
| `security/JwtServiceTest.java` | 签发 / 校验 / 过期 / 篡改 |
| `task/TaskIntegrationTest.java` | 真实 MySQL 的 CRUD + 权限隔离 |

### 当日验收

```bash
cd D:/a应聘/学习/workbuddy/smartdesk
mvn -B clean test        # 全部测试
mvn -B -P dev test       # dev profile 构建
```

**必须能回答**：
- `@SpringBootTest` 和 `@WebMvcTest` 的区别？
- 为什么不用 H2？
- `@MockBean` 为什么被删除了？（Boot 4 → `@MockitoBean`）
- 单元测试 mock 掉什么？集成测试 mock 掉什么？

---

## 8. Day 14：周复盘、重构包结构、限时面试

### 当日验收（计划原文：Week2 总验收）

> 从干净环境启动服务；演示登录、创建任务、查询任务；写一页系统架构说明。

```bash
# 1) 干净启动（模拟面试现场）
cd D:/a应聘/学习/workbuddy/smartdesk
mvn -B clean package -DskipTests

# 启动依赖（两个脚本都有 start/stop/status 子命令）
bash scripts/start-mysql-dev.sh start
bash scripts/start-redis-dev.sh start

# 起服务
MYSQL_PORT=3307 MYSQL_PASSWORD=smartdesk_root REDIS_PORT=6380 \
  java -jar target/smartdesk-0.1.0-SNAPSHOT.jar --server.port=18080

# 2) 完整演示脚本
#    注册 → 登录 → 创建任务 → 查询 → 筛选 → 更新 → 删除 → 越权测试
#    一键跑：python scripts/week2-acceptance.py   （36 项断言）
```

### 架构说明文档

见 `docs/architecture.md` —— 一页纸讲清楚：
- 分层结构与职责边界
- 请求链路
- 数据模型与索引设计
- 缓存策略与失效时机
- 认证与授权链路
- 配置与部署

### 限时面试（30 分钟，不查资料）

1. 你的项目分层是怎么分的？每层职责边界在哪？
2. 为什么加索引前要先看查询场景？索引的代价是什么？
3. `EXPLAIN` 的 `type` 从好到坏是什么顺序？
4. MySQL 默认隔离级别？RR 怎么解决幻读？
5. 缓存穿透、击穿、雪崩分别是什么？怎么解决？
6. 为什么写操作是"删缓存"不是"更新缓存"？
7. 缓存 key 少一个条件会出什么 bug？
8. 认证和授权的区别？JWT 为什么不能放敏感信息？
9. BCrypt 相比 MD5 好在哪两点？
10. 怎么防止用户改 URL 里的 id 越权访问？
11. 401 / 403 / 404 分别什么时候用？
12. Spring Profile 和 Maven Profile 的区别？
13. 为什么生产环境的配置项不写默认值？
14. Docker 多阶段构建的价值？
15. 为什么不用 H2 做集成测试？

---

## 9. 本周新增文件清单

```
smartdesk/
├── pom.xml                          # +Spring Security +JJWT +Cache +Testcontainers +3个 profile
├── Dockerfile                       # 新增：多阶段构建
├── compose.yaml                     # 重写：mysql + redis + app
├── .env.example                     # 新增：环境变量模板
├── docs/
│   ├── week1-guide.md               # 第1周手册
│   ├── week2-guide.md               # 本文件
│   └── architecture.md               # 新增：架构说明
├── scripts/
│   └── start-mysql-dev.sh            # MySQL dev 实例管理
└── src/
    ├── main/java/com/smartdesk/
    │   ├── common/
    │   │   └── GlobalExceptionHandler.java   # +3个handler
    │   ├── config/
    │   │   ├── CacheConfig.java              # 新增：Redis 缓存
    │   │   ├── MybatisPlusConfig.java
    │   │   └── SecurityConfig.java           # 新增：SecurityFilterChain
    │   ├── security/                          # 新增目录
    │   │   ├── CurrentUser.java
    │   │   ├── JwtAuthenticationFilter.java
    │   │   ├── JwtPrincipal.java
    │   │   └── JwtService.java
    │   ├── task/
    │   │   ├── controller/TaskController.java  # 重写：权限隔离 + 多条件查询
    │   │   ├── dto/TaskQuery.java             # 新增
    │   │   ├── entity/Task.java
    │   │   ├── entity/TaskStatus.java
    │   │   ├── service/TaskService.java       # 重写：ownerId 参数
    │   │   └── service/TaskServiceImpl.java   # 重写：缓存 + 权限
    │   └── user/                               # 新增目录
    │       ├── controller/AuthController.java
    │       ├── controller/UserResponse.java
    │       ├── dto/LoginRequest.java
    │       ├── dto/LoginResponse.java
    │       ├── dto/RegisterRequest.java
    │       ├── entity/AppUser.java
    │       ├── mapper/AppUserMapper.java
    │       ├── service/UserService.java
    │       └── service/UserServiceImpl.java
    └── main/resources/
        ├── application.yml            # 重写：环境变量化
        ├── application-local.yml      # 新增
        ├── application-test.yml       # 新增
        ├── application-prod.yml       # 新增
        └── db/schema.sql              # 重写：app_user + 3个复合索引 + 外键
```

---

## 10. 每天提交规范

```bash
git add -A
git commit -m "feat(day8): add composite indexes and multi-condition task query

- task table: 3 composite indexes (owner_id+status/due_date/priority)
- app_user table with unique index on username
- TaskQuery DTO for dynamic filtering
- Service.query() builds dynamic SQL via LambdaQueryWrapper"
```

提交粒度按天，不要一天一个大提交 —— 面试时展示 git 历史很有说服力。
