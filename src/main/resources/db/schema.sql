-- ============================================================
-- SmartDesk 任务表（Day5）
-- 引擎用 InnoDB，字符集 utf8mb4（支持 emoji 和生僻字）
-- ============================================================
CREATE DATABASE IF NOT EXISTS smartdesk
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE smartdesk;

DROP TABLE IF EXISTS task;

CREATE TABLE task (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title       VARCHAR(200) NOT NULL COMMENT '任务标题',
    description VARCHAR(2000)         DEFAULT NULL COMMENT '任务描述',
    status      VARCHAR(20)  NOT NULL DEFAULT 'TODO' COMMENT '状态: TODO/IN_PROGRESS/DONE/CANCELLED',
    priority    INT          NOT NULL DEFAULT 3 COMMENT '优先级 1-5，越大越优先',
    owner_id    BIGINT                DEFAULT NULL COMMENT '所属用户 id，用于数据隔离',
    due_date    DATETIME              DEFAULT NULL COMMENT '截止时间',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- Day5 要点：索引不是越多越好。这两个索引对应最常见的查询模式：
    -- 按 owner 查自己的任务；按 owner + status 查待办
    KEY idx_owner_id (owner_id),
    KEY idx_owner_status (owner_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '任务表';

-- ============================================================
-- 验证索引生效：EXPLAIN 走 idx_owner_status 而不是全表扫描
-- ============================================================
-- EXPLAIN SELECT * FROM task WHERE owner_id = 1 AND status = 'TODO';

-- ============================================================
-- Week2 会加的表（现在先建好骨架，第 2 周补字段）
-- ============================================================
-- CREATE TABLE app_user (
--     id            BIGINT       NOT NULL AUTO_INCREMENT,
--     username      VARCHAR(64)  NOT NULL,
--     password_hash VARCHAR(200) NOT NULL,
--     created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
--     PRIMARY KEY (id),
--     UNIQUE KEY uk_username (username)
-- ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
