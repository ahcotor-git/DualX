-- ============================================================================
-- V3 —— 想定与编成
--
-- 与旧库的对应关系（三张表收敛成两张）：
--   t_battle_base_config  → t_plan
--   t_battle_personnel    ┐
--   t_battle_team_config  ├→ t_plan_roster（逐人一行）
--   t_battle_team_config2 ┘
--
-- 归一化的理由：旧库有两套并存的编成表达——区间式（start_num~end_num）与
-- 列表式（nums 逗号串，varchar(4096)）。两者语义重叠，且列表式无法建索引、
-- 无法约束取值。新契约只承认「逐人记录」，区间/列表两种导入写法由 API 层
-- 展开后再落库，库里只保留一种形态。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 想定（旧称「战斗基础配置」）。
-- 注意：旧表同时有 map_id / indoor_map_id / outdoor_map_id 三个地图字段，
-- 新契约收敛为单一 map_id（内外双图不再并存，室外底图方案待定）。
-- ---------------------------------------------------------------------------
CREATE TABLE t_plan (
    id          CHAR(36)     NOT NULL COMMENT 'UUIDv7',
    name        VARCHAR(128) NOT NULL,
    map_id      CHAR(36)         NULL COMMENT '绑定的底图；可为空表示未指定',
    battle_mode VARCHAR(16)  NOT NULL DEFAULT 'TRAIN' COMMENT 'TRAIN=训练 / BATTLE=正式演习（旧 t_battle_base_config.mode）',
    remark      VARCHAR(255)     NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_plan_map (map_id),
    CONSTRAINT ck_plan_battle_mode CHECK (battle_mode IN ('TRAIN', 'BATTLE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '想定';

-- ---------------------------------------------------------------------------
-- 想定编成，逐人一行。整体替换（PUT /roster）时先删后插，不做逐行 diff。
-- combatRole / weaponKind 刻意不落库：契约明确它们由 vestNum 的编号区间推导
-- （<=1800 单兵、1801~4800 重武器/载具、65533~65535 危险物），存下来就会有
-- 与推导规则不一致的风险。
-- ---------------------------------------------------------------------------
CREATE TABLE t_plan_roster (
    id       BIGINT      NOT NULL AUTO_INCREMENT,
    plan_id  CHAR(36)    NOT NULL,
    vest_num INT         NOT NULL COMMENT '马甲编号，引用 t_vest.vest_num',
    name     VARCHAR(64)     NULL COMMENT '人员姓名',
    team     VARCHAR(16) NOT NULL COMMENT '契约 Team 枚举',
    hp       INT         NOT NULL DEFAULT 100 COMMENT '开演初始生命值',
    ammo1    INT         NOT NULL DEFAULT 30 COMMENT '主武器弹药',
    ammo2    INT         NOT NULL DEFAULT 30 COMMENT '副武器弹药',
    mode     VARCHAR(16) NOT NULL DEFAULT 'AUTO' COMMENT '轮询模式 AUTO / MANUAL',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_roster (plan_id, vest_num),
    KEY ix_plan_roster_plan_team (plan_id, team),
    CONSTRAINT ck_plan_roster_team CHECK (team IN ('RED', 'BLUE', 'ORANGE', 'YELLOW')),
    CONSTRAINT ck_plan_roster_mode CHECK (mode IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_plan_roster_vest_num CHECK (vest_num BETWEEN 1 AND 65532)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '想定编成（逐人一行）';
