-- ============================================================================
-- V4 —— 演习（场次 / 编成快照 / 日志 / 指令队列 / 命中）
--
-- 与旧库的对应关系：
--   t_battle              → t_exercise
--   t_battle_log          → t_exercise_log
--   t_battle_record       → （不建表）复盘时间轴改由 t_exercise.started_at + t_ms 表达
--   （新）                 → t_exercise_unit   （开演瞬间的编成快照）
--   （新）                 → t_exercise_command（下行指令队列）
--   （新）                 → t_hit_event
--
-- 关键取向：**「演习」与「复盘」是同一实体的两个视图**。开演 INSERT 一行，
-- 停演 UPDATE ended_at，此后本行即成为可回放的复盘记录——replayId 就是 exerciseId。
-- 因此本表不叫 t_replay，也不另建一份副本；否则「同一场演习」会有两处主键要维护。
--
-- 主键例外说明（呼应 V1 文件头第 1 条）：命中事件 t_hit_event 是高频写入表，
-- 用 BIGINT AUTO_INCREMENT 而非 UUIDv7。它天然属于某一场演习（exercise_id），
-- 生命周期随演习走，不需要跨库对账，自增即可；且 seq 直接对外充当契约里的
-- `HitEvent.seq`（全局单调递增，供增量拉取与去重）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 演习场次。
--
-- ⚠ 硬约束「同一时刻只能有一场演习进行中」在库层强制：MySQL 没有部分唯一索引，
--   因此用生成列 running_flag（进行中=1，已结束=NULL）配唯一索引。唯一索引不比较
--   NULL，所以无论并发开演多少次，最多只有一行能落进 running_flag=1。
--   这条约束不能只靠应用层的「先查再插」，那中间有竞态窗口。
-- ---------------------------------------------------------------------------
CREATE TABLE t_exercise (
    id            CHAR(36)     NOT NULL COMMENT 'UUIDv7；对外即 exerciseId / replayId',
    plan_id       CHAR(36)     NOT NULL,
    plan_name     VARCHAR(128) NOT NULL COMMENT '想定名快照：想定可被改名/删除，复盘列表要按此模糊搜索',
    map_id        CHAR(36)         NULL COMMENT '底图快照，可能为空（室外纯 GPS）',
    battle_mode   VARCHAR(16)  NOT NULL COMMENT 'TRAIN / BATTLE',
    started_at    DATETIME(3)  NOT NULL COMMENT '开演时刻（UTC），复盘时间轴的原点',
    ended_at      DATETIME(3)      NULL COMMENT 'NULL 即进行中；非 NULL 即已结束（可回放）',
    -- 结局统计在停演时一次性冻结，不做每次查询现算：
    -- 复盘列表每页 20 条，现算要对 unit / hit 表做三组聚合，随数据增长会明显拖慢列表。
    roster_count  INT          NOT NULL DEFAULT 0 COMMENT '参演人数（停演时冻结）',
    hit_count     INT          NOT NULL DEFAULT 0 COMMENT '命中总数（停演时冻结）',
    dead_count    INT          NOT NULL DEFAULT 0 COMMENT '阵亡总数（停演时冻结）',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    running_flag  TINYINT GENERATED ALWAYS AS (IF(ended_at IS NULL, 1, NULL)) STORED
                  COMMENT '生成列，仅为唯一索引服务，业务不得读写',
    PRIMARY KEY (id),
    UNIQUE KEY uk_exercise_running (running_flag),
    KEY ix_exercise_ended (ended_at),
    KEY ix_exercise_plan (plan_id),
    CONSTRAINT ck_exercise_battle_mode CHECK (battle_mode IN ('TRAIN', 'BATTLE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '演习场次（结束时即成为复盘记录）';

-- ---------------------------------------------------------------------------
-- 演习编成快照：开演瞬间从 t_plan_roster 拷一份。
-- 之所以快照而不直接引用想定：想定事后必被改（下一场要换人换队伍），
-- 复盘必须看到「当时那场」的编成，不能被后续编辑污染。
--
-- 不下发字段：combatRole / weaponKind 由 vest_num 编号区间推导（见 V3 说明），
-- 存下来只会与推导规则产生版本歧义。终局 hp/ammo 同理可从 t_track_sample
-- 的最后一条读出，不在这里重复。
-- ---------------------------------------------------------------------------
CREATE TABLE t_exercise_unit (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    exercise_id   CHAR(36)    NOT NULL,
    vest_num      INT         NOT NULL COMMENT '马甲编号',
    name          VARCHAR(64)     NULL COMMENT '人员姓名快照',
    team          VARCHAR(16) NOT NULL COMMENT '契约 Team 枚举',
    final_status  VARCHAR(16)     NULL COMMENT '停演时冻结的终局状态 OFFLINE / LIVE / DIE（供队伍战损统计）',
    dead_at       DATETIME(3)     NULL COMMENT '阵亡时刻；复盘存活曲线的依据',
    kills         INT         NOT NULL DEFAULT 0 COMMENT '击杀数（停演时冻结）',
    hits_dealt    INT         NOT NULL DEFAULT 0 COMMENT '命中数（停演时冻结）',
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_exercise_unit (exercise_id, vest_num),
    KEY ix_exercise_unit_team (exercise_id, team),
    CONSTRAINT ck_exercise_unit_team CHECK (team IN ('RED', 'BLUE', 'ORANGE', 'YELLOW')),
    CONSTRAINT ck_exercise_unit_status CHECK (final_status IS NULL OR final_status IN ('OFFLINE', 'LIVE', 'DIE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '演习编成快照（开演瞬间冻结）';

-- ---------------------------------------------------------------------------
-- 演习日志流水（对应旧 t_battle_log），指挥台右侧滚动日志的数据源。
-- 只增不改不删。协议自动产生的事件 operator_name 为 NULL；人工下发的记操作者。
-- downlink_hex 是排障命脉：逐字节保留下发原文，现场与示波器/串口助手对拍用。
-- ---------------------------------------------------------------------------
CREATE TABLE t_exercise_log (
    seq           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '对应契约 ExerciseLogEntry.seq',
    exercise_id   CHAR(36)     NOT NULL,
    at            DATETIME(3)  NOT NULL COMMENT '事件时刻（UTC）',
    log_type      VARCHAR(24)  NOT NULL COMMENT '契约 LogType 枚举',
    vest_num      INT              NULL COMMENT '与马甲无关的系统级日志为 NULL',
    operator_name VARCHAR(64)      NULL COMMENT '人工下发时的账号名；协议自动产生为 NULL',
    detail        VARCHAR(255)     NULL COMMENT '人类可读描述',
    downlink_hex  VARCHAR(256)     NULL COMMENT '下行指令十六进制原文，逐字节可核对',
    PRIMARY KEY (seq),
    KEY ix_exercise_log_exercise_seq (exercise_id, seq),
    KEY ix_exercise_log_exercise_at (exercise_id, at),
    KEY ix_exercise_log_vest (exercise_id, vest_num, seq),
    CONSTRAINT ck_exercise_log_type CHECK (log_type IN ('START_BATTLE', 'SHOT', 'DIE', 'LIVE', 'INJURE', 'RELOAD'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '演习日志流水';

-- ---------------------------------------------------------------------------
-- 下行指令队列。与 t_exercise_log 的分工：
--   t_exercise_command = **待发/已发未确认的工作队列**，指令送达即离队（可清理）；
--   t_exercise_log     = **只增的流水**，指令一发出就记一条，永不清理。
-- 之所以把队列落库而不是只放内存：马甲离线时指令要挂起等待重连（契约
-- CommandAck.accepted=false 即此语义），进程重启不能让挂起的指令凭空消失。
-- ---------------------------------------------------------------------------
CREATE TABLE t_exercise_command (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    exercise_id   CHAR(36)    NOT NULL,
    vest_num      INT         NOT NULL,
    command       VARCHAR(32) NOT NULL COMMENT '如 DIE / RELOAD / CONFIG',
    state         VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待发 / SENT 已发未确认 / ACKED 已确认 / FAILED / EXPIRED',
    payload_json  JSON            NULL COMMENT '指令参数（如充弹数量、武器槽位）',
    downlink_hex  VARCHAR(256)    NULL COMMENT '组装出的下行原文；PENDING 阶段可能尚未生成',
    operator_name VARCHAR(64)     NULL,
    queued_at     DATETIME(3) NOT NULL,
    sent_at       DATETIME(3)     NULL,
    finished_at   DATETIME(3)     NULL,
    fail_reason   VARCHAR(64)     NULL,
    PRIMARY KEY (id),
    KEY ix_exercise_command_pending (state, queued_at),
    KEY ix_exercise_command_vest (exercise_id, vest_num, state),
    CONSTRAINT ck_exercise_command_state CHECK (state IN ('PENDING', 'SENT', 'ACKED', 'FAILED', 'EXPIRED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '下行指令队列';

-- ---------------------------------------------------------------------------
-- 命中事件（对应协议 ff05 上报）。高频写入，主键用 BIGINT 自增，直接对外
-- 充当契约的 HitEvent.seq。
--
-- 坐标在这里冗余存一份（与 t_track_sample 同名同义的列）：命中是**离散事件**，
-- 复盘要按事件定位到「打中那一刻的位置」，不能让客户端去 track 里做最近邻插值
-- 反推——那在双采样点跨越位移时会算错。命中数远小于轨迹点数，冗余代价可接受。
--
-- t_ms 与 t_track_sample.t_ms 同一原点（exercise.started_at），复盘时可与轨迹混排。
-- ---------------------------------------------------------------------------
CREATE TABLE t_hit_event (
    seq                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '对外即 HitEvent.seq',
    exercise_id         CHAR(36)    NOT NULL,
    at                  DATETIME(3) NOT NULL COMMENT '命中时刻（UTC）',
    t_ms                INT         NOT NULL COMMENT '相对开演的毫秒偏移',
    hit_vest_num        INT         NOT NULL COMMENT '被击中者（ff05 字段一）',
    shooter_num         INT         NOT NULL COMMENT '开枪者编号（ff05 字段二）',
    shooter_is_soldier  TINYINT     NOT NULL COMMENT '编号 <=1800 为单兵',
    shooter_weapon_kind VARCHAR(24)     NULL COMMENT '契约 WeaponKind 枚举；单兵时为 NULL',
    hit_part            VARCHAR(16) NOT NULL COMMENT '契约 HitPart 枚举',
    lethal              TINYINT     NOT NULL COMMENT '服务端判定的致死结果，前端不再自算',
    -- 被击中者当时坐标（GeoPoint 的列式展开，与 t_track_sample 保持同名同义）
    coordinate_system   VARCHAR(8)      NULL COMMENT 'INDOOR / OUTDOOR',
    x_pct               DOUBLE          NULL,
    y_pct               DOUBLE          NULL,
    floor_num           INT             NULL,
    pos_num             INT             NULL,
    lng                 DOUBLE          NULL COMMENT 'BD-09 经度',
    lat                 DOUBLE          NULL COMMENT 'BD-09 纬度',
    wgs84_lng           DOUBLE          NULL,
    wgs84_lat           DOUBLE          NULL,
    PRIMARY KEY (seq),
    KEY ix_hit_event_exercise_seq (exercise_id, seq),
    KEY ix_hit_event_exercise_t (exercise_id, t_ms),
    KEY ix_hit_event_hit_vest (exercise_id, hit_vest_num),
    KEY ix_hit_event_shooter (exercise_id, shooter_num),
    CONSTRAINT ck_hit_event_hit_part CHECK (hit_part IN
        ('UNKNOWN', 'HEAD', 'CHEST', 'LEFT_HAND', 'RIGHT_HAND', 'ABDOMEN', 'BACK', 'LEFT_LEG', 'RIGHT_LEG')),
    CONSTRAINT ck_hit_event_coord_system CHECK (coordinate_system IS NULL OR coordinate_system IN ('INDOOR', 'OUTDOOR'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '命中事件';
