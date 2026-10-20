-- ============================================================================
-- V2 —— 底图与 UWB 标定
--
-- 与旧库的对应关系：
--   t_map        → t_map        （type 由 int 0/1 改为契约枚举 OUTDOOR/INDOOR）
--   t_map_floor  → t_map_floor  （points 结构冻结）
--   （新）        → t_anchor     （旧系统把基站坐标塞在 t_map_floor.uwb_meta 里，
--                                 新契约把基站拆成独立台账资源 /devices/anchors）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 底图。一张图一份图片（旧库是一层一份图片，新契约收敛为 map 级单图 + floorNum 区分层。
-- 这一点如果与实际场地不符，需要回头改契约，见下方 floor 表注释）。
-- ---------------------------------------------------------------------------
CREATE TABLE t_map (
    id           CHAR(36)      NOT NULL COMMENT 'UUIDv7',
    name         VARCHAR(128)  NOT NULL,
    map_type     VARCHAR(16)   NOT NULL COMMENT 'OUTDOOR=旧 type 0 / INDOOR=旧 type 1；旧 type 2(奥维)、4(离线) 随业务范围收缩一并废弃',
    image_path   VARCHAR(1024)     NULL COMMENT '底图图片相对路径（旧 t_map.path）',
    image_width  INT               NULL COMMENT '图片像素宽',
    image_height INT               NULL COMMENT '图片像素高',
    remark       VARCHAR(255)      NULL,
    -- UWB 标定快照：结构逐字节冻结，对应契约 UwbMeta
    --   { "bs1":{"xPx":..,"yPx":..}, ... "bs8":{..}|null, "realLengthCm":int, "realWidthCm":int }
    -- 现场调优沉淀的长宽基准就存在这里，解算链路只读本列。
    uwb_meta     TEXT              NULL COMMENT 'UWB 基站标定 JSON（冻结结构，见列注释上方说明）',
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_map_type (map_type),
    CONSTRAINT ck_map_type CHECK (map_type IN ('OUTDOOR', 'INDOOR'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '底图';

-- ---------------------------------------------------------------------------
-- 楼层点位表。契约没有独立的「楼层实体」，楼层只由 floorNum 标识，
-- 点位数组结构冻结为 [{posNum, xPct, yPct, label}]。
-- ---------------------------------------------------------------------------
CREATE TABLE t_map_floor (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    map_id     CHAR(36)    NOT NULL,
    floor_num  INT         NOT NULL COMMENT '楼层号，来自 ff03 长版上报的 floorNum',
    points     JSON            NULL COMMENT '点位表 [{posNum,xPct,yPct,label}]，结构冻结',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_map_floor (map_id, floor_num)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '楼层点位表';

-- ---------------------------------------------------------------------------
-- UWB 基站台账（契约 Anchor，接口 /api/v1/devices/anchors）。
-- ⚠ 坐标存在双源：本表 x_px/y_px 与 t_map.uwb_meta 里的 bsN。
--   已定的优先级：**解算只读 t_map.uwb_meta**；本表是设备台账（名称、启用开关），
--   其坐标用于「未绑定底图时的展示与初值」。PUT /devices/anchors 由服务层负责
--   同步刷新当前在用底图的 uwb_meta，避免两处各自漂移。
-- ---------------------------------------------------------------------------
CREATE TABLE t_anchor (
    anchor_id  TINYINT     NOT NULL COMMENT '1~8，与 41B 帧 ranges[i] 的 i+1 严格一一对应',
    name       VARCHAR(64)     NULL,
    x_px       DOUBLE          NULL COMMENT '底图像素横坐标（归一化 0~1）',
    y_px       DOUBLE          NULL COMMENT '底图像素纵坐标（归一化 0~1）',
    enabled    TINYINT     NOT NULL DEFAULT 1 COMMENT 'bs5~bs8 可关；bs1~bs4 为矩形四角，必开',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (anchor_id),
    CONSTRAINT ck_anchor_id CHECK (anchor_id BETWEEN 1 AND 8)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = 'UWB 基站台账';
