# scds-next 交接快照

> 更新：2026-10-28，Asia/Shanghai。此快照用于新对话接续，不是生产验收证明。
> 当前任务：**M5 REPLAY 四段全部落地，`ReplayController` 七条方法全部去 501**（testing 第 23~26 节）——
> R1 复盘读侧、R2 轨迹分片、R3 统计、R4 导出与下载（`exportReplay` / `getExportTask` / `downloadExport`）
> 均已接 core `ReplayService` / `ExportTaskService`；**全仓「仍抛 501 的方法」计数已降为 0**。
> 最新全量 `mvn test` **1138 项，失败/错误/跳过均 0**（见 testing 第 15~26 节）。
> 下文 2026-10-21 的部分保留为历史，凡与 testing 第 12~26 节冲突处，以该处为准。

## 1. 从这里继续

仓库：`e:\DualX\scds-next`。先读本快照，再读
[审查清单](docs/review.md)、[需求](docs/requirements.md)、
[架构与实施方案](docs/architecture.md)、[环境](docs/environments.md)、
[测试](docs/testing.md)和[下一对话提示词](docs/next-chat-prompt.md)。
HTTP/WS唯一契约入口是 [openapi.yaml](docs/api/openapi.yaml)。

已完成的里程碑（逐项证据在 testing 第 7~18 节）：SEC-01 / SEC-02 / ERR-01、TEST-01（隔离库 +
完整应用启动）、AUTH-HTTP / DEVICE-AUTH / AUTH-HARDEN、M2 LEDGER（台账/基站/绑定闭环）、
M2 PLAN（想定与编成、导入导出）、M2 MAP-BASE（底图 CRUD / 图片 / 楼层点位）、
M3 LIFE（开停演与恢复门禁、单实例保护）、M3 STATE（态势状态机与读侧）、
M4 PUSH-WS（判据 1~9、背压与保活）、M4 DEVICE-SELF、M4 T-STOP（停机释放链路）、
**M3 COMMAND MR-1 + MR-2 + MR-3 + 第 8 条 `moveVest`**、**M3 CAL-1（UWB 室内解算链路）**，
**M3 CAL-2（UWB 中心基准校准：CAL-2a 状态机 / CAL-2b 端点接线 / CAL-2c V11 + 端到端）**、
**M4 读侧「当前演习日志流水」`GET /api/v1/exercise/current/logs`**、
**PLAN 编成 `hp`/`ammo1`/`ammo2` 范围校验**、**`getVestLink`（每马甲链路状态去 501）**，
**M5 REPLAY R1（复盘读侧去 501）**、**M5 REPLAY R2（轨迹分片去 501）**、
**M5 REPLAY R3（统计去 501）**，以及**M5 REPLAY R4（导出与下载去 501，至此 `ReplayController` 七条全通）**。

下一原子任务的候选：**Flutter 客户端工程**（Android 单兵端 / Windows 指挥端均未开工）；
**CAL-2 的现场验收**（`calibrated_at` 可读性、50mm 静止阈值是否适配现场噪声）；
**网关下行接线**（14B/7B 帧）、**单实例保护 L6**、**T-PROD 部署**。启动前须重新核查仓库与环境，
不照搬本快照的瞬时结论。提交/推送仍须当次授权；本段 R4 的 HTTP 端到端补测**尚未提交**（见 §2），
网络失败时不得写成已入远端。

## 2. Git与工作树

| 项 | 当前状态 |
|---|---|
| 分支 | main |
| 源码本地HEAD | 本轮沿用 R3 末端 `b430c12`（docs: 回写 M5 REPLAY R3 统计的证据、口径与快照）；R4 改动在工作树 |
| 实际远端main | 以 `git status` 为准：**推送须当次授权**，未获授权前 R1/R2/R3 各三笔与 R4 改动仅在本机 |
| 已推送 | 更早批次已在远端：`e0c8192`（feat plan 范围校验）、`8c0f02e`（feat state 每马甲链路计数）、`b460716`（feat device `getVestLink` 接线）、`0df5909`（docs）、`143d40a`（docs）；CAL-2c 四笔、`865a0b9`（feat `moveVest`）、`c1198db`（feat 当前演习日志流水）、`398495b`（docs）与 CAL-1/MR-1~MR-3 亦已在远端 |
| 未推送（仅本机） | R1 三笔：`86b24b9`（feat replay core 读侧）、`011b0f3`（feat replay api 接线）、`65d1976`（docs）；R2 三笔：`8fdcadb`（feat replay core timeline）、`63949a1`（feat replay api 接线）、`9e3fb7e`（docs）；R3 三笔：`8e0aadf`（feat replay core 统计）、`7dc14d5`（feat replay api 接线）、`b430c12`（docs）；R4 改动尚未提交（core `SheetWriter`/`PlanRosterSheet`/`ExportTaskRepository`/`ReplayExportReport`/`ExportTaskService`/`ExportTaskServiceIT`；api `ReplayController`/`ApiSkeletonSmokeIT`/`ApiExceptionHandler`/`ApiExceptionHandlerBusinessTest`；app `RoleWritePermissionIT`/`MvcProblemIT`；docs `testing.md`/`snapshot.md`） |
| 本轮继续成果 | **M5 REPLAY R4（导出与下载）去 501**——`ReplayController` 七条方法**全部去 501**（全仓 501 计数 → 0）：受理 202 合并、`@Scheduled` 2s 扫描渲染、签名下载 URL；新增 core `ExportTaskService` / `ReplayExportReport` / `SheetWriter` 与 16 例 `ExportTaskServiceIT`。全量 `mvn test` **1136 项**全绿（**尚未提交**） |
| 凭据 | 开发.env未修改；新.env.test为独立owner-only测试凭据，两者均忽略，禁止打印或提交 |

网络失败时只保留本地提交、如实报告，不重写历史、不 force push；提交/推送须逐次授权，
不得因“上次批准过”自动续用。未改生产配置、pom、协议、算法或历史迁移。
不得恢复用户已删除的临时提交信息文件。

## 3. 实际实现状态

已实现：协议Framer/下行字节编码、冻结算法迁移、domain端口、
JDBC仓储、账号首次建号、登录限流、RS256签发/验签、
AuthService login/refresh/logout/me及事务并发保护、DeviceAuthService设备登录与SID单端在线、
服务端写权限矩阵、TTL与限流配置校验。
新增已完成（METRICS/JWT/ERR 与 TEST-01）：metrics子树ADMIN保护、JWT必需字段/原始类型/纯过期机器码分类、
MVC统一Problem及真实Boot共存回归；核心IT独立库、初始化前门禁和最小权限回归。
新增已完成（**M2 LEDGER**）：`VestLedgerService`（台账 CRUD、逻辑注销、引用保护、D03-4 绑定闭环）、
`AnchorLedgerService`（基站 list/upsert，bs1~4 必开）、`DeviceController` 台账/基站接线、
`SecurityConfig` 绑定路径 ADMIN 规则。

新增已完成（**M3 COMMAND MR-3**）：`ExerciseCommandExpiryTask`（core `@Component`，
`scds.command.sent-ttl` 默认 30s、`@Scheduled` 5s 一拍，只扫 `SENT` 且按 `sent_at` 判超时，
写 `SYSTEM` 存证后作废并清终态行）与仓储 `findSentBefore`。
新增已完成（**M3 CAL-1 UWB 解算链路**，分三步：CAL-1a 解算器 / CAL-1b 装配接线 / CAL-1c app 端到端）：
`IndoorPositionResolver`（41B 帧 → **整图**百分比坐标，
每马甲自持 `UwbFilter`/`UwbSolver` 状态）、`SituationStateService.applyUwb` / `loadIndoorMap`
（解算几何随本场底图装载，开演/停演各清一次；解不出只计数 `uwbFramesWithoutFix` 而不改坐标）、
`SituationSessionBinder` 注入 `BaseMapRepository` 按 `exercise.mapId` 读 `t_map`；
修复 `application.yml` 死配置键（`uwb-mode: enabled` → `mode: true`，原键从未生效）。
CAL-1c 在 `scds-app` 的 `SituationStateIT` 以真 Spring 装配 + 真库底图 + 真 `UwbFramer` 新增 2 例：
可解几何下解算写入整图 `INDOOR` 坐标 / `positionSource=UWB` / `signalQuality=4`；
无底图时只进「无解」计数、不臆造坐标（该帧按冻结成帧规则自拼，理由见 testing 第 17.3 节）。
新增已完成（**M3 CAL-2 UWB 中心基准校准**，分三步：CAL-2a 状态机 / CAL-2b 端点接线 / CAL-2c V11+端到端）：
`UwbCalibrationService`（core 进程内状态机 `IDLE/RUNNING/SUCCEEDED/FAILED`，`start/status/clear/onFrame`；
`finish` 用逐角中位数做静止一致性校验后，以请求里的**卷尺实测值覆盖**写回 `realLengthCm/realWidthCm`——
采样只做一致性校验、不反推几何）；`MapController` 三端点去 501（`POST` 202 / `GET` 200 / `DELETE` 204，
`DELETE` 兼作取消）；新增错误码 `DEVICE_LINK_NOT_OPEN`（409）；迁移 `V11__map_calibration.sql` 给
`t_map` 加 `calibrated_at DATETIME(3) NULL`，由 `MapLedgerService.saveUwbMeta` 与 `uwb_meta`
同一次写入落库；`SituationStateService.applyUwb` 里 `calibration.onFrame` **刻意早于**编成判定，
使标定能在**开演之前**采到帧。
新增已完成（**M3 COMMAND 第 8 条 `moveVest` 手动置位**，分三步：写入口 / 楼层点位读取 / 端点接线与端到端）：
`StateEvent.ManualPosition` + `SituationStateService.moveVest/applyManualPosition`（唯一写手线程
`scds-state-writer` 上改坐标，`PositionSource.MANUAL`、`CoordinateSystem.INDOOR`，阵亡不绕冻结）；
`MapLedgerService.floorPoint`（楼层点位表冻结 JSON → `(xPct,yPct)`，多种 miss 折叠成空 `Optional`）；
`CommandService.moveVest` 判语境与取坐标、`CommandController` 端点去 501；新增两码
`COMMAND_MOVE_FORBIDDEN`（403，正式演习禁止手动置位，与 `AUTH_FORBIDDEN` 分属两件事）与
`MAP_FLOOR_POINT_NOT_FOUND`（404，「图在、点位表里没这个点」）。**不发字节、不落
`t_exercise_command`、不写 `t_exercise_log`**（协议 `LogType` 无 `MOVE` 且已冻结）。
新增已完成（**M4 读侧「当前演习日志流水」`GET /api/v1/exercise/current/logs`**，三段合一）：
`ExerciseLifecycleService.currentLogs`（`sinceSeq` 为 null 走 `findLatest` 取最新一页、非 null 走
`findByExerciseIdSince`；`t_exercise_log.seq` 全局自增，故换场次游标不回退）、
`ExerciseController.listCurrentExerciseLogs` 去 501 并新增 `toLogEntry` 映射（两侧
`ExerciseLogEntry` 同名不同形状）、`ExerciseLifecycleHttpIT` +1 端到端；**无演习进行中返回
200 + 空页**（契约此端点无 404），`limit` 按契约 1..500 拦在参数校验层。详见 testing 第 20 节。
新增已完成（**`getVestLink` 每马甲链路状态去 501**，分三段：A1 core / A2 api 与端到端 / A3 docs）：
`SituationStateService.Unit` 补每马甲 `framesTotal`/`lastFrameType`/`droppedTotal`（写手线程在
heartbeat/gps/hit/mortar/simNo 分支记账；`ff06` 恒丢只增 `droppedTotal`；阵亡冻结期帧算丢弃且不刷新
`lastFrameType`；命中帧记在**被击中者**上）、新增读原语 `vestLink(int)` 与 `SituationQuery.vestLink`；
`DeviceController.getVestLink` 构造扩为 4 参（注入 `SituationQuery`）去 501——六字段全取自 core 状态机
（**不复用**链路级计数），`online` 为**纯 5000ms 窗口**判定，**404 判据 = 设备台账**（台账有但不在本场编成
→ 200 + `online=false` + 零计数）；**未新增错误码、未新增迁移、只读**。详见 testing 第 22 节。
新增已完成（**M5 REPLAY R1 复盘读侧去 501**，见 testing 第 23 节）：
`ReplayController.listReplays` / `getReplay` 两条接线，经新增的 core `ReplayService`
（**不另建 replay 仓储**——复盘与演习是 `t_exercise` 同一行，写入 `ended_at` 后同一行即复盘记录，
`replayId` 就是 `exerciseId`）；列表复用 `ExerciseRepository.findEnded/countEnded` 的同一份过滤条件
（`ended_at IS NOT NULL` + 想定名模糊 + 结束时间区间，按 `ended_at DESC, id DESC` 分页）；
**404 判据只有一个** `REPLAY_NOT_FOUND`（`ended_at IS NULL` 的进行中场次与「id 不存在」合并）；
`durationSeconds` 取 `endedAt - startedAt` **定值**（与当前演习 `Instant.now()` 现算口径显式区分）；
**未新增错误码（复用既有 `REPLAY_NOT_FOUND`）、未新增迁移、未新增依赖、只读**。
未完成：无——`ReplayController` 七条方法已于 R4 全部去 501（见下）。
新增已完成（**M5 REPLAY R2 轨迹分片去 501**，见 testing 第 24 节）：
`ReplayController.getReplayTimeline` 接线，经 core `ReplayService.timeline(replayId, cursor, limit, vestNums, stride)`；
**游标必须是 `t_track_sample.id`**（自增代理键、插入序即采样序，不重不漏），**不是 `t_ms`**
（同毫秒多点无法唯一定位起点）；`TrackSampleRepository` 的抽稀窗口函数须在**全量**上算，
否则抽稀相位逐片漂移会同时重复与漏点；`nextCursor` **只在「本片正好拉满」时给值**
（拉满不保证还有下一片，客户端多拉一次空片即收尾，换「不必多查 COUNT」）；
坏游标（非数字/负值）抛 `VALIDATION_FAILED`（400）**不静默退回 0**；
404 判据复用 R1 的 `requireReplay`（`REPLAY_NOT_FOUND`）；
`toTrackSample` 坐标四字段按原样透出、`t` 取 `tMs` 原值；
**未新增错误码、未新增迁移、未新增依赖、只读**。
新增已完成（**M5 REPLAY R3 统计去 501**，见 testing 第 25 节）：
`ReplayController.getReplayStatistics` 接线，经 core `ReplayService.statistics(replayId, timelineBuckets)`；
**数据源两分**——队伍战损（`teams`）与排行（`topShooters`）取 `t_exercise_unit` 停演**冻结的终局台账**
（不从 `t_hit_event` 命中流水反推，避免崩溃自愈未冻结时两套数），命中部位分布
（`hitPartDistribution`）单查 `t_hit_event` 分组（`HitEventRepository.countByHitPart`）；
**排行只列有战绩者**（`hitsDealt > 0 || kills > 0`），排序 `kills` 降序 → `hitsDealt` 降序 → `vestNum` 升序，
契约未给上限故**不人为截断**；**存活曲线**把 `[startedAt, endedAt]` 等分 `timelineBuckets` 段，
段首 `t = durationMs * i / buckets`，「存活」= `finalStatus != DIE`（**`OFFLINE` 计入存活**），
每段把本场所有队伍填 0 保证键集一致；`DIE` 而 `deadAt` 为 null 按「全程阵亡」处理；
`timelineBuckets` 由控制器 `@Min(10) @Max(500)` 兜底校验；404 判据与 R1/R2 同源（`REPLAY_NOT_FOUND`）；
`teams` 段复用 `scds-domain` 的中立线类型 `com.dualx.core.push.TeamSummary`；
**未新增错误码、未新增迁移、未新增依赖、只读**。
新增已完成（**M5 REPLAY R4 导出与下载去 501**，见 testing 第 26 节）：
`ReplayController` 三条（`exportReplay` 202 / `getExportTask` 200 / `downloadExport` 200）
构造扩为三参（`ReplayService` / `ExportTaskService` / `FileUrlSigner`）去 501，
经 core `ExportTaskService`（受理 / 查询 / 下载 + `@Scheduled` 2s 扫描执行器）与
`ReplayExportReport`（**统计三表 + 编成台账**，**不含逐点轨迹**）；
**受理不返 409**——同复盘同格式活跃任务**合并**、否则新建 `PENDING`（「失败后重导」是新受理）；
**409 `REPLAY_EXPORT_FAILED` 只在 `download` 产生**（未完成 / 已过期 / 文件读不到三合一）；
状态靠 `ExportTaskRepository` 的**当前态守卫**推进，`execute` **不标 `@Transactional`**、
`markRunning` 零影响行数是唯一并发闸门（单次性）；
`downloadUrl` 每次重签（短时效、窗口内可重复），URL 组装在接口层（与 `MapController.imageUrl` 同形）；
TTL `scds.export.expire-ttl` 默认 **24h**、**不新增清理任务**；
`XLSX`（四标签页）/`CSV`（纵向长表 + 「表类型」列 + UTF-8 BOM）走通用写手 `SheetWriter`，
**不引 POI / commons-csv**（JDK `java.util.zip` + `javax.xml.stream` 手写）；
本段**只回 `ApiExceptionHandler.statusOf` 登记 `REPLAY_EXPORT_FAILED → 409`**（码早已在契约与 `ApiErrorCode`，
**未改 openapi、未新增码、未新增迁移、未新增依赖**）。
file / map 其余业务方法按 testing 第 3 节矩阵推进。
Flutter工程尚无（Android 单兵端 / Windows 指挥端均未开工）。

实际版本为Java21、Spring Boot **3.5.7**、MySQL本机8.4.11；
以pom和当前代码为准，不沿用历史记忆里的Boot3.3.5。
Flyway文件V1-V7、V9-V11，共10份；18张业务表，另有Flyway历史表。
原预留V8自愈迁移未创建，而本地已应用V9；V10为演习存活/自愈迁移，V11为底图标定时刻列。

## 4. 验证证据与局限

**当前**全量`mvn test`：2026-10-28，**1136项，失败/错误/跳过均0**：
protocol19 + gateway49 + core603 + api139 + app326（solver/domain无测试）。
计数须按 Maven **模块级汇总**（`[INFO] Tests run:`）取值；按 `target/surefire-reports/TEST-*.xml`
累加会少 core 的 21 项（`@Nested` 内部类如 `TrackSamplingTest$Sampler`/`$Writer` 不落在外层类 XML 里），
口径说明见 testing 第 20 节。
M5 REPLAY R4 新增证据：core 新增 `ExportTaskServiceIT` **16 例**（587 → 603，全走真库真事务、
落盘隔离覆写 `scds.files.root`），详见 testing 第 26 节；
api 139 与 app 326 计数不变（`ApiSkeletonSmokeIT` 换替身/构造/路由值，
`ApiExceptionHandlerBusinessTest` 与 `MvcProblemIT` 换绊线码，`RoleWritePermissionIT` 补替身 Bean）。
M5 REPLAY R3 新增证据：core 新增 `ReplayServiceIT` **7 例**（579 → 586）与
`HitEventRepositoryIT` **1 例**（586 → 587），详见 testing 第 25 节；
api 139 与 app 326 计数不变（`ApiSkeletonSmokeIT` 仅换 501 示例端点）。
M5 REPLAY R2 新增证据：core 新增 `ReplayServiceIT` **8 例**（571 → 579，全走真库真事务），
详见 testing 第 24 节；api 139 与 app 326 计数不变（`ApiSkeletonSmokeIT` 仅换 501 示例端点）。
M5 REPLAY R1 新增证据：core 新增 `ReplayServiceIT` **7 例**（564 → 571，全走真库真事务），
详见 testing 第 23 节；api 139 与 app 326 计数不变（`ApiSkeletonSmokeIT` 仅换替身与路径常量、
`RoleWritePermissionIT` 仅补一个替身 Bean）；`getVestLink` 新增证据：core `SituationStateServiceTest` +6（原 36 → 42，详见 testing 第 22 节）、
app `HwLinkHttpIT` +1（原 5 → 6，端到端 404 与「在台账未编成 200 离线零计数」两分支）；
PLAN 编成范围校验新增证据：core `PlanServiceIT` +3（原 17 → 20），详见 testing 第 21 节；
M4 读侧「当前演习日志流水」新增证据：app `ExerciseLifecycleHttpIT` +1（原 2 → 3），详见 testing 第 20 节；
本段新增证据为 `moveVest`（手动置位）三段：core `SituationStateServiceTest` +4、
`MapLedgerServiceIT` +3，app `CommandHttpIT` +2（原 4 → 6），详见 testing 第 19 节；
CAL-2 的证据见第 18 节：core `UwbCalibrationServiceTest` 18 +
`BaseMapRepositoryIT` +2（CAL-2a/2c），app `MapBaseHttpIT` +1 与 `SituationStateIT` +1
（CAL-2b/2c，真装配 + 真库底图 + 真成帧器端到端）；
CAL-1 的证据见第 17 节（core `IndoorPositionResolverTest` 12 + `SituationStateServiceTest` +4 +
`SituationSessionBinderTest` +2，app `SituationStateIT` +2）；
MR-3 的证据是 `ExerciseCommandExpiryTaskIT` 6 例，详见 testing 第 16 节；
MR-1/MR-2 的证据是 `CommandHttpIT` 4 例，详见 testing 第 15 节。

当时`mvn clean verify`：2026-10-21 18:48:41 +08:00，
**681项，失败/错误/跳过均0**：protocol19 + core281 + api100 + app281。
35份XML与testcase计数一致，8个reactor成功。历史309/361/458/485/521/558不改写。
本段新增证据：`VestLedgerServiceIT`16、`AnchorLedgerServiceIT`5、`ApiSkeletonSmokeIT`70、
`RoleWritePermissionIT`63、`DeviceLedgerHttpIT`2（完整生产入口+真实MySQL+真实TCP），详见testing第12节。
AuthServiceIT有17项真实MySQL测试，包含refresh/logout并发、
重放撤销持久性、签发/插入失败回滚。报告在各模块target/surefire-reports。
API骨架使用standalone且只断言非404，不能证明真实Security或业务闭环。

历史fat jar在signing=true/false下均人工启动验证HTTP，已停止；本次未重复完整fat jar验收。
原实测缺陷的当前状态（下表 2026-10-21 的实测列只反映当时；R07 / R08 已被后续工作包取代）：

| 审查ID | 实测 |
|---|---|
| R01 | SEC-01已修；真实过滤链指标根/子树授权回归52项 |
| R02/R03 | SEC-02已修；JWT字段/时间/机器码回归97项 |
| R04 | ERR-01已修；Boot MVC回归27项，含3项真实Tomcat上传检查 |
| R05 | signing=false时无token账号login返回401，目标404 |
| R06 | 设备登录已按 D02 接线为**边缘本地签发**；只验签实例上该端点仍注册并抛500，是否改为条件注册属独立决策 |
| R07 | **已闭合**：DeviceAuthService、SID单端在线与换绑清SID已实现；`device/me` 已接线，见 testing 第 13 节 |
| R08 | **已闭合**：auth/device/exercise/command/situation/map/plan 业务方法均已接线（UWB 标定三端点已于 CAL-2 接线，COMMAND 第 8 条 `move`、「当前演习日志流水」与 `DeviceController.getVestLink` 均已接线，见 testing 第 20/22 节）；`ReplayController` 七条已**全部接线**（R1 读侧 + R2 轨迹分片 + R3 统计 + R4 导出下载，见 testing 第 23~26 节），**全仓「仍抛 501 的方法」计数为 0** |

R05 仍未修复；R06 的只验签分支待决策。R23数据库隔离子任务已完成；
R18-R28其他风险仍待对应工作包验证。
无真实硬件、solver数值对拍、物理断网、Flutter或生产节点验证。
发布结论：**不可生产发布**。完整问题及测试矩阵见review/testing。

## 5. 环境接续

最后检查（2026-10-27）：scds-local-mysql `Up (healthy)`，127.0.0.1:13306监听；
全量测试已跑完，应用进程均已停止。新对话必须重新核查，不照搬瞬时状态。
实际.env有DB秘密及JWT文件路径，没有SCDS_ADMIN_USERNAME/PASSWORD。
首次建号实现不等于已部署管理员，不擅自补凭据。

从scds-app目录运行：

```powershell
java -jar target\scds-next.jar --spring.profiles.active=local
```

从仓库根运行须覆盖dev公私钥路径，详见环境手册。
local需通过进程环境或命令行激活，仅在待导入.env里写profile不能提前激活它。
核心IT现在仅连接同一容器内的scds_next_test，scds_test只拥有精确测试库权限，
不共用开发凭据；新.env.test仅含SCDS_TEST_JDBC_URL、
SCDS_TEST_MYSQL_USER、SCDS_TEST_MYSQL_PASSWORD。
初始化前门禁拒绝误连、连接覆盖及危险Flyway模式；不允许指向生产。
AuthServiceIT仍真实提交、按自身UUID清理；多数仓储IT回滚。
测试库默认保留，Initialize-TestDatabase.ps1仅首次运行，已有库/账号/文件均拒绝覆盖。
空库已迁移V1-V7/V9-V11，两库校验和一致；开发与测试account/refresh/exercise均0。

## 6. 已确认与待确认

已确认：refresh按令牌原文SHA-256的token_hash查库，core不解析JWT；
logout无请求体，撤账号全部refresh；access无主动撤销。
管理员部署.env明文、首次BCrypt建号、不写seed；公开dev密钥仅开发。
（原「`device/me` 本阶段留 501」已作废：该端点随后已接线，见 testing 第 13 节。）
自愈写SYSTEM证据后删该场残留指令，同事务；
协议LogType不能加SYSTEM；谁持状态谁调推送端口。
2026-10-21新增已确认D14：同容器独立库、独立测试账号和凭据、保留库、
最小权限与初始化前门禁；不因此批准新容器、清库删卷、V10或其他决策。
2026-10-21新增已确认（M2 LEDGER，见 decision-register“LEDGER 执行边界”）：
契约计数 **69 REST + 2 WS**；逻辑注销=`enabled=0`+清SID且**保留密钥**，物理删除不外露；
引用保护**只拦“演习进行中”**（想定编成引用不拦）；绑定闭环 `POST`/`PUT`/`DELETE`
=`首次绑定`/`换绑`/`解绑`（解绑幂等、保留摘要）；绑定路径写权限单列 `ADMIN`；
`bs1~4` 必须保持启用；`listVests.status` 按心跳近似且 `DIE` 返回空集。

2026-10-27新增已确认（M3 COMMAND，见 testing 第 15 节）：
指令 `accepted=false` 只表示**未入队**（链路未开 / 串口队列满），两种成因都不落库不写日志；
顺序为**先发字节、成功后一次性落 `state=SENT`**；全体充弹回执与队列行 `vest_num=0`、审计 `vest_num` 记 NULL；
覆盖配置未传字段取**本场想定编成**原值、不写审计（协议 LogType 无 CONFIG）；
`t_plan_roster` 的 hp/ammo 范围校验**单列任务**（已由 2026-10-28 的 PLAN 任务收口，见 testing 第 21 节）。

2026-10-27新增已确认（M3 COMMAND MR-3 队列过期扫描，见 testing 第 16 节）：
超时窗 `scds.command.sent-ttl` 默认 **30s**、扫描周期 **5s**；只扫 `state='SENT'`（`PENDING` 在本实现下
不产生，是「等重连」的派发语义，不判超时）；顺序为**先写 SYSTEM 存证 → 再作废 EXPIRED
（`fail_reason='SENT_TIMEOUT'`）→ 再 `deleteFinished()` 清终态行**，同一事务；
载体为 core `@Component` + `Instant.now()`（不注入 `Clock`，否则 core 测试上下文起不来）；
超时是**队列卫生**语义而非「等回执」（协议无回执帧，`ACKED` 仍无人写入）。

2026-10-27新增已确认（M3 CAL-2 标定本体，见 testing 第 18 节）：
标定结果**覆盖写回** `uwb_meta` 的 `realLengthCm/realWidthCm`，并以请求里的**卷尺实测值**为准，
采样只做静止一致性校验、不反推几何（阈值 `calibStaticMaxDiffCm` 默认 50.0mm，逐角中位数口径）；
新增迁移 **V11** 给 `t_map` 加一列 `calibrated_at`（**取 V11 而非预留的 V8**：V9/V10 已应用、V8 从未创建，
补 V8 会受 Flyway 校验/顺序限制；不改历史迁移、不开 out-of-order），
标定的**内容与时刻由 `saveUwbMeta` 一次写入产生**；标定状态是**进程内**的（重启即丢）；
`start` 判据顺序固定为「几何 → 帧数 → 至少一个实测值 → 正整数 → 链路未开 409 → 进行中 409」；
新增错误码 `DEVICE_LINK_NOT_OPEN`（409，只认 `LinkState.OPEN`）；
`UwbCalibrationRequest.bs1ToBs3Cm` **保留契约字段名**（字面 bs1→bs3 是对角线），语义按描述解释为**宽度**。

2026-10-28新增已确认（M3 COMMAND 第 8 条 `moveVest` 手动置位，见 testing 第 19 节）：
403 判据为**本场 `BattleMode.BATTLE`**（新码 `COMMAND_MOVE_FORBIDDEN`，语义是「这场演习的模式不允许」，
与 `AUTH_FORBIDDEN` 的「角色/权限不足」分工，不复用后者）；坐标**只能**取自
本场底图的楼层点位表（未绑图 → 404 `MAP_NOT_FOUND`，图在但没这个点 → 404 `MAP_FLOOR_POINT_NOT_FOUND`，
点位表内部的多种 miss 折叠成同一个码）；**写入口只投事件**，
坐标由唯一写手线程写入（`PositionSource.MANUAL`），阵亡不绕冻结；
**不发字节、不落队列、不写审计**（回执 `downlinkHex` 恒 null，协议 `LogType` 无 `MOVE`）；
`accepted=false` 仅当态势侧已停止接收；`CommandService.moveVest` 不记操作员审计（控制器不加 `Jwt` 入参）。

2026-10-28新增已确认（PLAN 编成 `hp`/`ammo1`/`ammo2` 范围校验，见 testing 第 21 节）：
范围取 `hp ∈ [0, 255]`、`ammo1`/`ammo2 ∈ [0, 65535]`（来源是 14B 轮询配置帧的字节宽度，
与契约 `VestConfigRequest` 一致）；校验点只有 `PlanService.validate` 一处，同时覆盖
`replaceRoster` 与 `importPlan` 两条写路径，越界 400 `VALIDATION_FAILED`（**未新增错误码**）；
**不在库层加 CHECK**（V3 是已应用历史迁移，且库层一致性本就由应用层保证，故**未新增迁移**）；
契约 `RosterEntry` 补 `minimum`/`maximum` 与 `VestConfigRequest` 对齐。

2026-10-28新增已确认（`getVestLink` 去 501，见 testing 第 22 节）：
六字段（`vestNum`/`online`/`lastHeartbeatAt`/`lastFrameType`/`framesTotal`/`droppedTotal`）**全取自 core 状态机**，
**不复用**链路级 `LinkStatus` 计数（设计 05 §5 / 设计 06 §S7 的红线）；
`lastFrameType` 只取五类白名单 `ff03`/`ff04`/`ff05`/`ff07`/`ff0a`，`ff06`（UWB 生效时应丢弃的旧室内定位帧）
**恒只进 `droppedTotal`**；`framesTotal` + `droppedTotal` 互斥且穷尽（非「收到」即「丢弃」）；
命中帧 `ff05` 计在**被击中者**上；阵亡冻结期到达的帧算丢弃且不刷新 `lastFrameType`；
`online` 是**纯 5000ms 窗口**判定（不用 `status == LIVE` 兜底）；
**404 判据 = 设备台账**（`VestLedgerService.get`），台账有但当前不在本场编成内 → **200 + `online=false` + 零计数**；
每马甲计数随 `beginExercise` 重置；**未新增错误码（复用 `DEVICE_VEST_NOT_FOUND`）、未新增迁移、只读**。

2026-10-28新增已确认（M5 REPLAY R1 复盘读侧去 501，见 testing 第 23 节）：
**不另建 replay 表/仓储**——`t_exercise` 一行两用，`ended_at IS NULL` 即「当前演习」，
写入 `ended_at` 后同一行即复盘记录，`replayId` 就是 `exerciseId`，无「转存」动作；
列表复用 `ExerciseRepository.findEnded/countEnded` 的同一份过滤条件（`ended_at IS NOT NULL`
+ 想定名模糊 + 结束时间区间，`ended_at DESC, id DESC`），翻页与 `total` 口径不会各自漂移；
**404 判据只有一个** `REPLAY_NOT_FOUND`——`ended_at IS NULL` 的进行中场次与「id 不存在」
对复盘入口是同一件事，不区分两种成因；
`durationSeconds` 取 `endedAt - startedAt` **定值**，与当前演习 `Instant.now()` 现算口径显式区分；
**复用既有 `REPLAY_NOT_FOUND`（未新增错误码、未改 openapi）、未新增迁移、未新增依赖、只读**。

2026-10-28新增已确认（M5 REPLAY R2 轨迹分片去 501，见 testing 第 24 节）：
**游标必须是 `t_track_sample.id`**——它是自增代理键且插入序即采样序，用它分片不重不漏；
`t_ms` 只保证时间语义，同毫秒多点无法唯一定位起点，**不作游标**；
`TrackSampleRepository` 的窗口函数须在**全量**上算抽稀（不带以游标为界），否则每片从 1 重新编号、
抽稀相位逐片漂移，会同时出现重复与漏点；
`nextCursor` **只在「本片正好拉满」时给值**——拉满不保证还有下一片（可能刚好整除），
客户端会多拉一次空片，用一次空请求换「不必多查一次 COUNT」；
坏游标（非数字/负值）抛 `VALIDATION_FAILED`（400）**响亮失败、不静默退回 0**
（否则形成「永远拉第一页」的死循环）；404 判据与 R1 同源（`requireReplay` → `REPLAY_NOT_FOUND`）；
`toTrackSample` 坐标四字段按原样透出、`t` 取 `tMs` 原值；
**未新增错误码、未新增迁移、未新增依赖、只读**。
**R4 前置提醒**：`REPLAY_EXPORT_FAILED` 的 409 状态刻意仍未登记，落地导出时须一并登记，
并同步修改把它当「未登记状态」绊线的 `MvcProblemIT` 与 `ApiExceptionHandlerBusinessTest`。

2026-10-28新增已确认（M5 REPLAY R3 统计去 501，见 testing 第 25 节）：
**数据源两分**——队伍战损与排行取 `t_exercise_unit` 停演**冻结的终局台账**，
**不**从 `t_hit_event` 命中流水反向统计（崩溃自愈未冻结时会给出两套数）；
命中部位分布（`hitPartDistribution`）台账里没有，单查 `t_hit_event` 按 `hit_part` 分组；
**排行只列有战绩者**（`hitsDealt > 0 || kills > 0`），排序 `kills` 降序 → `hitsDealt` 降序 → `vestNum` 升序，
契约 `topShooters` 未给上限故**不截断**（截断会让「第 N 名」随实现变化、客户端无法复算）；
**存活曲线**把 `[startedAt, endedAt]` 等分 `timelineBuckets` 段，段首 `t = durationMs * i / buckets`，
「存活」定义为 `finalStatus != DIE`（**`OFFLINE` 计入存活**——离线不等于阵亡），
每段把本场所有出现过的队伍都填 0 保证键集一致；`DIE` 而 `deadAt` 为 null 的异常态按「全程阵亡」处理；
`timelineBuckets` 由控制器 `@RequestParam(defaultValue = "120")` + `@Min(10) @Max(500)` 兜底校验；
404 判据与 R1/R2 同源（`requireReplay` → `REPLAY_NOT_FOUND`）；
`teams` 段复用 `scds-domain` 的中立线类型 `com.dualx.core.push.TeamSummary`（不新造同形 DTO）；
**未新增错误码、未新增迁移、未新增依赖、只读**。
2026-10-28新增已确认（M5 REPLAY R4 导出与下载去 501，见 testing 第 26 节）：
导出内容 = **统计三表 + 编成台账，不含逐点轨迹**；`XLSX` 四标签页 / `CSV` 纵向长表（多列「表类型」）
走通用写手 `SheetWriter`（**不引 POI / commons-csv**，JDK 自带手写，与 PLAN 模板导出同一取向）；
异步载体 = **core `@Scheduled` 扫描执行器**（2s 一拍、`findPending` 取旧不取新；
core 测试上下文不跑 `@Scheduled`，IT 直接调 `tick()`）；
**受理 `POST` 不返 409**——同复盘同格式活跃任务**合并**、否则新建 `PENDING`
（「失败后重导」是新的 202 受理）；**409 `REPLAY_EXPORT_FAILED` 只在 `download` 产生**
（未完成 / 已过期 / 文件读不到三合一），本段即「回 `statusOf` 登记 409」；
状态流转靠 `ExportTaskRepository` 的**当前态守卫**（`markRunning` 零影响行数=唯一并发闸门），
`execute` **不标 `@Transactional`**（边写库边写文件）；`downloadUrl` 只在 `SUCCEEDED` 时给值且**每次重签**，
URL 组装在接口层（与 `MapController.imageUrl` 同形）；产物 TTL `scds.export.expire-ttl` 默认 **24h**、
**不新增清理任务**；**未改 openapi、未新增码**（`REPLAY_EXPORT_FAILED` 早已登记）、
**未新增迁移**（复用 V5 的 `t_export_task`）、**未新增依赖**。

待确认：只验签实例的设备签发能力；权限与单兵可见性；UWB SERIAL/启停；
容量、文件保留及RPO/RTO；`t_anchor` 与 `t_map.uwb_meta` 双源如何统一、
“在建底图”定义及 `DIE` 在台账层的表示。不得默认同意推荐方案。
下列原待确认项已被后序工作包裁定并落地，不再挂起：自愈迁移号（已定为 V10）、
链路两通道的数组/信封根类型、跨进程 revision 恢复、WS 超窗 close 语义
（见 testing 第 3 节 T-PUSH / T-BACKPRESSURE 矩阵与对应提交）。

## 7. 不可越过的边界

- HTTP11001、UWBTCP12001；WS连着仍Poll，同构载荷。
- 最后心跳<=5000ms在线；阵亡冻结最后有效坐标，motion=FROZEN，判活才解除。
- 马甲/UWB逐字节及算法/参数冻结，gateway/core不互依，domain承载SPI。
- UTC/RFC3339、DB datetime(3)、t_ms INT、Problem含code/traceId。
- 每业务阶段先方案确认；原子修改<=3文件，修复最多5轮。
- 不覆盖用户改动，不改.env或历史迁移，不清库/删卷/reset/force push。
- 提交和推送须有当次授权；网络失败保留本地提交并如实报告。

## 8. 历史文档校验

冻结协议从旧迁移目录按原字节复制，SHA256：
`7CB9B4988B0D6BE64A6CA5A6B4AE91BC8005DA11466727C46B5BF0DD5859BD92`。
协议内容不编辑；未来staging/checkout时仍需防Windows换行转换。

已完成校验：

- 11份非冻结Markdown文档，82个相对链接目标全部存在。
- SnakeYAML2.4解析11份YAML，禁用重复键；441个$ref文件及JSON Pointer目标均存在。
- 从根paths解析69个REST、2个WS操作，operationId无缺失/重复。
- TooManyRequests的Retry-After位于Response Object headers层级。
- Surefire报告重计309项、失败/错误/跳过均0。
- git diff --check通过；修改范围为文档、契约说明/响应结构和.env.example。

未执行完整OpenAPI专业lint；未验证协议未来Git检出字节、物理硬件、Flutter或生产。
链接目标存在不等于所有业务说明已通过验收；后续实现必须同步文档与真实测试证据。
最新证据：M5 REPLAY R4（导出与下载去 501）详见 testing 第 26 节；
M5 REPLAY R3（统计去 501）详见 testing 第 25 节；
M5 REPLAY R2（轨迹分片去 501）详见 testing 第 24 节；
M5 REPLAY R1（复盘读侧去 501）详见 testing 第 23 节；
`getVestLink`（每马甲链路状态去 501）详见 testing 第 22 节；
PLAN 编成 `hp`/`ammo1`/`ammo2` 范围校验详见 testing 第 21 节；
M4 读侧「当前演习日志流水」（`listCurrentExerciseLogs`）详见 testing 第 20 节；
M3 COMMAND 第 8 条 `moveVest`（手动置位）详见 testing 第 19 节；
M3 CAL-2（标定本体，含 CAL-2c）详见第 18 节；M3 CAL-1（含 CAL-1c）详见第 17 节；
COMMAND MR-3 详见第 16 节、MR-1/MR-2 详见第 15 节；
T-STOP 详见第 14 节；DEVICE-SELF 详见第 13 节；M2 LEDGER 详见第 12 节；TEST-01 全量应用启动详见第 11 节。
本快照已同步 1136 项测试、M5 REPLAY 四段（R1 读侧 / R2 轨迹分片 / R3 统计 / R4 导出下载）交付
（代码与文档已就位、**待授权提交与推送**）与下一任务候选（Flutter 客户端 / CAL-2 现场验收 /
R4 的 HTTP 端到端补测），
不要求重做已完成 SEC/ERR/AUTH/LEDGER/PLAN/MAP-BASE/LIFE/STATE/PUSH/DEVICE-SELF/T-STOP/COMMAND/CAL-1/CAL-2。
