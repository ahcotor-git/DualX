# 设计 01 —— 演习存活自愈（僵尸演习）

> 状态：**待评审**　|　前置依赖：无　|　涉及 DDL：V8
> 关联：本文档只解决「进程异常退出后 `t_exercise` 里的进行中场次永久阻塞新演习」这一个问题，不涉及协议与解算。

---

## 1. 问题

### 1.1 现象

`t_exercise` 用生成列 + 唯一索引强制「同一时刻只有一场演习进行中」：

```sql
running_flag  TINYINT GENERATED ALWAYS AS (IF(ended_at IS NULL, 1, NULL)) STORED,
UNIQUE KEY uk_exercise_running (running_flag),
```

这条设计本身是对的（见 [V4__exercise.sql](../api/../db/migration/V4__exercise.sql) 文件头注释：不能只靠应用层「先查再插」，中间有竞态窗口）。但它把「进行中」的判定**完全绑定在 `ended_at IS NULL` 上**，而 `ended_at` 只由正常停演路径写入。

于是：进程崩溃 / 断电 / 现场直接拔电源后，`ended_at` 永远为 NULL，库里留下一行永不结束的「进行中」演习。

### 1.2 后果（为什么必须在阶段 4 前解决）

- **新演习彻底开不了**：`startExercise` 必然撞唯一索引 → 只能返回 409 `EXERCISE_ALREADY_RUNNING`。
- **自我恢复无路径**：`DELETE /api/v1/exercise/current`（停演）在应用启动后其实可以把它关掉——但前提是有人知道要去点一下，且 `getCurrentExercise` 返回的 `running=true` 看起来像「真有一场在跑」，现场操作员不会去停一场不存在的演习。
- **没有任何可判据**：表里没有「谁在跑这一场」和「最后一次活着是什么时候」的信息，恢复逻辑无法区分「正常进行中」与「僵尸」。

> 结论：这不是健壮性优化，是**功能性缺陷**。它在生产的第一次非正常退出时就会暴露，且只能靠手工改库绕过。

---

## 2. 现状事实（已核对）

| 事实 | 出处 |
|---|---|
| `t_exercise` 无 `owner_instance`、无 `heartbeat_at`、无 `end_reason` | [V4__exercise.sql](../../scds-core/src/main/resources/db/migration/V4__exercise.sql#L30-L54) |
| 唯一索引为 `uk_exercise_running(running_flag)`，不含任何实例维度 | 同上 |
| 结局统计 `roster_count` / `hit_count` / `dead_count` 约定「停演时冻结」 | 同上 L38-L42 |
| 指令队列状态含 `EXPIRED`，且有 `fail_reason` 列 | 同上 L116-L135 |
| 指令队列注释明确要求「进程重启不能让挂起的指令凭空消失」 | 同上 L113-L114 |
| 契约中停演返回 `{exerciseId, endedAt, replayId}`，并声明「落库失败不影响停演本身」 | [exercise.yaml](../../docs/api/paths/exercise.yaml#L361-L395) |
| 契约中 `replayId` 即 `exerciseId`（同一实体的两个视图） | 同上 + V4 文件头 |

---

## 3. 设计目标与非目标

**目标**

1. 进程启动后，系统能**自动**识别僵尸场次并使其不再阻塞新演习。
2. 僵尸场次**不能凭空消失**：它的轨迹、命中、日志要保留，且要能在复盘列表里被认出「这是异常中断的一场」。
3. 保持「同一时刻只有一场演习」这条硬约束的库层强制性不变。
4. 不引入新的中间件、不依赖数据库会话级锁。

**非目标**

- **不做「热接管」**（重启后继续跑同一场）。理由见 §4.1。
- 不做多节点/主从选举。已定「每客户独立实例」，边缘只有一个进程。
- 不改 `ended_at` 的语义（NULL 仍严格表示「进行中」）。

---

## 4. 方案

### 4.1 为什么不接管

有人会想：重启后读回内存态继续跑不就行了？**不行**，因为运行态无法恢复：

- 态势模型（每个单元的实时坐标、HP、弹药、`motion`）只在内存里，`t_track_sample` 是高频抽样轨迹，不是可回放的权威状态。
- 链路状态（串口 fd、TCP 12001 监听、UWB 标定上下文）随进程消失，且不会自动重连。
- 挂起中的 `PENDING`/`SENT` 指令已失去「该马甲是否收到」的判定依据。
- 最关键：**现场的物理状态已经前进**（人还在跑、马甲还在发帧），而服务端落后了一整段时间。补不回来。

所以正确动作是**明确结束**这一场，把它冻结成一条可回放的复盘记录。这也是唯一能保持「数据不说谎」的选择。

### 4.2 新增列（V8）

```sql
ALTER TABLE t_exercise
    ADD COLUMN owner_instance VARCHAR(64) NULL
        COMMENT '持有本场运行权的进程实例标识；NULL 表示历史行或创建时未记录',
    ADD COLUMN heartbeat_at   DATETIME(3) NULL
        COMMENT '该实例最后一次心跳（UTC）；活跃性判定依据，与唯一索引的互斥性互补',
    ADD COLUMN end_reason     VARCHAR(24) NULL
        COMMENT 'NORMAL 正常停演 / CRASH_RECOVERED 异常中断后由自愈收尾',
    ADD CONSTRAINT ck_exercise_end_reason
        CHECK (end_reason IS NULL OR end_reason IN ('NORMAL', 'CRASH_RECOVERED'));

-- 自愈扫描走这条：只关心「未结束」的行，量极小
CREATE INDEX ix_exercise_running_owner ON t_exercise (ended_at, owner_instance);
```

- 三列皆可 NULL，**不影响任何既有行**；若当前库里存在进行中的行，其 `owner_instance`/`heartbeat_at` 为 NULL，会被 §4.4 的规则判为僵尸。
- 列注释里明确区分两个正交概念，避免后来者误以为唯一索引可以替代心跳：
  - **互斥性**（同一时刻只有一场）→ 由 `uk_exercise_running` 硬保证。
  - **活跃性**（这一场是否还有活着的进程在跑）→ 由 `heartbeat_at` 判断。

### 4.3 实例标识与心跳

- `instanceId`：进程启动时生成一次，存内存。默认取 `UUIDv7`；**允许配置覆盖**（`scds.instance-id`），便于现场把同一台机器上「同一次部署」视为同一实例做诊断。
- 心跳：`@Scheduled(fixedDelay = 2000)` 的任务，仅当本进程持有进行中场次时执行：

```sql
UPDATE t_exercise
   SET heartbeat_at = ?
 WHERE id = ?
   AND ended_at IS NULL
   AND owner_instance = ?
```

  - `owner_instance = ?` 条件不可省：防止并发/异常场景下「已被别人收尾的场次」被本方心跳重新刷活。
  - 无演习进行中时**不写库、不持连接**（避免空转产生无意义 IO）。
- 心跳周期建议 2000ms，与马甲在线判定（5000ms）同量级便于现场统一理解；两者数值不必相同，也不要复用同一个配置项（一个是硬件在线、一个是进程在线）。

### 4.4 启动自愈

**时机**：`ApplicationReadyEvent` 之后、开始接受外部调用之前完成。用 `ApplicationRunner` + `@Order` 保证先于任何 `startExercise` 路径执行；同时对 `startExercise` 的唯一索引冲突做兜底（本来就返回 409，不会静默）。

**规则**（对 `SELECT ... WHERE ended_at IS NULL` 的唯一一行）：

| 条件 | 判定 | 动作 |
|---|---|---|
| 无行 | 正常 | 什么都不做 |
| `owner_instance = 本实例` 且 `now - heartbeat_at <= graceMs` | 同实例快速重启 | 仍按僵尸处理（见 §4.1：无法接管） |
| `owner_instance` 不同 或 `heartbeat_at` 为 NULL 或已过期 | 僵尸 | 收尾 |

> 即：**只要启动时发现「进行中」的行，一律收尾**。`graceMs` 只用于日志措辞与告警分级，不改变动作——这与 §4.1 的结论一致，也避免了「有时候接管、有时候收尾」这种现场无法预期的行为。
> `graceMs` 建议默认 30000ms（> 心跳周期 × 10）。

**收尾动作**（必须与正常停演共用同一段代码，避免两套逻辑漂移）：

1. 复用 `freezeAndClose(exerciseId, EndReason.CRASH_RECOVERED)`：
   - 从 `t_exercise_unit` / `t_hit_event` 聚合冻结 `roster_count` / `hit_count` / `dead_count`；
   - 计算每行 `t_exercise_unit.final_status`；
   - 写 `ended_at = now(UTC)`、`end_reason`。
2. 把该场次残留的 `PENDING` / `SENT` 指令置为 `EXPIRED`，`fail_reason = 'INSTANCE_RESTART'`，`finished_at = now()`。
   —— 注意是**标记而非删除**，遵守 V4 L113-L114 的约定。
3. 向 `t_exercise_log` 追加一条系统日志：`log_type = 'START_BATTLE'`？**否**。现有 `ck_exercise_log_type` 只允许 `START_BATTLE/SHOT/DIE/LIVE/INJURE/RELOAD`，**没有容纳系统事件的类型**。这里需要决策，见 §5 待裁定 Q3。
4. 打一条 WARN 日志，带 `exerciseId`、`owner_instance`、`heartbeat_at`、`ended_at`，便于现场追溯中断时刻。

### 4.5 第二个入口（运行期心跳失效）

启动自愈只覆盖「启动时」。若进程一直活着但心跳线程死了（例如线程池被占满），会出现「这一场还在跑、但心跳已过期」的情形。此时**不应**由别的逻辑去抢收尾，否则会把真在跑的一场误停。

处理：心跳任务自身失败时记 ERROR 并递增一个可观测指标（见 05 可观测性议题），**不做自动收尾**。理由：真在跑的演习被误停的代价，远大于僵尸多存活一段时间。

---

## 5. 落地清单

| 层 | 改动 |
|---|---|
| `scds-core` 迁移 | 新增 `V8__exercise_liveness.sql`（§4.2 的 DDL） |
| `scds-core` 领域 | `EndReason` 枚举（`NORMAL` / `CRASH_RECOVERED`）→ 置于 `com.dualx.core.domain`（与 `EndReason` 同类的 `MotionState` 等已在该包） |
| `scds-core` 仓储 | `ExerciseRepository`：新增 `findRunning()`、`updateHeartbeat(id, instanceId, at)`、`closeRunning(id, endedAt, reason, stats)`；`ExerciseCommandRepository`：`expirePending(exerciseId, reason, at)` |
| `scds-core` 服务 | `ExerciseLifecycleService`（承载 `startExercise` / `stopExercise` / `freezeAndClose` 的共用地基） |
| `scds-core` 启动 | `ExerciseLivenessRecover`（`ApplicationRunner`，`@Order(10)`）+ `ExerciseHeartbeatTask`（`@Scheduled`） |
| `scds-app` | 打开 `@EnableScheduling`；新增 `scds.instance-id`、`scds.exercise.liveness.grace-ms` 配置 |
| 契约 | 可选：`Exercise` schema 暴露 `endReason`（见 Q2） |

---

## 6. 待裁定

- **Q1｜僵尸场次是否出现在复盘列表？**
  推荐：**出现**，并在列表按 `endReason` 可区分（前端可打「异常中断」标签）。
  理由：现场数据宝贵，且「异常中断」本身是重要的现场事实。

- **Q2｜`endReason` 是否进契约？**
  推荐：**进**，加到 `Exercise` 与停演响应。否则前端无法把「异常中断」这件事告诉操作员。

- **Q3｜系统事件往哪写？**
  现状：`t_exercise_log.log_type` 的 CHECK 只有 6 个业务类型，装不下「系统收尾」。
  三个选项：
  - (a) 扩展 CHECK 增加 `SYSTEM`（**推荐**）：一处小改，日志面板天然能显示。
  - (b) 不写日志，只写 WARN 应用日志：库内无痕，现场排障只能翻应用日志。
  - (c) 写 `t_audit_log`：语义是「人的动作」，系统自身的收尾塞进去会让审计表含义变浑。
  若取 (a)，`LogType` 枚举（`scds-protocol`）是否也要加 `SYSTEM`？→ **不动协议**：`LogType` 是协议枚举，加值会影响协议层语义；改由契约层（`schemas/common.yaml#/LogType`）与领域层承载。此项需与契约负责人确认。

- **Q4｜`graceMs` 取值**
  推荐 30000ms（> 心跳 ×10）。仅影响日志措辞，不影响动作。

---

## 7. 验收判据

1. 手工制造僵尸（`UPDATE t_exercise SET ended_at = NULL WHERE id = 'x'`，并保证 `owner_instance`/`heartbeat_at` 为 NULL）→ 重启应用 → 该行 `ended_at` 被填、`end_reason = 'CRASH_RECOVERED'`、统计三项已冻结，且**新演习能正常开始**。
2. 正常开演 → 正常停演 → `end_reason = 'NORMAL'`，心跳列停止更新。
3. 正常开演 → 运行中（心跳每 2s 递增）→ 断言心跳列在 10s 内至少变化 3 次。
4. 开演后杀进程 → 重启 → 僵尸被收尾，且 `t_exercise_command` 中原 `PENDING`/`SENT` 行变为 `EXPIRED`（**不是被删除**）。
5. 唯一索引仍生效：并发两次 `startExercise` 只允许一个成功。
