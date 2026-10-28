# scds-next 交接快照

> 更新：2026-10-28，Asia/Shanghai。此快照用于新对话接续，不是生产验收证明。
> 当前任务：**M6 CLIENT-BASE 段 1——共享契约 / 通信基座（testing 第 33 节）**——在
> `clients/scds_client/lib/shared/api/` 落三文件：`api_contract.dart`（`kHttpPort = 11001` / `kApiPrefix = /api/v1` /
> `kWsPrefix = /ws/v1` / `kProblemMediaType` + `ApiErrorCode` 常量族，逐字对齐 `openapi.yaml` 与
> `schemas/common.yaml`）、`api_problem.dart`（RFC 7807 `ApiProblem`/`FieldError` + `ApiException`，
> `tryParse` 对不合契约正文返回 `null` **不臆造缺省值**）、`api_client.dart`（**`dart:io HttpClient`** 封装：
> `get/post/put/delete/send`、`resolve`/`api`、`Bearer` 注入、**非 2xx 解析 Problem 后抛 `ApiException`**、`close`）。
> **只依赖 `dart:io`，未新增依赖、未动 `pubspec.yaml`**；**本段不含测试**（按裁定拆为段 1b），
> 门禁为 `flutter analyze` → `No issues found!`；提交 `dbc5dd9`。
> 本任务紧接 **M6 CLIENT-BASE 段 0——Flutter 工程脚手架（testing 第 32 节）**（经用户 2026-10-28 裁定
> **D12**（单工程 + 共享 Dart 契约/通信包 + Android 单兵入口 / Windows 指挥入口）与 **D13**（授权**仅一次**
> `flutter create` 例外，固定目录与清单、不含业务）后用 `flutter create --org com.dualx --project-name scds_client
> --platforms=android,windows clients/scds_client` 生成 `clients/scds_client`，磁盘 50 文件 / **git 跟踪 45 文件**，
> `flutter analyze` 无问题、`flutter test` 通过；**只做脚手架，不含业务**）。
> 更早为 **R4 遗留收口（testing 第 31 节）**、**L6 单实例保护口径收口（testing 第 30 节）**（归属进程管理层，
> 无 Java 改动）与 **M3 LIFE 开演链路就绪 409 硬前置（testing 第 29 节）**，三者**均已在远端**。
> 更早已落 **M5 REPLAY 四段**（R1 复盘读侧 / R2 轨迹分片 / R3 统计 / R4 导出下载，
> `ReplayController` 七条全去 501、**全仓「仍抛 501 的方法」计数为 0**）与 **M3 CAL-2 现场验收收口**，
> 两者亦**均已在远端**。
> 最新全量 `mvn test` **1151 项，失败/错误/跳过均 0**（见 testing 第 15~31 节；客户端段在 Dart 侧、
> 不进 Maven reactor，故计数不变）。
> 下文 2026-10-21 的部分保留为历史，凡与 testing 第 12~33 节冲突处，以该处为准。

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
**M5 REPLAY R3（统计去 501）**，以及**M5 REPLAY R4（导出与下载去 501，至此 `ReplayController` 七条全通）**，
以及**M3 LIFE 网关下行接线（开演 14B/7B 与停演 7B 出站）**，
**M3 LIFE 开演链路就绪 409 硬前置（`DEVICE_LINK_NOT_OPEN`，含既有 IT 的「先开串口链路」改造）**，
**L6 单实例保护口径收口（归属进程管理层，无 Java 改动）**，
**R4 遗留收口（`downloadUrl` 签名窗口过期 HTTP 用例，测试侧独立重算签名；在既有用例内扩断言，计数仍 1151）**、
**M6 CLIENT-BASE 段 0（Flutter 工程脚手架，D12/D13 裁定后一次性生成 `clients/scds_client`，不含业务）**、
**M6 CLIENT-BASE 段 1（共享契约/通信基座：`api_contract` + `api_problem` + `api_client`，只依赖 `dart:io`）**。

下一原子任务的候选：**M6 CLIENT-BASE 段 1b（客户端基座测试）**——用本地 `HttpServer` 打桩验
契约解析、`Bearer` 注入与非 2xx → `ApiException` 映射（测试文件是第 4 个文件，需当次授权破例，
或拆为独立可验收子任务）；其后为段 2 登录（对接 `AuthService` RS256）→ 段 3 连接配置 + 自身态势只读
（Android 优先）→ 段 4 Android 入口装配 + 模拟器 `zaa_pixel6` 冒烟 → 段 5 Windows 指挥端骨架
（本机缺 Visual Studio，**不能原生构建**，安装需另授权）。
另有一条单列后续：**T-PROD 部署**；以及一条**观察项**——硬件 ACK 解析（「已发送」≠「已确认」仍无人写入
`ACKED`，且协议无回执帧）。启动前须重新核查仓库与环境，
不照搬本快照的瞬时结论。提交/推送仍须当次授权；本轮段 1 的代码笔（`dbc5dd9`）**已本地提交**，
docs 回写笔待提交（见 §2）。

## 2. Git与工作树

| 项 | 当前状态 |
|---|---|
| 分支 | main |
| 源码本地HEAD | 本轮 M6 CLIENT-BASE 段 0 两笔**已入远端**（脚手架 `b16c348` + docs `f4c9f15`，另有快照订正 `78bbb99`）；段 1 代码笔 `dbc5dd9` **已本地提交、待与本 docs 笔一并推送**；HEAD 随后续回写前移，故不钉死具体 hash |
| 实际远端main | 已入远端的是 `78bbb99 docs: 快照订正——M6 CLIENT-BASE 段 0 两笔已推送入远端`（其前为段 0 脚手架 `b16c348`、docs `f4c9f15`、`1950b4e` 快照订正）；更早为本轮 R4 遗留收口三笔 tip `068d706` 与 L6 口径收口三笔 `ac9353a..4b76f2b` |
| 已推送 | `b16c348..78bbb99` 已入远端：**M6 CLIENT-BASE 段 0 三笔**（`b16c348` 脚手架 `clients/scds_client` 45 文件 + `f4c9f15` docs 回写 3 文件 + `78bbb99` 快照订正）；更早 `4b76f2b..068d706` 为 R4 遗留收口三笔、`ac9353a..4b76f2b` 为 L6 口径收口三笔；再往前（链路就绪硬前置三笔、网关下行五笔、CAL-2 现场验收收口三笔、R1/R2/R3 各三笔与 R4 全部改动、CAL-2c 四笔、`865a0b9` `moveVest`、`c1198db` 日志流水、`398495b` docs 及 CAL-1/MR-1~MR-3）亦均在远端 |
| 未推送（仅本机） | 本轮段 1 的**两笔**——代码笔 `dbc5dd9`（`clients/scds_client/lib/shared/api/` 三文件：`api_contract.dart` / `api_problem.dart` / `api_client.dart`）＋ 本 docs 回写笔（`docs/decision-register.md` 段 1 登记行与文末节、`docs/testing.md` 第 33 节、`snapshot.md`）；推送后此格即为**无** |
| 本轮继续成果 | **M6 CLIENT-BASE 段 1：共享契约 / 通信基座**——`clients/scds_client/lib/shared/api/` 三文件：
`api_contract.dart`（端口/前缀/media type 与 `ApiErrorCode` 常量族）、`api_problem.dart`（RFC 7807 模型 +
`ApiException`）、`api_client.dart`（**`dart:io HttpClient`** 封装，非 2xx 抛 `ApiException`）。
**只依赖 `dart:io`，未新增依赖、未动 `pubspec.yaml`**；**不含测试**（按裁定留段 1b）；
门禁 `flutter analyze` → `No issues found!`。代码笔 `dbc5dd9` **已本地提交**，随本 docs 笔推送 |
| 上一轮成果（段 0） | **M6 CLIENT-BASE 段 0：Flutter 工程脚手架**——按 D12（单工程 + 共享包 + 双入口）/D13（一次性脚手架例外）生成 `clients/scds_client`（磁盘 50 文件 / **git 跟踪 45 文件**），`flutter analyze` 无问题、`flutter test` 通过。**只做脚手架，不含业务**；本段两笔（`b16c348` + `f4c9f15`）**均已推送** |
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
新增已完成（**M3 LIFE 网关下行接线**：开演/停演出站，分 A core 接线 / B api `replayId` + 契约 /
C core 用例 / D app 端到端四段，见 testing 第 28 节）：
`ExerciseLifecycleService.startExercise` / `stopExercise` 的**事务尾段**经注入的 `DeviceLinkAdmin`
调用 `sendVestCommand` 下发——开演**先做完库写入与态势装载 → 再逐台发 14B 轮询配置帧 → 最后发 7B 开始帧**，
停演**先冻结终局 → 再发 7B 结束帧**；组帧复用 `DownlinkCommands.pollConfig/startBattle/finishBattle`，
**冻结常量一字未动**；全体下行用占位 `BROADCAST_VEST_NUM = 0`、写日志时该字段记 `NULL`；
发送异常 **catch 后 WARN + 写 `t_exercise_log(SYSTEM)` 存证，方法照常返回、事务照常提交**
（响应仍 201/200，**不发布半启动态**——这是 D11 的落地口径：物理副作用无法由 JDBC 撤回，故只留痕不回滚）；
链路未开（`snapshot()` 无 `SERIAL_VEST` 且非 `OPEN`）**静默跳过**（只 WARN，不发字节、不写日志）——
本段**保留**该分支为防御（兜「门禁检查与下发之间链路被并发关闭」的窗口）；
`ExerciseController.stopExercise` 响应补 `replayId = exerciseId`（停演即开一场可复盘的回放）。
新增已完成（**M3 LIFE 开演链路就绪 409 硬前置**：分 A core 判据 / B core 用例 / C app 用例 /
D app 端到端与既有 IT 改造 / E 文档收口五段，见 testing 第 29 节）：
`ExerciseLifecycleService.startExercise` 在**写库之前**校验存在处于 `OPEN` 的 `SERIAL_VEST` 链路
（判据与紧随其后的下发共用同一个 `vestLinkOpen()`，两处口径不会各自漂移），未开即 409
**`DEVICE_LINK_NOT_OPEN`**（**复用**既有码，未新增）；判据顺序固定为 503 门禁 → 404 想定不存在 →
400 编成为空 → 409 `DEVICE_LINK_NOT_OPEN` → 插库（409 `EXERCISE_ALREADY_RUNNING`），
被任一条件拒下的请求**不留半启动状态**；**停演刻意不设此门禁**——卡死的场次必须随时停得掉；
`OPENING` 不算就绪（只认 `LinkState.OPEN`）。
新增已完成（**L6 单实例保护口径收口**，见 testing 第 30 节）：以 `environments.md` §6.4 的「已结论」为准，
单实例保护**落在进程管理层**（四形态：容器固定 `container_name` + `restart: unless-stopped` 且禁 `--scale`、
原生服务单实例单元、升级先停后启、不允许第二节点/无 HA），应用侧三件（开演 `claim(owner_instance)`、
2000ms 心跳（守卫带 owner）、`uk_exercise_running` 唯一索引）**已具备且有 IT 覆盖**，**本类无相关代码改动**；
订正三处矛盾表述（`ExerciseLifecycleService` 类 javadoc / `testing.md` §28.3+§29.3 / `decision-register`
网关下行段），并在 testing 新增第 30 节（订正清单 + 归属论证 + 现场验收清单）。**只动 javadoc 与文档**。
新增已完成（**R4 遗留收口：`downloadUrl` 签名窗口过期的 HTTP 用例**，见 testing 第 31 节）：
在 `ReplayExportHttpIT.xlsxExportOverRealHttp` 内补「签名正确、仅 `exp` 已过 → 403 `FILE_URL_INVALID`」断言；
签名器持 `Clock.systemUTC()`、HTTP IT 推不动时钟，故改由**测试侧按契约独立重算**签名
（`@BeforeAll` 显式给固定 `scds.files.sign-secret`，按 `路径 + '\n' + exp` 的 HMAC-SHA256、Base64URL 无填充），
先用「未过期」（`exp=now+300s`）正例自证重算与服务端一致、再用「已过期」（`exp=now-60s`）样本验 403。
**在既有用例内扩断言，无新增用例**（计数仍 1151）；**未改验签逻辑、未引入可注入 Clock 到生产**。
**未做**：硬件 ACK 解析（「已发送」≠「已确认」那条线仍在，且协议无回执帧）。
**未新增错误码、未新增迁移、未新增依赖**。
新增已完成（**M6 CLIENT-BASE 段 0：Flutter 工程脚手架**，见 testing 第 32 节）：
按 D12（**单工程 + 共享 Dart 契约/通信包 + Android 单兵入口 / Windows 指挥入口**）与
D13（授权**仅一次** `flutter create` 例外，固定目录与文件清单、**不含业务**）生成
`clients/scds_client`——`flutter create --org com.dualx --project-name scds_client --platforms=android,windows`，
磁盘写 50 文件、**git 跟踪 45 文件**（`build/`/`.dart_tool/`/Gradle wrapper 由模板自带 `.gitignore` 排除）；
`lib/main.dart` 为模板默认 Counter、`android/**` 的 `MainActivity.kt` 落在 `com.dualx.scds_client`、
`windows/**` 为 CMake + runner C++；**依赖只有模板默认**（`flutter` + `cupertino_icons` + `flutter_lints`）；
验收 `flutter analyze` → `No issues found!`、`flutter test` → `All tests passed!`；
**未写业务代码**（段 1 才是共享契约/通信基座）、**未安装 Visual Studio**（`vswhere` 不存在、
`flutter doctor` 报缺 Desktop development with C++，故 Windows 端**本机不能原生构建**）；
本段是 Dart 侧资产、**不进 Maven reactor**，全量计数仍 **1151**。
新增已完成（**M6 CLIENT-BASE 段 1：共享契约 / 通信基座**，见 testing 第 33 节）：
`clients/scds_client/lib/shared/api/` 三文件——`api_contract.dart`（`kDefaultHost` / `kHttpPort = 11001` /
`kApiPrefix = /api/v1` / `kWsPrefix = /ws/v1` / `kProblemMediaType` + `ApiErrorCode` 常量族，逐字对齐
`docs/api/openapi.yaml` 的 `servers` 与 `docs/api/schemas/common.yaml` 的 `Problem.code`）、
`api_problem.dart`（RFC 7807 `ApiProblem`/`FieldError` 与 `ApiException`；`tryParse` 对缺必填字段、
非 JSON、非对象的正文一律返回 `null`，**不臆造缺省值**）、`api_client.dart`
（**`dart:io HttpClient`** 封装：`get/post/put/delete/send`、`resolve`/`api`、`Bearer` 注入、
**非 2xx 解析 Problem 后抛 `ApiException`**、`close`；`ApiResponse.body` 保留原始字节以支持产物下载）；
**只依赖 `dart:io`**（目标平台 Android / Windows），**未新增依赖、未动 `pubspec.yaml`**；
本段**不含测试**（按裁定拆为段 1b：本地 `HttpServer` 打桩验契约解析、`Bearer` 注入与非 2xx 映射），
故只有静态门禁 `flutter analyze` → `No issues found!`；`main.dart` / `widget_test.dart` 仍是模板原样。
file / map 其余业务方法按 testing 第 3 节矩阵推进。
Flutter 工程**段 0（脚手架）+ 段 1（契约/通信基座）已就位**，但**业务未开工**——
Android 单兵端与 Windows 指挥端**均无业务代码**（无登录、无态势、无入口装配），也**未与后端联调**。

实际版本为Java21、Spring Boot **3.5.7**、MySQL本机8.4.11；
以pom和当前代码为准，不沿用历史记忆里的Boot3.3.5。
Flyway文件V1-V7、V9-V11，共10份；18张业务表，另有Flyway历史表。
原预留V8自愈迁移未创建，而本地已应用V9；V10为演习存活/自愈迁移，V11为底图标定时刻列。

## 4. 验证证据与局限

**当前**全量`mvn test`：2026-10-28，**1151项，失败/错误/跳过均0**：
protocol19 + gateway49 + core611 + api139 + app333（solver/domain无测试）。
计数须按 Maven **模块级汇总**（`[INFO] Tests run:`）取值；按 `target/surefire-reports/TEST-*.xml`
累加会少 core 的 21 项（`@Nested` 内部类如 `TrackSamplingTest$Sampler`/`$Writer` 不落在外层类 XML 里），
口径说明见 testing 第 20 节。
L6 单实例保护口径收口新增证据：**无测试增减**——本任务只改类 javadoc 与文档（`testing.md` §28.3/§29.3 订正 +
新增第 30 节、`decision-register.md` 网关下行段订正 + 文末「L6 口径订正」节），全量仍 **1151 项**（0/0/0）不变；
判据与现场验收清单详见 testing 第 30 节。
R4 遗留收口新增证据：**无测试增减**——本任务在既有 `ReplayExportHttpIT.xlsxExportOverRealHttp` 内扩断言
（`@BeforeAll` 加固定 `scds.files.sign-secret` + 新辅助 `resignWithExp`/`hmac`），全量仍 **1151 项**（0/0/0）不变；
定向 `mvn -o -pl scds-app -am test -Dtest=ReplayExportHttpIT` 亦 2/2 绿（0/0/0），构造口径详见 testing 第 31 节。
M6 CLIENT-BASE 段 0（Flutter 脚手架）证据：**Maven 侧无增减**——本段产物在 `clients/scds_client`（Dart），
**不在 reactor 内**，全量仍 **1151 项**（0/0/0）；客户端侧门禁为 `flutter analyze` → `No issues found!`
与 `flutter test` → `All tests passed!`（模板默认 Counter widget test），详见 testing 第 32 节。
**Windows 桌面端未构建**（本机缺 Visual Studio）；Android 端未启动模拟器 `zaa_pixel6`，故**不代表任何联调通过**。
M6 CLIENT-BASE 段 1（共享契约/通信基座）证据：**Maven 侧同样无增减**（Dart 侧资产，**不在 reactor 内**），
全量仍 **1151 项**（0/0/0）；本段**不含测试**，客户端侧门禁只有 `flutter analyze` → `No issues found!`；
测试按裁定留段 1b（本地 `HttpServer` 打桩验契约解析、`Bearer` 注入与非 2xx → `ApiException` 映射），
详见 testing 第 33 节。
M3 LIFE 开演链路就绪 409 硬前置新增证据：core `ExerciseLifecycleServiceIT` **+1**（610 → 611，
新增 `startExercise_withVestLinkClosed_isDeviceLinkNotOpen` 并加 `@BeforeEach openVestLink()` 补前置）；
app `ExerciseLifecycleHttpIT` **+1**（331 → 333 中的一笔，新增 `startWithVestLinkClosedIsRejectedAs409`）、
`ExerciseDownlinkHttpIT` **+1**（3 → 4，开演侧反转为 409 + 拆分「停演侧链路已关仍是 200」独立用例）；
既有 app IT 同步补「先开串口链路」前置（`DeviceSelfHttpIT`/`PushCrashRecoveryIT`/`CommandHttpIT`/
`ReplayExportHttpIT`，其中 `ReplayExportHttpIT` 另需 `scds.gateway.mode=simulator`）；
全量 **1151 项**，详见 testing 第 29 节。
M3 LIFE 网关下行接线新增证据：core 新增 `ExerciseLifecycleServiceDownlinkTest` **5 例**（605 → 610，
`DeviceLinkAdmin` 桩 + `InOrder` 验「发了什么、按什么顺序、失败留不留痕」，逐帧与 `DownlinkCommands`
现编码逐字节比对，不写死 hex 字面量）；app 新增 `ExerciseDownlinkHttpIT` **3 例**（328 → 331，
真 Boot + 真 MySQL + 真安全链 + `scds.gateway.mode=simulator`，专属编号段 62500+）；
全量 **1148 项**，详见 testing 第 28 节。
M3 CAL-2 现场验收收口新增证据：core `BaseMapRepositoryIT` **+2**（603 → 605，批量 `IN` 读
`calibrated_at` 的「只回已标定行」与「空入参回空表」两条语义）；app `MapBaseHttpIT` 计数**不变**
（在既有端到端用例内加 `calibratedAt` 的前后断言，并校验列表整页批量读与详情给出**同一时刻**）；
详见 testing 第 27 节（含计数表，并订正 testing 第 26 节计数表**漏列 protocol 19 条**的口径）。
R4 HTTP 端到端补测新增证据：app `ReplayExportHttpIT` **2 例**（326 → 328，真 Boot + 真库 + 真安全链
端到端验三端点），详见 testing 第 26 节。
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

2026-10-28新增已确认（M3 CAL-2 现场验收收口，见 testing 第 27 节）：
标定时刻 `calibrated_at` 是**重启后唯一还在的证据**（标定会话状态本身只在内存里），故在契约
`MapDetail` 加**可空** `calibratedAt`（纯新增、向后兼容）并接通读侧；
它与 `calibrated` **同源但不等价**——`calibrated` 判「参数是否齐备」，本字段记「最近一次落库的时刻」，
故**非空必然 `calibrated=true`，反之不成立**（改名/换图型不刷新它）；
读取走 `BaseMapRepository.findCalibratedAt` 的**批量 `IN` 查询**（列表整页一次取回，避免 N+1），
**不给 `BaseMap` 领域记录加第 11 个分量**（不扰动二十余处 `new BaseMap(...)`）；
`saveUwbMeta` 仍是 `calibrated_at` 的**唯一写点**；
50mm 静止阈值**已可配**（`scds.uwb.calib-static-max-diff-cm`，默认 5.0cm → 服务层 ×10 = 50mm），
本段**不改码**、现场调参即可；四角编号（`bs1` 左下 → `bs2` 右下 → `bs3` 右上 → `bs4` 左上）
**仍只有文档约定、无代码校验**，按裁定**不动代码**、只入现场验收清单（`validateUwbMeta` 只拦缺角/越界/
三点共线/长宽非正整数）；**未改 openapi、未新增错误码、未新增迁移、未新增依赖**。

2026-10-28新增已确认（M3 LIFE 网关下行接线，见 testing 第 28 节）：
开演/停演的下行落在 `ExerciseLifecycleService` 的**事务尾段**——先做完库写入与态势装载/冻结，**再发硬件**
（开演：逐台 14B 轮询配置帧 → 最后 7B 开始帧；停演：先冻结终局 → 再 7B 结束帧）；
发送失败**只留痕不回滚**（catch 后 WARN + 写 `t_exercise_log(SYSTEM)` 存证，方法照常返回、事务照常提交、
响应仍 201/200，**不发布半启动态**）——这是 D11 的落地口径：物理副作用（已发字节）无法由 JDBC 撤回；
链路未开（`snapshot()` 无 `SERIAL_VEST` 且非 `OPEN`）**静默跳过**（只 WARN，不发字节、不写日志）；
全体下行的占位马甲编号 `BROADCAST_VEST_NUM = 0`，写日志时该字段记 **`NULL`**；
停演响应补 `replayId = exerciseId`；
**未做**：硬件 ACK 解析（「已发送」≠「已确认」那条线仍在）；
**未新增错误码、未新增迁移、未新增依赖**，协议冻结字节一字未动。

2026-10-28新增已确认（M3 LIFE 开演链路就绪 409 硬前置，见 testing 第 29 节）：
开演前必须存在处于 **`OPEN`** 的 `SERIAL_VEST` 链路，否则 409 **`DEVICE_LINK_NOT_OPEN`**
（**复用**既有码，未新增；该码早已在 `ApiErrorCode`、`ApiExceptionHandler.statusOf` 与契约
`common.yaml` 三处登记）；判据落在 `ExerciseLifecycleService.startExercise`，位置在**想定/编成校验之后、
插库之前**，与紧随其后的硬件下发**共用同一个 `vestLinkOpen()`**；
判据顺序固定为 503 门禁 → 404 想定不存在 → 400 编成为空 → 409 `DEVICE_LINK_NOT_OPEN` →
插库（409 `EXERCISE_ALREADY_RUNNING`）——全部前置条件都在写库之前判定，被任一条件拒下的请求
**不留半启动状态**；**`OPENING` 不算就绪**（只认 `LinkState.OPEN`）；
**停演刻意不设此门禁**——卡死的场次必须随时停得掉，故 `stopExercise` 的 409 仍只表示
「当前没有演习进行中」；第 28 节的「链路未开静默跳过」**保留为防御**，只兜「门禁检查与下发之间
链路被并发关闭」的窗口；**未新增错误码、未新增迁移、未新增依赖、未改协议冻结字节**。

2026-10-28新增已确认（L6 单实例保护口径收口，见 testing 第 30 节）：
**L6 不是待开工的代码任务，而是部署前提**——归属**进程管理层**（`environments.md` §6.4，
四形态要求：容器固定 `container_name` + `restart: unless-stopped` 且禁 `--scale`／原生服务单实例单元／
升级先停后启／不允许第二节点、无 HA）；应用侧该有的三件**已具备且有 IT 覆盖**——开演
`claim(owner_instance)`、2000ms 心跳（守卫带 owner）、`uk_exercise_running` 唯一索引
（V4 生成列 `running_flag`），它们只保证「一场之内」不自相矛盾，**挡不住**第二进程启动时
`ExerciseLivenessRecover` 把在跑场次**不区分 owner** 一律收尾（`endReason=CRASH_RECOVERED` /
`fail_reason='INSTANCE_RESTART'`）；按 D05 **不引分布式中间件、不带库级会话锁或 `GET_LOCK`、
不做租约**，故**无 Java 改动**；本段为**口径订正**（统一 `ExerciseLifecycleService` 类 javadoc、
`testing.md` §28.3/§29.3、`decision-register` 网关下行段原先互相矛盾的「未做／仍未落地」表述，
一律以 §6.4 为准），**未新增代码、未新增错误码、未新增迁移、未新增依赖**，全量计数仍 **1151** 项。

2026-10-28新增已确认（R4 遗留收口：`downloadUrl` 签名窗口过期的 HTTP 用例，见 testing 第 31 节）：
`downloadUrl` 的**签名本身过期**（签名正确、仅 `exp` 已过）与「签名被篡改」**共码** 403
`FILE_URL_INVALID`（`FileController` 刻意不拆分两者：客户端处置动作相同——回详情接口取新 URL，
区分只会告诉探测者「路径确实存在，只是过期了」）；**构造口径**——签名器持 `Clock.systemUTC()`、
HTTP IT 推不动时钟，故在**测试侧按契约独立重算**签名（`@BeforeAll` 显式给固定 `scds.files.sign-secret`，
按 `路径 + '\n' + exp` 的 HMAC-SHA256、Base64URL 无填充），**先用「未过期」正例自证重算与服务端一致，
过期样本的 403 才说明是过期而非签错**；`path` 参数原样沿用原 URL 已编码片段、只换 `exp` 与 `signature`；
**未改验签逻辑、未引入可注入 Clock 到生产、未新增用例**（即测试侧扩断言），全量计数仍 **1151** 项。

2026-10-28新增已确认（D12 / D13 与 M6 CLIENT-BASE 段 0，见 testing 第 32 节）：
**D12** 取「**单工程 + 共享 Dart 契约/通信包 + Android 单兵入口 / Windows 指挥入口**」——一个 Flutter 工程
承载双端、契约与通信代码共享、两平台入口各自装配，**不**拆多工程、**不**引代码生成器产 SDK；
**D13** 授权**仅一次** `flutter create` 脚手架例外（固定目录与文件清单、**不含业务**），
**不成为后续常规手段**，业务代码仍守「每原子任务 ≤3 文件」；
段 0 生成 `clients/scds_client`（`--org com.dualx --project-name scds_client --platforms=android,windows`，
磁盘 50 / git 跟踪 45 文件），依赖仅模板默认，`flutter analyze` 无问题、`flutter test` 通过；
**Windows 端本机不能原生构建**（`vswhere` 不存在、`flutter doctor` 缺 VS 的 Desktop development with C++，
安装工作负载**需另授权**）；本段**未写业务、未新增依赖、未改后端与协议冻结项**，
Maven 全量计数仍 **1151** 项。

2026-10-28新增已确认（M6 CLIENT-BASE 段 1 共享契约/通信基座，见 testing 第 33 节）：
客户端 HTTP 通信**只依赖 `dart:io`**（`HttpClient`），**不引第三方 HTTP 包、不动 `pubspec.yaml`**
（目标平台只有 Android / Windows，`dart:io` 够用）；契约常量（端口 11001、`/api/v1`、`/ws/v1`、
`application/problem+json`）与 `ApiErrorCode` 是 `common.yaml` / `openapi.yaml` 的**逐字镜像**，
**客户端不新增、不改名错误码**；错误模型 `ApiProblem` 按 RFC 7807 三必填字段（`status`/`code`/`traceId`）
解析，`tryParse` 对不合契约的正文返回 `null`——**客户端不臆造缺省值**；`send` 在非 2xx 时
**解析 Problem 后抛 `ApiException`**（不做静默吞错）；`ApiResponse.body` 保留原始字节，供后续
产物下载端点复用；本段**不含测试**（按裁定拆为段 1b），故只有静态门禁 `flutter analyze`。

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
最新证据：M6 CLIENT-BASE 段 1——共享契约 / 通信基座（`lib/shared/api/` 三文件，只依赖 `dart:io`，
非 2xx 抛 `ApiException`，本段不含测试、门禁为 `flutter analyze`）详见 testing 第 33 节；
M6 CLIENT-BASE 段 0——Flutter 工程脚手架（`clients/scds_client`，D12 双入口 / D13 一次性例外，
`flutter analyze` 无问题 + `flutter test` 通过，不含业务）详见 testing 第 32 节；
R4 遗留收口——`downloadUrl` 签名窗口过期的 HTTP 用例（测试侧独立重算签名，
在既有 `ReplayExportHttpIT.xlsxExportOverRealHttp` 内扩断言）详见 testing 第 31 节；
L6 单实例保护口径收口（归属进程管理层，以 `environments.md` §6.4 为准，无 Java 改动）
详见 testing 第 30 节；
M3 LIFE 开演链路就绪 409 硬前置（`DEVICE_LINK_NOT_OPEN`，含既有 IT 的「先开串口链路」改造）
详见 testing 第 29 节；
M3 LIFE 网关下行接线（开演 14B/7B 与停演 7B 出站，含 D11 落地口径）详见 testing 第 28 节；
M3 CAL-2 现场验收收口（`MapDetail.calibratedAt` 可读性）详见 testing 第 27 节；
M5 REPLAY R4（导出与下载去 501）详见 testing 第 26 节；
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
本快照已同步 1151 项测试、M6 CLIENT-BASE 段 1（共享契约 / 通信基座 `lib/shared/api/` 三文件，
**只依赖 `dart:io`**、非 2xx 抛 `ApiException`、本段不含测试）与段 0（Flutter 工程脚手架 `clients/scds_client`，
D12 双入口 / D13 一次性例外，`flutter analyze` + `flutter test` 通过，**均已推送**）、
R4 遗留收口（`downloadUrl` 签名窗口过期 HTTP 用例，测试侧独立重算签名，
在既有用例内扩断言）、L6 单实例保护口径收口（归属进程管理层，以 `environments.md` §6.4 为准，
无 Java 改动）、M3 LIFE 开演链路就绪 409 硬前置（`DEVICE_LINK_NOT_OPEN`，含既有 IT 的
「先开串口链路」改造，**已推送** `8dda7be..f5bb261`）、M3 LIFE 网关下行接线（开演 14B/7B 与停演 7B 出站，D11 落地口径，
**已推送** `0d8ef02..8dda7be`）、M5 REPLAY 四段（R1 读侧 / R2 轨迹分片 / R3 统计 / R4 导出下载，
含 HTTP 端到端补测）与 M3 CAL-2 现场验收收口（`MapDetail.calibratedAt` 可读性，**均已推送**）
与下一任务候选（M6 CLIENT-BASE 段 1 契约/通信基座 / T-PROD 部署），
不要求重做已完成 SEC/ERR/AUTH/LEDGER/PLAN/MAP-BASE/LIFE/STATE/PUSH/DEVICE-SELF/T-STOP/COMMAND/CAL-1/CAL-2。
