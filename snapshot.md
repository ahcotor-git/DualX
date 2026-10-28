# scds-next 交接快照

> 更新：2026-10-29，Asia/Shanghai。此快照用于新对话接续，不是生产验收证明。
> 当前任务：**M6 CLIENT-WINDOWS 续段（UI 接线）——command 域指挥指令页 + 全体充弹入口（testing 第 47 节）**——
> 承接段 46 共享层（只落 models + api + 测试），按用户当次裁定「**command 域 UI 接线（推荐）**」，
> 并在入口与范围决策点选「**态势页每行 → 单兵指令页（推荐）**」＋「**先做核心 7 端（不含 move）（推荐）**」。
> 范围＝**把 command 域写端点接进指挥端 UI**：全体态势页每行点开 `CommandPage`（六端口：判死 / 判活 / 判伤 / 超范围 /
> 单兵充弹 / 覆盖配置），AppBar「全体充弹」广播入口（`reload-all`）；`moveVest`（手动置位、`x-internal`）**不接 UI**；
> **不含**地图/标定、想定、复盘导出。
> **一笔** `2a42efb`（**4 文件** / 956 insertions / 10 deletions，**1 新 3 改**）：`lib/features/console/command_page.dart`（**新**：
> `CommandPage` 六端口 + **17 控件键** + `describeCommandAck()`）＋ `lib/features/console/overall_situation_page.dart`（改：单元行改
> `_unitRow`（可点击 → `onCommand(vestNum)`）、AppBar 加「全体充弹」→ `_ReloadAllDialog` + `reloadAll` 端口）＋
> `lib/app/console_app.dart`（改：`CommandApi` + `_openOverallSituation` 传 `onCommand`/`reloadAll` + `_openCommand`）＋
> `test/console_widget_test.dart`（改，**+10 例**）。**口径要点**：**HTTP 200 只表示「已受理」**——`accepted=false`
> 属成功受理，前端 **SnackBar 提示「未下发」而非报错**；**全体广播回执显示「全体」**（`ack.isBroadcast == (vestNum == 0)`）；
> 覆盖配置 `toJson()` **只发非 null 键**；目标文案 `目标 #14｜admin（OPERATOR）`。
> **本原子任务 4 文件，破「≤3 文件」上限，已按无人值守授权显式声明例外**。
> 门禁 `flutter test` → **225 例全绿**、`flutter analyze` → `No issues found!`；Maven 侧 **0/0/0**（基准 1151 项）。
> **未验项**：**未对着真实后端实例**跑过真实指令下发（判死 / 充弹 / 覆盖配置均未真连）；`moveVest` UI 未接。
> 本任务紧接 **M6 CLIENT-WINDOWS 续段（写操作共享层）——command 域指挥指令客户端：8 个写端点（testing 第 46 节）**——
> 承段 45「只读先行，写入另开段」，按用户当次裁定「**继续**」，并在决策点选「**共享层全 8 端点（推荐）**」
> （只落 models + api + 测试，恰 3 文件；UI 接线另开段）＋ `moveVest`「**纳入（推荐）**」。
> 范围＝command 域**全部 8 个写端点**（判死 / 判活 / 判伤 / 超范围 / 单兵充弹 / 全体充弹 / 覆盖配置 / 手动置位）；
> **不含** UI 接线、地图/标定、想定、复盘导出。
> **一笔** `e464452`（3 文件 / 516 insertions，**3 新**）：`lib/shared/api/command_models.dart`（新：`WeaponSlot`/`VestMode`
> 两枚举 + `ReloadRequest`/`VestConfigRequest`/`MoveVestRequest` 三请求体 + `CommandAck`；`Team` 复用 `situation_models.dart`）＋
> `lib/shared/api/command_api.dart`（新：`CommandApi` 八端点）＋ `test/shared/api/command_api_test.dart`（新，**12 例**）。
> **口径要点**：**HTTP 200 只表示「已受理」**——`accepted=false`（马甲离线 / 队列满）仍属成功受理、**未下发任何字节**、
> `queuedAt` 为空；**全体充弹 `vestNum` 固定为 0**（显示「全体」，不是编号 0 的马甲）；**覆盖配置**未传字段保持原值，
> `toJson()` **只发非 null 键**；**全部 POST/PUT**（`setVestConfig` 是唯一 `PUT`）；`moveVest` 联调专用（正式演习拒 403）。
> 本原子任务**恰 3 文件**，**未破上限**。
> 门禁 `flutter test` → **215 例全绿**、`flutter analyze` → `No issues found! (ran in 2.3s)`；Maven 侧 **0/0/0**（基准 1151 项）。
> **未验项**：**未对着真实后端实例**跑过真实指令下发；**UI 尚未接线**。
> 本任务紧接 **M6 CLIENT-WINDOWS 续段（只读先行）——exercise 域只读客户端：当前演习状态 + 当前演习日志流水（testing 第 45 节）**——
> 按用户当次裁定「**推进下一段**」，并在决策点选「**指令 + 当前演习日志流水（推荐）**」＋「**只读先行，写入另开段（推荐）**」。
> 范围＝exercise 域**两端点只读**（`GET /exercise/current` + `GET /exercise/current/logs`）；
> **指令（[command.yaml](docs/api/paths/command.yaml) 的 8 个写端点）另开段**，**不含**地图/标定、想定、复盘导出。
> **一笔** `88e2b34`（3 文件 / 501 insertions，**3 新**）：`lib/shared/api/exercise_models.dart`（新：`BattleMode`/`ExerciseEndReason`/
> `ExerciseLogType` 三枚举 + `Exercise`/`ExerciseLogEntry`/`ExerciseLogPage` 三类）＋ `lib/shared/api/exercise_api.dart`
> （新：`ExerciseApi` 持窄端口 `ApiTransport`，两端点 `getCurrentExercise` / `listCurrentExerciseLogs`）＋
> `test/shared/api/exercise_api_test.dart`（新，**8 例**）。
> **口径要点**：**无演习回 HTTP 200**（`running=false` / 空页 + `latestSeq=null`，**不是 404**）；日志是**游标语义** `{items, latestSeq}`；
> `SYSTEM` 条目可能无 `vestNum`；`Exercise` DTO 实现**未下发** `endReason`（保留字段不臆造）；未知枚举字面量 → `FormatException`。
> 本原子任务**恰 3 文件**，**未破上限**。
> 门禁 `flutter test` → **203 例全绿**、`flutter analyze` → `No issues found! (ran in 3.7s)`；Maven 侧 **0/0/0**（基准 1151 项）。
> **未验项**：**未对着真实后端实例**跑过真实当前演习状态 / 日志流水拉取；写入类指令（command 域）**尚未实现**。
> 本任务紧接 **M6 CLIENT-RESILIENCE 段 D（后半）——指挥端「全体态势」页接 `SituationFeed`（WS + 10s 兜底 Poll 双通道）（testing 第 44 节）**——
> 按用户当次裁定「**继续**」，并在决策点选「**页面自建（推荐）**」（`SituationFeed` 由页面 `initState` 建、`dispose` 释放）
> 与「**单元+连接状态+重同步（推荐）**」（页面内容范围）。范围＝**把段 A/B 的 `SituationFeed` 接进指挥端一个新页**
> （单元列表 + 连接通道状态文案 + AppBar「重新同步」按钮），**不含**指令、地图/标定、想定、复盘导出。
> **一笔** `8bdd6d7`（4 文件 / 508 insertions，**1 新 3 改**）：`lib/features/console/overall_situation_page.dart`（新：
> `OverallSituationPage` 页面自建 `SituationFeed`，`kOverallSituation*` 控件键族；**增量帧 `teamSummary` 恒空**故不在此页渲染队伍汇总）+
> `lib/features/console/team_summary_page.dart`（改：AppBar 加「全体态势」入口 `kTeamSummarySituationEntryKey`，
> 未注入 `onOpenSituation` 时不渲染）＋ `lib/app/console_app.dart`（改：`_openOverallSituation` 把 `fetch`/`connect`
> 两个端口装配到当前账号令牌）＋ `test/console_widget_test.dart`（改，+7 例 + 假件 `TestSituationSocket`/`TestSituationConnector`）。
> 门禁 `flutter test` → **195 例全绿**、`flutter analyze` → `No issues found! (ran in 3.4s)`；Maven 侧 **0/0/0**（基准 1151 项）。
> **未验项**：**未对着真实后端实例跑过真实 WS 握手 / 真实 close `4000` / 真实增量归并**。
> **单兵端不在本段范围且非遗漏**：WS 握手只认账号令牌（`03-auth.md` Q7：设备令牌连 WS → `403`；§4.7：令牌走 `Authorization: Bearer` 头），
> 且 `DeviceSession` **无 `refreshToken`**。
> 本任务紧接 **M6 CLIENT-RESILIENCE 段 D（前半）——指挥端认证接线（会话托管 + 手动续期委托 + 会话终结导航）（testing 第 43 节）**——
> 按用户当次裁定：「**RES-D UI 接线（推荐）**」、节奏「**沿用：代码 1 笔 + docs 1 笔 + 尝试推送（推荐）**」、
> 「**拆两段，先做认证接线（推荐）**」、指挥端账号页「续期」按钮「**委托 transport（推荐）**」。
> 范围＝**把段 C 的装饰器接进指挥端外壳**（登录即 `adopt`、账号页「续期」按钮委托 `refreshNow()`、
> 会话终结由 `onLost` 清存储并**弹回登录页**），**不含**「全体态势」页接 `SituationFeed`（段 D 后半）、指令、地图/标定、想定、复盘。
> **一笔** `1c47d68`（5 文件）：`lib/shared/auth/refreshing_api_transport.dart`（改，加 `adopt` / `forget` / `refreshNow`）+
> `lib/app/console_app.dart`（改，全链路委托装饰器 + `kConsoleLoginRouteName` + `_returnToLogin`）+
> `lib/features/console/account_page.dart`（改，仅 typedef 文档）+ `test/shared/auth/refreshing_api_transport_test.dart`（改，+6 例）+
> `test/console_widget_test.dart`（改，+4 例 + 脚本化传输桩 `ScriptedTransport`）。
> 门禁 `flutter test` → **188 例全绿**、`flutter analyze` → `No issues found! (ran in 3.2s)`。
> **未验项**：**未对着真实后端实例跑过真实续期 / 真实会话终结**；「全体态势」页**尚未接 `SituationFeed`**（段 D 后半）。
> **单兵端不在本段范围且非遗漏**：WS 握手只认账号令牌（`03-auth.md` Q7：设备令牌连 WS → `403`；§4.7：令牌走 `Authorization: Bearer` 头），
> 且 `DeviceSession` **无 `refreshToken`**。
> 本任务紧接 **M6 CLIENT-RESILIENCE 段 C——认证单飞（401 → 刷新 → 重试一次）+ `ApiTransport` 窄端口抽取（testing 第 42 节）**——
> 按用户当次裁定：方向取「**CLIENT-RESILIENCE（推荐）**」、节奏「**沿用（推荐）**」（＝代码 1 笔 + docs 1 笔 + 提交后尝试推送）。
> 范围＝**传输层装饰器与端口抽取**，**不含**把装饰器接到 `device_app` / `console_app`（留 RES-D）、指令、地图/标定、想定、复盘。
> **一笔** `5e83692`（6 文件）：`lib/shared/auth/refreshing_api_transport.dart`（新）+
> `test/shared/auth/refreshing_api_transport_test.dart`（新，15 例）+ `lib/shared/api/api_client.dart`（改，新增 `ApiTransport` 端口）+
> `auth_api.dart` / `situation_api.dart` / `health_api.dart`（改，构造参数 `ApiClient` → `ApiTransport`）。
> 门禁 `flutter test` → **178 例全绿**、`flutter analyze` → `No issues found! (ran in 2.6s)`。
> **未验项**：**未对着真实后端实例跑过真实续期**（滚动刷新是否即时作废旧 `refreshToken` / 撤销重放的连锁撤销 /
> `expiresIn` 与服务端时钟偏差均未验证）；装饰器**尚未接线到任何页面**。
> 本任务紧接 **M6 CLIENT-RESILIENCE 段 A/B——态势本地缓存（双通道同源归并）+ 双通道调度器（WS + 兜底 Poll）（testing 第 41 节）**——
> 按用户当次裁定：方向取「**CLIENT-RESILIENCE（推荐）**」、节奏「**沿用（推荐）**」。
> 范围＝**本地缓存归并（段 A）+ WS 连接端口与双通道调度（段 B）**，
> **不含**认证单飞（留 RES-C，已完成）、UI 接线（留 RES-D）、指令、地图/标定、想定、复盘。
> **段 A** 落 `7a5004f`（2 文件）：`lib/shared/state/situation_cache.dart`（新）+ `test/shared/state/situation_cache_test.dart`（新，15 例）。
> **段 B** 落 `a0c8ff2`（4 文件）：`lib/shared/state/situation_socket.dart`（新）+ `lib/shared/state/situation_feed.dart`（新）+
> `lib/shared/api/api_contract.dart`（改，+`kSituationSubprotocol`）+ `test/shared/state/situation_feed_test.dart`（新，15 例）。
> 段 A/B 门禁 `flutter test` → **163 例全绿**、`flutter analyze` → `No issues found! (ran in 2.7s)`。
> **未验项**：**未对着真实后端实例建立过真实 WS 连接**（子协议协商 / `Bearer` 握手 / 30s 无上行断开 / 真实 close `4000` /
> `PING`·`PONG` 往返均未验证）。
> 本任务紧接 **M6 CLIENT-WINDOWS 段 6——指挥端账号与会话（续期 refresh + 身份重载 me + 无体 logout）（testing 第 40 节）**——
> 按用户当次裁定：范围取「**CLIENT-WINDOWS 续段：账号会话（refresh/me/logout）**」，并在无人值守下就两处语义裁定
> 「**logout 无论成败都清本地会话**」与「**refresh 手动按钮触发、不引定时器**」。范围＝**续期 + 身份重载 + 退出**，
> **不含** WS / revision 缓存聚合 / 指令 / 地图标定 / 想定 / 复盘。
> **一笔**（`lib/features/console/account_page.dart`（新）+ `team_summary_page.dart` 账号入口 +
> `console_login_page.dart` 注释订正 + `console_app.dart` 装配与 `_account` 持有当前会话 +
> `console_widget_test.dart` 新增 15 例）；门禁 `flutter test` → **133 例全绿**、`flutter analyze` → `No issues found!`。
> **未验项**：本机**未安装 Visual Studio**，Windows 原生构建与真机联调**不可执行**；续期/退出的真实令牌流转未在真实实例上验证。
> 本任务紧接 **M6 CLIENT-WINDOWS 段 5——Windows 指挥端骨架（平台入口分派 + 连接配置健康诊断 +
> 账号登录 + 队伍汇总总览）（testing 第 39 节）**——按用户当次裁定：「**段 5 Windows 指挥端骨架**」，
> 并在无人值守下授权「**按推荐方式继续**」且**放宽「每原子任务 ≤3 文件」上限**（改超 3 文件不再单独授权；
> 其余顶层纪律不变）。范围＝**入口分派 + 连接配置（含 `/actuator/health` 健康诊断）+ 账号登录 +
> 队伍汇总总览（HTTP Poll）**，**不含 WS / revision 缓存聚合 / refresh·me·logout / 指令 / 复盘**。
> **共享层笔** `17a0e10`（4 文件）：`lib/shared/api/api_client.dart`（抽出私有 `_send`，新增 `probe(path)`
> 走 `resolve()`——**不带** `/api/v1` 前缀，供应用级端点探活）＋ `lib/shared/api/health_api.dart`（新：
> `kHealthPath` / `HealthReport` / `InstanceHealthApi.probe()`，**永不抛异常**，把 2xx / 非 2xx / 网络失败
> 收敛成 `UNKNOWN` / `HTTP_<code>` / `UNREACHABLE`）＋ `lib/shared/polling/poller.dart`（新：把段 4c 的
> `SituationPoller` 抽成**泛型** `PollingFeed<T>`，`Timer.periodic` + `_inFlight` 单飞 + 失败保陈旧数据 +
> `describeError`）＋ `lib/features/situation/situation_page.dart`（改用 `PollingFeed<UnitState>`，**控件键与文案不变**）。
> **指挥端装配笔** `aa5457b`（9 文件）：`lib/main.dart`（**平台分派** `Platform.isWindows ?
> ScdsConsoleApp : ScdsClientApp`）＋ `lib/app/client_factory.dart`/`device_app.dart`/`console_app.dart`
> （新：`httpApiClient` 双端共用；原 `ScdsClientApp` 迁入；`ScdsConsoleApp` 的 `_probe` 用**一次性客户端**
> 探完即关、不顶掉业务链路）＋ `lib/features/connection/connection_page.dart`（改：可选 `probe` +
> 健康诊断区 4 控件键，`_diagnose()` 用**当前输入字段**；`probe == null` 时该区整块不渲染，**单兵端不受影响**）
> ＋ `lib/features/console/console_login_page.dart`（新）＋ `lib/features/console/team_summary_page.dart`
> （新：**直读** `getTeamSummary`，不读恒空的增量帧 `teamSummary`；可选计数缺失显示 `—`；默认 2000ms）
> ＋ `test/widget_test.dart`（改 1 行 import）＋ `test/console_widget_test.dart`（新：**23 例**）。
> 两笔代码提交 `17a0e10`/`aa5457b`；门禁 `flutter test` → **118 例全绿**、`flutter analyze` → `No issues found!`。
> **未验项**：本机**未安装 Visual Studio**，`flutter build windows` / Windows 原生运行 / **真实后端联调**
> 在本段**不可执行**（Windows 页面测试**不得**用 Chrome 替代 Windows 桌面构建验收）。
> 本任务紧接 **M6 CLIENT-BASE 段 4——Android 入口装配（4a 入口 + 连接配置页 / 4b 设备登录页 /
> 4c 自身态势页 + 1s 轮询 / 4d Android debug 明文许可）（testing 第 38 节）**——
> 按用户当次裁定：「**段 4 Android 入口装配**」「**拆 4a/4b/4c（+4d）**」「**配置 → 设备登录 → 自身态势**」
> 「**零依赖手写**」「**模拟器 + adb reverse 11001**」。4a 落 `lib/main.dart`（外壳装配：注入
> `InMemoryConnectionConfigStore` + `InMemorySessionStore`；换配置即换 `ApiClient` 实例并 `close` 旧实例）
> 与 `lib/features/connection/connection_page.dart`（连接配置页：默认预填 `127.0.0.1:11001`；端口非数字或
> `ConnectionConfig` 构造抛 `ArgumentError` 时**不写库、不跳转**，只置错误文案）；4b 落
> `lib/features/device/device_login_page.dart`（设备登录页：`AuthApi.deviceLogin` → `DeviceSession.fromTokenPair`
> → `sessionStore.writeDevice`；`AUTH_DEVICE_NOT_BOUND` 归为「编号或密钥不正确」文案）；4c 落
> `lib/features/situation/situation_page.dart`（自身态势页 + `SituationPoller`：`Timer.periodic` **1000 ms**、
> **至多一个在途请求**，失败**不清空** `latest` 只置 `lastError`，刷新文案 `已刷新 N 次（每 1000 ms）`）；
> 4d 落 Android **debug-only** 网络配置（`android/app/src/debug/AndroidManifest.xml` +
> `res/xml/network_security_config.xml`，**只放行 `127.0.0.1`/`localhost` 明文**，release 不合并该覆盖层）。
> 四笔代码提交 `1f61d27`/`ee33764`/`1bbb284`/`3600056`；门禁 `flutter test` → **95 例全绿**、
> `flutter analyze` → `No issues found!`。**已在本机用 simulator 网关 + 专属种子（62400 段）完成一次
> 模拟器端到端冒烟**（轮询 5 次 → 20 次持续刷新、页面字段与种子逐项一致），冒烟后按 62400 段精确回收；
> 该冒烟**只证「仿真通过」、不等于现场通过**，且**段 4 仍不含 WebSocket 与 revision 缓存聚合**。
> 本任务紧接 **M6 CLIENT-BASE 段 3——连接配置（3a）+ 态势契约模型（3b）+ 态势只读四端点（3c）（testing 第 37 节）**——
> 按用户当次四项裁定：「**拆 3a/3b/3c 三段**」「**内存实现先行、不引依赖**」「**四端点只读全做**」
> 「**每段 1 笔代码 + 末段统一 docs**」。3a 落 `lib/shared/config/connection_config.dart`
> （`ConnectionConfig` 构造即校验：空白地址 / 越界端口抛 `ArgumentError`；默认值逐字等于
> `kDefaultHost`/`kHttpPort`；导出 `baseUri`/`display`）与 `connection_config_store.dart`
> （`ConnectionConfigStore` 端口 + `InMemoryConnectionConfigStore`，**不落盘**）＋10 例测试；
> 3b 落 `lib/shared/api/situation_models.dart`（`SituationEnvelope`/`UnitState`/`HitEvent`/`TeamSummary`/
> `GeoPoint`/`HitEventPage` + 8 个契约枚举，**HTTP Poll 与 WS 同构共用**，解析口径「不臆造缺省值」，
> 且**未知枚举字面量 → `FormatException`**）＋15 例测试；3c 落 `lib/shared/api/situation_api.dart`
> （`SituationApi` 四只读端点 `/situation`、`/situation/vests/{vestNum}`、`/situation/teams/summary`、
> `/situation/hits`；查询串**只拼非 null 参数**；409 `SITUATION_REVISION_TOO_OLD` 由调用方改全量重拉）＋9 例测试。
> 三笔代码提交 `1fc329a`/`c5c1e70`/`6b58f93`；门禁 `flutter test` → **73 例全绿**、
> `flutter analyze` → `No issues found!`；**本段不含 WebSocket 与轮询调度器**（留段 4 与 UI 装配接线）。
> 本任务紧接 **M6 CLIENT-BASE 段 2b——auth 客户端基座测试 + 前缀缺陷修复（testing 第 36 节）**——
> 补测段 2 的 `AuthApi` / 模型 / 会话存储时**抓到并修复一个真实缺陷**：`ApiClient` 的
> `get/post/put/delete` 经 `send` → `resolve()` 只补前导斜杠、**不含 `kApiPrefix`**（只有 `api()` 才拼），
> 故 `AuthApi` 五端点实际请求 `/auth/login` 等**无 `/api/v1` 前缀**路径（现场 404）；契约依据是
> `openapi.yaml` **51 条 REST 全在 `/api/v1` 下**（含 `/api/v1/files`）。修法取
> 「**修 `ApiClient`，`send` 统一走 `api()`**」。落地**4 文件**（1 修复 + 3 测试），分
> `54e830b`（fix，1 行）/ `d770556`（test，3 文件**24 用例**）两笔；门禁 `flutter test` → **39 例全绿**。
> 更前为 **M6 CLIENT-BASE 段 2——auth 域模型与客户端（账号 + 设备登录）+ 内存会话存储（testing 第 35 节）**——
> 在 `clients/scds_client/` 落**恰 3 文件**（**未破每任务 ≤3 文件上限**）：`lib/shared/api/auth_models.dart`
> （`AuthUser` / `TokenPair` / `DeviceTokenPair`，逐字对齐 `domain.yaml` 必填集；必填缺失或形状不符抛
> `FormatException`，**不臆造缺省值**）、`lib/shared/api/auth_api.dart`（`AuthApi` 持 `ApiClient`：`login` /
> `refresh` / `logout`（无体、204）/ `me` / `deviceLogin`；只拼路径与解析模型，非 2xx 交 `ApiClient`
> 抛 `ApiException`，**不吞错、不重试、不缓存令牌**）、`lib/shared/auth/session_store.dart`
> （`AccountSession` / `DeviceSession` + `SessionStore` 抽象端口 + `InMemorySessionStore` 进程内实现；
> `expiresIn` 在客户端换算成 `expiresAt`，**不猜服务端时钟**；端口与实现分离，为后续平台安全存储留位）。
> 按当次裁定：范围「**账号 + 设备两端登录**」、存储「**内存实现**」、测试「**拆段 2b**」；
> 门禁 `flutter analyze` → `No issues found!`；提交 `a26fc28`（**代码笔已本地提交，尚未推送**）。
> 再前为 **M6 CLIENT-BASE 段 1b——通信基座契约测试（testing 第 34 节）**——新建
> `clients/scds_client/test/shared/api/api_client_test.dart`：**14 用例**，用本机 `HttpServer` 打桩
> （非 mock 库）断言段 1 三文件的**真实 HTTP 行为**（路径拼接 / 请求构造 / 非 2xx → `ApiException` /
> `ApiProblem.tryParse` 不臆造缺省值）；`flutter analyze` + `flutter test` **全绿**；提交 `ac89a6a`。
> 段 1b 的实现坑留档：打桩响应**必须显式声明 charset**（`HttpResponse` 无 charset 时按 latin1 编码，
> 写含中文的 Problem 正文抛 `Contains invalid characters`、客户端表现为 **15s 超时**），且
> `ContentType.change` 在 `dart:_http` **不存在**（须用 `ContentType(type, subtype, charset: 'utf-8')`）。
> 更早为 **M6 CLIENT-BASE 段 1——共享契约 / 通信基座（testing 第 33 节）**——在
> `clients/scds_client/lib/shared/api/` 落三文件：`api_contract.dart`（`kHttpPort = 11001` / `kApiPrefix = /api/v1` /
> `kWsPrefix = /ws/v1` / `kProblemMediaType` + `ApiErrorCode` 常量族，逐字对齐 `openapi.yaml` 与
> `schemas/common.yaml`）、`api_problem.dart`（RFC 7807 `ApiProblem`/`FieldError` + `ApiException`，
> `tryParse` 对不合契约正文返回 `null` **不臆造缺省值**）、`api_client.dart`（**`dart:io HttpClient`** 封装：
> `get/post/put/delete/send`、`resolve`/`api`、`Bearer` 注入、**非 2xx 解析 Problem 后抛 `ApiException`**、`close`）；
> **只依赖 `dart:io`，未新增依赖、未动 `pubspec.yaml`**；**该段不含测试**（按裁定拆为段 1b）。
> 更早为 **M6 CLIENT-BASE 段 0——Flutter 工程脚手架（testing 第 32 节）**（经用户 2026-10-28 裁定
> **D12**（单工程 + 共享 Dart 契约/通信包 + Android 单兵入口 / Windows 指挥入口）与 **D13**（授权**仅一次**
> `flutter create` 例外，固定目录与清单、不含业务）后用 `flutter create --org com.dualx --project-name scds_client
> --platforms=android,windows clients/scds_client` 生成 `clients/scds_client`，磁盘 50 文件 / **git 跟踪 45 文件**，
> `flutter analyze` 无问题、`flutter test` 通过；**只做脚手架，不含业务**）。
> 再早为 **R4 遗留收口（testing 第 31 节）**、**L6 单实例保护口径收口（testing 第 30 节）**（归属进程管理层，
> 无 Java 改动）与 **M3 LIFE 开演链路就绪 409 硬前置（testing 第 29 节）**，三者**均已在远端**。
> 更早已落 **M5 REPLAY 四段**（R1 复盘读侧 / R2 轨迹分片 / R3 统计 / R4 导出下载，
> `ReplayController` 七条全去 501、**全仓「仍抛 501 的方法」计数为 0**）与 **M3 CAL-2 现场验收收口**，
> 两者亦**均已在远端**。
> 最新全量 `mvn test` **1151 项，失败/错误/跳过均 0**（见 testing 第 15~31 节；客户端段在 Dart 侧、
> 不进 Maven reactor，故计数不变）。
> 下文 2026-10-21 的部分保留为历史，凡与 testing 第 12~37 节冲突处，以该处为准。

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
**M6 CLIENT-BASE 段 1（共享契约/通信基座：`api_contract` + `api_problem` + `api_client`，只依赖 `dart:io`）**、
**M6 CLIENT-BASE 段 1b（通信基座契约测试：本机 `HttpServer` 打桩 14 例，验路径拼接/请求构造/非 2xx 映射/`tryParse`）**、
**M6 CLIENT-BASE 段 2（auth 域模型与客户端：账号 + 设备登录，内存会话存储，恰 3 文件）**、
**M6 CLIENT-BASE 段 2b（auth 客户端基座测试 24 例 + `ApiClient` 缺 `/api/v1` 前缀缺陷修复）**、
**M6 CLIENT-BASE 段 3（连接配置 `ConnectionConfig`/`ConnectionConfigStore` + 态势契约模型 `situation_models.dart`
（Poll/WS 同构）+ 态势只读四端点 `SituationApi`，三段 34 例）**、
**M6 CLIENT-BASE 段 4（Android 入口装配 4a/4b/4c/4d：外壳装配 + 连接配置页 + 设备登录页 +
自身态势页与 `SituationPoller` + debug 回环明文许可，四段 23 例；含 simulator 网关 + 62400 段专属种子冒烟）**、
**M6 CLIENT-WINDOWS 段 5（Windows 指挥端骨架：`main.dart` 平台分派 + `ScdsConsoleApp` 外壳 + 连接配置页健康诊断
（`probe` + `health_api.dart`）+ 账号登录页 + 队伍汇总总览页（HTTP Poll）；共享层抽泛型 `PollingFeed<T>`，两笔 23 例）**、
**M6 CLIENT-WINDOWS 段 6（指挥端账号与会话：续期 refresh + 身份重载 me + 无体 logout；`account_page.dart` 三端口 +
`console_app.dart` 的 `_account` 持有当前会话，一笔 15 例，累计 133 例）**、
**M6 CLIENT-RESILIENCE 段 A/B（态势本地缓存 `SituationCache`——把 HTTP Poll 与 WS 回来的**同一个** `SituationEnvelope`
按 `epoch` + `revision` 归并（换代清空、全量替换、增量去旧、命中去重、缓存不跨会话），15 例；
WS 窄端口 `SituationSocket`（子协议 `scds.situation.v1`、close `4000`）与双通道调度器 `SituationFeed`
（WS 未连 2s / 已连 10s 兜底、15s `PING` 保活、3s 退避重连、HTTP 单飞、退出竞态），15 例；两笔 6 文件，累计 163 例）**、
**M6 CLIENT-RESILIENCE 段 C（认证单飞：401 → 刷新 → 重试一次；抽 `ApiTransport` 窄端口使装饰器可套在业务域客户端外
而不惊动业务域；`RefreshingApiTransport` 单飞 + `refreshCount` 证据计数，刷新被 401 拒绝才判会话终结；15 例，累计 178 例）**、
**M6 CLIENT-RESILIENCE 段 D（前半：指挥端认证接线——装饰器成为指挥端**唯一**会话持有者，登录即 `adopt()`、
账号页「续期」按钮**委托** `refreshNow()`、会话终结由 `onLost` 清存储并**弹回登录页**（`kConsoleLoginRouteName` + `popUntil`）；
`console_app` 的 `clientFactory` 收窄到 `ApiTransport`、页内重复的会话合并逻辑被删除；10 例）**、
**M6 CLIENT-RESILIENCE 段 D（后半：指挥端「全体态势」页接 `SituationFeed`——`OverallSituationPage` 页面自建双通道 Feed
（WS 未连 2s / 已连 10s 兜底、15s `PING`）、单元列表 + 连接通道状态 + 「重新同步」，`team_summary_page` 加「全体态势」入口；
7 例，累计 195 例。单兵端两端都不接，非遗漏）**、
**M6 CLIENT-WINDOWS 续段（只读先行：exercise 域只读客户端——当前演习状态 + 日志流水，`ExerciseApi` 两端点；
无演习回 200 而非 404、日志游标语义 `{items, latestSeq}`、`SYSTEM` 条目无 `vestNum`、`Exercise` 实现未发 `endReason`；
8 例，累计 203 例）**、
**M6 CLIENT-WINDOWS 续段（写操作共享层：command 域指挥指令客户端——8 个写端点 `CommandApi`（判死/判活/判伤/超范围/
单兵充弹/全体充弹/覆盖配置/手动置位）；`CommandAck` 受理语义（`accepted=false` 仍属 200）、全体充弹 `vestNum=0`、
覆盖配置只发非 null 键、`moveVest` 联调专用；12 例，累计 215 例）**、
**M6 CLIENT-WINDOWS 续段（UI 接线：command 域指挥指令页 + 全体充弹入口——`CommandPage` 六端口（判死/判活/判伤/
超范围/单兵充弹/覆盖配置）+ 全体态势页每行点开 + AppBar「全体充弹」`reload-all` 广播 + `_ReloadAllDialog`；
`accepted=false` 提示「未下发」而非报错、广播回执显示「全体」、`moveVest` 不接 UI；10 例，累计 225 例。
本原子任务 4 文件，破「≤3 文件」上限并经无人值守授权显式声明例外）**。

下一原子任务的候选：**CLIENT-WINDOWS 其余子域（想定与生命周期 exercise 域写端点、地图与标定 map 域、
复盘与导出 replay 域）**；**`moveVest` 手动置位 UI（视联调需要再定）**；
**T-PROD 部署**；
以及**平台安全存储（Android Keystore / Windows DPAPI）+ 连接配置落盘（需另授权引依赖）**；
另有**安装 Visual Studio C++ 工作负载（需另授权）以补齐 Windows 原生构建验收**。
另有一条**观察项**——硬件 ACK 解析（「已发送」≠「已确认」仍无人写入
`ACKED`，且协议无回执帧）。启动前须重新核查仓库与环境，
不照搬本快照的瞬时结论。提交/推送仍须当次授权；**段 0 至段 6 各笔与 CLIENT-RESILIENCE 段 A/B/C、D 前半各笔
及 RES-D 后半三笔（`8bdd6d7`/`3adf094`/`06b48ab`）、段 45 两笔（`88e2b34`/`f35e79f`）、段 46 两笔
（`e464452`/`ef648a0`）与段 46 推送标记 `61e9260`（含各 docs 笔）均已入远端**；段 47 代码笔 `2a42efb`
与其 docs 笔**待推**（本地 `main` 领先 `origin/main`，见 §2）。

## 2. Git与工作树

| 项 | 当前状态 |
|---|---|
| 分支 | main |
| 源码本地HEAD | 本轮 **CLIENT-WINDOWS 续段（UI 接线）** 已提交、**仅本机**（`2a42efb`：`lib/features/console/command_page.dart`（新）+ `lib/features/console/overall_situation_page.dart` + `lib/app/console_app.dart` + `test/console_widget_test.dart`，共 **4 文件** 956 insertions / 10 deletions）；其前 **CLIENT-WINDOWS 续段（写操作共享层）**（`e464452`：3 文件 516 insertions，**3 新**）+ 该段 docs 笔 `ef648a0` + 段 46 推送标记 `61e9260`，再前 **CLIENT-WINDOWS 续段（只读先行）**（`88e2b34`：3 文件 501 insertions，**3 新**）+ 该段 docs 笔 `f35e79f`，再前为 **CLIENT-RESILIENCE 段 D（后半）**（`8bdd6d7`：4 文件 508 insertions）、段 D 后半 docs 笔 `3adf094`、段 D 后半快照笔 `06b48ab`；工作树**有未提交改动**（段 47 docs 三文件：`docs/testing.md` + `docs/decision-register.md` + `snapshot.md`） |
| 实际远端main | **`origin/main` 当前 tip 为 `61e9260`（段 46 推送标记笔）；本地 `main` 领先 1 笔（段 47 代码笔 `2a42efb`）**（其前为段 46 docs 笔 `ef648a0`、段 46 代码笔 `e464452`、段 45 两笔（代码 `88e2b34` / docs `f35e79f`）、段 D 后半三笔（代码 `8bdd6d7` / docs `3adf094` / 快照 `06b48ab`）、段 D 前半三笔（代码 `1c47d68` / docs `46ca9d1` / 推送标记 `8fe7e48`）、段 C 推送标记笔 `496174e`、段 C docs 笔 `d52d29c`、段 C 代码笔 `5e83692`、段 A/B docs 笔 `2efcef5`、段 B `a0c8ff2`、段 A `7a5004f`、段 6 docs 笔 `cd62dda` 与段 6 代码笔 `3c41e5c`、段 5 docs 笔 `b97f9b2` 与两笔 `aa5457b`/`17a0e10`、段 4 docs 笔 `778de25` 与四笔 `1f61d27`/`ee33764`/`1bbb284`/`3600056`、段 3 docs 笔 `cdb2897` 与三笔 `6b58f93`/`c5c1e70`/`1fc329a`、段 2b 三笔 `fb197ba`/`d770556`/`54e830b`、段 2 两笔 `c1079c8`/`a26fc28`、段 1b 两笔 `6074e18`/`ac89a6a`、段 1 两笔 `8303add`/`dbc5dd9`、段 0 三笔 `78bbb99`/`f4c9f15`/`b16c348`、`1950b4e` 快照订正）；更早为 R4 遗留收口三笔 tip `068d706` 与 L6 口径收口三笔 `ac9353a..4b76f2b` |
| 已推送 | 至 `61e9260`（段 46 推送标记笔）为止：**段 46 两笔（`e464452` + `ef648a0`）+ 段 46 推送标记 `61e9260` + 段 0~段 6 各笔与 docs 笔 + CLIENT-RESILIENCE 段 A `7a5004f` / 段 B `a0c8ff2` / 段 A/B docs `2efcef5` / 段 C `5e83692` / 段 C docs `d52d29c` / 段 C 推送标记 `496174e` / 段 D 前半 `1c47d68` / 段 D 前半 docs `46ca9d1` / 段 D 前半推送标记 `8fe7e48` / 段 D 后半 `8bdd6d7` / 段 D 后半 docs `3adf094` / 段 D 后半快照 `06b48ab` / 段 45 `88e2b34` / 段 45 docs `f35e79f`**（`b16c348..ef648a0`）与更早的 R4 遗留收口三笔、L6 口径收口三笔、链路就绪硬前置三笔、网关下行五笔、CAL-2 现场验收收口三笔、R1/R2/R3 各三笔与 R4 全部改动、CAL-2c 四笔、`865a0b9` `moveVest`、`c1198db` 日志流水、`398495b` docs 及 CAL-1/MR-1~MR-3 **均在远端** |
| 未推送（仅本机） | 段 47 **代码笔 `2a42efb`**（4 文件 / 956 insertions / 10 deletions）与其 **docs 笔**（`docs/testing.md` + `docs/decision-register.md` + `snapshot.md`，尚未提交）；本地 `main` 领先 `origin/main` **1 笔** |
| 本轮成果（CLIENT-WINDOWS 续段·UI 接线） | **M6 CLIENT-WINDOWS 续段（UI 接线）：command 域指挥指令页 + 全体充弹入口**——承接段 46 共享层，按用户当次裁定「**command 域 UI 接线（推荐）**」，并在入口与范围决策点选「**态势页每行 → 单兵指令页（推荐）**」＋「**先做核心 7 端（不含 move）（推荐）**」。**一笔** `2a42efb`（**4 文件** / 956 insertions / 10 deletions，**1 新 3 改**）：`lib/features/console/command_page.dart`（**新**：`CommandPage` 六端口 `vestNum`/`session`/`kill`/`revive`/`injure`/`outOfRange`/`reload`/`setConfig`；**17 控件键**；三 typedef `VestCommand`/`ReloadCommand`/`ConfigCommand`；`describeCommandAck()`——`final String target = ack.isBroadcast ? '全体' : '#${ack.vestNum}'`，回执文案 `${ack.command} → $target｜$outcome`；`_run` 单飞去重 + `ApiException`/通用异常分支；`_reload` 校验 0..65535；`_saveConfig` 用 `_optionalInt` 构造 + `toJson().isEmpty` 校验）＋ `lib/features/console/overall_situation_page.dart`（改：单元行改 `_unitRow`——注入 `onCommand` 时包 `InkWell`（`overallSituationUnitTapKey`）→ `onCommand(unit.vestNum)`，未注入时保持纯文本；AppBar `actions` 加「全体充弹」`TextButton`（`kOverallSituationReloadAllButtonKey`）→ `_openReloadAll()`；新增 `_ReloadAllDialog`（弹量 `TextField` + 武器 `DropdownButton<WeaponSlot>`（`isExpanded: true`）+ 校验非法不关闭））＋ `lib/app/console_app.dart`（改：`onConnected` 建 `CommandApi`；`_openOverallSituation` 增 `command` 参数并传 `onCommand: (vestNum) => _openCommand(...)` 与 `reloadAll: (req) => command.reloadAllVests(request: req, token: _account!.accessToken)`；新增 `_openCommand(...)` push `CommandPage` 六端口闭包各调 `command.killVest`/`reviveVest`/`injureVest`/`outOfRangeVest`/`reloadVest`/`setVestConfig`）＋ `test/console_widget_test.dart`（改，**+10 例**：`group('指挥指令页（段 47）')` **6 例** + `group('指挥端全体态势页·指令入口（段 47）')` **4 例**；新增助手 `ackOf`/`unexpectedCommand`/`pumpCommand`（**放大测试视口** `physicalSize = Size(1200, 2400)` + `addTearDown(tester.view.reset)` 以容纳长页））。**口径要点**：**HTTP 200 只表示「已受理」**——`accepted=false` 属成功受理，前端 **SnackBar 提示「未下发」而非报错**；**全体广播回执显示「全体」**（`ack.isBroadcast == (vestNum == 0)`）；覆盖配置 `toJson()` **只发非 null 键**；`moveVest`（手动置位）**不接 UI**。**本原子任务 4 文件，破「≤3 文件」上限，已按无人值守授权显式声明例外**。门禁 `flutter test` → **225 例全绿**、`flutter analyze` → `No issues found!`；Maven 侧 **0/0/0**（基准 1151 项）。**两处红灯已修**：① `ApiException` 不在 `api_client.dart` 而定义于 `api_problem.dart`（改 import）；② `InputDecorator` 无界宽度断言（武器槽位包 `Expanded` + `DropdownButton isExpanded: true`）。**未验项**：**未对着真实后端实例**跑过真实指令下发（判死 / 充弹 / 覆盖配置均未真连）；`moveVest` UI 未接 |
| 上一轮成果（CLIENT-WINDOWS 续段·写操作共享层） | **M6 CLIENT-WINDOWS 续段（写操作共享层）：command 域指挥指令客户端——8 个写端点（判死 / 判活 / 判伤 / 超范围 / 单兵充弹 / 全体充弹 / 覆盖配置 / 手动置位）**——按用户当次裁定「**继续**」，并在决策点选「**共享层全 8 端点（推荐）**」（UI 接线另开段）＋ `moveVest`「**纳入（推荐）**」。**一笔** `e464452`（3 文件 / 516 insertions，**3 新**）：`lib/shared/api/command_models.dart`（**新**：`WeaponSlot`/`VestMode` 两枚举 + `ReloadRequest`/`VestConfigRequest`/`MoveVestRequest` 三请求体 + `CommandAck`；`Team` 复用 `situation_models.dart`）＋ `lib/shared/api/command_api.dart`（**新**：`CommandApi` 持窄端口 `ApiTransport`——八端点 `killVest`/`reviveVest`/`injureVest`/`outOfRangeVest`/`reloadVest`/`reloadAllVests`/`setVestConfig`（唯一 `PUT`）/`moveVest`）＋ `test/shared/api/command_api_test.dart`（**新**，**12 例**，`HttpServer` 打桩）。**口径要点**：**HTTP 200 只表示「已受理」**——`accepted=false`（马甲离线 / 队列满）仍属成功受理、**未下发任何字节**、`queuedAt` 为空，前端应提示而非报错；**全体充弹 `vestNum` 固定为 0**（`CommandAck.isBroadcast == (vestNum == 0)`，真实编号下界 1）；**覆盖配置 `toJson()` 只发非 null 键**；`moveVest` 联调专用（正式演习 403 `COMMAND_MOVE_FORBIDDEN`）。本原子任务**恰 3 文件**（**未破上限**）。门禁 `flutter test` → **215 例全绿**、`flutter analyze` → `No issues found! (ran in 2.3s)`；Maven 侧 **0/0/0**（基准 1151 项）。**一处 analyze 红灯已修**：测试助手 `bodyJson(_Seen)` 在公开签名中用了私有类型 → 改名 `_bodyJson` 并全量替换调用点。**未验项**：**未对着真实后端实例**跑过真实指令下发；**UI 尚未接线** |
| 上一轮成果（CLIENT-WINDOWS 续段·只读先行） | **M6 CLIENT-WINDOWS 续段（只读先行）：exercise 域只读客户端——当前演习状态 + 当前演习日志流水**——按用户当次裁定「**推进下一段**」，并在决策点选「**指令 + 当前演习日志流水（推荐）**」＋「**只读先行，写入另开段（推荐）**」。契约摸底确认 [command.yaml](docs/api/paths/command.yaml) **8 个端点全为写操作、无只读**，故本段只读面＝exercise 域两端点。**一笔** `88e2b34`（3 文件 / 501 insertions，**3 新**）：`lib/shared/api/exercise_models.dart`（**新**：`BattleMode`/`ExerciseEndReason`/`ExerciseLogType` 三枚举 + `Exercise`/`ExerciseLogEntry`/`ExerciseLogPage` 三类，解析口径与 `situation_models.dart` 同源）＋ `lib/shared/api/exercise_api.dart`（**新**：`ExerciseApi` 持窄端口 `ApiTransport`——`getCurrentExercise({token})` → `GET /exercise/current`；`listCurrentExerciseLogs({token, sinceSeq, limit})` → `GET /exercise/current/logs`）＋ `test/shared/api/exercise_api_test.dart`（**新**，**8 例**，`HttpServer` 打桩）。**口径要点**：**无演习是常态、不是错误**——两端点无演习均回 HTTP 200（`running=false` / 空页 + `latestSeq=null`），调用方**必须先判 `running`**；**日志是游标语义** `{items, latestSeq}`（`latestSeq` = 本批最大 seq，下次作 `sinceSeq`，**不需要 total**）；**`SYSTEM` 条目可能既无 `vestNum` 也无 `downlinkHex`**（服务端自造，不对应协议帧）；**`Exercise` DTO 实现未下发 `endReason`**（保留字段**不臆造默认值**）；未知 `battleMode`/`endReason`/`type` 字面量一律 `FormatException`。本原子任务**恰 3 文件**（**未破上限**）。门禁 `flutter test` → **203 例全绿**、`flutter analyze` → `No issues found! (ran in 3.7s)`；Maven 侧 **0/0/0**（基准 1151 项）。**未验项**：**未对着真实后端实例跑过真实当前演习状态 / 日志流水拉取**；写入类指令（command 域）**尚未实现** |
| 上一轮成果（RES-D 后半） | **M6 CLIENT-RESILIENCE 段 D（后半）：指挥端「全体态势」页接 `SituationFeed`（WS + 10s 兜底 Poll 双通道）**——按用户当次裁定「**继续**」，并在决策点选「**页面自建（推荐）**」（`SituationFeed` 由页面 `initState` 建、`dispose` 释放）与「**单元+连接状态+重同步（推荐）**」（页面内容范围）；沿用节奏「代码 1 笔 + docs 1 笔 + 尝试推送」。**一笔** `8bdd6d7`（4 文件 / 508 insertions，**1 新 3 改**）：`lib/features/console/overall_situation_page.dart`（**新**：`OverallSituationPage` 页面自建 `SituationFeed`——构造注入 `fetch`/`connect`，`initState` `..start()`、`dispose()` 释放；AppBar「重新同步」→ `requestResync()`；`ListenableBuilder` 读 `cache.units`；`gotFirstFrame = pollSuccessCount > 0 || wsFrameCount > 0`；`kOverallSituation*` 控件键族；**增量帧 `teamSummary` 恒空，故队伍汇总仍由 `TeamSummaryPage` 直读 `GET /situation/teams/summary`**）＋ `lib/features/console/team_summary_page.dart`（改：AppBar 加「全体态势」入口 `kTeamSummarySituationEntryKey`，未注入 `onOpenSituation` 不渲染）＋ `lib/app/console_app.dart`（改：`_openOverallSituation` 把 `fetch`（`situations.getSituation(token: _account!.accessToken)`）/`connect`（`connectSituationSocket(baseUri, accessToken)`）两端装配到当前账号令牌）＋ `test/console_widget_test.dart`（改：`group('指挥端全体态势页（RES-D 后半）')` **7 例** + 假件 `TestSituationSocket`（**单订阅**控制器）/`TestSituationConnector`（`silent` 档返回**永不落地** future））。**口径要点**：**页面自建 Feed、缓存随页面生灭不跨会话**；单兵端**不接**（WS 只认账号令牌——`03-auth.md` Q7 设备令牌连 WS → `403`；`DeviceSession` 无 `refreshToken`）。门禁 `flutter test` → **195 例全绿**、`flutter analyze` → `No issues found! (ran in 3.4s)`；Maven 侧 **0/0/0**（基准 1151 项）。**一处红灯已修**：WS 同源归并用例找不到 `overallSituation.unit.21`——根因是假 socket 用 **broadcast** 控制器与 `situation_feed_test.dart` 的**单订阅**模式不一致（且缺一拍 `pump`）；修为**单订阅控制器** + `emit` 后补 `await tester.pump()`。**未验项**：**未对着真实后端实例跑过真实 WS 握手 / 真实 close `4000` / 真实增量归并**；三笔（`8bdd6d7` + `3adf094` + `06b48ab`）**已于 `4e8b231..f35e79f` 推送成功入远端** |
| 上一轮成果（RES-D 前半） | **M6 CLIENT-RESILIENCE 段 D（前半）：指挥端认证接线（会话托管 + 手动续期委托 + 会话终结导航）**——按裁定「**RES-D UI 接线（推荐）**」「**沿用：代码 1 笔 + docs 1 笔 + 尝试推送（推荐）**」「**拆两段，先做认证接线（推荐）**」「指挥端账号页『续期』按钮**委托 transport（推荐）**」。**一笔** `1c47d68`（5 文件 / 502 insertions / 45 deletions，**0 新 5 改**）：`lib/shared/auth/refreshing_api_transport.dart`（改：新增 `adopt()` / `forget()` / `refreshNow()`——手动续期**复用** `_refreshOnce()` 同一把 `_refreshInFlight` 单飞闸，会话缺失 / 无 `refreshToken` 时**不发请求**直接抛 `StateError`；`_authorized` 补「会话引用已空即**不再刷新**、原样抛出」；把「刷新被 `401` 拒绝 → 清会话 + `onLost`」**收敛进 `_refresh`**，使自动与手动两条入口同口径）＋ `lib/app/console_app.dart`（改：`clientFactory` 返回类型 `ApiClient` → **`ApiTransport`**，`_clientFor` / `_probe` 同步改口；新增 `_transportFor()`（`onRefreshed` 写回会话存储、`onLost` 清存储并**弹回登录页**）与 `_returnToLogin()`（`popUntil` 到首页或 `kConsoleLoginRouteName`）；`auth` / `situations` 都包在 `transport` 外；登录页路由打 `RouteSettings(name: kConsoleLoginRouteName)`；`onLoggedIn` 调 `transport.adopt(session)`；`_openAccount` 的 `refresh` 改为 `transport.refreshNow()`，**删除页内重复的会话合并逻辑**）＋ `lib/features/console/account_page.dart`（改：`AccountRefresh` typedef 文档改为「生产接线**委托** `refreshNow()`」，**页面本体零改动**）＋ `test/shared/auth/refreshing_api_transport_test.dart`（改：`expectApiFailure` 改**泛型** + `group('手动续期')` **6 例**）＋ `test/console_widget_test.dart`（改：`group('指挥端认证接线（RES-D）')` **4 例** + **脚本化传输桩** `ScriptedTransport`——借内层 `ApiClient` 拼路径、**不建连接、不发真实请求**，记录真实请求序列与 `Authorization` 头）。**口径要点**：**会话所有权上收到装饰器**（页面拿到的 `AccountSession` 只是展示快照）；**单兵端两端都不接且非遗漏**（WS 握手只认账号令牌——`03-auth.md` Q7 设备令牌连 WS → `403`、§4.7 令牌走 `Authorization: Bearer` 头；`DeviceSession` 无 `refreshToken`）。门禁 `flutter test` → **188 例全绿**、`flutter analyze` → `No issues found! (ran in 3.2s)`；Maven 侧 **0/0/0**（基准 1151 项）。**一处测试断言口径修正**：账号页续期用例原断言「全量序列恰 3 笔」，但汇总页 2s 兜底轮询会在期间**再拉一次** `summary`（与本用例无关）——改为**子序列**断言（`containsAllInOrder`）＋「`POST /auth/refresh` 恰 1 次」＋按 key 取刷新请求读其体。**未验项**：**未对着真实后端实例跑过真实续期 / 真实会话终结**；「全体态势」页**尚未接 `SituationFeed`**（段 D 后半，本会话已完成）；单兵端两端都不接；本笔（`1c47d68`）+ 段 D 前半 docs 笔（`46ca9d1`）**已在远端** |
| 上一轮成果（RES-C） | **M6 CLIENT-RESILIENCE 段 C：认证单飞（401 → 刷新 → 重试一次）+ `ApiTransport` 窄端口抽取**——按裁定方向「**CLIENT-RESILIENCE（推荐）**」、节奏「**沿用（推荐）**」。**一笔** `5e83692`（6 文件 / 685 insertions / 8 deletions）：`lib/shared/api/api_client.dart`（改：新增窄端口 `abstract interface class ApiTransport`（`baseUri`/`api`/`resolve`/`get`/`post`/`put`/`delete`/`send`/`probe`/`close`），`ApiClient implements ApiTransport` 并逐方法补 `@override`）＋ `lib/shared/auth/refreshing_api_transport.dart`（新：`RefreshingApiTransport implements ApiTransport`——**装饰器**包内层传输；仅「401 且 `code == AUTH_TOKEN_EXPIRED`」才刷新；`used` 会话在**发起前**捕获作续期判据，`identical(_session, used)` 为假则**直接用新令牌重试、不发第二次刷新**（滚动刷新契约下重放旧 `refreshToken` 会撤销该账号全部 `refreshToken`，故单飞是**正确性要求**）；`_refreshInFlight` 单飞 + `refreshCount` 证据计数；刷新请求走**内层**传输 `AuthApi(inner)` 以免拦自己；刷新被 401 拒绝 → 清 `_session` + `onLost()` + 原请求**不重试**；恰**重试一次**）＋ `lib/shared/api/auth_api.dart`/`situation_api.dart`/`health_api.dart`（改：构造参数 `ApiClient` → `ApiTransport`）＋ `test/shared/auth/refreshing_api_transport_test.dart`（新：**15 例** = 续期重试 3 + 单飞 2 + 不触发续期 4 + 续期失败 3 + 透传 3）。**口径要点**：触发判据是「**到期**」不是「任何 401」；初发令牌取 `explicitToken ?? used?.accessToken`（调用方显式令牌优先）；设备端不适用（`DeviceSession` 无 `refreshToken`）。门禁 `flutter test` → **178 例全绿**、`flutter analyze` → `No issues found! (ran in 2.6s)`；Maven 侧 **0/0/0**（Flutter 资产不进 reactor，基准 1151 项）；**未验项**：**未对着真实后端实例跑过真实续期**（滚动作废、重放连锁撤销、`expiresIn` 与服务端时钟偏差均未验证）；装饰器**尚未接线到任何页面**；本笔（`5e83692`）+ 段 C docs 笔（`d52d29c`）**已在远端** |
| 上一轮成果（RES 段 A+B） | **M6 CLIENT-RESILIENCE 段 A/B：态势本地缓存（双通道同源归并）+ 双通道调度器（WS + 兜底 Poll）**——按裁定方向「**CLIENT-RESILIENCE（推荐）**」、节奏「**沿用（推荐）**」。**段 A** `7a5004f`（2 文件 / 466 insertions）：`lib/shared/state/situation_cache.dart`（新：`SituationCache` 把 HTTP Poll 与 WS 回来的**同一个** `SituationEnvelope` 按 `epoch` + `revision` 归并成一份视图——换代清空 + `needsFullResync`、全量整体替换 `units`、增量仅在已对齐时 upsert `changes` / 删 `removedVestNums`、`revision <= 本地` 与未对齐帧一律丢弃、命中按 `seq` 去重升序上限 `kMaxCachedHits = 500`、`teamSummary` 只留最近一次非空、`reset()` 缓存不跨会话）＋ `test/shared/state/situation_cache_test.dart`（新：**15 例**）。**段 B** `a0c8ff2`（4 文件 / 1085 insertions）：`lib/shared/state/situation_socket.dart`（新：`SituationSocket` 窄端口 + `SituationSocketConnector` + `kSituationRevisionTooOldCloseCode = 4000` + `connectSituationSocket()` + `IoWebSocketSituationSocket`（`.cast<String>()`））＋ `lib/shared/state/situation_feed.dart`（新：`SituationFeed extends ChangeNotifier`——WS 未连 2s / 已连 10s 兜底双档互斥、15s `PING` 保活、3s 退避重连、HTTP 单飞、控制帧跳过 / 坏帧计丢弃、`requestResync()` 两态、`dispose` 退出竞态）＋ `lib/shared/api/api_contract.dart`（改 +3 行：`kSituationSubprotocol = 'scds.situation.v1'`）＋ `test/shared/state/situation_feed_test.dart`（新：**15 例**，全部跑在 `testWidgets` 假时钟上）。**口径要点**：**WS 不替代 Poll**；超窗（close `4000` / HTTP 409）同走 `markFullResync()` 且不算错误态；`_onSocketDown` 超窗分支**先作废 revision 再退出 WS 模式**（本段自查修正的顺序缺陷）。门禁 `flutter test` → **163 例全绿**、`flutter analyze` → `No issues found! (ran in 2.7s)`；**未验项**：**未对着真实后端实例建立过真实 WS 连接**；本两笔（`7a5004f` + `a0c8ff2`）+ 段 A/B docs 笔（`2efcef5`）**已在远端** |
| 上一轮成果（段 6） | **M6 CLIENT-WINDOWS 段 6：指挥端账号与会话（续期 refresh + 身份重载 me + 无体 logout）**——按裁定「CLIENT-WINDOWS 续段：账号会话（refresh/me/logout）（推荐）」，并在无人值守下就两处语义裁定：「**logout 无论成败都清本地会话**」「**refresh 手动按钮触发、不引定时器**」。**一笔**（5 文件：1 新 4 改）：`lib/features/console/account_page.dart`（新：`kConsoleAccount*` 控件键族 + 四端口 `AccountRefresh`/`AccountReload`/`AccountLogout`/`SessionClearing`；`_refresh()` 无 `refreshToken` 则**不发请求**只提示重新登录，`_refreshing` 单飞去重；`_logout()` **无论成败都 `await clearSession()`**，失败留本页 + 「返回登录」才导航）＋ `lib/features/console/team_summary_page.dart`（改：AppBar `actions` 加「账号」入口 `kTeamSummaryAccountEntryKey`，未注入 `onOpenAccount` 时不渲染）＋ `lib/features/console/console_login_page.dart`（改：类注释指向 `AccountPage`，**代码零改动**）＋ `lib/app/console_app.dart`（改：新增 `_account` **持有当前会话**——汇总页 `fetch` 闭包**按次读取** `_account!.accessToken`，续期后新令牌**真生效**；`_openAccount` 装配 refresh/reload/logout/clearSession/onLoggedOut）＋ `test/console_widget_test.dart`（改：`accountSession` 扩参 + `apiFailure`/`pumpAccount`/`tapAccountButton` 助手 + **15 例** = 汇总页入口 2 + 账号与会话页 13）。门禁 `flutter test` → **133 例全绿**、`flutter analyze` → `No issues found! (ran in 2.8s)`；**未验项**：本机未装 Visual Studio，Windows 原生构建与真机联调**不可执行**，续期/退出的真实令牌流转未在真实实例上验证；本段代码笔与 docs 笔**已在远端**（`3c41e5c` + `cd62dda`） |
| 上一轮成果（段 5） | **M6 CLIENT-WINDOWS 段 5：Windows 指挥端骨架（平台入口分派 + 连接配置健康诊断 + 账号登录 + 队伍汇总总览）**——按裁定「段 5 Windows 指挥端骨架（推荐）」，并经授权**放宽「每原子任务 ≤3 文件」**。**共享层笔** `17a0e10`（4 文件）：`lib/shared/api/api_client.dart`（抽出 `_send` + 新增 `probe(path)` 走 `resolve()`——**不带** `/api/v1` 前缀）＋ `lib/shared/api/health_api.dart`（新：`InstanceHealthApi.probe()` **永不抛异常**，收敛成 `UNKNOWN`/`HTTP_<code>`/`UNREACHABLE`）＋ `lib/shared/polling/poller.dart`（新：泛型 `PollingFeed<T>`，`Timer.periodic` + `_inFlight` 单飞 + 失败保陈旧数据）＋ `lib/features/situation/situation_page.dart`（改用 `PollingFeed<UnitState>`，**控件键与文案不变**）。**指挥端装配笔** `aa5457b`（9 文件）：`lib/main.dart`（**平台分派** `Platform.isWindows ? ScdsConsoleApp : ScdsClientApp`）＋ `lib/app/client_factory.dart`/`device_app.dart`/`console_app.dart`（新）＋ `lib/features/connection/connection_page.dart`（改：可选 `probe` + 健康诊断区，`probe == null` 时整块不渲染，**单兵端不受影响**）＋ `lib/features/console/console_login_page.dart`（新）＋ `lib/features/console/team_summary_page.dart`（新：**直读** `getTeamSummary`，可选计数缺失显示 `—`，默认 2000ms）＋ `test/widget_test.dart`（改 1 行 import）＋ `test/console_widget_test.dart`（新：**23 例** = 外壳 4 + 健康诊断 5 + 登录 7 + 汇总 7）。门禁 `flutter test` → **118 例全绿**、`flutter analyze` → `No issues found! (ran in 3.9s)`；**未验项**：本机未装 Visual Studio，Windows 原生构建与真机联调**不可执行**；
本段两笔（`17a0e10` + `aa5457b`）+ docs 笔**已在远端**（`b97f9b2`） |
| 上一轮成果（段 4） | **M6 CLIENT-BASE 段 4：Android 入口装配（4a 入口 + 连接配置页 / 4b 设备登录页 / 4c 自身态势页 + 1s 轮询 / 4d Android debug 明文许可）**——按裁定「段 4 Android 入口装配」「拆 4a/4b/4c（+4d）」「零依赖手写」「模拟器 + adb reverse 11001」。4a：`lib/main.dart`（外壳装配 + `ApiClient` 生命周期）+ `lib/features/connection/connection_page.dart`（构造即校验，非法**不写库不跳转**）＋9 例；4b：`lib/features/device/device_login_page.dart`（`deviceLogin` → `DeviceSession` → `writeDevice`）＋8 例；4c：`lib/features/situation/situation_page.dart`（`SituationPoller` 1000 ms、至多一个在途、失败不清空 `latest`）＋6 例；4d：`android/app/src/debug/AndroidManifest.xml` + `android/app/src/debug/res/xml/network_security_config.xml`（**只放行 `127.0.0.1`/`localhost` 明文**，release 不含本覆盖层）。门禁 `flutter test` → **95 例全绿**、`flutter analyze` → `No issues found!`；**已用 simulator 网关 + 62400 段专属种子做模拟器端到端冒烟**（轮询 5 → 20 次、字段逐项一致），冒烟后精确回收；**段 4 不含 WS 与 revision 缓存聚合**；
本段四笔（`1f61d27` + `ee33764` + `1bbb284` + `3600056`）+ docs 笔 `778de25`**已在远端** |
| 上一轮成果（段 3） | **M6 CLIENT-BASE 段 3：连接配置（3a）+ 态势契约模型（3b）+ 态势只读四端点（3c）**——按裁定「拆 3a/3b/3c 三段」且「每段 1 笔代码」。3a：`lib/shared/config/connection_config.dart`（`ConnectionConfig` 构造即校验、默认值逐字对齐 `kDefaultHost`/`kHttpPort`、`baseUri`/`display`）+ `connection_config_store.dart`（端口 + `InMemoryConnectionConfigStore`，**不落盘**）＋10 例；3b：`lib/shared/api/situation_models.dart`（6 模型 + 8 契约枚举，**Poll/WS 同构共用**，**未知枚举 → `FormatException`**）＋15 例；3c：`lib/shared/api/situation_api.dart`（四只读端点，查询串只拼非 null 参数，409 `SITUATION_REVISION_TOO_OLD` 交调用方改全量）＋9 例。门禁 `flutter test` → **73 例全绿**、`flutter analyze` → `No issues found!`；
本段四笔（`1fc329a` + `c5c1e70` + `6b58f93` + docs 笔 `cdb2897`）**已在远端** |
| 上一轮成果（段 2b） | **M6 CLIENT-BASE 段 2b：auth 客户端基座测试 + 前缀缺陷修复**——补测段 2 时**抓到真实缺陷**：`ApiClient` 的 `get/post/put/delete` 经 `send` → `resolve()` 只补前导斜杠、**不含 `kApiPrefix`**，`AuthApi` 五端点实际请求无 `/api/v1` 前缀路径（现场 404）；修法取「**`send` 统一走 `api()`**」（1 行）。落 **4 文件**（1 修复 + 3 测试，两笔提交守「≤3 文件」）：`lib/shared/api/api_client.dart`、
`test/shared/api/auth_models_test.dart`（10 例）/`test/shared/api/auth_api_test.dart`（8 例）/
`test/shared/auth/session_store_test.dart`（6 例）。门禁 `flutter test` → **39 例全绿**、`flutter analyze` → `No issues found!`；
本段三笔（`54e830b` + `d770556` + `fb197ba`）**已在远端** |
| 更早成果（段 2） | **M6 CLIENT-BASE 段 2：auth 域模型与客户端（账号 + 设备登录）+ 内存会话存储**——`clients/scds_client/` 下
**恰 3 文件**（**未破每任务 ≤3 文件上限**）：`lib/shared/api/auth_models.dart`（`AuthUser` / `TokenPair` /
`DeviceTokenPair`，逐字对齐 `domain.yaml` 必填集，**不臆造缺省值**）、`lib/shared/api/auth_api.dart`
（`AuthApi` 持 `ApiClient`：`login` / `refresh` / `logout`（无体、204）/ `me` / `deviceLogin`）、
`lib/shared/auth/session_store.dart`（`AccountSession` / `DeviceSession` + `SessionStore` 抽象端口 +
`InMemorySessionStore` 进程内实现）。门禁 `flutter analyze` → `No issues found!`；
本段两笔（`a26fc28` + `c1079c8`）**已在远端** |
| 更早成果（段 1b） | **M6 CLIENT-BASE 段 1b：通信基座契约测试**——`clients/scds_client/test/shared/api/api_client_test.dart`
（**14 用例**，本机 `HttpServer` 打桩，断言路径拼接 / 请求构造 / 非 2xx → `ApiException` / `ApiProblem.tryParse`
**不臆造缺省值**）。门禁 `flutter analyze` → `No issues found!`、`flutter test` → **14 例全绿**；
本段两笔（`ac89a6a` + `6074e18`）**均已推送** |
| 更早成果（段 1） | **M6 CLIENT-BASE 段 1：共享契约 / 通信基座**——`clients/scds_client/lib/shared/api/` 三文件 `api_contract.dart` / `api_problem.dart` / `api_client.dart`（**`dart:io HttpClient`** 封装，非 2xx 抛 `ApiException`）。**只依赖 `dart:io`，未新增依赖、未动 `pubspec.yaml`**；**不含测试**（按裁定留段 1b）；门禁 `flutter analyze`。本段两笔（`dbc5dd9` + `8303add`）**均已推送** |
| 更早成果（段 0） | **M6 CLIENT-BASE 段 0：Flutter 工程脚手架**——按 D12（单工程 + 共享包 + 双入口）/D13（一次性脚手架例外）生成 `clients/scds_client`（磁盘 50 文件 / **git 跟踪 45 文件**），`flutter analyze` 无问题、`flutter test` 通过。**只做脚手架，不含业务**；本段三笔（`b16c348` + `f4c9f15` + `78bbb99`）**均已推送** |
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
新增已完成（**M6 CLIENT-BASE 段 1b：通信基座契约测试**，见 testing 第 34 节）：
`clients/scds_client/test/shared/api/api_client_test.dart`（**14 用例**）——用本机 `HttpServer.bind(loopbackIPv4, 0)`
打桩（非 mock 库）断言段 1 三文件的**真实 HTTP 行为**：路径拼接（`api()` 带 `/api/v1` 前缀并编码查询、
`resolve()` 补前导斜杠、契约常量 `kHttpPort = 11001` 等）、请求构造（`Accept` 覆盖 `problem+json`、
`jsonBody` 的 `Content-Type`/UTF-8、`Bearer` 注入、二进制正文原样返回）、**非 2xx 统一抛 `ApiException`**
（Problem 正文带 code/status/traceId、`VALIDATION_FAILED` 字段级 errors、非 Problem 正文 `problem == null`
且 `code` 回落 `HTTP_<status>`、2xx 含 204 不抛）、`ApiProblem.tryParse` **不臆造缺省值**（缺 `traceId`、
非 JSON/非对象/空正文一律 null）；门禁 `flutter analyze` → `No issues found!`、`flutter test` → **14 例全绿**；
**只加测试、未改实现**。
新增已完成（**M6 CLIENT-BASE 段 2：auth 域模型与客户端（账号 + 设备登录）+ 内存会话存储**，见 testing 第 35 节）：
`clients/scds_client/` 下**恰 3 文件**——`lib/shared/api/auth_models.dart`（`AuthUser`（`userId`/`username`/`roles`
+ 可选 `displayName`/`permissions`/`lastLoginAt`）、`TokenPair`、`DeviceTokenPair`，逐字对齐 `domain.yaml` 必填集；
必填缺失或形状不符抛 `FormatException`，**不臆造缺省值**）、`lib/shared/api/auth_api.dart`（`AuthApi` 持
`ApiClient`：`login`（401 `AUTH_BAD_CREDENTIALS` 不区分账号/密码）/ `refresh`（滚动）/ `logout`（无体、204）/
`me` / `deviceLogin`（401 `AUTH_DEVICE_NOT_BOUND`）；只拼路径与解析模型，非 2xx 交 `ApiClient` 抛 `ApiException`，
**不吞错、不重试、不缓存令牌**）、`lib/shared/auth/session_store.dart`（`AccountSession` / `DeviceSession`（由
`TokenPair`/`DeviceTokenPair` 派生，`expiresIn` 在客户端换算成 `expiresAt`、**不猜服务端时钟**）+ `SessionStore`
抽象端口 + `InMemorySessionStore` 进程内实现（重启即丢、**不落盘**、令牌不入日志）；端口与实现分离，为后续
平台安全存储只换实现留位）。门禁 `flutter analyze` → `No issues found!`；**本段无测试**（按裁定拆段 2b）。
新增已完成（**M6 CLIENT-BASE 段 2b：auth 客户端基座测试 + `/api/v1` 前缀缺陷修复**，见 testing 第 36 节）：
补测段 2 时**抓到并修复一个真实缺陷**——`ApiClient` 的 `get`/`post`/`put`/`delete` 经 `send` → `resolve()`
只补前导斜杠、**不含 `kApiPrefix`**（只有 `api()` 才拼），故 `AuthApi` 五端点实际请求 `/auth/login` 等
**无 `/api/v1` 前缀**路径（现场 404）；契约依据是 `openapi.yaml` **51 条 REST 全在 `/api/v1` 下**
（含 `/api/v1/files`）。修法取「**修 `ApiClient`，`send` 统一走 `api()`**」（1 行）。落 **4 文件**
（1 修复 + 3 测试，分 `54e830b` fix / `d770556` test 两笔守「原子 ≤3 文件」）：
`lib/shared/api/api_client.dart`、`test/shared/api/auth_models_test.dart`（10 例：模型边界）、
`test/shared/api/auth_api_test.dart`（8 例：五端点路径/正文/`Bearer`/错误映射）、
`test/shared/auth/session_store_test.dart`（6 例：会话派生与内存存储）。门禁 `flutter test` → **39 例全绿**、
`flutter analyze` → `No issues found!`。
新增已完成（**M6 CLIENT-BASE 段 3：连接配置（3a）+ 态势契约模型（3b）+ 态势只读四端点（3c）**，见 testing 第 37 节）：
按裁定**拆 3a/3b/3c 三段、每段 1 笔代码**，共 7 文件 34 例测试。
**3a**：`lib/shared/config/connection_config.dart`（`ConnectionConfig` 构造即校验——空白地址 / 越界端口抛
`ArgumentError`；`defaults` 逐字等于 `kDefaultHost`/`kHttpPort`；导出 `baseUri`（可直接喂 `ApiClient`）与
`display`）、`lib/shared/config/connection_config_store.dart`（`ConnectionConfigStore` 抽象端口 +
`InMemoryConnectionConfigStore` 进程内实现，**不落盘**——平台持久化待授权引依赖后再换实现）。
**3b**：`lib/shared/api/situation_models.dart`——`SituationEnvelope` / `UnitState` / `HitEvent` / `TeamSummary` /
`GeoPoint` / `HitEventPage` + 8 个契约枚举（`Team`/`VestStatus`/`HitPart`/`WeaponKind`/`MotionState`/
`CombatRole`/`PositionSource`/`CoordinateSystem`）；一套类型**同时服务 HTTP Poll 与 WS 帧**（同构硬约束，
WS 落地时不新增第二套解析）；解析口径「**不臆造缺省值**」（必填缺失 → `TypeError`、形状不符与
**未知枚举字面量** → `FormatException`、可空集合缺省 → 空列表）。
**3c**：`lib/shared/api/situation_api.dart`——`SituationApi` 四只读端点（`GET /api/v1/situation`（`sinceRevision`
增量 / `includeSnapshotOnFirst` / `hitsLimit`）、`/situation/vests/{vestNum}`、`/situation/teams/summary`、
`/situation/hits`（`sinceSeq`/`limit`））；查询串**只拼非 null 参数**（缺省交服务端默认值）；令牌按 `Bearer`
提交（指挥端 `accessToken`、单兵端 `deviceToken`）；409 `SITUATION_REVISION_TOO_OLD` 由调用方改全量重拉。
门禁 `flutter test` → **73 例全绿**、`flutter analyze` → `No issues found!`；**本段不含 WS、轮询调度与界面**（留段 4）。
新增已完成（**M6 CLIENT-BASE 段 4：Android 入口装配 4a/4b/4c/4d**，见 testing 第 38 节）：
按裁定**拆 4a/4b/4c/4d 四段、每段 1 笔代码**，共 11 文件 23 例测试。
**4a**：`lib/main.dart`（外壳装配——注入 `InMemoryConnectionConfigStore` + `InMemorySessionStore`；
换配置即换 `ApiClient` 实例并 `close` 旧实例；导航链 连接配置页 → 设备登录页 → 自身态势页）与
`lib/features/connection/connection_page.dart`（连接配置页，控件键 `kConnection*`；进入预填
`store.read() ?? ConnectionConfig.defaults`；非法输入**不写库、不跳转**）。
**4b**：`lib/features/device/device_login_page.dart`（设备登录页，控件键 `kDeviceLogin*`；`typedef DeviceLogin`；
`int.tryParse` 失败 / 密钥空各自给文案；提交期间禁用按钮；`ApiException` → `_describe()`）。
**4c**：`lib/features/situation/situation_page.dart`（自身态势页 + `SituationPoller extends ChangeNotifier`，
`kSituationPollInterval = Duration(seconds: 1)`；`_inFlight` 保证**至多一个在途请求**；失败**不清空**
`latest`；字段键 `kSituation*`；定位文案 = 室内整图百分比 / 室外 BD-09 + WGS-84 双呈现，**客户端不换算**）。
**4d**：`android/app/src/debug/AndroidManifest.xml`（挂 `@xml/network_security_config`）+
`android/app/src/debug/res/xml/network_security_config.xml`（`cleartextTrafficPermitted="true"` 仅
`127.0.0.1`/`localhost`；release 不合并）。
门禁 `flutter test` → **95 例全绿**、`flutter analyze` → `No issues found!`；详见 testing 第 38 节。
新增已完成（**M6 CLIENT-WINDOWS 段 5：Windows 指挥端骨架**，见 testing 第 39 节）：
按裁定落**两笔**（共享层先行 → 指挥端装配），共 13 文件 23 例测试。
**共享层**（`17a0e10`）：`lib/shared/api/api_client.dart`（抽出私有 `_send(method, Uri, ...)`，`send` 改为委托
`_send(api(path, query))`；新增 `probe(String path)` 走 `resolve(path)`——**不带** `/api/v1` 前缀，供应用级端点
如 `/actuator/health` 探活）、`lib/shared/api/health_api.dart`（`kHealthPath` / `HealthReport{status, detail,
httpStatus?}` / `InstanceHealthApi.probe()`，**永不抛异常**，把 2xx / 非 2xx / 网络失败收敛成 `UP`/`DOWN`/
`HTTP_<code>`/`UNREACHABLE`/`UNKNOWN`）、`lib/shared/polling/poller.dart`（把段 4c 的 `SituationPoller` 抽成
**泛型** `PollingFeed<T> extends ChangeNotifier`：`Timer.periodic` + `_inFlight` 单飞、失败保陈旧数据只置
`lastError`、`successCount`、`describeError` 回调）、`lib/features/situation/situation_page.dart`（改用
`PollingFeed<UnitState>`，**控件键与文案不变**，段 4 既测用例行为不变）。
**指挥端装配**（`aa5457b`）：`lib/main.dart`（**平台分派** `Platform.isWindows ? ScdsConsoleApp : ScdsClientApp`）、
`lib/app/client_factory.dart`（`httpApiClient` 工厂双端共用）、`lib/app/device_app.dart`（原 `ScdsClientApp`
迁入）、`lib/app/console_app.dart`（`ScdsConsoleApp`——`_probe` 用**一次性客户端**探完即 `finally close()`，
不顶掉业务链路 `_client`）、`lib/features/connection/connection_page.dart`（改：可选 `probe` + 健康诊断区 4
控件键 `connection.health*`，`_diagnose()` 用**当前输入字段**；`probe == null` 时该区整块不渲染）、
`lib/features/console/console_login_page.dart`（新：账号 trim 后空 / 密码空本地报错，`authBadCredentials` →
「账号或密码不正确」、`rateLimited` → 「尝试过于频繁」，**不做 refresh/me/logout**）、
`lib/features/console/team_summary_page.dart`（新：`PollingFeed<List<TeamSummary>>` 默认 2000ms，
**直读 `getTeamSummary`**、可选计数缺失显示 `—`）、`test/widget_test.dart`（改 1 行 import）、
`test/console_widget_test.dart`（新：**23 例** = 外壳 4 + 健康诊断 5 + 登录 7 + 汇总 7）。
门禁 `flutter test` → **118 例全绿**、`flutter analyze` → `No issues found! (ran in 3.9s)`；详见 testing 第 39 节。
新增已完成（**M6 CLIENT-WINDOWS 段 6：指挥端账号与会话（续期 refresh + 身份重载 me + 无体 logout）**，见 testing 第 40 节）：
按裁定落**一笔**（5 文件：1 新 4 改，+15 例）。`lib/features/console/account_page.dart`（新：`kConsoleAccount*`
控件键族；四端口 `AccountRefresh` / `AccountReload` / `AccountLogout` / `SessionClearing`；`_refresh()` 在
`refreshToken == null || isEmpty` 时**不发请求**只提示「重新登录」，`_refreshing` **单飞**去重且按钮禁用；成功
`_session = next` 且 `next.user ?? _user` **保留原身份快照**（滚动刷新可能不回 `user`）；`_logout()`
**无论成败都 `await widget.clearSession()`**，失败留本页置 `_signedOut` + 提示「服务端未确认，已本地退出」，
点「返回登录」才 `onLoggedOut` 导航）、`lib/features/console/team_summary_page.dart`（改：AppBar `actions`
加「账号」入口 `kTeamSummaryAccountEntryKey`，未注入 `onOpenAccount` 时不渲染）、
`lib/features/console/console_login_page.dart`（改：类注释指向 `AccountPage`，**代码零改动**）、
`lib/app/console_app.dart`（改：新增 `_account` **持有当前会话**——汇总页 `fetch` 闭包**按次读取**
`_account!.accessToken`，确保续期后新令牌**真生效**；`_openAccount` 装配 refresh/reload/logout/clearSession/
onLoggedOut，续期后 `writeAccount` 回写存储）、`test/console_widget_test.dart`（改：`accountSession` 扩参 +
`apiFailure`/`pumpAccount`/`tapAccountButton` 助手 + **15 例** = 汇总页入口 2 + 账号与会话页 13）。
门禁 `flutter test` → **133 例全绿**、`flutter analyze` → `No issues found! (ran in 2.8s)`；详见 testing 第 40 节。
新增已完成（**M6 CLIENT-RESILIENCE 段 A/B：态势本地缓存 + 双通道调度器**，见 testing 第 41 节）：
段 A `lib/shared/state/situation_cache.dart`（新：把 HTTP Poll 与 WS 回来的**同一个** `SituationEnvelope` 按
`epoch` + `revision` 归并——换代清空 + `needsFullResync`、全量整体替换、增量仅在已对齐时 upsert / 删、
`revision <= 本地` 与未对齐帧丢弃、命中按 `seq` 去重上限 `kMaxCachedHits = 500`、`teamSummary` 只留最近一次非空、
`reset()` 缓存不跨会话）；段 B `lib/shared/state/situation_socket.dart`（新：WS 窄端口 + `connectSituationSocket()` +
`IoWebSocketSituationSocket`，close `4000` 判超窗）与 `lib/shared/state/situation_feed.dart`（新：`SituationFeed`
双档轮询 2s / 10s 兜底互斥、15s `PING` 保活、3s 退避重连、HTTP 单飞、退出竞态），并给 `api_contract.dart`
补 `kSituationSubprotocol = 'scds.situation.v1'`。门禁 `flutter test` → **163 例全绿**、`flutter analyze` → `No issues found! (ran in 2.7s)`。
file / map 其余业务方法按 testing 第 3 节矩阵推进。
Flutter 工程**段 0（脚手架）+ 段 1（契约/通信基座）+ 段 1b（基座测试）+ 段 2（auth 域客户端）+
段 2b（auth 客户端基座测试与前置缺陷修复）+ 段 3（连接配置 + 态势模型 + 态势只读四端点）+
段 4（Android 入口装配：连接配置页 + 设备登录页 + 自身态势页 + 1s 轮询 + debug 明文许可）+
段 5（Windows 指挥端骨架：平台分派 + 连接配置健康诊断 + 账号登录 + 队伍汇总总览）+
段 6（指挥端账号与会话：续期 refresh + 身份重载 me + 无体 logout）+
CLIENT-RESILIENCE 段 A/B（态势本地缓存双通道同源归并 + WS 端口与双通道调度器）+ 段 C（认证单飞与
`ApiTransport` 窄端口）+ 段 D 前半（认证接线）+ 段 D 后半（「全体态势」页接 `SituationFeed`）+
CLIENT-WINDOWS 续段（exercise 域只读客户端 / command 域写操作共享层 / command 域 UI 接线）已就位**；
**Android 单兵端已有可运行入口**（配置 → 设备登录 → 自身态势，已用 simulator 网关 + 专属种子
做过模拟器端到端冒烟，**只证仿真通过**），**Windows 指挥端已有可运行入口**（连接配置 → 账号登录 →
队伍汇总总览 → 账号与会话 → 全体态势页（已接 `SituationFeed` 双通道）→ 单兵指令页 / 全体充弹，
**仅过 `flutter analyze` + `flutter test`，未做 Windows 原生构建与真实后端联调**），
WS 连接与 revision 缓存聚合**已在共享层实现（段 A/B）并已接线到指挥端「全体态势」页（段 D 后半）**，
但**未对真实实例建过 WS 连接**、指令下发**未真连**，也**未做生产联调**。

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
M6 CLIENT-BASE 段 1b（通信基座契约测试）证据：**Maven 侧仍无增减**（Dart 侧资产，**不在 reactor 内**），
全量仍 **1151 项**（0/0/0）；客户端侧门禁 `flutter analyze` → `No issues found!` 与
`flutter test test/shared/api/api_client_test.dart` → `All tests passed!`（**14 例**）；
本段**只加测试文件、未改实现**，同时留档两个实现坑——打桩响应**必须显式声明 charset**
（无 charset 时 `HttpResponse` 按 latin1 编码，写中文 Problem 正文抛 `Contains invalid characters`、
表现为客户端 15s 超时）、`ContentType.change` 在 `dart:_http` **不存在**；详见 testing 第 34 节。
M6 CLIENT-BASE 段 2（auth 域模型与客户端）证据：**Maven 侧仍无增减**（Dart 侧资产，**不在 reactor 内**），
全量仍 **1151 项**（0/0/0）；本段**不含测试**（按裁定拆段 2b），客户端侧门禁只有
`flutter analyze` → `No issues found!`；范围取「账号 + 设备两端登录」、存储取「内存实现」，
落 `lib/shared/api/auth_models.dart` / `lib/shared/api/auth_api.dart` / `lib/shared/auth/session_store.dart`
三文件（恰 3 文件），详见 testing 第 35 节。
M6 CLIENT-BASE 段 2b（auth 客户端基座测试 + 前缀缺陷修复）证据：**Maven 侧仍无增减**（Dart 侧资产，
**不在 reactor 内**），全量仍 **1151 项**（0/0/0）；客户端侧门禁 `flutter test` → `All tests passed!`
（**39 例** = 段 1b 14 + 本段 24 + 模板 1）与 `flutter analyze` → `No issues found!`；
本段**修复一个真实缺陷**——`ApiClient.send` 由 `resolve()` 改走 `api()`，`get/post/put/delete` 统一带
`/api/v1`（段 1b 用例未断言请求路径故未暴露）；落 4 文件分 `54e830b`（fix）/`d770556`（test）两笔；
详见 testing 第 36 节。
M6 CLIENT-BASE 段 3（连接配置 + 态势契约模型 + 态势只读四端点）证据：**Maven 侧仍无增减**（Dart 侧资产，
**不在 reactor 内**），全量仍 **1151 项**（0/0/0）；客户端侧门禁 `flutter test` → `All tests passed!`
（**73 例** = 段 1b 14 + 段 2b 24 + 本段 34 + 模板 1）与 `flutter analyze` → `No issues found!`；
本段守「每原子任务 ≤3 文件」——3a 落 3 文件、3b 落 2 文件、3c 落 2 文件，分 `1fc329a`/`c5c1e70`/`6b58f93` 三笔；
测试仍只用 `dart:io` + `flutter_test`（**未新增依赖**）；详见 testing 第 37 节。
M6 CLIENT-BASE 段 4（Android 入口装配）证据：**Maven 侧仍无增减**（Dart 侧资产，**不在 reactor 内**），
全量仍 **1151 项**（0/0/0）；客户端侧门禁 `flutter test` → `All tests passed!`（**95 例** =
段 1b 14 + 段 2b 24 + 段 3 34 + 本段 23）与 `flutter analyze` → `No issues found!`；
本段守「每原子任务 ≤3 文件」——4a 2 文件、4b 1 文件、4c 1 文件、4d 2 文件 + 测试文件，
分 `1f61d27`/`ee33764`/`1bbb284`/`3600056` 四笔；**端到端冒烟**（2026-10-28）用 simulator 网关 +
专属种子（62400 段）在 AVD `zaa_pixel6`（`emulator-5554`）上 `adb reverse tcp:11001 tcp:11001` 跑通，
轮询 `已刷新 5 次 → 20 次（每 1000 ms）`，页面字段与种子逐项一致（`OFFLINE`/`RED`/`hp 100`/`ammo 30/30`/
`revision 1`），无 `拉取失败` 与 `CLEARTEXT_NOT_PERMITTED`；冒烟后按 62400 段精确回收、停后端与模拟器；
**该冒烟只证「仿真通过」，不代表现场/真机通过**；测试仍只用 `dart:io` + `flutter_test`（**未新增依赖**）；
详见 testing 第 38 节。
M6 CLIENT-WINDOWS 段 5（Windows 指挥端骨架）证据：**Maven 侧仍无增减**（Dart 侧资产，**不在 reactor 内**），
全量仍 **1151 项**（0/0/0）；客户端侧门禁 `flutter test` → `All tests passed!`（**118 例** =
段 1b 14 + 段 2b 24 + 段 3 34 + 段 4 23 + 本段 23）与 `flutter analyze` → `No issues found! (ran in 3.9s)`；
本段经授权**放宽「每原子任务 ≤3 文件」**——共享层笔 `17a0e10`（4 文件）与指挥端装配笔 `aa5457b`（9 文件），
共 13 文件；测试仍只用 `dart:io` + `flutter_test`（**未新增依赖**）；
**未验项**：本机**未安装 Visual Studio**，`flutter build windows` / Windows 原生运行 / **真实后端联调**在本段
**不可执行**，Windows 页面测试**不得**用 Chrome 替代 Windows 桌面构建验收；
详见 testing 第 39 节。
M6 CLIENT-WINDOWS 段 6（指挥端账号与会话）证据：**Maven 侧仍无增减**（Dart 侧资产，**不在 reactor 内**），
全量仍 **1151 项**（0/0/0）；客户端侧门禁 `flutter test` → `All tests passed!`（**133 例** =
段 1b 14 + 段 2b 24 + 段 3 34 + 段 4 23 + 段 5 23 + 本段 15）与 `flutter analyze` → `No issues found! (ran in 2.8s)`；
本段一笔 5 文件（1 新 4 改），测试仍只用 `dart:io` + `flutter_test`（**未新增依赖**、未引 `intl`）；
**未验项**：本机**未安装 Visual Studio**，`flutter build windows` / Windows 原生运行 / **真实后端联调**在本段
**不可执行**，续期与退出的**真实令牌流转**未在真实实例上验证；
详见 testing 第 40 节。
M6 CLIENT-RESILIENCE 段 A/B（态势本地缓存 + 双通道调度器）证据：**Maven 侧仍无增减**（Dart 侧资产，
**不在 reactor 内**），全量仍 **1151 项**（0/0/0）；客户端侧门禁 `flutter test` → `All tests passed!`（**163 例** =
段 1b 14 + 段 2b 24 + 段 3 34 + 段 4 23 + 段 5 23 + 段 6 15 + 段 A 15 + 段 B 15）与 `flutter analyze` →
`No issues found! (ran in 2.7s)`；段 A 一笔 2 文件、段 B 一笔 4 文件，测试仍只用 `dart:io` + `flutter_test`（**未新增依赖**）；
段 B 用例跑在 `testWidgets` **假时钟**上（2s / 10s / 15s / 3s 档位精确推进）；**未验项**：**未对着真实后端实例建立过真实
WS 连接**，子协议协商 / `Bearer` 握手 / 30s 无上行断开 / 真实 close `4000` 与 `PING`·`PONG` 往返**均未验证**；
详见 testing 第 41 节。
M6 CLIENT-RESILIENCE 段 D（前半，指挥端认证接线）证据：**Maven 侧仍无增减**（Dart 侧资产，**不在 reactor 内**），
全量仍 **1151 项**（0/0/0）；客户端侧门禁 `flutter test` → `All tests passed!`（**188 例** =
段 1b 14 + 段 2b 24 + 段 3 34 + 段 4 23 + 段 5 23 + 段 6 15 + 段 A 15 + 段 B 15 + 段 C 15 + **段 D 10**）与
`flutter analyze` → `No issues found! (ran in 3.2s)`；本段一笔 5 文件（**0 新 5 改**），
测试用**脚本化窄端口桩** `ScriptedTransport`（借内层 `ApiClient` 拼路径、**不建连接、不发真实请求**，**未引入 mock 库**）；
**未验项**：**未对着真实后端实例跑过真实续期 / 真实会话终结**；「全体态势」页**尚未接 `SituationFeed`**（段 D 后半）；单兵端两端都不接（WS 只认账号令牌、`DeviceSession` 无 `refreshToken`）；详见 testing 第 43 节。
M6 CLIENT-RESILIENCE 段 D（后半，指挥端「全体态势」页接 `SituationFeed`）证据：**Maven 侧仍无增减**（Dart 侧资产，**不在 reactor 内**），
全量仍 **1151 项**（0/0/0）；客户端侧门禁 `flutter test` → `All tests passed!`（**195 例** =
段 1b 14 + 段 2b 24 + 段 3 34 + 段 4 23 + 段 5 23 + 段 6 15 + 段 A 15 + 段 B 15 + 段 C 15 + 段 D 前半 10 + **段 D 后半 7**）与
`flutter analyze` → `No issues found! (ran in 3.4s)`；本段一笔 4 文件（**1 新 3 改**），页面**自建** `SituationFeed`（`initState` `..start()` / `dispose` 释放），
测试用假件 `TestSituationSocket`（**单订阅** `StreamController`）/`TestSituationConnector`（`silent` 档返回**永不落地** future），**未引入 mock 库**；
**未验项**：**未对着真实后端实例跑过真实 WS 握手 / 真实 close `4000` / 真实增量归并**；本段两笔（代码 `8bdd6d7` / docs `3adf094`）
**尚未入远端**（两次 `git push origin main` 均因 `Failed to connect to github.com:443` 网络失败）；详见 testing 第 44 节。
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

2026-10-28新增已确认（M6 CLIENT-BASE 段 1b 通信基座契约测试，见 testing 第 34 节）：
测试**不打桩框架内部**，而是 `HttpServer.bind(InternetAddress.loopbackIPv4, 0)` 起**随机端口**，断言
**真实 HTTP 行为**（请求头、正文编码、状态码与非 2xx → `ApiException` 的映射），故须在 `tearDown`
关 `ApiClient` 与 `server`；`baseUri` 用 `server.port`；**打桩响应必须显式声明 charset**——
`HttpResponse` 无 charset 时按 **latin1** 编码，写含中文的 Problem 正文（如「不能为空」）抛
`Contains invalid characters`，服务端不回响应、客户端表现为 **15s 超时**（不是报错，排查时先看编码）；
且 `ContentType.change` 在 `dart:_http` **不存在**，须用 `ContentType(type, subtype, charset: 'utf-8')`；
捕获断言统一走 `expectApiFailure(future)` 助手（未抛时 `fail` 报真实状态码），避免
`.then(onError: (e) => e as ApiException)` 在收到非 `ApiException` 时强转失败掩盖真实错误；
本段**只加 1 个测试文件、未改实现**、**未新增依赖、未新增错误码、未新增迁移**，全量计数仍 **1151** 项。

2026-10-28新增已确认（M6 CLIENT-BASE 段 2 auth 域模型与客户端，见 testing 第 35 节）：
范围取「**账号 + 设备两端登录**」——账号侧 `login`/`refresh`/`logout`/`me`，设备侧 `deviceLogin`，
**不做**连接配置、态势、离线策略；令牌存取取「**内存实现**」（`InMemorySessionStore`，进程内、**不落盘**、
重启即丢），**未引平台安全存储**（Android Keystore / Windows DPAPI 需另行授权引依赖）；
Dart 模型逐字对齐 `domain.yaml` 必填集（`AuthUser`/`TokenPair`/`DeviceTokenPair`），
**不臆造缺省值**（缺必填或形状不符抛 `FormatException`），`expiresAt` 由客户端按
`(now ?? DateTime.now().toUtc()).add(Duration(seconds: expiresIn))` **换算为绝对时刻**；
端口实现分离——`AuthApi` 只拼路径 + 解析契约模型，**不吞错、不重试、不缓存令牌**（非 2xx 由
`ApiClient` 抛 `ApiException`）；`logout` **无请求体**（契约如此，只带 `accessToken`）；
测试按裁定**拆段 2b**，故本段只有静态门禁 `flutter analyze`（`No issues found!`）；
本段**未改后端、未新增依赖、未新增错误码、未新增迁移**，全量计数仍 **1151** 项。

2026-10-28新增已确认（M6 CLIENT-BASE 段 2b auth 客户端基座测试 + 前缀缺陷修复，见 testing 第 36 节）：
**业务请求统一带 `/api/v1`**——用户当次裁定修法取「**修 `ApiClient`，`send` 统一走 `api()`**」，
即 `get`/`post`/`put`/`delete` 自动拼 `kApiPrefix`，业务域客户端只传**业务相对路径**
（依据：`openapi.yaml` **51 条 REST 全在 `/api/v1` 下**，含 `/api/v1/files`）；
被修缺陷为 `send` 原走 `resolve()` 只补前导斜杠、**不含前缀**，致 `AuthApi` 五端点请求
`/auth/login` 等无前缀路径（现场 404），**段 1b 用例未断言请求路径故漏过**——
新增用例**必须断言请求路径**（本段 `auth_api_test.dart` 逐条断言 `$kApiPrefix/...`）；
测试口径沿用段 1b——本机 `HttpServer` 打桩（非 mock 库）断言**真实 HTTP 行为**，
模型**不臆造缺省值**（缺必填或形状不符即抛），会话 `expiresAt` 由客户端按注入/当前 UTC 换算；
本段**只加测试 + 1 行修复**、**未新增依赖、未新增错误码、未新增迁移、未改后端**，全量计数仍 **1151** 项。

2026-10-28新增已确认（M6 CLIENT-BASE 段 3 连接配置 + 态势契约模型 + 态势只读四端点，见 testing 第 37 节）：
**连接配置**——默认值必须与契约 `servers` 一致（`127.0.0.1:11001`），端口是硬约束故**不给改端口入口**；
用户输入的地址与端口**构造即校验**（空白地址 / 越界端口抛 `ArgumentError`），存储走
`ConnectionConfigStore` 抽象端口，本段只落**内存实现**（**不落盘**，重启回默认）；
**态势模型**——`situation_models.dart` 一套类型**同时服务 HTTP Poll 与 WS 帧**（同构硬约束，
WS 落地时不再写第二套解析）；解析新增一条口径：**未知枚举字面量 → `FormatException`**
（宁可显式失败，不静默落到默认分支）；`GeoPoint` 契约未声明必填故**全字段可空**，且两套坐标
（室内 `xPct`/`yPct`、室外 BD-09 与 WGS-84）**均由服务端下发，客户端不做换算、不翻转 Y 轴**；
**态势只读**——`SituationApi` 只负责四端点与查询串（**只拼非 null 参数**，缺省交服务端默认值）；
`sinceRevision` 超窗由服务端回 409 `SITUATION_REVISION_TOO_OLD`，**调用方**据此丢弃缓存改全量重拉；
`epoch` 与本地不一致即视为服务端换代（同样改全量）；
本段**未做**：WebSocket 连接、轮询调度器、revision 缓存聚合、任何界面（均留段 4）；
**未改后端、未新增依赖、未新增错误码、未新增迁移、未改契约**，全量计数仍 **1151** 项。

2026-10-28新增已确认（M6 CLIENT-BASE 段 4 Android 入口装配 + 模拟器冒烟，见 testing 第 38 节）：
**入口装配顺序固定为「配置 → 设备登录 → 自身态势」**（4a/4b/4c），换连接配置即**换 `ApiClient` 实例
并关闭旧实例**，会话与连接配置本段仍走**内存实现、不落盘**（平台安全存储需另授权引依赖）；
**轮询周期取 1000 ms**，与硬件串口 200 ms 周期**无关**（契约只要求兜底不超 10 s），
`SituationPoller` **至多一个在途请求**且失败**不清空** `latest`（只置 `lastError`）；
**Android 明文许可只开 debug 覆盖层、且只放行 `127.0.0.1`/`localhost`**，release 构建不合并该覆盖层
（明文照旧全禁）；**单兵端本段不接 WebSocket**、不做 revision 缓存聚合（留后段）；
**连接配置页非法输入不写库、不跳转**（构造即校验，见段 3 口径）；
模拟器冒烟用 `scds.gateway.mode=simulator` + 专属种子（62400 段）跑通并**事后精确回收**，
**只证仿真通过、不等于现场通过**；
本段**未改后端、未新增依赖、未新增错误码、未新增迁移、未改契约**，全量计数仍 **1151** 项。

2026-10-28新增已确认（M6 CLIENT-WINDOWS 段 5 Windows 指挥端骨架，见 testing 第 39 节）：
**入口按平台分派**（`Platform.isWindows` → `ScdsConsoleApp` / 否则 `ScdsClientApp`），D12 口径**不拆多 Flutter 工程**
（单工程 + 共享契约/通信包 + 双入口）；`httpApiClient` 工厂提到 `app/client_factory.dart` **双端共用**；
**`/actuator/health` 不走 `/api/v1`**，故新增 `ApiClient.probe(path)` 走 `resolve()`（**不放宽 `api()` 语义**），
健康端点**无令牌**、期望 200 `UP`（依据 `environments.md` §4）；`InstanceHealthApi.probe()` **永不抛异常**，
把 2xx / 非 2xx / 网络失败收敛成 `HealthReport`，UI 只负责呈现；**诊断用一次性客户端**（探完 `finally close()`，
不顶掉业务链路 `_client`）；
**轮询纪律统一**——段 4c 的 `SituationPoller` 抽成**泛型** `PollingFeed<T>`（`shared/polling/poller.dart`），
双端复用同一纪律（**至多一个在途**、失败保陈旧数据只置 `lastError`）；
**队伍汇总直读专用端点** `getTeamSummary`（增量帧 `SituationEnvelope.teamSummary` **恒为空**，客户端须自缓存全量），
**可选计数缺失显示 `—`、不臆造 0**，默认轮询 **2000 ms**；
**本段不做**：WS 连接、revision 缓存聚合、refresh/me/logout、指令、地图/标定、想定、复盘导出（均留后段）；
**未验项**：本机未装 Visual Studio，Windows 原生构建与真实后端联调**不可执行**，Windows 页面测试**不得**
用 Chrome 替代 Windows 桌面构建验收；
本段**未改后端、未新增依赖、未新增错误码、未新增迁移、未改契约**，全量计数仍 **1151** 项。

2026-10-28新增已确认（M6 CLIENT-WINDOWS 段 6 指挥端账号与会话，见 testing 第 40 节）：
**续期由手动按钮触发、不引定时器**（`_refreshing` 单飞去重 + 按钮禁用，避免重复刷与后台静默续期）；
`refreshToken == null || isEmpty` 时**不发请求**，只提示「当前会话没有刷新令牌，无法续期，请重新登录」
（内存会话可能只有 `accessToken`，不臆造令牌）；**滚动刷新可能不回 `user`**，故成功后续期结果
取 `next.user ?? _user` **保留原身份快照**；
**退出无论成败都清本地会话**——`_logout()` 先试 `logout(accessToken)`，`ApiException`/其他异常只记原因，
随后**必调** `clearSession()`；失败时留本页置 `_signedOut` 并提示「服务端未确认，已本地退出（原因）」，
点「返回登录」才 `onLoggedOut` 导航（`accessToken` 无状态，本地清除即完成退出的安全诉求）；
**续期后新令牌必须真生效**——`console_app.dart` 用 `_account` **持有当前会话**，汇总页 `fetch` 闭包
**按次读取** `_account!.accessToken`（若用 `session` 闭包捕获会导致「只换存储、请求仍用旧令牌」）；
**身份重载（`me`）只做展示刷新**，不发令牌、不落盘；契约端点**复用已接线 `AuthApi`**（`refresh`/`me`/无体 `logout`），
**未引依赖、未新增错误码、未改契约、未改后端、未新增迁移**（无 refreshToken 与已撤销令牌重放两条边界
均落在 UI 侧判据，不去重试）；**未验项**：本机未装 Visual Studio，续期/退出的真实令牌流转未在真实实例上验证；
本段全量计数仍 **1151** 项（Maven 侧 0/0/0），客户端侧 133 例全绿。

2026-10-28新增已确认（M6 CLIENT-RESILIENCE 段 A/B 态势本地缓存与双通道调度器，见 testing 第 41 节）：
**WS 是加速器不是替代品**——契约硬约束「WS 在线仍须保持 ≤10s 兜底轮询」落成 `wsPollFloor = 10s`；
WS 未连时用 `pollInterval = 2s` 建议档，两套定时器**互斥切换**、HTTP **单飞**（`_inFlight`）两档共用一把闸；
**超窗判据用关闭码而非文案**——WS 侧 `closeCode == 4000`、HTTP 侧 `ApiErrorCode.situationRevisionTooOld`，
两者同走 `cache.markFullResync()` 且**都不算错误态**（`lastError` 不置）；
**`_onSocketDown` 超窗分支必须先作废 revision 再退出 WS 模式**——否则退出时触发的那一拍兜底 Poll
会带着**已过期的 `sinceRevision`** 出去、必然再吃一次 409（本段自查发现并修正的顺序缺陷）；
**同源归并谁先到都不让旧帧覆盖新状态**——两条通道共用一份 `SituationCache`，去重由缓存按 `revision` 完成，
**增量帧的 `teamSummary` 恒为空**（客户端须自缓存全量）；`reset()` 保证**缓存不跨会话泄漏**；
**控制帧（含 `action`）不算数据**；**假时钟纪律**：`tester.pump()`（无参）只刷微任务**不推进假时钟**、
`tester.pump(Duration.zero)` 才走一次 elapse、且 `feed.dispose()` 必须写在用例体内；
**未引依赖、未改后端、未新增错误码、未改契约、未新增迁移**；
**未验项**：**未对着真实后端实例建立过真实 WS 连接**（子协议协商 / `Bearer` 握手 / 30s 无上行断开 /
真实 close `4000` 与 `PING`·`PONG` 往返均未验证）；
本段全量计数仍 **1151** 项（Maven 侧 0/0/0），客户端侧 **163 例**全绿。

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
最新证据：M6 CLIENT-WINDOWS 段 6——指挥端账号与会话（`lib/features/console/account_page.dart`（新：
`kConsoleAccount*` 控件键族 + 四端口 `AccountRefresh`/`AccountReload`/`AccountLogout`/`SessionClearing`，
`_refresh()` **单飞**且无 `refreshToken` 不发请求，`_logout()` **无论成败都清本地会话**）+
`lib/features/console/team_summary_page.dart`（AppBar `actions` 加「账号」入口）+
`lib/features/console/console_login_page.dart`（类注释指向 `AccountPage`，代码零改动）+
`lib/app/console_app.dart`（`_account` **持有当前会话**，汇总页 `fetch` **按次读取**新令牌）+
`test/console_widget_test.dart`（**15 例** = 汇总页入口 2 + 账号与会话页 13））
一笔共 5 文件 15 例（`flutter test` **133 例全绿**、`flutter analyze` → `No issues found! (ran in 2.8s)`）；
**未验项**：本机未装 Visual Studio，Windows 原生构建与真机联调**不可执行**，续期/退出的真实令牌流转
未在真实实例上验证）详见 testing 第 40 节；
M6 CLIENT-WINDOWS 段 5——Windows 指挥端骨架（`lib/main.dart` **平台分派**
`Platform.isWindows ? ScdsConsoleApp : ScdsClientApp` + `lib/app/console_app.dart` 指挥端外壳 +
`lib/app/client_factory.dart` 双端共用工厂 + `lib/features/connection/connection_page.dart` 健康诊断区
（可选 `probe`，`probe == null` 时整块不渲染）+ `lib/shared/api/health_api.dart`（`InstanceHealthApi.probe()`
**永不抛异常**）+ `lib/shared/polling/poller.dart` 泛型 `PollingFeed<T>` +
`lib/features/console/console_login_page.dart` 账号登录页 +
`lib/features/console/team_summary_page.dart` 队伍汇总总览页（**直读 `getTeamSummary`**、可选计数缺失显示 `—`））
两笔共 13 文件 23 例（`flutter test` **118 例全绿**），分 `17a0e10`（共享层）/`aa5457b`（指挥端装配）两笔；
**未验项**：本机未装 Visual Studio，Windows 原生构建与真机联调**不可执行**，Windows 页面测试**不得**
用 Chrome 替代 Windows 桌面构建验收）详见 testing 第 39 节；
M6 CLIENT-BASE 段 4——Android 入口装配（`lib/main.dart` 外壳装配与 `ApiClient` 生命周期 +
`lib/features/connection/connection_page.dart` 连接配置页（非法输入不写库不跳转）+
`lib/features/device/device_login_page.dart` 设备登录页（`deviceLogin` → `DeviceSession` → `writeDevice`）+
`lib/features/situation/situation_page.dart` 自身态势页与 `SituationPoller`（1000 ms、至多一个在途、
失败不清空 `latest`）+ `android/app/src/debug/**` 明文许可（**只放行回环**、release 不合并））
四段共 11 文件 23 例（`flutter test` **95 例全绿**），分 `1f61d27`/`ee33764`/`1bbb284`/`3600056` 四笔；
**已用 simulator 网关 + 62400 段专属种子在 AVD `zaa_pixel6` 上做过一次端到端冒烟**（轮询 5 → 20 次、
字段逐项一致，冒烟后精确回收，**只证仿真通过**）详见 testing 第 38 节；
M6 CLIENT-BASE 段 3——连接配置（`lib/shared/config/connection_config.dart` +
`connection_config_store.dart`，构造即校验、默认值对齐契约、内存实现不落盘）、
态势契约模型（`lib/shared/api/situation_models.dart`，6 模型 + 8 契约枚举、**Poll/WS 同构**、
未知枚举 → `FormatException`）与态势只读四端点（`lib/shared/api/situation_api.dart`，
查询串只拼非 null 参数、409 `SITUATION_REVISION_TOO_OLD` 交调用方改全量）三段共 7 文件 34 例
（`flutter test` **73 例全绿**），分 `1fc329a`/`c5c1e70`/`6b58f93` 三笔）详见 testing 第 37 节；
M6 CLIENT-BASE 段 2b——auth 客户端基座测试 + `/api/v1` 前缀缺陷修复
（`test/shared/api/auth_models_test.dart` 10 + `test/shared/api/auth_api_test.dart` 8 +
`test/shared/auth/session_store_test.dart` 6，共 **24 例**；并修 `lib/shared/api/api_client.dart` 的
`send` 缺前缀缺陷，分 `54e830b`/`d770556` 两笔）详见 testing 第 36 节；
M6 CLIENT-BASE 段 2——auth 域模型与客户端（`lib/shared/api/auth_models.dart` /
`lib/shared/api/auth_api.dart` / `lib/shared/auth/session_store.dart`，范围取账号 + 设备两端登录、
存储取内存实现、本段不含测试按裁定拆段 2b，门禁为 `flutter analyze`）详见 testing 第 35 节；
M6 CLIENT-BASE 段 1b——通信基座契约测试（`test/shared/api/api_client_test.dart`，
本机 `HttpServer` 打桩 **14 例**，验路径拼接 / 请求构造 / 非 2xx → `ApiException` / `tryParse` 不臆造缺省值，
`flutter analyze` + `flutter test` 14 例全绿）详见 testing 第 34 节；
M6 CLIENT-BASE 段 1——共享契约 / 通信基座（`lib/shared/api/` 三文件，只依赖 `dart:io`，
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
本快照已同步 1151 项测试、M6 CLIENT-RESILIENCE 段 A/B（态势本地缓存
`lib/shared/state/situation_cache.dart`（把两条通道的**同一个** `SituationEnvelope` 按 `epoch` + `revision` 归并，
换代清空 / 全量替换 / 增量去旧 / 命中去重 / 缓存不跨会话）15 例、
WS 窄端口 `lib/shared/state/situation_socket.dart`（子协议 `scds.situation.v1`、close `4000`）与
双通道调度器 `lib/shared/state/situation_feed.dart`（2s / 10s 兜底双档、15s `PING`、3s 退避重连、HTTP 单飞、退出竞态）15 例，
并给 `lib/shared/api/api_contract.dart` 补 `kSituationSubprotocol`；两笔 6 文件 30 例
`7a5004f`/`a0c8ff2`；客户端 **163 例全绿**；**尚未推送**）、
M6 CLIENT-WINDOWS 段 6（指挥端账号与会话
`lib/features/console/account_page.dart`（新：四端口 + 单飞续期 + 退出无论成败清会话）/
`lib/app/console_app.dart`（`_account` **持有当前会话**，汇总页 `fetch` 按次读新令牌）/
`lib/features/console/team_summary_page.dart`（AppBar 账号入口）/ `lib/features/console/console_login_page.dart`（注释订正）/
`test/console_widget_test.dart` 15 例，一笔 5 文件 15 例；客户端 **133 例全绿**；**已推送** `3c41e5c` + `cd62dda`）、
段 5（Windows 指挥端骨架 `lib/main.dart` **平台分派** + `lib/app/console_app.dart` 指挥端外壳 +
`lib/app/client_factory.dart` 双端共用工厂 + `lib/features/connection/connection_page.dart` 健康诊断区 +
`lib/shared/api/health_api.dart`（`InstanceHealthApi.probe()` 永不抛异常）+ `lib/shared/polling/poller.dart`
泛型 `PollingFeed<T>` + `lib/features/console/console_login_page.dart` + `team_summary_page.dart`（直读 `getTeamSummary`），
两笔 13 文件 23 例 `17a0e10`/`aa5457b`；**尚未推送**）、M6 CLIENT-BASE 段 4（Android 入口装配 `lib/main.dart` /
`lib/features/connection/connection_page.dart`（连接配置页，非法输入不写库不跳转）/
`lib/features/device/device_login_page.dart`（设备登录页，`deviceLogin` → `DeviceSession` → `writeDevice`）/
`lib/features/situation/situation_page.dart`（自身态势页 + `SituationPoller` 1000 ms、至多一个在途、
失败不清空 `latest`）与 `android/app/src/debug/**`（**只放行回环明文**、release 不合并），
四段 11 文件 23 例，分 `1f61d27`/`ee33764`/`1bbb284`/`3600056` 四笔；**本机已用 simulator 网关 +
62400 段专属种子做过模拟器端到端冒烟、事后精确回收（只证仿真通过）；尚未推送**）、
段 3（连接配置 `lib/shared/config/connection_config.dart` /
`connection_config_store.dart`（端口 + 内存实现，**不落盘**）、态势契约模型
`lib/shared/api/situation_models.dart`（6 模型 + 8 契约枚举，**Poll/WS 同构**，未知枚举 → `FormatException`）
与态势只读四端点 `lib/shared/api/situation_api.dart`（查询串只拼非 null 参数，
409 `SITUATION_REVISION_TOO_OLD` 交调用方改全量重拉），三段 7 文件 34 例，
分 `1fc329a`/`c5c1e70`/`6b58f93` 三笔；**已推送** `cdb2897`）、
段 2b（auth 客户端基座测试 `test/shared/api/auth_models_test.dart` /
`test/shared/api/auth_api_test.dart` / `test/shared/auth/session_store_test.dart` 共 **24 例**，
并修 `lib/shared/api/api_client.dart` 的 `send` 缺 `/api/v1` 前缀缺陷，分 `54e830b`/`d770556` 两笔；
**已推送**）、段 2（auth 域模型与客户端 `lib/shared/api/auth_models.dart` /
`lib/shared/api/auth_api.dart` / `lib/shared/auth/session_store.dart`，
范围取**账号 + 设备两端登录**、存储取**内存实现**、本段不含测试按裁定拆段 2b，门禁为 `flutter analyze`）、
段 1b（通信基座契约测试 `test/shared/api/api_client_test.dart`，
本机 `HttpServer` 打桩 **14 例**，`flutter analyze` + `flutter test` 全绿）、
段 1（共享契约 / 通信基座 `lib/shared/api/` 三文件，
**只依赖 `dart:io`**、非 2xx 抛 `ApiException`、本段不含测试）与段 0（Flutter 工程脚手架 `clients/scds_client`，
D12 双入口 / D13 一次性例外，`flutter analyze` + `flutter test` 通过，**均已推送**）、
R4 遗留收口（`downloadUrl` 签名窗口过期 HTTP 用例，测试侧独立重算签名，
在既有用例内扩断言）、L6 单实例保护口径收口（归属进程管理层，以 `environments.md` §6.4 为准，
无 Java 改动）、M3 LIFE 开演链路就绪 409 硬前置（`DEVICE_LINK_NOT_OPEN`，含既有 IT 的
「先开串口链路」改造，**已推送** `8dda7be..f5bb261`）、M3 LIFE 网关下行接线（开演 14B/7B 与停演 7B 出站，D11 落地口径，
**已推送** `0d8ef02..8dda7be`）、M5 REPLAY 四段（R1 读侧 / R2 轨迹分片 / R3 统计 / R4 导出下载，
含 HTTP 端到端补测）与 M3 CAL-2 现场验收收口（`MapDetail.calibratedAt` 可读性，**均已推送**）
与下一任务候选（CLIENT-RESILIENCE：WS 连接与轮询调度器 / CLIENT-WINDOWS 续段：指令、地图/标定、想定、复盘导出 /
T-PROD 部署 / 平台安全存储），
不要求重做已完成 SEC/ERR/AUTH/LEDGER/PLAN/MAP-BASE/LIFE/STATE/PUSH/DEVICE-SELF/T-STOP/COMMAND/CAL-1/CAL-2。
