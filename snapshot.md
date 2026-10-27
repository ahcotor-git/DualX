# scds-next 交接快照

> 更新：2026-10-27，Asia/Shanghai。此快照用于新对话接续，不是生产验收证明。
> 当前任务：**M3 CAL-1 UWB 室内解算链路（解算器 + 装配接线 + app 端到端）已完成并本地提交**；
> 最新全量 `mvn test` **1055 项，失败/错误/跳过均 0**（见 testing 第 15、16、17 节）。
> 下文 2026-10-21 的部分保留为历史，凡与 testing 第 12~17 节冲突处，以该处为准。

## 1. 从这里继续

仓库：`e:\DualX\scds-next`。先读本快照，再读
[审查清单](docs/review.md)、[需求](docs/requirements.md)、
[架构与实施方案](docs/architecture.md)、[环境](docs/environments.md)、
[测试](docs/testing.md)和[下一对话提示词](docs/next-chat-prompt.md)。
HTTP/WS唯一契约入口是 [openapi.yaml](docs/api/openapi.yaml)。

已完成的里程碑（逐项证据在 testing 第 7~17 节）：SEC-01 / SEC-02 / ERR-01、TEST-01（隔离库 +
完整应用启动）、AUTH-HTTP / DEVICE-AUTH / AUTH-HARDEN、M2 LEDGER（台账/基站/绑定闭环）、
M2 PLAN（想定与编成、导入导出）、M2 MAP-BASE（底图 CRUD / 图片 / 楼层点位）、
M3 LIFE（开停演与恢复门禁、单实例保护）、M3 STATE（态势状态机与读侧）、
M4 PUSH-WS（判据 1~9、背压与保活）、M4 DEVICE-SELF、M4 T-STOP（停机释放链路），
**M3 COMMAND MR-1 + MR-2 + MR-3**，以及 **M3 CAL-1（UWB 室内解算链路：CAL-1a 解算器 /
CAL-1b 装配接线 / CAL-1c app 端到端）**。

下一原子任务的候选（详见 testing 第 17 节与第 3 节矩阵）：M3 **CAL-2**（UWB 标定本体：
三端点去 501、进程内状态机、`V11` 校准列、现场实测长宽覆盖写回 `uwb_meta`）；COMMAND 剩余的
`move`（等地图置位写入口）；M5 EXPORT（replay 导出，仍 501）；`getVestLink` 仍 501；Flutter 客户端
（Android 单兵端 / Windows 指挥端）尚无工程。启动前须重新核查仓库与环境，不照搬本快照的瞬时结论。
提交/推送仍须当次授权；含 CAL-1c 在内的本轮提交推送状态见第 2 节，网络失败时不得写成已入远端。

## 2. Git与工作树

| 项 | 当前状态 |
|---|---|
| 分支 | main |
| 源代码HEAD | 上一步 `ce2b33d` 已入远端 `main`；本轮 CAL-1c 的 test + docs 两个提交随后补推 |
| 实际远端main | 本次提交前与本地 HEAD `ce2b33d` 一致；前几次 `git push` 曾因 443 连接被重置/超时失败，改用 `git -c http.version=HTTP/1.1 push` 后成功 |
| 已推送 | `78ce05e`（feat 室内解算 IndoorPositionResolver）、`05563a7`（docs 四角编号更正为 bs1↔bs4）、`0c80727`（feat 41B 帧解算接入态势写侧）、`eda1b1a`（docs CAL-1 口径回写）、`ce2b33d`（docs CAL-1 推送状态与里程碑） |
| 本轮继续成果 | 工作树含 CAL-1c 改动：`scds-app/.../SituationStateIT.java`（+2 用例）+ `docs/testing.md`（第 17 节）；合入 CAL-1c 后全量 1055 项全绿 |
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
未完成：COMMAND 的 `move`（手动置位，等地图置位写入口）；`getVestLink` 仍 501；
M3 **CAL-2**（UWB 标定本体：三端点去 501、`V11` 校准列、现场实测长宽覆盖写回 `uwb_meta`）与
M5 EXPORT（replay 导出）仍 501；
replay / file / map 其余业务方法按 testing 第 3 节矩阵推进。
Flutter工程尚无（Android 单兵端 / Windows 指挥端均未开工）。

实际版本为Java21、Spring Boot **3.5.7**、MySQL本机8.4.11；
以pom和当前代码为准，不沿用历史记忆里的Boot3.3.5。
Flyway文件V1-V7、V9-V10，共9份；18张业务表，另有Flyway历史表。
原预留V8自愈迁移未创建，而本地已应用V9；V10为演习存活/自愈迁移。

## 4. 验证证据与局限

**当前**全量`mvn test`：2026-10-27，**1055项，失败/错误/跳过均0**：
protocol19 + gateway49 + core528 + api139 + app320（solver/domain无测试）。
本段新增证据为 CAL-1（解算链路）共 3 段：core `IndoorPositionResolverTest` 12 +
`SituationStateServiceTest` +4 + `SituationSessionBinderTest` +2（CAL-1a/1b），
app `SituationStateIT` +2（CAL-1c，真装配 + 真库底图 + 真 `UwbFramer` 端到端），详见 testing 第 17 节；
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
| R08 | **已闭合**：auth/device/exercise/command/situation/map/plan 业务方法均已接线；仅 `move`、`getVestLink`、UWB 标定与 replay 导出仍 501 |

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
空库已迁移V1-V7/V9/V10，两库校验和一致；开发与测试account/refresh/exercise均0。

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
`t_plan_roster` 的 hp/ammo 范围校验**单列任务**，本次不拦。

2026-10-27新增已确认（M3 COMMAND MR-3 队列过期扫描，见 testing 第 16 节）：
超时窗 `scds.command.sent-ttl` 默认 **30s**、扫描周期 **5s**；只扫 `state='SENT'`（`PENDING` 在本实现下
不产生，是「等重连」的派发语义，不判超时）；顺序为**先写 SYSTEM 存证 → 再作废 EXPIRED
（`fail_reason='SENT_TIMEOUT'`）→ 再 `deleteFinished()` 清终态行**，同一事务；
载体为 core `@Component` + `Instant.now()`（不注入 `Clock`，否则 core 测试上下文起不来）；
超时是**队列卫生**语义而非「等回执」（协议无回执帧，`ACKED` 仍无人写入）。

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
最新证据：M3 CAL-1（含 CAL-1c）详见 testing 第 17 节；COMMAND MR-3 详见第 16 节、MR-1/MR-2 详见第 15 节；
T-STOP 详见第 14 节；DEVICE-SELF 详见第 13 节；M2 LEDGER 详见第 12 节；TEST-01 全量应用启动详见第 11 节。
本快照已同步 1055 项测试、M3 CAL-1 交付与下一任务候选（CAL-2），
不要求重做已完成 SEC/ERR/AUTH/LEDGER/PLAN/MAP-BASE/LIFE/STATE/PUSH/DEVICE-SELF/T-STOP/COMMAND/CAL-1。
