-- ============================================================
-- SmartDesk 数据库 Schema（Week2 完整版）
--
-- 引擎统一用 InnoDB（支持事务、行锁、外键），字符集 utf8mb4。
--
-- Day8 要点：索引不是越多越好，要按"最常见的查询模式"来建。
-- Day11 要点：app_user 表加唯一索引，是"用户名不能重复"的数据库级保证，
--              不能只靠应用层 if 判断（并发下会漏）。
-- ============================================================

CREATE DATABASE IF NOT EXISTS smartdesk
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE smartdesk;

-- ============================================================
-- 用户表（Day11）
-- ============================================================
DROP TABLE IF EXISTS app_user;

CREATE TABLE app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名，全局唯一',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，绝不存明文',
    nickname      VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 禁用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 唯一索引：数据库层面保证用户名不重复。
    -- 面试可讲：为什么不用应用层判断？因为两个并发请求可能同时通过
    -- "用户名不存在"的检查，都去插入，最终产生重复数据。
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '用户表';

-- ============================================================
-- 任务表（Day5 建立骨架，Day8 补索引，Day11 加外键）
-- ============================================================
DROP TABLE IF EXISTS task;

CREATE TABLE task (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title       VARCHAR(200) NOT NULL COMMENT '任务标题',
    description VARCHAR(2000)         DEFAULT NULL COMMENT '任务描述',
    status      VARCHAR(20)  NOT NULL DEFAULT 'TODO' COMMENT '状态: TODO/IN_PROGRESS/DONE/CANCELLED',
    priority    INT          NOT NULL DEFAULT 3 COMMENT '优先级 1-5，越大越优先',
    owner_id    BIGINT       NOT NULL COMMENT '所属用户 id，用于数据隔离',
    due_date    DATETIME              DEFAULT NULL COMMENT '截止时间',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),

    -- ------------------------------------------------------------
    -- Day8：索引设计
    -- ------------------------------------------------------------
    -- 原则：索引要匹配真实查询场景，且遵循最左前缀原则。
    --
    -- idx_owner_status (owner_id, status)
    --   覆盖查询：WHERE owner_id=? AND status=?
    --   也能覆盖：WHERE owner_id=?（最左前缀）
    --
    -- idx_owner_due (owner_id, due_date)
    --   覆盖查询：WHERE owner_id=? AND due_date BETWEEN ? AND ?
    --   用于"我的待办按截止时间筛选 / 找出已过期"
    --
    -- idx_owner_priority (owner_id, priority)
    --   覆盖查询：WHERE owner_id=? ORDER BY priority DESC
    --
    -- 为什么不用单列索引 (owner_id)？
    --   因为复合索引 (owner_id, status) 的最左前缀已经能覆盖 owner_id 单列查询，
    --   再建一个单列索引是冗余的，白白占空间和写入开销。
    --
    -- 面试可讲：索引的代价
    --   1. 占磁盘空间
    --   2. INSERT/UPDATE/DELETE 时要维护索引，变慢
    --   3. 优化器可能选错索引（用 EXPLAIN 看 key 和 rows）
    KEY idx_owner_status (owner_id, status),
    KEY idx_owner_due (owner_id, due_date),
    KEY idx_owner_priority (owner_id, priority),

    -- 外键约束：保证 owner_id 一定指向存在的用户。
    -- 面试可讲：外键的取舍 —— 严格性 vs 写入性能。
    --   互联网大厂倾向"逻辑外键"（只存 id 不建 FK），牺牲严格性换性能；
    --   传统企业系统倾向建 FK，数据一致性由数据库兜底。
    --   本项目选 FK，因为数据量小、且教学价值更高。
    CONSTRAINT fk_task_owner FOREIGN KEY (owner_id) REFERENCES app_user (id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '任务表';

-- ============================================================
-- 验证索引生效（Day8 验收命令）
-- ============================================================
-- EXPLAIN SELECT id, title FROM task
--   WHERE owner_id = 1 AND status = 'TODO';
--
-- 期望输出：
--   key    = idx_owner_status   ← 走了索引
--   type   = ref                ← 范围查找，不是全表扫描
--   rows   = 1                  ← 预估扫描行数很小
--
-- 对比全表扫描的写法（key=NULL, type=ALL）：
--   EXPLAIN SELECT id, title FROM task WHERE title = 'xxx';
