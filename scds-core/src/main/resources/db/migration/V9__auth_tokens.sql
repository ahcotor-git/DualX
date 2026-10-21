-- ============================================================================
-- V9 —— 鉴权令牌（设计 03）
--
-- 本文件只做两件事：给 refreshToken 一个可撤销的落点，给设备令牌一个单端在线的落点。
-- **刻意不种任何账号**：初始管理员由部署侧写入 .env（SCDS_ADMIN_USERNAME /
-- SCDS_ADMIN_PASSWORD），应用首次启动时用 BCrypt 建号。在迁移文件里写死初始口令
-- 等于所有客户共用同一把钥匙；而写死一个散列也不行——散列同样可被拿去撞库，
-- 且运维无法在不改迁移文件的前提下换口令。
--
-- 版本号说明：设计文档已把 V8 分配给「演习心跳」（design/01），本文件是 V9（design/03）。
-- 实现顺序不必与版本号同序。若在 V9 已应用之后再补 V8，Flyway 默认会因「乱序」
-- 拒绝启动——现场库每次部署新建、本机开发库为空库，重建即可，不需要开启
-- out-of-order（那会把真正的乱序问题一起放进来）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 账号刷新令牌。契约要求 refreshToken 可撤销，而「可撤销」与「无状态」互斥：
-- 一旦要能查、能作废，就必须有地方存，JWT 自身做不到这一点。
--
-- 只存 token_hash 而非令牌原文：库被整份拖走时也无法直接冒用。用 SHA-256 而非
-- BCrypt——token 是高熵随机串、不是人设的口令，不需要抗暴力破解的慢哈希，
-- 而校验路径在刷新时会频繁走，慢哈希会白白拖慢它。
--
-- replaced_by 支撑「滚动刷新」的链路追溯：每次 refresh 都把旧行置 revoked_at 并
-- 指向新行，于是同一账号的令牌形成一条链。收到一个「已被撤销过」的令牌即可判定
-- 为被盗后重放，进而按 account_id 撤销整条链（RFC 6819 的标准做法）。
-- ---------------------------------------------------------------------------
CREATE TABLE t_refresh_token (
    id          CHAR(36)    NOT NULL COMMENT 'UUIDv7，即 JWT 的 jti',
    account_id  CHAR(36)    NOT NULL COMMENT '账号主键（t_account.id）',
    token_hash  CHAR(64)    NOT NULL COMMENT 'SHA-256(令牌原文) 的十六进制；绝不存原文',
    issued_at   DATETIME(3) NOT NULL COMMENT '签发时刻（UTC）',
    expires_at  DATETIME(3) NOT NULL COMMENT '过期时刻（UTC），扫描清理按此列',
    revoked_at  DATETIME(3)     NULL COMMENT '被撤销时刻；NULL 表示仍有效',
    replaced_by CHAR(36)        NULL COMMENT '滚动刷新时指向新令牌 id，用于链路追溯',
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    KEY ix_refresh_account (account_id, expires_at),
    CONSTRAINT ck_refresh_token_expires CHECK (expires_at > issued_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '账号刷新令牌（可撤销）';

-- ---------------------------------------------------------------------------
-- 设备令牌单端在线。契约要求「同一马甲重复登录时旧令牌失效」，本设计不新建会话表，
-- 而是在台账上加一列：
--
--   deviceLogin 成功 → 生成新的 sid 写入本列，令牌带 sid claim；
--   校验设备令牌    → 比对 claim.sid 与库中该列，不等即失效。
--
-- 于是「重复登录顶掉旧令牌」是天然结果，不需要定时任务、不存在会话表与台账不一致的
-- 中间态（旧系统的 t_com 正是把运行态与配置放同一行，进程重启后库里留着假象）。
--
-- device_session_sid 置 NULL 表示该设备当前无有效会话（登出 / 换绑 / 退役）。
-- 查询恒以 vest_num（主键）定位，故不额外建索引。
-- ---------------------------------------------------------------------------
ALTER TABLE t_vest
    ADD COLUMN device_session_sid CHAR(36)    NULL COMMENT '当前有效的设备令牌会话 ID；登出/换绑置 NULL',
    ADD COLUMN device_login_at    DATETIME(3) NULL COMMENT '最后一次设备登录时刻（UTC）';
