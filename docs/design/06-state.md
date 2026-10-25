# 设计 06 —— 态势状态所有者（STATE 分段方案）

> 编写日期：2026-10-24，Asia/Shanghai。本文是 **STATE 开工前的分段方案**，不是已实现证明。
> 协议字节以 [PROTOCOL.md](../PROTOCOL.md) 为唯一权威；契约以 [api/openapi.yaml](../api/openapi.yaml) 为准。
> 本文**不修改**任何冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
> **在线窗口（5000ms）** 或校验算法。
> 前置：HW-LINK（D06/D10）已收口；D11 中与 STATE 无关的部分留给 LIFE / COMMAND。

---

## 1. 问题

### 1.1 上行帧没有业务消费者（HW-LINK 遗留边界）

[05-hw-link.md](05-hw-link.md) §4.7 已明示：`HardwareEventSink` 的实现在 STATE，未实现前
app 装配的是显式日志型 sink（[LoggingHardwareEventSink](../../scds-app/src/main/java/com/dualx/config/LoggingHardwareEventSink.java)），
上行帧只打 WARN。即**「网关能解帧」≠「态势可用」**。

### 1.2 四个态势端点全为 501

[SituationController](../../scds-api/src/main/java/com/dualx/api/situation/SituationController.java) 的
`getSituation` / `getVestState` / `getTeamSummary` / `listHits` 全部抛 `NotImplementedException`；
[DeviceController](../../scds-api/src/main/java/com/dualx/api/device/DeviceController.java) 的
`getVestLink` 仍是 501（H6 明示：其权威来源是 core 状态机，不是链路级状态）。

### 1.3 没有单一状态所有者

在线判定、阵亡冻结、命中记录、轨迹采样四处都没有落点；每处各写一遍必然分叉。
本工作包的全部意义是**把它们收进同一个所有者**，让 HTTP 查询与后续 WS 推送读到同一份真源。

---

## 2. 现状事实（已核对）

| 项 | 事实 |
|---|---|
| 上行端口 | `HardwareEventSink` 已定义（`onSerialFrame` / `onUwbFrame` / `onLinkStateChanged`），**无实现**；约定「被读线程直接调用，阻塞操作须自行切有界队列」 |
| 网关行为 | 网关是哑管道：照常上报 `ff06`，由 core 在 UWB 生效时丢弃（协议 §1.4④）；`snapshot()` 的链路计数器是**链路级**，不得冒充每马甲计数 |
| 协议上行 | `SerialFrame` = `Heartbeat(vestNum,ammo1,hp,ammo2,floorNum,posNum)` / `Gps` / `Hit(hitVestNum,shooterNum,hitPart)` / `IndoorLocation` / `Mortar` / `SimNo`；`UwbFrame` = `vestNum` + 8 路测距 |
| 解算 | `UwbSolver.solve2D(vest, meta, ranges, px, py, ...)` → `Solve2DResult`；参数走 `ScdsUwbProperties`。解算在 core 侧，网关不做几何计算 |
| 在线窗口 | `HEARTBEAT_ONLINE_WINDOW_MS = 5000`（`ProtocolConstants`），契约 `UnitState.status` 同口径，**冻结** |
| 命中强度 | **已作废**（PROTOCOL.md §6.7）：`ff05` 不携带强度，故致死**不能**由命中帧直接算出 |
| 阵亡权威 | 马甲自报 `ff03.hp`；`判死` 指令是另一入口（属 COMMAND）。`VestStatus` = `OFFLINE/LIVE/DIE` |
| 领域枚举 | `MotionState`(MOVING/STATIONARY/FROZEN)、`PositionSource`(UWB/GPS/MANUAL/NONE)、`SampleReason`(TIMER/DISPLACEMENT/EVENT/STATE_CHANGE)、`ExerciseEndReason`(NORMAL/CRASH_RECOVERED)、`ExerciseLogType`(含 SYSTEM) 均已就绪 |
| 仓储 | `TrackSampleRepository.insertAll/countByExerciseId/deleteByExerciseId`；`HitEventRepository.insert/findByExerciseIdSince/countByShooter/countLethalByShooter`；`ExerciseUnitRepository.freezeOutcome(exerciseId,vestNum,finalStatus,deadAt,kills,hitsDealt)` / `countDeadByExerciseId` |
| 表 | `t_track_sample`(V5)、`t_hit_event`(V4)、`t_exercise_unit`(V4) 与所需列/索引**已存在**，本工作包**不需要新迁移** |
| 契约 | `getSituation`(sinceRevision / includeSnapshotOnFirst / hitsLimit) → `SituationEnvelope`；`getVestState` → `UnitState`；`getTeamSummary` → `TeamSummary[]`；`listHits`(sinceSeq/limit) → `{items,latestSeq}` |
| 缺省态口径 | D15-⑥ 无演习 → 200 + `running=false`；D15-⑦ 离线且从未定位 → `position=null`，**阵亡则冻结最后有效坐标且不解除冻结**；D15-⑧ 危险物只出现在 `shooterNum` |
| 收尾口径 | `ExerciseLifecycleService.freezeAndClose` 的阵亡数取 `countDeadByExerciseId`（**读库**），故单位终局必须在此之前落库 |
| 架构取向 | 「谁持有状态谁调 PushPort」；态势 revision **单写事件循环** + 环形窗口（[architecture.md](../architecture.md) §7） |
| 依赖方向 | `scds-api` → `scds-core` 直连（同 `ExerciseController` 用 `PlanService`）；`scds-gateway` 零 Spring 依赖，只认 domain 端口 |
| 线载荷位置 | `UnitState`/`CombatRole`/`SituationEnvelope`/`TeamSummary`/`HitEvent` 现住 `com.dualx.api.dto.situation`；[设计 02](02-push-ports.md) §4.3 已裁定下沉到 `com.dualx.core.push`，并明确否决「双模型 + 映射函数」乙案。STATE 只先下沉**状态所有者真正持有**的 `UnitState` + `CombatRole` |

---

## 3. 设计目标与非目标

**目标**

1. **单一状态所有者**：内存里只有一份态势态（每个参演单元一行），所有变更经它串行写入。
2. **有界事件处理**：上行帧只入有界队列即返回，**绝不阻塞网关读线程**；队列满按策略处理且可观测。
3. **在线边界**：距最后心跳 > 5000ms 判离线，产生**状态跳变**；判活/判死/命中同样走这条事件路径。
4. **阵亡冻结**：阵亡后坐标与 `motion=FROZEN` 冻结，判活才解除。
5. **命中与计数**：命中落 `t_hit_event`，维护 `kills` / `hitsDealt`（每马甲与每队）。
6. **批量轨迹**：四类 `sampleReason` 判定 + 批量落 `t_track_sample`。
7. **core 读侧**：`getSituation` / `getVestState` / `getTeamSummary` / `listHits` 的读模型与语义
   （全量/增量/超窗 409/无演习 `running=false`），HTTP 接线属 M4。

**非目标（本工作包不做）**

- 不做 `watchSituation`（WS）与 `SituationPushPort` / `LinkStatusPushPort`（属 PUSH-WS / PUSH-MODEL，依赖 D07/D08）。
- **不做 HTTP 接线**：`SituationController` 4 端点与 `DeviceController.getVestLink` 属 M4
  （[实施计划](../implementation-plan.md) M4「domain 载荷和推送 SPI、Poll 查询、链路同构」）。
- 不搬线载荷 `SituationEnvelope` / `TeamSummary` / `HitEvent`（M4 按 [设计 02](02-push-ports.md) 下沉）。
- 不做 `epoch`/跨代恢复（D08）：本包的 revision 环形窗口只保证**同代**增量。
- 不做 COMMAND（8 类指令的下行与回执状态机）——本包只**消费**指令造成的状态后果（判活解除冻结等由 COMMAND 下发、STATE 记账）。
- 不做 CALIBRATION（UWB 标定三端点）、REPLAY、EXPORT。
- 不改协议字节、端口、轮询周期、5000ms 窗口与解算参数。
- 不做容量结论（D10 未闭合，见 §4.7）。

---

## 4. 方案

### 4.1 单一所有者与线程模型（单消费者事件循环）

```
网关读线程 ── onSerialFrame/onUwbFrame ──► 有界队列（非阻塞 offer）
                                              │
                                    1 个工作线程（唯一写手）
                                              │
       ┌──────────────────────────────────────┼───────────────────────────────┐
       ▼                                      ▼                               ▼
  更新内存态（唯一真源）              判定在线/冻结/命中/采样        批量写库（轨迹/命中/单元终局）
       │
       └──► revision += 1（单写）→ 环形窗口
```

- **唯一写手**：所有状态变更都在这个线程上完成，因此内部不需要任何锁；查询侧读的是不可变快照。
- **队列有界**：`scds.state.event-queue-capacity`。溢出策略 `REJECT`（拒收并计数）/ `DROP_LOW_PRIORITY`（丢轮询类心跳的重复态）。
  **上行帧本身不带优先级**，故本包只区分「心跳的纯状态刷新」与「事件帧（命中/GPS/UWB 坐标）」：前者可丢，后者不可丢。
- **不引入虚拟线程**：D10 要求线程选型先实测；本包按平台线程实现，线程数与容量全部走配置。

### 4.2 在线判定与阵亡冻结（D15-⑦）

- **在线**：`now - lastHeartbeatAt <= 5000ms` → `LIVE`（OFLINE 判据来自协议 §5.1，冻结）。
- **扫描驱动**（本包裁定）：定时任务按 `scds.state.offline-scan-interval-ms`（默认 **1000ms**，必须 < 5000）
  在事件线程上扫描，越界即 `LIVE → OFFLINE`，并记一次 `STATE_CHANGE` 采样点。
  为什么不用读取时惰性判定：轨迹里必须留下**离散的离线时刻**，否则复盘只能反推。
- **阵亡**：`ff03.hp == 0` → `DIE`、`deadAt = now`、`motion = FROZEN`、坐标**冻结在最后有效值**。
  同时把 `frozenMotion`（冻结前的 motion）暂存，供判活还原。
- **判活**：由 COMMAND 下发后调用本包的状态入口 → 解除冻结、`motion` 还原为 `frozenMotion`（无则 `null`）、
  `deadAt = null`、`LIVE`（坐标不还原，等下一帧坐标到达）。
- **从未定位**：`position = null` 且 `positionSource = NONE`；阵亡冻结**不会**把它变成非空（D15-⑦ 只管「保留最后有效坐标」）。
- **`ff06` 丢弃**：UWB 生效时丢弃 `IndoorLocation`（协议 §1.4④）；判别开关取链路配置，不在本包新造开关。

### 4.3 命中与计数（§6.7 强度已作废）

- `ff05` 到达即落 `t_hit_event`：`tMs` 与 `t_track_sample.t_ms` 同原点；坐标冗余存被击中者当时坐标；
  `shooterIsSoldier = shooterNum <= 1800`；`shooterWeaponKind` 由编号区间映射；危险物（65533~65535）只出现在 `shooterNum`（D15-⑧）。
- **致死口径（本包自定，非裁定项）**：命中帧不携带强度，服务端的致死判定发生在**心跳**。
  因此 `lethal` 采用**回填**：命中先落库 `lethal=false`；该马甲在同一演习内转入 `DIE` 时，
  把它**最近一条** `lethal=false` 的命中回填为 `true`（同一事务）。
  > 原基线没有这张判定表；若现场另有定则，只改这一处，不动数据结构与其它逻辑。
- **计数**：`hitsDealt` = 该 shooter 名下的命中行数；`kills` = 其中 `lethal=true` 的行数
  （对应既有 `countByShooter` / `countLethalByShooter`）。队伍汇总按 `t_exercise_unit.team` 聚合。

### 4.4 轨迹采样与批量落库

| `sampleReason` | 触发 |
|---|---|
| `EVENT`(2) | 命中、阵亡、判活、判伤、充弹等离散事件点 |
| `STATE_CHANGE`(3) | `status` 或 `motion` 跳变（含离线扫描判出 `LIVE→OFFLINE`） |
| `DISPLACEMENT`(1) | 距上次采样位移超过 `scds.state.sample-displacement-*` |
| `TIMER`(0) | 距上次采样超过 `scds.state.sample-interval-ms` |

- **一次采样一行**，字段全部来自内存态；`tMs` = `now - startedAt`（UTC，同原点）。
- **批量**：先入内存缓冲，达到 `scds.state.track-batch-size` 或 `track-flush-interval-ms` 时由**事件线程**一次 `insertAll`。
  停演收尾时**必须 flush**，否则末段轨迹丢在缓冲里。
- 落库失败不回滚内存态（态势不能因为写库慢而停摆），记 ERROR + 计数；下次 flush 继续。

### 4.5 revision 与增量环形窗口（D07/D08 边界）

- `revision` 由事件线程**单写**，任何单元变化 `+1`；`UnitState.revision` 记录「该单元最后一次变化的 revision」。
- 环形窗口保留最近 `scds.situation.revision-window`（默认 **1024**，**沿用 [设计 02](02-push-ports.md) §5 的既有键**）条**单元级**变化。
- `getSituation`：
  - 不带 `sinceRevision` → 全量（`full=true`，`units` 有效）；
  - 带 `N` 且 `N` 在窗口内 → 增量（`full=false`，`changes` / `removedVestNums` / `hits`）；
  - 带 `N` 但**超出窗口** → 409 `SITUATION_REVISION_TOO_OLD`（`ApiErrorCode` 已存在），客户端改全量。
- **本包不做 `epoch`/跨代**：进程重启后 revision 从 1 重新开始，与 D08 的跨代恢复一起留给 PUSH-MODEL。
  这一点必须在实现与文档里写明，不得让客户端误以为跨重启可续传。
- 无演习进行中：`running=false`、`units` 空数组；`includeSnapshotOnFirst=false` 时仍返回一帧空快照（契约默认 `true`）。

### 4.6 与 LIFE 的衔接（不含指令状态机）

- **开演**：LIFE 的 `startExercise` 成功后调用 STATE 装载 —— 按 `exercise.planId()` 从 **`t_plan_roster`**（想定编成）
  读编成到内存态（初始 `OFFLINE`、`position=null`、`positionSource=NONE`、装载时 `revision` **归零**），
  记录 `startedAt` 作为 `tMs` 原点。
  > **S6 落地修正**：本节原写「从 `t_exercise_unit` 读编成」，但该表是终局台账，**没有**初始 `hp/ammo/mode` 三列，
  > 而装载要的正是带这三项的 `RosterEntry`（即 `t_plan_roster` 的领域记录）。两份编成在开演时同源
  > （演习快照由 `startExercise` 从想定编成而来），故改读想定编成，**不新造默认值**。
  > 另：`revision` 依 §4.5 在装载时**归零**，非「从当时值起算」。
- **停演**：在 LIFE 调 `freezeAndClose` **之前**，STATE 先对全体单位写一次终局
  （复用 `ExerciseUnitRepository.freezeOutcome`：存活写 `LIVE`、阵亡写 `DIE` + `dead_at` + 终值 `kills/hitsDealt`），
  **再 flush 轨迹缓冲**。顺序反了会让 `freezeAndClose` 读到 0 阵亡（它读的是库里的终局）。
  > **S6 落地时的实现口径**：flush 收进 `endExercise()`（即写终局**之前**）——flush 位置不影响任何读者
  > （`freezeAndClose` 读 `t_exercise_unit` + `t_hit_event`，**不读** `t_track_sample`），收进去可让
  > 「停演必 flush」不依赖调用方纪律；两者仍都在 `freezeAndClose` 之前完成。
- STATE **不感知**「流程/提交/补偿」这类事务语义（D11 属 LIFE / COMMAND），只提供幂等的装载/收尾入口。

### 4.7 配置项与仿真（D10 口径，未闭合声明）

统一收在 `GatewayProperties` 之外的**新前缀 `scds.state.*`**（本包新增，不动 `scds.gateway.*`）：

| 键（拟） | 默认 | 说明 |
|---|---|---|
| `scds.state.event-queue-capacity` | 待定 | 事件队列容量（D10 候选） |
| `scds.state.event-queue-overflow` | `REJECT` | `REJECT` / `DROP_LOW_PRIORITY` |
| `scds.state.offline-scan-interval-ms` | 1000 | 在线扫描周期，必须 < 5000 |
| `scds.state.sample-interval-ms` | 待定 | 定时采样周期 |
| `scds.state.sample-displacement-*` | 待定 | 位移阈值（室内像素 / 室外米各一） |
| `scds.state.track-batch-size` / `track-flush-interval-ms` | 待定 | 轨迹批量落库 |
| `scds.situation.revision-window` | 1024 | 增量环形窗口条数（**沿用 [设计 02](02-push-ports.md) 既有键，不用 `scds.state.*`**） |

> ⚠ **D10 未闭合声明**：上表数值是**设计候选**，不是容量结论。现场人数/基站/客户端/帧率/SLA 与
> RPO/RTO 未给出前，不得据本表宣称容量达标；§7 的容量项一律标注「待现场验证」。

**无硬件验证（本包裁定）**：**复用** `scds.gateway.mode=simulator`（HW-LINK 已交付的
[SimulatedLinkAdapter](../../scds-gateway/src/main/java/com/dualx/gateway/sim/SimulatedLinkAdapter.java)
可注入确定的帧字节），**不新增第二套开关**。仿真通过不得写成现场通过。

---

## 5. 落地清单（分段与文件白名单）

每段 ≤3 个修改文件，段间可独立验收。

| 段 | 内容 | 文件（白名单） |
|---|---|---|
| **S1a** | 载荷下沉（域侧）：`CombatRole`、`UnitState` 原样搬入 `com.dualx.core.push` | `scds-domain` 新增 `core/push/CombatRole`、`core/push/UnitState` |
| **S1b** | 载荷下沉（改引用 + 删旧）：两个引用方改指向 domain 新类型，同时删掉 api 旧 `UnitState` | `scds-api` 删 `dto/situation/UnitState`、改 `dto/situation/SituationEnvelope`、改 `dto/auth/DeviceSelf` |
| **S1c** | 载荷下沉（清理）：api 旧 `CombatRole` 已无引用，删除 | `scds-api` 删 `dto/situation/CombatRole` |
| **S2** | 有界事件队列 + 单消费者循环（纯 Java，可单测） | `scds-core` 新增 `StateEventQueue`、单测 |
| **S3** | 单一所有者核心：心跳/在线/冻结/命中/每马甲计数（注入时钟） | `scds-core` 新增 `SituationStateService`、`HitEventRepository` 增回填方法、单测 |
| **S4** | 采样判定 + 批量落库 | `scds-core` 新增 `TrackSampler`、`TrackBatchWriter`、单测 |
| **S5** | revision 环形窗口与增量窗口 | `scds-core` 新增 `SituationRevisionLog`、单测 |
| **S4b**（实际执行时补段） | S4/S5 接线：把采样、批量落库、环形窗口接入状态变化点与装载/收尾入口 | `scds-core` 改 `SituationStateService`、其单测 |
| **S6**（已落地） | 与 LIFE 衔接：装载编成、停演终局（顺序见 §4.6） | `scds-core` 新增 `SituationSessionBinder`、单测 |
| **S7**（已落地） | core 读侧 `SituationQuery`：全量/增量/单马甲/队伍汇总/命中列表 + 超窗语义 | `scds-core` 新增 `SituationQuery`、单测 |
| **S8**（已落地） | 端到端 IT（仿真注入帧 → 读侧断言） | `scds-app` 新增 `SituationStateIT` |

> **本包不接 HTTP/WS**：`SituationController` 4 端点、`DeviceController.getVestLink`、WS 通道，
> 以及 `SituationEnvelope` / `TeamSummary` / `HitEvent` 的线载荷下沉，均属 **M4 PUSH-MODEL / PUSH-WS**
> （[设计 02](02-push-ports.md)）。本包交付到 **core 读侧**为止，S7/S8 的判据在 core 层证明。
> 这是与 [设计 02](02-push-ports.md) §4.3 的口径收口：不新造 `SituationSnapshot`/`UnitSituation`/`SituationDelta`，
> 也不把 `SituationQuery` 做成 domain 端口——两套并行会留下重复类型与一次多余搬迁。

**S7 的一处必须记明**：`getVestLink` 的字段跨了两层来源 ——
`online` / `lastHeartbeatAt` / `lastFrameType` / 每马甲 `framesTotal` 属 **STATE**（本包，core 读侧提供），
而 `DeviceController` 现有的 6 条链路端点的计数属**链路级**（HW-LINK）；HTTP 接线在 M4 完成。
两处**不得互相冒充**：链路 `framesTotal` 不因某马甲多发帧而变，每马甲 `framesTotal` 也不因链路重开而清零（除停演重置）。

---

## 6. 待裁定

| 项 | 说明 |
|---|---|
| D07 链路载荷 | `watchLinks` 的端口/链路快照同构口径（数组 vs 对象），属 PUSH-MODEL，本包不做 |
| D08 跨代 | `epoch` 与跨重启续传：本包 revision 只保证同代增量 |
| D10 容量 | 队列容量、批量大小、采样周期与位移阈值需现场数据；本包只落可配置项并标注未验证 |
| 命中致死口径 | §4.3 的**回填**是本包自定口径（原基线无判定表）；若现场另有定则只改这一处 |
| `combatRole` 推导 | `WEAPON`/`VEHICLE` 在 1801~4800 内如何划分（`WeaponKind` 有 12 段）、以及编号空隙（如 2001~2200、3401~3800）归哪一类，**未裁定**。S1 只做**原样搬迁**，S3 填值前须先裁定，不擅自造规则 |
| 真硬件验收 | 真串口/UWB 盒子的在线与冻结行为必须在现场对拍，自动测试不能替代 |

---

## 7. 验收判据

**自动测试（本工作包可证明）**

- [ ] 心跳到达 → `LIVE`、`lastHeartbeatAt` 更新、每马甲 `framesTotal` 递增
- [ ] 超过 5000ms 无心跳 → `OFFLINE`，且轨迹里留下一条 `STATE_CHANGE` 采样
- [ ] `hp == 0` → `DIE` + `deadAt` + `motion=FROZEN` + 坐标冻结；判活解除并还原 motion
- [ ] 离线且从未定位 → `position=null` + `positionSource=NONE`；阵亡不解除冻结（D15-⑦）
- [ ] 命中落库字段逐字段正确（含 `shooterIsSoldier` / `shooterWeaponKind` / 危险物编号）
- [ ] `kills` / `hitsDealt` 与命中表一致；队伍汇总与编成一致
- [ ] 四类 `sampleReason` 各自可触发一次；停演时缓冲被 flush
- [ ] 读侧全量/增量/超窗 409 `SITUATION_REVISION_TOO_OLD` 三条分支（HTTP 状态码由 M4 映射，本包验语义与 `code`）
- [ ] 无演习 → 读侧 `running=false` + 空 `units`（D15-⑥）；未知马甲 → 读侧未命中（M4 映射 404）
- [ ] 队列溢出按策略处理且不阻塞调用方；`real` 模式上行帧仍走同一路径

**现场待验收（本工作包不能证明）**

- [ ] 真实马甲 115200 下的在线/冻结与现场一致
- [ ] 真实 UWB 盒子经 12001 的连续坐标解算与 `signalQuality` 门限
- [ ] 容量、长稳、备份 RPO/RTO（D10）

---

## 8. 与 PROTOCOL.md 及契约的关系

本文**不修改** `PROTOCOL.md`。协议字节、校验、粘包分支、`ff05` 字段、`ff06` 丢弃语义、
41B 结构与 `uwb_meta` 结构全部沿用冻结文档；若实现与协议文档冲突，以 `PROTOCOL.md` 为准并先改文档再改代码。
契约侧本工作包**只接线、不改形状**；若确需改（例如新增端点），先改唯一 OpenAPI 再改代码。
