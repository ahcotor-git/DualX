-- ============================================================================
-- V10 —— 演习存活自愈（僵尸演习）
--
-- 问题：t_exercise 用生成列 running_flag + 唯一索引 uk_exercise_running 强制
-- 「同一时刻只有一场演习进行中」，而「进行中」完全绑定在 `ended_at IS NULL` 上，
-- 而 ended_at 只由正常停演路径写入。进程崩溃 / 断电 / 直接拔电后 ended_at 永远为 NULL，
-- 库里留下永不结束的一行，之后 startExercise 必然撞唯一索引 → 现场只能手工改库绕过。
--
-- 本迁移只补齐「判定僵尸所需的证据」，不含任何业务逻辑：
--   owner_instance  谁在跑这一场
--   heartbeat_at    那个进程最后一次活着是什么时候
--   end_reason      这一场是正常停演结束的，还是异常中断后被自愈收尾的
--
-- ⚠ 互斥性与活跃性是**两个正交概念**，不要以为唯一索引能取代心跳：
--   互斥性（同一时刻只有一场）→ uk_exercise_running 硬保证；
--   活跃性（这一场是否还有活着的进程在跑）→ 只能靠 heartbeat_at 判断。
--
-- 版本号说明：原设计预留号为 V8，而 V9 已应用、V8 从未创建。直接补 V8 会成为低于
-- 当前版本的未应用迁移并受 Flyway 校验/顺序限制，故取**下一递增版本 V10**；
-- 不改任何历史迁移，也不开启 out-of-order。见 docs/design/01-exercise-liveness.md。
-- ============================================================================

ALTER TABLE t_exercise
    ADD COLUMN owner_instance VARCHAR(64) NULL
        COMMENT '持有本场运行权的进程实例标识；NULL 表示历史行或创建时未记录',
    ADD COLUMN heartbeat_at   DATETIME(3) NULL
        COMMENT '该实例最后一次心跳（UTC）；活跃性判定依据，与唯一索引的互斥性互补',
    ADD COLUMN end_reason     VARCHAR(24) NULL
        COMMENT 'NORMAL 正常停演 / CRASH_RECOVERED 异常中断后由自愈收尾；进行中为 NULL',
    ADD CONSTRAINT ck_exercise_end_reason
        CHECK (end_reason IS NULL OR end_reason IN ('NORMAL', 'CRASH_RECOVERED'));

-- 自愈扫描只关心「未结束」的行（同库至多一行，量极小），带上 owner_instance 便于
-- 按实例追溯「这一场是谁留下的」。这条索引不参与互斥判定，互斥仍由 uk_exercise_running 负责。
CREATE INDEX ix_exercise_running_owner ON t_exercise (ended_at, owner_instance);

-- ---------------------------------------------------------------------------
-- 系统事件不能借用协议帧类型。
--
-- 自愈在删除残留指令前，必须先把证据（指令 ID、类型、马甲、原状态、下行原文、原因）
-- 写成一条 SYSTEM 审计日志，再按场次范围删除残留指令；而原 CHECK 只允许 6 个协议值，
-- 写 SYSTEM 会直接违反约束。
--
-- 冻结边界：`scds-protocol.LogType` 是**带 code 0~5 的协议帧类型**，不对应任何帧的
-- SYSTEM 不得加进去，协议字节与解算结果一律不动。这里扩展的是「日志条目类型」这一层。
-- ---------------------------------------------------------------------------
ALTER TABLE t_exercise_log
    DROP CHECK ck_exercise_log_type;

ALTER TABLE t_exercise_log
    ADD CONSTRAINT ck_exercise_log_type CHECK (log_type IN
        ('START_BATTLE', 'SHOT', 'DIE', 'LIVE', 'INJURE', 'RELOAD', 'SYSTEM'));
