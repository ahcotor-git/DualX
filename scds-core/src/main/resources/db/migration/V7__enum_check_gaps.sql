-- ============================================================================
-- V7 —— 枚举列 CHECK 约束补齐（V4 / V5 遗漏项）
--
-- 背景：全项目审查发现两处漏网的枚举列——V4 的 t_hit_event.shooter_weapon_kind
-- 与 V5 的 t_track_sample.position_source。二者都直接对应契约枚举，但没加 CHECK，
-- 而同表其余枚举列（hit_part / coordinate_system / status / motion）都已加。
-- 此处补齐，使全库口径一致：枚举列一律存契约里的英文枚举名，并由 CHECK 兜底，
-- 杜绝脏值入库。
--
-- 为何新建 V7 而不直接改 V4 / V5：V4 / V5 已在 e935ec3 提交并应用，Flyway 已记录
-- 其校验和；就地修改会让已有库启动时报 validation failed。追加迁移是唯一既不破坏
-- 已有环境、又能同时适用于全新克隆库的做法。
--
-- 附带：t_ms 列注释补充上限说明（用户裁定保留 INT）。INT 有符号上限
-- 2147483647ms ≈ 24.85 天，单场实兵演习远达不到；保留 INT 可使
-- ix_track_exercise_t / ix_hit_event_exercise_t 的索引键更小。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- t_hit_event.shooter_weapon_kind：契约 WeaponKind（12 值），单兵命中时为 NULL。
-- 取值与 docs/api/schemas/common.yaml#/WeaponKind 逐字一致。
-- ---------------------------------------------------------------------------
ALTER TABLE t_hit_event
    ADD CONSTRAINT ck_hit_event_weapon_kind CHECK (shooter_weapon_kind IS NULL OR shooter_weapon_kind IN
        ('HOWITZER_35', 'ROCKET_80', 'ROCKET_120', 'IFV', 'ASSAULT_VEHICLE', 'APC',
         'HJ73', 'MORTAR', 'FIRE_POINT', 'MINE', 'EXPLOSIVE', 'GRENADE'));

-- ---------------------------------------------------------------------------
-- t_track_sample.position_source：契约 situation.yaml#/UnitState.positionSource（4 值）。
-- ---------------------------------------------------------------------------
ALTER TABLE t_track_sample
    ADD CONSTRAINT ck_track_position_source CHECK (position_source IS NULL OR position_source IN
        ('UWB', 'GPS', 'MANUAL', 'NONE'));

-- ---------------------------------------------------------------------------
-- t_ms 注释补齐（类型不变，仅改注释，MySQL 8 走 INPLACE）。
-- ---------------------------------------------------------------------------
ALTER TABLE t_track_sample
    MODIFY COLUMN t_ms INT NOT NULL COMMENT '相对开演的毫秒偏移（时间轴唯一依据）；INT 上限约 24.85 天，单场演习足够';

ALTER TABLE t_hit_event
    MODIFY COLUMN t_ms INT NOT NULL COMMENT '相对开演的毫秒偏移；INT 上限约 24.85 天，单场演习足够';
