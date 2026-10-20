-- ============================================================================
-- V5 —— 轨迹采样与导出任务
--
-- 与旧库的对应关系：
--   t_battle_record_detail → t_track_sample
--   （新）                  → t_export_task
--
-- 主键例外说明（呼应 V1 文件头第 1 条）：t_track_sample 是全库写入量最大的表
-- （一场演习数十万条），主键用 BIGINT AUTO_INCREMENT，不用 UUIDv7：
-- UUIDv7 虽是 36 字节的 CHAR 且带时间前缀，但作为 InnoDB 聚簇主键仍比 8 字节
-- 自增宽数倍，且它内嵌的时间是**写入时刻**而非**采样时刻**——轨迹按 t_ms 排序
-- 与插入顺序并不一致，用 UUIDv7 换不来任何顺序收益，只买来体积。轨迹行天然
-- 从属于某场演习、不跨库对账，自增足够。
--
-- 落库策略（应用层实现，此处只说明为何这样设计表）：
--   不逐帧写。上行帧频率高（室内 200ms 级），逐帧写会把磁盘和连接池打满。
--   采用「降采样 + 批量异步 flush」：位移超阈值、或距上一条采样 ≥1s、或发生
--   事件点（命中/阵亡/状态跳变）时才产出采样；攒够 1s 或 500 行即批量提交。
--   sample_reason 列保留「为什么记了这一点」，复盘抽稀与排障时才知道哪些点
--   是不可省略的语义点。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 轨迹采样。列名与契约 TrackSample 一一对应，坐标列与 t_hit_event 同名同义。
--
-- 不存绝对时间：绝对时刻 = t_exercise.started_at + t_ms。冗余一列 DATETIME(3)
-- 在数十万行上是白白多出的体积，而复盘界面本来只用相对时间轴。
-- ---------------------------------------------------------------------------
CREATE TABLE t_track_sample (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    exercise_id       CHAR(36)    NOT NULL,
    vest_num          INT         NOT NULL,
    t_ms              INT         NOT NULL COMMENT '相对开演的毫秒偏移（时间轴唯一依据）',
    status            VARCHAR(16) NOT NULL COMMENT '停演/阵亡期间仍需按原值持续落点，以便复盘还原静止画面',
    hp                SMALLINT        NULL,
    ammo1             INT             NULL,
    ammo2             INT             NULL,
    motion            VARCHAR(16)     NULL COMMENT '契约 MotionState：MOVING / STATIONARY / FROZEN',
    position_source   VARCHAR(8)      NULL COMMENT 'UWB / GPS / MANUAL / NONE',
    signal_quality    TINYINT         NULL COMMENT 'UWB 可用基站数 0~8；<2 时坐标不可信',
    -- 坐标（GeoPoint 的列式展开）
    coordinate_system VARCHAR(8)      NULL COMMENT 'INDOOR / OUTDOOR',
    x_pct             DOUBLE          NULL COMMENT '室内归一化横坐标 0~1',
    y_pct             DOUBLE          NULL COMMENT '室内归一化纵坐标 0~1（Y 轴映射已由服务端完成）',
    floor_num         INT             NULL,
    pos_num           INT             NULL,
    lng               DOUBLE          NULL COMMENT 'BD-09 经度',
    lat               DOUBLE          NULL COMMENT 'BD-09 纬度',
    wgs84_lng         DOUBLE          NULL,
    wgs84_lat         DOUBLE          NULL,
    sample_reason     TINYINT     NOT NULL COMMENT '0=定时 / 1=位移超阈值 / 2=事件点 / 3=状态跳变',
    PRIMARY KEY (id),
    KEY ix_track_exercise_t (exercise_id, t_ms),
    KEY ix_track_exercise_vest_t (exercise_id, vest_num, t_ms),
    CONSTRAINT ck_track_status CHECK (status IN ('OFFLINE', 'LIVE', 'DIE')),
    CONSTRAINT ck_track_motion CHECK (motion IS NULL OR motion IN ('MOVING', 'STATIONARY', 'FROZEN')),
    CONSTRAINT ck_track_coord_system CHECK (coordinate_system IS NULL OR coordinate_system IN ('INDOOR', 'OUTDOOR')),
    CONSTRAINT ck_track_sample_reason CHECK (sample_reason BETWEEN 0 AND 3)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '轨迹采样（降采样后；分片游标取 id，客户端按 t_ms 自行排序）';

-- ---------------------------------------------------------------------------
-- 复盘导出任务（xlsx / csv）。文件本体走存储，本表只记任务状态与文件位置。
-- 「同一复盘同一格式的并发导出合并为同一 taskId」由服务层保证：先按
-- (replay_id, format) 查活跃任务，命中即返回。这里不加唯一索引——活跃态是
-- 会流转的状态（PENDING/RUNNING → SUCCEEDED），用生成列硬约束反而会在
-- 「已完成后再导出一次」时与大原则冲突，得不偿失。
-- ---------------------------------------------------------------------------
CREATE TABLE t_export_task (
    id             CHAR(36)     NOT NULL COMMENT 'UUIDv7；对外即 taskId',
    replay_id      CHAR(36)     NOT NULL COMMENT '即 t_exercise.id',
    format         VARCHAR(8)   NOT NULL COMMENT 'XLSX / CSV',
    state          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '契约 AsyncTaskState',
    progress_pct   TINYINT      NOT NULL DEFAULT 0 COMMENT '0~100',
    file_name      VARCHAR(255)     NULL,
    file_path      VARCHAR(1024)    NULL COMMENT '存储侧相对路径，不对外暴露；下载走一次性签名 URL',
    error_message  VARCHAR(255)     NULL,
    expire_at      DATETIME(3)      NULL COMMENT '文件过期时刻，过期需重新导出',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_export_task_replay (replay_id, format, state),
    KEY ix_export_task_state (state, created_at),
    CONSTRAINT ck_export_task_format CHECK (format IN ('XLSX', 'CSV')),
    CONSTRAINT ck_export_task_state CHECK (state IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_export_task_progress CHECK (progress_pct BETWEEN 0 AND 100)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '复盘导出任务';
