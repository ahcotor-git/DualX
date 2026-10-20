-- ============================================================================
-- V1 —— 认证与审计（边缘库）
--
-- 全库统一约定（后续迁移不再重复声明）：
--   1. 业务实体主键一律 CHAR(36) 的 UUIDv7，由应用侧生成。理由：云管与边缘双形态
--      下数据要双向汇入/对账，bigint 自增会撞号；UUIDv7 自带时间前缀，又不至于像
--      UUIDv4 那样随机写造成索引页分裂。
--      例外——高频写入的命中/轨迹表（t_hit_event / t_track_sample）改用
--      BIGINT AUTO_INCREMENT，理由见 V4 / V5 的文件头说明。
--   2. 时间一律 DATETIME(3)，库内只存 UTC。MySQL 的 DATETIME 不做时区换算，
--      因此不会随服务器时区漂移；展示时区由应用层负责。
--   3. 不建外键约束。云管/边缘双向汇入与批量导入时，外键会让写入顺序变成硬约束；
--      引用完整性改由应用层保证。这一点与旧库的做法不同，属有意选择。
--   4. 枚举列一律存契约里的英文枚举名（RED / LIVE / INDOOR ...），不存协议字节值。
--      协议字节 ↔ 枚举名的映射只存在于 scds-protocol 层，硬件细节不进数据库。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 指挥台账号。旧系统用 t_license（机器码授权）替代账号体系，该表已废弃：
-- 私有化部署的授权改由部署侧处理，不再混进业务库。
-- ---------------------------------------------------------------------------
CREATE TABLE t_account (
    id            CHAR(36)     NOT NULL COMMENT 'UUIDv7',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名',
    display_name  VARCHAR(64)      NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt，绝不可逆',
    enabled       TINYINT      NOT NULL DEFAULT 1,
    last_login_at DATETIME(3)      NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '指挥台账号';

-- ---------------------------------------------------------------------------
-- 账号角色。刻意不做成单列逗号串：逗号串既无法建索引，也没法约束取值。
-- ---------------------------------------------------------------------------
CREATE TABLE t_account_role (
    account_id CHAR(36)    NOT NULL,
    role_code  VARCHAR(16) NOT NULL COMMENT '对应契约 Role 枚举',
    PRIMARY KEY (account_id, role_code),
    CONSTRAINT ck_account_role CHECK (role_code IN ('ADMIN', 'DIRECTOR', 'OPERATOR', 'VIEWER'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '账号角色（一人可多角色）';

-- ---------------------------------------------------------------------------
-- 审计日志：谁、在什么时候、下达了什么下行指令。指令原文（downlink_hex）留给
-- t_exercise_log，本表只记「人的动作」，两者职责不重叠。
-- ---------------------------------------------------------------------------
CREATE TABLE t_audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    account_id  CHAR(36)         NULL COMMENT '系统自身动作时为 NULL',
    action      VARCHAR(64)  NOT NULL COMMENT '如 command.kill / exercise.start / device.link.open',
    target_type VARCHAR(32)      NULL COMMENT '如 VEST / MAP / PLAN / EXERCISE',
    target_id   VARCHAR(64)      NULL,
    detail_json JSON             NULL COMMENT '动作参数等结构化细节',
    client_ip   VARCHAR(64)      NULL,
    at          DATETIME(3)  NOT NULL COMMENT '动作发生时刻（UTC）',
    PRIMARY KEY (id),
    KEY ix_audit_at (at),
    KEY ix_audit_account_at (account_id, at),
    KEY ix_audit_target (target_type, target_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '操作审计';
