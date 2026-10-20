-- ============================================================================
-- V6 —— 设备台账与链路配置
--
-- 与旧库的对应关系：
--   （新）  → t_vest         （旧系统没有设备台账：马甲只存在于想定编成里，
--                              换一场演习就丢，SIM 号/固件版本无处沉淀）
--   t_com  → t_link_config  （旧表一行代表一条串口，且把「配置」与「运行态」
--                             混在一起；新表只留配置，运行态见下方说明）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 马甲设备台账。以 vest_num 为自然主键——它是协议里的 uint16 编号，全系统
-- （编成、轨迹、命中、态势）都用它标识设备，再插一个代理主键只会多一次映射。
--
-- bind_secret_hash：契约 DeviceLoginRequest 用「设备号 + 绑定密钥」登录单兵 App，
--   密钥必须可校验所以存 BCrypt 摘要（与 t_account.password_hash 同策略）。
--   若部署选择「以 SIM 号作密钥」，则本列为空、改校验 sim_no，由
--   deployment-mode 配置决定，两者不并存。
--
-- enabled 与 bound 是两件事，不可合并：
--   enabled=0 → 这台设备在演习中根本不算数（报修/退役），态势里直接忽略；
--   bound=0   → 设备好的，只是还没被某台单兵终端 App 认领。
-- ---------------------------------------------------------------------------
CREATE TABLE t_vest (
    vest_num         INT          NOT NULL COMMENT '协议 uint16 编号，1~1800 单兵 / 1801~4800 重武器载具 / 65533~65535 危险物',
    name             VARCHAR(64)      NULL COMMENT '人员姓名（可选，编成里可覆盖）',
    team             VARCHAR(16)      NULL COMMENT '契约 Team 枚举；未编入想定时为空',
    sim_no           VARCHAR(32)      NULL COMMENT 'SIM 号，来自 ff0a（18B）上报',
    model            VARCHAR(64)      NULL,
    firmware_version VARCHAR(32)      NULL,
    bind_secret_hash VARCHAR(100)     NULL COMMENT '单兵 App 绑定密钥的 BCrypt 摘要；用 SIM 号作密钥时为空',
    bound            TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已被某个单兵终端 App 认领',
    enabled          TINYINT      NOT NULL DEFAULT 1 COMMENT '0=报修/退役，演习中忽略该设备',
    last_seen_at     DATETIME(3)      NULL COMMENT '最后一次收到该马甲的合法帧时刻（UTC）',
    remark           VARCHAR(255)     NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (vest_num),
    KEY ix_vest_team (team),
    KEY ix_vest_sim_no (sim_no),
    KEY ix_vest_last_seen (last_seen_at),
    CONSTRAINT ck_vest_num CHECK (vest_num BETWEEN 1 AND 65532),
    CONSTRAINT ck_vest_team CHECK (team IS NULL OR team IN ('RED', 'BLUE', 'ORANGE', 'YELLOW'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '马甲设备台账';

-- ---------------------------------------------------------------------------
-- 链路配置。按 link_kind 一行，最多三行：
--   SERIAL_VEST     马甲串口（协议 A，文本 HEX/ASCII 帧）
--   UWB_TCP_SERVER  UWB 走 TCP，服务端监听（**端口硬约束 12001**，改不得）
--   UWB_SERIAL      UWB 走串口（与 UWB_TCP_SERVER 二选一）
--
-- ⚠ 只存「配置」，不存「运行态」。旧 t_com 把 status / msg 与端口号同放一行，
--   结果是进程重启后库里留着一份「上次是打开状态」的假象，而实际链路是关的。
--   契约的 LinkStatus（state / fdCount / framesTotal / droppedTotal / crcErrorTotal
--   / lastFrameAt）因此**完全不落库**，由运行中的链路对象实时提供——它描述的是
--   进程内状态，落库即失真。enabled 是唯一与运行有关的列，含义仅为「开机自动打开」。
-- ---------------------------------------------------------------------------
CREATE TABLE t_link_config (
    link_kind                 VARCHAR(24)  NOT NULL COMMENT '契约 LinkKind 枚举',
    serial_port_name          VARCHAR(64)      NULL COMMENT '如 COM7；串口链路必填',
    serial_baud_rate          INT              NULL COMMENT '串口链路必填，如 115200',
    serial_flow_control_rts_cts TINYINT      NOT NULL DEFAULT 0 COMMENT '硬件流控开关',
    uwb_bind_host             VARCHAR(64)      NULL COMMENT '仅 UWB_TCP_SERVER：监听地址',
    uwb_bind_port             INT              NULL COMMENT '仅 UWB_TCP_SERVER：监听端口，硬约束 12001',
    enabled                   TINYINT      NOT NULL DEFAULT 0 COMMENT '开机自动打开；与链路当前是否打开无关',
    remark                    VARCHAR(255)     NULL,
    created_at                DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (link_kind),
    CONSTRAINT ck_link_kind CHECK (link_kind IN ('SERIAL_VEST', 'UWB_TCP_SERVER', 'UWB_SERIAL'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '链路配置（仅配置，不含运行态）';
