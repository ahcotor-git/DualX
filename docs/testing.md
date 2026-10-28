# 测试策略、结果与发布门禁

> 核查日期：2026-10-21。代码基线：`da02622`；测试结束 12:58:42 +08:00。
> 本文区分自动测试、人工HTTP实测和待验证项目，不使用测试总数代替业务覆盖率。
> 第1至6节保留交接基线历史；SEC-01修复及13:36:58 +08:00的新验证结果见第7节。
> SEC-02字段/时间校验的新验证记录见第8节，后续结果不覆盖历史证据。
> ERR-01见第9节；TEST-01数据库隔离及521项最新全量结果见第10节。
> M2 LEDGER台账/基站/绑定与681项最新全量结果见第12节。
> M4 DEVICE-SELF单兵自身视角与1020项最新全量结果见第13节（第2节RT-SID行已被第13节取代）。
> M4 T-STOP停机与立即重启及1021项最新全量结果见第14节（第3节T-STOP行已被第14节取代）。
> M3 CAL-1 UWB 室内解算链路（解算器 + 装配接线 + app 端到端）与1055项最新全量结果见第17节。
> M5 REPLAY R1 复盘读侧（`listReplays` / `getReplay`）与1104项全量结果见第23节；
> R2 轨迹分片（`getReplayTimeline`）与1112项全量结果见第24节；
> R3 统计（`getReplayStatistics`）与1120项全量结果见第25节；
> R4 导出与下载（`exportReplay` / `getExportTask` / `downloadExport`）与1138项全量结果见第26节。
> M3 CAL-2 现场验收收口（`MapDetail.calibratedAt` 可读性）与1140项最新全量结果见第27节。
> M3 LIFE 网关下行接线（开演逐台 14B + 开始帧 / 停演结束帧）与1148项最新全量结果见第28节。

## 1. 本次自动化结果

命令：仓库根 `mvn clean verify`，本地MySQL healthy，Java21.0.12.1。

| 模块 | 测试数 | 失败/错误/跳过 | 能证明什么 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | ASCII/二进制Framer、边界、黄金样本与下行字节编码 |
| core | 211 | 0/0/0 | JDBC仓储、约束、分页、批量功能、建号、限流与AuthService |
| api | 79 | 0/0/0 | 66 REST路由存在、501形状、业务错误映射、RS256生产decoder往返 |
| solver/domain/gateway/app | 0 | 无测试 | 构建成功，不证明算法数值、网关、完整应用或状态业务正确 |
| 总计 | **309** | **0/0/0** | 全部reactor构建成功，不等于现场演习闭环通过 |

报告由Surefire生成于各模块 `target/surefire-reports/TEST-*.xml`，
为可重建中间产物，不纳入交接文档或版本库。新运行会覆盖报告。
API 79 = 骨架冒烟67 + BusinessException5 + RsaTokenSigner7。
骨架冒烟67 = REST路由66 + 统一501响应1，不是67条REST契约。

### 1.1 关键已有用例

- `SerialGoldenSamplesTest`、`SerialFramerEdgeCasesTest`：上行ASCII、下行字节、长短心跳及重同步。
- `UwbFramerGoldenSamplesTest`：3000条41B样本逐字节及ranges独立重算，半包/粘包/坏帧/残包有界。
- 黄金样本JSON的原vestNum/ranges派生值存在旧PowerShell截断错误，测试独立重算；
  不能为了消除差异改冻结hex字节。
- `AdminAccountBootstrapIT`：首次建号、BCrypt、不覆盖已有账号；主要为测试事务中的手工实例，
  不证明真实首次生产启动事务、管理员部署已配置或外部配置正确。
- `LoginThrottleTest`、`RolePermissionsTest`：窗口锁定与映射，不覆盖账号大小写和跨请求并发预算。
- `AuthServiceIT`：17项真实MySQL；不加外层测试事务，专属UUID清理，
  验证换发、重放、账号锁、refresh/logout交错、签发/插入失败回滚、外层回滚不复原撤销。
- `RsaTokenSignerTest`：7项；测试内现生成RSA，通过生产JwtDecoderConfig往返；
  不包含真实expired错误出口或必需claims拒绝用例。

### 1.2 范围限制

`ApiSkeletonSmokeIT`用standaloneSetup，不加载Security过滤链，不能用其“未带令牌非404”
断言证明生产端点无需鉴权。它也只要求“非404”，multipart错误落到500仍会通过。
当前全量日志中两个上传请求得到MultipartException并进入500兜底，这是测试质量缺口。
未映射的业务错误码刻意返回500的绊线用例通过，不表示业务码→HTTP映射已经完整。

## 2. 本次运行时证据

用已构建fat jar从scds-app目录启动local，非mock HTTP客户端检查。
signing=true与false均成功，健康探测200，验证结束停止了本次启动的两个进程。
测试JWT使用公开开发私钥现签，不存储或输出完整token，不触碰生产密钥。

| ID | 前置与请求 | 实测 | 目标/结论 |
|---|---|---|---|
| RT-START | local + 本地PEM + 当前DB，signing=true/false | 两次均启动，11001回环，8份迁移schema9 | 本地完整装配已通过人工检查 |
| RT-HEALTH | 无令牌GET /actuator/health | 200 UP | 通过 |
| RT-401 | 无令牌GET /api/v1/exercise/current | 401 INVALID，code/traceId与头存在 | 此安全失败形状通过 |
| RT-403 | 有效device aud访问exercise/current | 403 FORBIDDEN、traceId | aud横向边界此项通过 |
| RT-METRICS | VIEWER访问/actuator/metrics | 403 | 根路径通过 |
| RT-METRICS-DETAIL | 同VIEWER访问/actuator/metrics/jvm.memory.used | **200，暴露指标** | 失败，应要求ADMIN，R01 |
| RT-CLAIMS | 合法签名但没有exp的账号用途token访问exercise/current | **501**，已越过安全链 | 失败，应401，R03 |
| RT-EXPIRED | 合法签名exp=now-300s访问exercise/current | **401 AUTH_TOKEN_INVALID** | 错误类别失败，应EXPIRED，R02 |
| RT-SID | device token携带不存在SID访问auth/device/me | **501**，已越过安全链 | SID校验尚缺，R07 |
| RT-DISABLED | signing=false，无令牌POST auth/login | **401** | 目标404未实现，R05 |
| RT-VALIDATION | signing=false，无令牌POST device/login，JSON={} | **400默认Problem，无code/traceId** | 契约失败，R04 |
| RT-BUSINESS | 正常账号aud token访问exercise/current | 501 NOT_IMPLEMENTED | 只证明认证后进入占位，不证明开演 |

没有进行物理断网、JWKS故障注入、真串口、UWB TCP、WS序列、Flutter或生产节点测试。
“本地PEM启动成功”不得写成“真实无外网现场专项已通过”。
任意不存在accountId的已签名accessToken可过当前过滤链属于其无状态信任签发模型，
本次只用它测试已声明的aud/role边界，不据此认定DB账号存在性校验已完成。

## 3. 后续测试矩阵

| ID | 类别 | 最小用例 | 当前 |
|---|---|---|---|
| T-SEC-1 | 真实安全过滤链 | 401/403、指标根及子路径、refresh aud拒绝业务、白名单遍历 | 少数人工通过，自动缺 |
| T-SEC-2 | JWT验证 | 缺exp/sub/aud、错误类型、真实过期、nbf、错签、算法、issuer | 必需claim/expired已发现失败 |
| T-SEC-3 | MVC统一错误 | 400校验/JSON、404、405、multipart、413、媒体类型、errors/traceId | 失败/未覆盖 |
| T-SEC-4 | 能力与接口 | signing=false不注册账号端点，带/不带token都是约定404；me只读 | 失败 |
| T-SEC-5 | 设备 | bound/enabled/密钥，SID缺失与顶替，事务失败不顶掉旧会话，主动缓存失效 | 未实现 |
| T-SEC-6 | 授权与限流 | 各角色写接口、单兵字段裁剪、用户名大小写、并发第6次、可信代理伪造 | 未实现或未测 |
| T-AUTH-HTTP | 登录接线 | 正确DTO、204logout、me映射、绝不泄露passwordHash、429 Retry-After | 未接线 |
| T-DB-UPGRADE | DDL升级 | 空库完整迁移、已有V9升级、SYSTEM日志CHECK/JDBC/契约，checksum保护 | V9已验证；自愈缺 |
| T-RECOVERY | 演习恢复 | 断电/杀进程重启、同场存证再删、事务失败、恢复门禁、单实例 | 未实现 |
| T-EXERCISE | 生命周期 | 并发开演仅1、重复停演、统计冻结、引用快照、命令恢复 | 仓储层部分通过，服务缺 |
| T-STATE | 状态机 | 5000ms边界、阵亡坐标绝不改、判活、无效UWB/GPS、ff06分流 | 未实现 |
| T-ALGORITHM | 冻结对拍 | Solver/Filter旧新数值序列、参数默认值、LOST/重捕获/离群、Y映射 | 仅迁移差异核查 |
| T-HARDWARE | 链路 | 分片、重连、串口独占、配置绑定、12001启停、互斥、坏帧统计 | gateway为空 |
| T-PUSH | 同构恢复 | 固定屏障首帧、订阅间隙、window超窗、跨代epoch、RESYNC、PING | 部分实现：单测覆盖首帧全量/逐帧补齐/RESYNC超窗/PING保活/注销（态势 `SituationWebSocketHandlerTest`、`SituationSessionRegistryTest`；链路 `LinkWebSocketHandlerTest`、`LinkSessionRegistryTest`），端到端已过判据1两端同构与判据3超窗（携「当前 revision − 窗口 − 1」握手 → close 4000 → 改全量重连，`PushIsomorphismIT`），判据2补齐逐帧（携「当前 revision − 3」握手 → 三帧 full=false、revision 连续无跳号）与判据6演习切换（正常停演一支：真 HTTP 开停演 → 下一帧 full=true 且 running/units 随之反转，`PushSituationRecoveryIT`；进程被杀一支：关掉第一代上下文而**不停演、不清库** → 第二代启动自愈按 `CRASH_RECOVERED` 补写收尾 → 客户端下一帧 full=true/running=false/units 空且与 Poll 同构，且僵尸不再占住唯一索引、新演习照样能开、同代内 revision 只增不减，`PushCrashRecoveryIT`），判据8订阅间隙（变化持续注入时订阅 → 首帧仍为 full=true；携 Poll 读到的 revision 续订 → 首帧只能是 R 或 R+1、其后增量逐帧 +1，停注入后追平服务端 revision，`PushSituationRecoveryIT`），判据9跨代恢复（同一 JVM 内先后起两个完整上下文＝两枚 epoch：新进程 revision 长过旧代 N 后携旧 N 重连，帧自报新代 epoch 并按新代序号补齐、证明数值无法辨别换代；客户端据此丢弃缓存改不带 sinceRevision 重连，取到与 Poll 同构的新代权威全量，`PushCrossProcessRecoveryIT`） |
| T-BACKPRESSURE | 背压 | 慢客户端独立、满队列、全量后不回退、线程退出、30s无上行关闭 | 部分实现：队满丢帧不阻塞调用方、全量替代不回退、空闲超时关闭、注销后不投递、慢会话不阻塞投递已由两侧会话表单测覆盖（态势 `SituationSessionRegistryTest`、链路 `LinkSessionRegistryTest`）；线程退出（注销后发送线程退出）已由态势会话表单测覆盖；30s无上行关闭已端到端（覆写 `scds.push.idle-timeout-ms=1500ms` 证关闭码 1000/`IDLE_TIMEOUT`、会话注销、活跃数回落，并以持续 PING 收到 PONG 且不被断开证明判的是「距上次上行」，`PushKeepAliveIT`）；真填满 TCP 缓冲的慢客户端隔离已端到端（判据 4：裸 socket 走真 WS 握手、读掉连接首帧后停止读取，直至其 TCP 缓冲与队列表被填满而全局丢帧计数 > 0；同期健康会话的帧流全程 revision 零跳号、订阅后再无 `full=true`——以帧流等价取证「其余会话的计数为 0」；慢客户端恢复读取后先吐陈旧前缀、再被收敛为一帧 `full=true`，其后逐帧续上零缺口，`PushBackpressureIT`） |
| T-STOP | 生命周期 | HTTP/WS/串口/TCP/DB队列完整关闭并立即重启 | 已端到端（`ShutdownRestartIT`）：一次 `ConfigurableApplicationContext.close()` 后 WS 会话被服务端直接断开（按设计 02 §4.8 不广播关帧）、HTTP 端口不再被服务、硬约束 12001 交还、`scds-state-writer`/`uwb-boss`/`uwb-worker` 平台线程消失、Hikari 池关闭；随后同一 JVM 立即重启能在 12001 上重新开链。停机未释放链路的生产缺口（装配层补 `LinkShutdown`）即由本用例取证，见第14节 |
| T-FILE | 文件 | 路径穿越、大小类型限制、下载授权、过期清理、断电恢复 | 未实现 |
| T-CLIENT | Flutter | Android模拟器/真机与Windows、持续Poll、WS半开、重登、复盘 | 无工程 |
| T-PROD | 部署 | TLS、独立密钥、offline、远端JWKS策略、迁移、备份恢复、单实例 | 未执行 |

## 4. 环境隔离

当前IT与开发共享scds_next库。大多数仓储IT加@Transactional，
AuthServiceIT为真实提交并在AfterEach按自己UUID清理。
不得运行在生产DB，也不得宣称所有测试都自动回滚。
若中途杀测试进程，清理钩子可能未执行；只能识别本轮测试专属记录，不做全表清空。

建议在后续获批任务建立独立测试库/凭据，给同一版本迁移和应用账号。
可通过SCDS_JDBC_URL等已有参数覆盖，但先配置测试专用库及权限；
不应仅改URL导致应用和生产共用同一个秘密。
批量性能用例须明确带rewriteBatchedStatements，并记录实际多行写入与耗时，
不能引用当前不带该参数的IT作为性能证明。

## 5. 常用命令与证据

```powershell
# 仓库根，全量（要求本地MySQL及.env）
mvn clean verify

# 指定核心测试，同时构建依赖
mvn -pl scds-core -am test "-Dtest=AuthServiceIT" "-Dsurefire.failIfNoSpecifiedTests=false"

# 纯协议，通常不依赖数据库
mvn -pl scds-protocol test
```

PowerShell5含多个点的-D参数应加引号，避免拆成Maven lifecycle phase。
`test`阶段已显式收集*Test与*IT，没有独立Failsafe阶段；不要假设IT只在verify运行。
针对某模块的test运行，不替代最终全量verify。

每次记录commit/工作树差异、时间与时区、命令、JDK/DB、测试数和失败、
报告位置、是否真网络/真硬件、清理状态。不能把预计结果写为实测。

## 6. 压测与正式发布门禁

尚未给出最大马甲/客户端数、帧频、端到端p95/p99延迟、DB写吞吐、
内存/CPU上限、长稳时长、RPO/RTO，禁止虚构“几十台已压测通过”。
待产品/现场提供容量后，分层测Framer吞吐、事件队列、解算、
轨迹批量、WS慢会话、并发签发及完整链路。
关注REQUIRES_NEW在外层事务存在时额外占用DB连接的池容量。

发布最低条件：全部强制需求有成功证据，安全缺口关闭，硬件与冻结状态机、
双通道恢复、生产密钥与配置、备份恢复、客户端均验证。
当前状态为**不可生产发布**，待测试不因本次文档补全而视为通过。

## 7. SEC-01 指标子路径授权回归

执行日期：2026-10-21，Asia/Shanghai（+08:00）。
基线HEAD仍为`da02622`，结果对应未提交工作树，不是新增提交或远端部署。
用户确认本任务的3文件实施、专项及全量验证；未授权提交或推送。

### 7.1 修改范围

1. `scds-api/src/main/java/com/dualx/api/security/SecurityConfig.java`：
   现有GET `/actuator/info`、`/actuator/metrics` ADMIN规则加入`/actuator/metrics/**`。
   不改变其他白名单、aud检查、签发开关或JWT验证器。
2. `scds-app/src/test/java/com/dualx/security/SecurityMetricsIT.java`：
   同一源文件包含`SecurityMetricsIT`（signing=true）和
   `SecurityMetricsReadOnlyIT`（signing=false），两类各26个动态测试。
3. 本文件：追加证据；原309项测试和人工HTTP失败记录保留，不改写为修复后结果。

### 7.2 真实过滤链口径

采用`@SpringBootTest`和`@AutoConfigureMockMvc`，加载生产SecurityConfig、
JwtDecoderConfig、TraceIdFilter、401/403出口及真实Actuator端点。
不是standaloneSetup，不mock JwtDecoder、不预置SecurityContext，也没有替代指标Controller。
测试内生成2048位RSA密钥和真实RS256 Bearer令牌；不读取部署私钥或实际.env。
MockMvc关闭请求转储，避免失败报告打印令牌。
ADMIN请求显式Accept application/json，保留Actuator默认媒体类型配置。

两个测试上下文使用`sec01-test` profile，排除DataSource/Flyway自动装配，
不扫描应用业务、管理员建号或硬件Bean；断言上下文无DataSource。
测试签发辅助函数独立于被测实例能力，不意味着signing=false设备签发方案已获批。
测试经真实过滤链到Mock Servlet，不绑定TCP端口；没有重新执行fat jar真实网络HTTP验证。

| 每种签发配置的检查 | 数量 | 修复后 |
|---|---:|---|
| 配置与无DataSource | 1 | 实际signing配置正确，无数据库Bean |
| 缺Bearer/错签，根与详情 | 4 | 401 AUTH_TOKEN_INVALID |
| device/refresh用途，根与详情 | 4 | 403 AUTH_FORBIDDEN |
| VIEWER/DIRECTOR/OPERATOR，根/详情/tag/未知子路径 | 12 | 403 AUTH_FORBIDDEN |
| ADMIN，索引/详情/tag | 3 | 200，验证jvm.memory.used及真实测量字段 |
| 无令牌health | 1 | 200 UP |
| 指定traceId的403 | 1 | 沿用X-Trace-Id |
| 合计 | 26 | 两配置共52项 |

每个401/403均验证Problem媒体类型、status/code/instance，
traceId非空且正文与响应头一致；自动生成和请求传入traceId都覆盖。
普通device/refresh令牌不附ADMIN角色，本任务不扩展为伪造角色的威胁模型测试。

### 7.3 修复前后证据

专项命令（同时选择同源文件中的两类测试）：

```powershell
mvn -pl scds-app -am test "-Dtest=SecurityMetrics*IT" "-Dsurefire.failIfNoSpecifiedTests=false"
```

| 运行 | 结束时间 +08:00 | 结果 |
|---|---|---|
| 修复前最终测试组织 | 13:35:49 | 52项，18失败，0错误/跳过；预期BUILD FAILURE |
| 加入子树ADMIN规则后 | 13:36:20 | 52项，0失败/错误/跳过；BUILD SUCCESS |
| 根目录`mvn clean verify` | 13:36:58 | 361项，0失败/错误/跳过；全部8个reactor项目SUCCESS |

修复前18失败仅来自三种非管理员账号、两配置下的详情/tag/未知子路径：
详情和tag实际200，未知指标实际404，均应在授权阶段403。
初轮测试的Actuator媒体类型断言已通过显式Accept修正；
嵌套继承测试在当前Surefire产生合并计数，因此改为同文件内两独立测试类。
最终两份XML各26项，testcase节点与tests属性一致，不把错误的中间报告计数当验收。

全量：protocol 19、core 211、api 79、app 52，总361；
solver/domain/gateway仍无自动测试，本任务没有补齐这些模块的功能。
报告位于各模块`target/surefire-reports/TEST-*.xml`，clean verify已覆盖中间报告。

### 7.4 环境、清理和剩余限制

- JDK Zulu 21.0.12.1、Maven 3.9.9、Spring Boot 3.5.7。
- 本机`scds-local-mysql` MySQL 8.4.11 healthy，监听127.0.0.1:13306。
- 全量IT仍使用开发库，含AuthServiceIT真实提交和AfterEach专属UUID清理；
  运行后只读核查`auth_it_%`账号计数为0，没有手动删表或删业务记录。
- Flyway仍为V1-V7、V9全部成功；未添加/修改迁移、清库、删卷或覆盖配置。
- 测试结束11001/12001无监听；本任务没有启动硬件或公网服务。
- 冻结协议SHA256仍为`7CB9B4988B0D6BE64A6CA5A6B4AE91BC8005DA11466727C46B5BF0DD5859BD92`。
- 本轮未提交、未推送；已有用户文档、契约和.env.example改动保留。

结论：SEC-01的GET指标根/子树权限规则及自动回归已通过。
T-SEC-1仅此指标子集和相关401/403/health覆盖，不表示全部白名单遍历或安全任务完成。
SEC-02、MVC统一错误、签发能力路由、SID、业务接线、硬件、WS和Flutter仍待实施/决策，
**不可生产发布**。交接快照和全量计划保留原基线，本节为当前SEC-01实施证据。

## 8. SEC-02 JWT 字段与过期分类

2026-10-21用户明确选择推荐字段方案，授权两个原子任务，各最多3文件。
该确认只覆盖字段完整性和过期分类，不批准D01的登录限流/身份归一化等其他子项，
也不批准设备签发、SID数据库校验、V10或其他待决策方案。
HEAD仍为`da02622`，以下证据对应未提交工作树。

### 8.1 SEC-02A 字段和时间校验

本段白名单：

1. `scds-api/src/main/java/com/dualx/api/security/JwtDecoderConfig.java`。
2. 新增`scds-app/src/test/java/com/dualx/api/security/SecurityJwtValidationIT.java`。
3. 本文件。

落地规则：

- iss必须是非空字符串，匹配实例issuer；exp必须是存在且在底层日期表示范围内的NumericDate。
- sub必须是字符串；账号/refresh为规范小写完整UUID，设备为无前导零的1..65532十进制编号。
- aud为一个已知用途，接受字符串或单元素字符串数组；未知、混用、重复多元素均拒绝。
- device必须带规范UUID sid；这里只验结构，不查t_vest_device_session或缓存。
- roles缺席/空数组不授予角色；存在时必须是已知角色字符串数组，null/错类型/未知角色拒绝。
- iat/nbf/jti可缺席，存在时严格验类型；jti为非空字符串，不参与token_hash刷新定位。
- 保留Spring的iat < exp构造约束、RS256验签及证书绑定默认校验。
- 维持60秒时钟偏移；exp恰在now-60s仍有效，nbf恰在now+60s仍有效。
- 原始JSON结构检查只拒绝非法载荷，不信任未验签内容；签名、issuer及其他检查通过后才判断过期。
- 原始类型检查防止Nimbus将数值sub转字符串、Spring将非数组roles转成字符串列表。
- 本地PEM和JWKS不让Nimbus提前检查时间，统一由返回decoder中的Spring校验完成。

生产decoder仍使用公共validator工厂保留证书绑定；JwtTimestampValidator是final，
使用其公共setClock而非继承。每次请求使用固定的同一时刻，不修改共享Clock引入并发竞态。
只在标准结果全部是时间错误、nbf不在未来、且exp超过偏移时输出AUTH_TOKEN_EXPIRED；
错签、错误issuer、证书绑定失败、非法字段、未来nbf及复合错误均为AUTH_TOKEN_INVALID。
SEC-02A出口仍兼容旧英文前缀，彻底去文案依赖留给SEC-02B。

专项命令：

```powershell
mvn -pl scds-app -am test "-Dtest=SecurityJwtValidationIT,SecurityMetrics*IT" "-Dsurefire.failIfNoSpecifiedTests=false"
```

| 检查 | 本段最终数量 | 口径 |
|---|---:|---|
| 必需字段/类型/iat-exp关系/证书绑定 | 46 | 真实RSA、原始JSON，401 Problem |
| 设备编号及sid结构 | 14 | 真实过滤链，未查数据库 |
| 签名/算法/时间及复合错误 | 13 | 真实过滤链，pure expired与INVALID分开 |
| 有效令牌/可缺席字段/用途权限 | 9 | 200或正常403，不误拒绝合法签发 |
| 固定UTC Clock偏移边界 | 6 | 调生产decoder，exp -61/-60/-59，nbf +59/+60/+61 |
| JWKS | 1 | 生产JWKS decoder，冷装配零请求、验签后取钥、过期/缺exp/错签回归 |
| SEC-02A新增合计 | 89 | 其中82个HTTP过滤链检查，7个生产decoder检查 |
| SEC-01原有指标回归 | 52 | 两签发能力配置各26项，不改旧测试 |

JWKS用JDK HttpServer临时绑定127.0.0.1随机端口，仅发布本轮内存生成的测试公钥，
finally stop释放；不是业务HTTP端口变更，不访问外部JWKS，不是生产断网换钥验收。
专项无DataSource/Flyway/管理员建号/硬件扫描，MockMvc不绑定TCP，
并关闭请求转储；未读取实际.env或部署私钥。

| 运行 | 结束时间 +08:00 | 结果 |
|---|---|---|
| 修复前初版矩阵 | 13:43:31 | 78项，37失败、1错误、0跳过；roles=null触发认证转换NPE |
| SEC-02A最终专项 | 13:47:28 | 141项全部通过 |
| SEC-02A `mvn clean verify` | 13:48:06 | 450项全部通过，8个reactor项目SUCCESS |

中间两次编译揭示私有证书validator和final时间validator的API限制，改为公共工厂/Clock配置。
中间过期夹具仅改exp却保留更晚iat，触发Spring“expiresAt must be after issuedAt”；
最终expire辅助同时设置历史iat/nbf/exp。初版三项过期失败不作为有效pure-expired证明。
最终三用途合法过期令牌与复合无效令牌均实测，iat>=exp另有拒绝测试，不放宽生产约束。

全量模块：protocol 19、core 211、api 79、app 141，共450；失败/错误/跳过均0。
全量仍含开发库真实提交的AuthServiceIT及测试专属清理，不是隔离测试库已经建立。
AuthService、刷新token_hash、无请求体logout、设备签发能力及业务Controller未修改。
本段验证完成，机器码出口待下一段；尚不可生产发布，未提交/推送。

### 8.2 SEC-02B 机器码出口

本段白名单：

1. `scds-api/src/main/java/com/dualx/api/security/ApiProblemAuthenticationEntryPoint.java`。
2. `scds-app/src/test/java/com/dualx/api/security/SecurityJwtValidationIT.java`。
3. 本文件。

删除EXPIRED_DESCRIPTION_PREFIX和英文description判断。
遍历认证异常cause链中的JwtValidationException，
只有所有校验错误均为AUTH_TOKEN_EXPIRED才输出过期；混合、未知码或没有校验错误一律INVALID。
返回Problem和traceId形状不变，不改变403出口或API业务接线。

新增8项直接出口/TraceIdFilter测试：本地化和空description、伪装过期文案的INVALID/未知码、
过期与无效混合、全过期、多层cause、仅有异常文案。
这些使用合成OAuth2Error作补充，明确不宣称是8个真实HTTP JWT测试；
原有82个真实签名/过滤链、7个生产decoder、52个指标权限检查全部继续运行。

旧出口的负向验证命令：

```powershell
mvn -pl scds-app -am test "-Dtest=SecurityJwtValidationIT#entryPointClassifiesOnlyMachineCodes" "-Dsurefire.failIfNoSpecifiedTests=false"
```

修复后的专项继续使用8.1命令；全量从仓库根执行`mvn clean verify`。

| 运行 | 结束时间 +08:00 | 结果 |
|---|---|---|
| SEC-02B旧出口负向验证 | 13:49:45 | 8项，6失败，0错误/跳过，预期BUILD FAILURE |
| SEC-02B完整安全专项 | 13:50:18 | 149项全部通过 |
| SEC-02B `mvn clean verify` | 13:50:58 | 458项全部通过，8个reactor项目SUCCESS |

最终模块：protocol 19、core 211、api 79、app 149，共458，失败/错误/跳过均0。
32份Surefire XML的tests属性与testcase节点数量一致，报告中Bearer JWT转储匹配数为0。
最终报告替换中间结果；不把修复前失败或中间450项写成最终458项的同一次运行。

### 8.3 最终状态与边界

- 本轮按两段执行，每段3文件，合计4个不同文件；未扩大到AuthService或其他业务代码。
- SEC-01的SecurityConfig及原指标测试本轮没有修改，回归52项仍全部通过。
- 原始字段校验对历史缺exp/sub、混合aud、非规范UUID等令牌是有意兼容性收紧，
  云端签发器必须满足8.1规则；本仓库RsaTokenSigner往返7项通过。
- refresh业务仍以token_hash定位，jti不强制存在；本次未改变账号限流、全账号logout或重放处理。
- MySQL仍healthy；只读核查Flyway V1-V7/V9成功、auth_it_%专属账号计数0；
  全量IT按原测试清理，没有手动删除业务数据、改迁移、清库或删卷。
- 11001/12001未留下监听；临时JWKS仅测试回环公钥，已在finally释放。
- 协议SHA256仍为`7CB9B4988B0D6BE64A6CA5A6B4AE91BC8005DA11466727C46B5BF0DD5859BD92`。
- 实际.env、已有用户文档/契约改动保留；未提交/推送，HEAD仍da02622，实际远端未重新核验。

结论：SEC-02已完成本次批准的必需字段、类型及纯过期机器码分类，SEC-01回归保持通过。
SID真实性/顶替失效、签发能力、MVC通用Problem、HTTP业务接线、硬件/自愈/双通道/Flutter
仍未完成；公开开发钥、隔离测试库、容量与现场验收问题仍存在。
**不可生产发布**。旧快照、review、设计和决策表的历史状态未在本轮批量改写，
后续交接以本节作为SEC-01/02最新证据，D01其他子项和D02-D15不因此视为获批。

## 9. ERR-01 MVC 通用 Problem

### 9.1 契约段

用户批准完整方案：新增 ROUTE_NOT_FOUND(404)、METHOD_NOT_ALLOWED(405)、
NOT_ACCEPTABLE(406)、PAYLOAD_TOO_LARGE(413)、UNSUPPORTED_MEDIA_TYPE(415)。
先登记 common.yaml，再补 ApiErrorCode；参数、JSON、缺失上传字段仍用 VALIDATION_FAILED。
本段仅修改上述两文件及本记录；不改变业务错误映射、Security、配置或数据库。
保留 Boot ProblemDetails，后续以显式 advice 优先级和真实 Boot MVC 回归验证扩展字段。
当前仅完成登记，不代表 MVC 出口已通过验收；不提交或推送。
契约段验证：2026-10-21 13:58:15 +08:00，`mvn -pl scds-domain -am test`
成功，protocol 19项通过、domain 编译通过（无独立测试）。

### 9.2 实现段与专项证据

本段白名单：ApiExceptionHandler.java、MvcProblemIT.java、本记录。
ApiExceptionHandler 显式 `@Order(-1)`，保留 Boot 默认 advice（测试断言其确实共存）。
覆盖 DTO/ModelAttribute、参数与 JSON 绑定、MVC/旧 AOP 方法校验、404/405/406/413/415。
保留框架响应头（含405 Allow）；仅校验响应携带非空 errors，无法定位具体字段时用 `$`。
JSON、属性类型绑定和 multipart 解析失败不回显拒绝值或底层异常描述。
控制器返回值校验失败保留500 INTERNAL_ERROR，不误报为客户端400。
业务状态映射、限流 Retry-After、占位501及已有 Security 401/403保持不变。

测试采用受控 Boot 上下文，导入真实 AuthController/DTO、生产 advice 与 TraceIdFilter。
排除数据库、Flyway及安全自动配置，不扫描业务或硬件；不把本测试称为完整安全链验收。
27项：24项 Boot MockMvc/装配检查，3项回环随机端口真实 Tomcat multipart检查
（单文件超限、整个请求超限、缺 boundary）；测试限制1KB/4KB仅在测试属性中，
未改变生产上传限制，不使用11001/12001。

```powershell
mvn -pl scds-app -am test "-Dtest=MvcProblemIT,SecurityJwtValidationIT,SecurityMetrics*IT,ApiExceptionHandlerBusinessTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

| 运行 | 结束时间 +08:00 | 结果 |
|---|---|---|
| 首次测试装配 | 13:59:25 | 23项装配错误，遗漏Actuator安全自动配置排除；修正测试配置，不算有效负向基线 |
| 修复前有效基线 | 14:00:02 | 23项，16失败，0错误/跳过；默认Problem缺code，含真实上传413 |
| 首轮专项 | 14:00:54 | MVC23+安全149+既有业务5，共177项通过 |
| 补充边界负向 | 14:01:51 | 5项，2失败；旧AOP返回值误报400、ModelAttribute回显拒绝值 |
| 最终专项 | 14:02:25 | MVC27+安全149+既有业务5，共181项通过 |

专项的api/app统计分别为5/176，总181；不只引用最后一个模块的176。
本段两轮生产修复均通过后续验证，未超过单故障5轮限制。全量验收待下一段。

### 9.3 全量验收与范围

2026-10-21 14:03:29 +08:00，根目录 `mvn clean verify` 成功：
protocol 19、core 211、api 79、app 176，共485项，失败/错误/跳过均0，
8个reactor项目SUCCESS。33份Surefire XML的tests与testcase节点总数一致。
新MVC测试27项均通过，SEC-01/02原149项保持通过。

验收段仅更新本记录及common.yaml历史缺口注释；三段各不超过3文件，
本轮合计5个不同文件。没有改鉴权、业务接线、应用配置、协议、算法、历史迁移或实际.env。
只读确认Flyway V1-V7/V9均成功、auth_it_%专属账号0；没有手动清表、清库或删卷。
11001/12001无监听；测试随机端口随Surefire进程退出释放。
协议SHA256保持 `7CB9B4988B0D6BE64A6CA5A6B4AE91BC8005DA11466727C46B5BF0DD5859BD92`。
工作树保留先前改动，暂存区为空，HEAD仍da02622；没有提交、推送或重新核验实际远端。

结论：ERR-01已完成本次批准的MVC错误出口与回归。R04的Boot advice共存问题
有自动化修复前/后证据；这不是正式fat jar、反向代理或完整生产安全链验收。
Tomcat在进入MVC前拒绝的非法HTTP报文、其他过滤器异常、代理层上传限制不在本次出口范围。
未完成R05条件路由、R21业务状态映射、独立测试库、设备SID、gateway、
演习/推送/Flutter及现场验收；**仍不可生产发布**。
历史review/快照未批量改写，最新SEC与ERR证据分别以第8、9节为准。

## 10. TEST-01 数据库隔离子任务

2026-10-21用户先授权提交并推送已有成果，创建64b73e1并正常推送main，
连同此前3个认证提交一起同步；git ls-remote确认实际远端完整SHA为
`64b73e1a8c4cdac1a397c55a00e942dd5ff9f473`，与本地一致。
随后用户单独批准D14“同容器独立库”方案，以下结果对应64b73e1之后的未提交工作树。

### 10.1 分段范围

| 段 | 文件白名单 | 结果 |
|---|---|---|
| 环境 | Initialize-TestDatabase.ps1、忽略的.env.test、decision-register.md | 已建scds_next_test、scds_test；独立随机口令和owner-only ACL |
| 配置与门禁 | core测试application.yml、TestCoreApplication.java、TestDatabaseGuardTest.java | 只导入.env.test，取消开发凭据回退，初始化前拒绝误配置 |
| 精确授权收紧 | 建库脚本、TestDatabaseGuardTest.java、decision-register.md | 修正MySQL库级GRANT的下划线通配符，核查精确转义授权 |
| 验收记录 | 本记录、snapshot.md、next-chat-prompt.md | 同步最新证据，不改历史运行结果 |

每段最多3文件，合计9个不同文件（含不入库的.env.test）。
未改实际开发.env、生产代码、pom、协议/算法或历史迁移；不创建容器或删库/删卷。
脚本仅适用于Windows本机既有healthy容器和127.0.0.1:13306，默认保留测试库。
已有同名库、账号或凭据文件时失败关闭；重复CheckOnly已实测拒绝且无改动。
脚本不自动迁移、重置或清理，若中断则保留凭据、核对部分完成状态后人工决定。
root仅在容器内用于本轮建库/授权；测试使用仅测试库的DML/CREATE/ALTER/INDEX/REFERENCES，
无DROP、GRANT OPTION或开发库权限。下划线精确转义，避免授予相似库名的模式权限。

门禁为测试启动类的静态BeanFactoryPostProcessor，先于DataSource/Flyway初始化。
只接受本机13306、固定scds_next_test、scds_test、rewriteBatchedStatements=true及非空测试口令；
拒绝独立Flyway/Hikari连接覆盖、JNDI、clean-enabled与out-of-order。
这是固定本机开发测试配置，不是用于不可信代码的沙箱；数据库权限为第二层保护。
测试连接强制UTC会话，Flyway仅复用V1-V7/V9，不增加迁移或修改校验和。

### 10.2 验证

```powershell
powershell -NoProfile -File Initialize-TestDatabase.ps1 -CheckOnly
# 仅首次且前置全部通过时执行，已有资源会拒绝：
powershell -NoProfile -File Initialize-TestDatabase.ps1
mvn -pl scds-core -am test "-Dtest=TestDatabaseGuardTest,TestDatabaseIsolationIT,AuthServiceIT" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn clean verify
```

| 运行 | 结束时间 +08:00 | 结果 |
|---|---|---|
| 门禁专项（无DB连接） | 14:39:41 | 29项通过，25个负向上下文和4个直接验证 |
| 空库迁移+隔离+认证事务 | 14:40:05 | 52项通过；新测试库从空schema应用8份迁移至V9 |
| 精确GRANT回归后专项 | 14:41:34 | 53项通过：门禁29、真实隔离7、认证17 |
| 根目录clean verify | 14:42:30 | protocol19/core247/api79/app176，共521项全部通过，8个reactor SUCCESS |

35份Surefire XML的tests属性与testcase节点数一致，失败/错误/跳过均0。
核心有效Flyway连接日志只指向scds_next_test；7个真实隔离检查覆盖catalog/用户/URL、
精确授权、开发库读拒绝、WHERE 1=0的开发库写拒绝、迁移/表数、UTC及配置。
WHERE 1=0即使权限回归也不会删除行；未运行有作用于开发库的测试DML。
开发库account/refresh/exercise运行前后均0；测试库上述表运行后均0；
两库V1-V7/V9校验和/成功状态共8项一致。仅只读核查，没有手工清表。
多数仓储测试事务回滚，AuthServiceIT继续真实提交并只清理自身UUID，未改业务测试语义。
11001/12001无监听，协议SHA256保持第9节原值，构建产物/报告不入库。

结论：TEST-01的数据库隔离子任务完成；完整ScdsNextApplication启动、
真实安全链+数据库共同装配验收尚未补齐，不能把整个TEST-01或M0标为完成。
既有app安全/MVC测试仍为受控无DB上下文，不是完整应用替代品。
后续完整启动测试须显式导入测试凭据、执行初始化前门禁、禁用真实管理员建号、
使用临时内存密钥及回环随机端口，不读取开发/生产私钥或新增默认业务权限。
本次继续开发的成果尚未提交/推送，实际.env.test仍被忽略；**不可生产发布**。
最终检查：git diff --check通过，建库脚本PowerShell语法错误0；
测试报告中本轮真实测试口令的字面匹配文件数0，.env.test仍为owner-only且被忽略。

## 11. TEST-01 完整应用启动子任务

2026-10-21用户要求继续，并明确选择“完整回归（推荐）”，批准以下3文件范围：
ApplicationStartupSupport.java、ApplicationStartupIT.java及本记录。
上轮工作树成果原样保留；本轮不改生产源码、配置、pom、协议、迁移或任何凭据。
本节为最新验收状态；snapshot.md和next-chat-prompt.md中“完整启动待办/521项”
属于上一停点，未在本3文件原子任务中批量更新。

### 11.1 启动与安全边界

直接以ScdsNextApplication为SpringApplication主源启动，无自动配置排除，
不替换AuthService、TokenSigner、JwtDecoder、SecurityFilterChain或数据库业务bean。
同测试classpath上的旧IT含普通Configuration；仅通过Boot TypeExcludeFilter
排除target/test-classes中的组件，避免将旧受控测试上下文扫描入生产装配。
生产target/classes及依赖jar组件正常扫描，实际主入口、业务、安全和管理员runner均存在。
这仍是源码级完整装配回归，不是执行打包jar或真硬件现场验收。

在config-data发现之前固定classpath:/application.yml和专用full-startup-test profile，
不启用local、不发现外部配置、不导入开发.env，只明确读取唯一的.env.test候选文件。
测试文件必须恰含三个SCDS_TEST_*键，目标/账号固定为本机13306上的scds_next_test/scds_test；
拒绝空白或未解析口令，错误信息不回显文件内容或秘密。
测试最高优先级配置显式留空管理员三项及PEM文件路径/JWKS，密钥每次内存生成。
签发模式加载临时公私钥；只验签模式仅向应用提供临时公钥，私钥仅在测试侧模拟外部签发。
不创建管理员、不调用业务建号或清理操作，运行前/启动后/关闭后只读比较
t_account/t_account_role/t_refresh_token/t_exercise计数。

启动前验证一次，config-data解析后再通过程序注册的BeanFactoryPostProcessor检查，
先于DataSource、Flyway、WebServer和ApplicationRunner初始化。
门禁覆盖危险目标/账号/口令、非回环或固定端口、local/导入及profile组、
管理员建号、部署PEM/JWKS、独立Flyway/Hikari/JNDI连接及常见驱动连接覆盖、
clean-enabled、out-of-order和独立management端口。35个负向上下文的初始化tripwire均为0。
门禁是固定本机配置的防误操作措施，不是用于不可信代码的完整沙箱。
已有数据库账号精确授权继续作为第二层隔离。

两种模式顺序启动回环随机HTTP端口，真实HTTP客户端有连接/请求超时，
请求不转储Bearer或密钥。try-with-resources关闭客户端和应用上下文，
随后断言Hikari池关闭及对应随机端口拒绝连接；不注册长期JVM shutdown hook。

### 11.2 真实装配及验证

两种模式均验证唯一生产入口、安全链、AuthController、ApiExceptionHandler、
AdminAccountBootstrap、AuthService和真实RsaTokenSigner，管理员配置三项确为空。
真实数据库catalog/CURRENT_USER、UTC会话及SHOW GRANTS精确转义授权均正确，
Flyway.validate通过，原V1-V7/V9保持成功，没有新增或修改迁移。
签发模式验证真实签发器的access/refresh/device令牌可以由生产decoder验签；
只验签模式验证签发调用拒绝、应用无私钥且可验证测试侧外部签发的账号令牌。
这些签发器检查不意味着设备登录/SID业务已经实现。

每种模式经真实Tomcat HTTP验证：公开health为UP、无Bearer/错签401、
VIEWER指标子路径403、ADMIN真实指标200、受保护auth/me占位501、
已认证未知路由404；Problem状态、code、instance、content-type及traceId均正确。
只验签login/refresh/logout条件路由R05未修复，也没有用此次启动通过宣称其返回404。
设备签发D02及其他业务决策仍待确认，没有扩大白名单或调整现有授权语义。

```powershell
mvn -pl scds-app -am test "-Dtest=ApplicationStartupIT" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn clean verify
```

| 运行 | 结束时间 +08:00 | 结果 |
|---|---|---|
| 首次完整启动专项 | 14:52:02 | 37项通过：35个初始化前负向门禁、2种真实应用启动/HTTP/关闭检查 |
| 根目录clean verify | 14:53:19 | protocol19/core247/api79/app213，共558项全通过，8个reactor SUCCESS |

36份Surefire XML的tests与testcase均558，失败/错误/跳过均0。
测试报告中真实.env.test口令字面匹配文件数0；fat jar包含唯一生产入口，
未包含ApplicationStartup测试类、.env或test-classes条目。
全量前后开发和测试库account/refresh/exercise计数均0，两库8份迁移校验和/成功状态一致。
11001/12001无监听，MySQL仍为127.0.0.1:13306；专项随机端口关闭检查通过。
协议SHA256仍为第9节冻结值。现存Flyway MySQL8.4支持警告和Mockito动态agent提示未修改。

结论：TEST-01的数据库隔离和本次完整应用启动两个子任务均已验收，
不是生产发布批准，也不是完整业务、实际fat jar运行、gateway或Flutter交付验收。
下一业务前置仍为D02设备签发能力确认，然后分段推进AUTH-HTTP；
不自动提交/推送当前及上轮工作树成果，**仍不可生产发布**。

## 12. M2 LEDGER 台账/基站/绑定闭环

2026-10-21 按 LEDGER 工作包分段实施：台账服务与基站服务、`DeviceController` 接线、
绑定闭环写权限。本节记录本段证据；第1节的 309 项是历史基线，被本节取代。

命令（仓库根）：`mvn -B clean verify`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| core | 281 | 0/0/0 | 含新增 `VestLedgerServiceIT` 16、`AnchorLedgerServiceIT` 5 |
| api | 100 | 0/0/0 | `ApiSkeletonSmokeIT` 70（device 域补齐至 17 条路由）、`MvcProblemIT` 等 |
| app | 281 | 0/0/0 | 含新增 `DeviceLedgerHttpIT` 2；`RoleWritePermissionIT` 63 |
| solver/domain/gateway | 0 | 无测试 | 构建成功，不证明算法数值或网关业务 |
| 总计 | **681** | **0/0/0** | 8 个 reactor SUCCESS，制品 `scds-next.jar` 生成于 18:48:41 +08:00 |

### 12.1 本段新增/更新的用例

- `VestLedgerServiceIT`（core，16）：登记/重复编号 409、修改、逻辑注销、绑定/换绑/解绑闭语义、
  SID 顶替，以及「未绑定但残留会话」这种前置（不能借用 `openSession` 造——它同一条 UPDATE 会把 `bound` 置 1）。
- `AnchorLedgerServiceIT`（core，5）：建档、就地改写（不回滚 `created_at`、不加行）、
  `bs5~8` 可关、`bs1~4` 关闭 → 400 `VALIDATION_FAILED`、列表升序。
- `ApiSkeletonSmokeIT`（api，70）：信任链外的路由存在性与 501 形状；device 域由 14 条补到 17 条。
- `RoleWritePermissionIT`（app，63）：写权限矩阵逐 operationId 回归，含新增的三条绑定路由。
- `DeviceLedgerHttpIT`（app，2）：完整生产入口 + 真实 MySQL + 真实 TCP 的端到端形状——
  201/400/404/409 状态码、Problem 的 `code`/`instance`、绑定密钥不回显、
  `device/login` 真能换到令牌、换绑后 SID 置空、解绑幂等、注销后行仍在 `enabled=false`。

### 12.2 只在 HTTP 上才看得见的两处约定

- **密钥只进不出**：`VestDevice` 不含 `bindSecretHash`/`secret` 任何形态；请求体与响应体分离
  （`VestBindingRequest` 只进，`VestDevice` 只出）。
- **Ant 通配只吃一段**：`/devices/vests/*` 覆盖不到 `/vests/{n}/binding`。
  本段先用回归证明缺口（三条绑定路由穿透到 `anyRequest()` 得 404），再补
  `POST·PUT·DELETE /api/v1/devices/vests/*/binding` → `ADMIN` 三条规则。

### 12.3 边界

现存 Flyway MySQL 8.4 支持警告和 Mockito 动态 agent 提示未修改，与本段无关。
本段为**代码验收**，不是生产发布批准；不提交、不推送，工作树成果保留。
`getVestLink`/`getLinks`/`listSerialPorts` 与串口/UWB 开关当时仍为 501——`scds-gateway` 无主源码
（链路端点已由 HW-LINK 收口，`getVestLink` 已由第 22 节收口）。
`t_anchor` 与 `t_map.uwb_meta` 的双源问题按设计缺口登记，本段不写 `uwb_meta`。

## 13. M4 DEVICE-SELF 单兵自身视角

2026-10-27 按 DEVICE-SELF 工作包分三段实施：越权收口（`DeviceScope`）、SID 单端在线接线与
`device/me` 去 501、端到端验收。本节记录本段证据；第 12 节的 681 项与第 2 节的 RT-SID 行是历史，
被本节取代。

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动 |
| core | 504 | 0/0/0 | `DeviceAuthService` 新增 `requireActiveSession`（SID 校验的首个生产调用方） |
| api | 135 | 0/0/0 | 新增 `DeviceScope`；`SituationController` 加 `@PreAuthorize`；`device/me` 去 501 |
| app | 313 | 0/0/0 | 含新增 `DeviceSelfHttpIT` 3；`RoleWritePermissionIT` 65 |
| solver/domain | 0 | 无测试 | 构建成功，不证明算法数值或状态业务 |
| 总计 | **1020** | **0/0/0** | 全 reactor SUCCESS |

### 13.1 本段新增/更新的用例

- `DeviceSelfHttpIT`（app，3，完整生产入口 + 真实 MySQL + 真实安全链）：
  - 设备令牌读 `auth/device/me` → 200，编号/名称/队伍来自台账、`state` 来自本场编成（装载后未收帧为 `OFFLINE`）；
  - 设备令牌读自身实时状态 200、读他人马甲 403 `AUTH_FORBIDDEN`（决策 04 D04「仅自身」的可执行形式）；
  - 重登顶掉旧会话后，旧设备令牌在 `device/me` 上 401 `AUTH_TOKEN_INVALID`。
- `RoleWritePermissionIT`（app，65）：矩阵新增三条——设备令牌读自身放行、读他人 403、指挥台读任意马甲放行。
- `ApiSkeletonSmokeIT`（api，71）：`device/me` 不再是占位，501 形状改用仍为占位的 `devices/vests/{n}/link`
  （该端点已由第 22 节接线，501 示例现改用真占位的 `GET /api/v1/replays`）。

### 13.2 两处只在方法级鉴权上才看得见的约定

- **域级 matcher 表达不了「同一 URL 内的资源级范围」**：`/api/v1/situation/vests/**` 的
  `hasAnyAuthority(AUD_DEVICE, AUD_EDGE)` 有意保持不变，收口交给 `@PreAuthorize("@deviceScope.allowSelf(...)")`
  ——依据 `docs/design/03-auth.md` §6 Q8「按需补」的例外条款。
- **方法级拒绝必须单列 403 出口**：`@PreAuthorize` 的 `AccessDeniedException` 抛在 DispatcherServlet 之内，
  不走安全链的 `ApiProblemAccessDeniedHandler`；不登记就会落进 `handleUnexpected` 变成 500，
  于是「filter chain 拒」与「方法级拒」给出不同状态。`ApiExceptionHandler` 已补同形 403
  （同 `code`、同 `instance`）。

### 13.3 RT-SID 行的现状（取代第 2 节）

第 2 节 `RT-SID` 记录的是「device token 携带不存在 SID 访问 `auth/device/me` → **501**，SID 校验尚缺」。
本段已把该缺口补齐：同一请求现在得到 **401 `AUTH_TOKEN_INVALID`**，由
`DeviceAuthService.requireActiveSession` 比对令牌 `sid` 与 `t_vest.device_session_sid` 判定
（换绑/退役会把该列置 NULL，同一马甲重登会覆盖为新值）。第 2 节保留为历史证据，不作为当前行为。

### 13.4 边界

现存 Flyway MySQL 8.4 支持警告与 Mockito 动态 agent 提示未修改，与本段无关。
`getVestLink` 当时仍为 501（`lastFrameType`/`framesTotal`/`droppedTotal` 在 `UnitState` 无数据源，单列任务）
——已由第 22 节收口（每马甲三计数补在 `SituationStateService`）。
`docs/api/openapi.yaml` 中 `deviceToken` 的注释已从「自身 + 所属队伍」更正为「仅自身」（与 D04 对齐）。
本段为**代码验收**，不是生产发布批准。

## 14. M4 T-STOP 停机与立即重启

2026-10-27 按 T-STOP 工作包实施（`docs/requirements.md` 的 `INV-STOP`、设计 02 §4.8、设计 05 §4.2）。
采「负例先行」：先写端到端用例实测取证，据结果再决定是否补生产代码。本节记录本段证据；
第 3 节矩阵的 T-STOP 行是历史，被本节取代。

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动（链路句柄的 `close()` 本就齐备，缺的是调用方） |
| core | 504 | 0/0/0 | 未改动 |
| api | 135 | 0/0/0 | 未改动 |
| app | 314 | 0/0/0 | 含新增 `ShutdownRestartIT` 1 |
| solver/domain | 0 | 无测试 | 构建成功，不证明算法数值或状态业务 |
| 总计 | **1021** | **0/0/0** | 全 reactor SUCCESS |

### 14.1 实测暴露的生产缺口（本段修复）

**现象**：只调一次 `ConfigurableApplicationContext.close()`，`scds.gateway.mode=real` 下 12001
仍被本进程占用，直到 JVM 退出才交还——即 `server.shutdown=graceful` 承诺的「串口链路在退出前
释放」实际没有发生（`application.yml` 第 15-16 行写明强杀会让下一次启动抢不到 COM 口）。

**根因**：三种链路句柄各自的 `close()` 早已实现（设计 05 §4.2 要求句柄自持 Netty / 读线程并能
在关闭时中断它们），但 `com.dualx.core.port.DeviceLinkAdmin` 上并没有「把三条一起关掉」的方法，
于是「谁在停机时调用 `close`」一直没有归属——`RealDeviceLinkAdmin` / `SimulatedLinkAdapter`
都不是 Spring bean，`scds-gateway` 又刻意零 Spring 依赖，`@PreDestroy` 无处可写。

**修复**：在装配层 `com.dualx.config.GatewayConfig` 补一个 `LinkShutdown implements DisposableBean`
的 bean，`destroy()` 里对 `DeviceLinkAdmin` 依次调 `closeSerial` / `closeUwbTcpServer` / `closeUwbSerial`。
只依赖端口方法，因此真链路与仿真共用同一份释放逻辑；三条链路各自兜 `RuntimeException`，
一条失败不影响其余两条被释放。依赖顺序保证它先于 `deviceLinkAdmin`、`linkRegistry` 被销毁。

### 14.2 用例判据（`ShutdownRestartIT`，app，1）

`shutdownReleasesEverythingAndImmediateRestartBindsSameHardPort`——完整生产入口 + 真实 MySQL +
真实安全链，`scds.gateway.mode` 不覆盖（走 `application.yml` 的 `real` 默认值）：

- 停机**前**先自证基线成立：装配的是 `RealDeviceLinkAdmin`、开 UWB TCP 后 12001 真被本进程占用、
  链路状态 WS 已收到首帧且 `closed == false`（客户端不主动关，否则「已断开」不能归因于停机）、
  `uwb-boss` 与 `scds-state-writer` 线程在位、HTTP 端口在服务。
- 停机**后**逐项断言资源已还：WS 会话被服务端直接断开（设计 02 §4.8 裁定停机不广播关帧）；
  12001 可再绑定；HTTP 端口 `connect` 被拒；`scds-state-writer`、`uwb-boss`、`uwb-worker`
  平台线程全部消失；Hikari 池 `isClosed()`。断言前用 10s 轮询等待，因为停机是异步收尾。
- **立即重启**：同一 JVM 内再起一个完整上下文 → `/actuator/health` 200，且能在硬约束 12001 上
  重新开链并回 `state=OPEN`。

**为什么必须用真机端口而非端口 0**：端口 0 每次分配新号，第二次绑定成功什么也证明不了。
12001 是硬约束里唯一的固定监听口，故以它为准；被占用时按 `HwLinkHttpIT` 的先例以
`Assumptions.assumeTrue` 跳过，而不是换端口重试（「换个端口也能跑」正是硬约束要禁掉的行为）。
同理，**线程判据不可省**：Netty 事件循环与态势写手都是守护线程，端口没放掉 JVM 照样能退出，
「停机没报错」不等于「资源已还」——端口是现象，线程是根因。

### 14.3 边界

本段只覆盖**优雅停机**这一条路径（`context.close()`）。现场强杀 / 断电不在本段，属 T-RECOVERY。
WS 判据的口径是「会话被服务端断开」，不是「收到关闭帧」——后者与设计 02 §4.8 的裁定相反。
`getVestLink` 与 COMMAND / CALIBRATION 仍未落地，与本段无关（`getVestLink` 当时仍 501，已由第 22 节收口）。
本段为**代码验收**，不是生产发布批准。

## 15. M3 COMMAND 指令下发（MR-1 + MR-2）

2026-10-27 按 M3 COMMAND 工作包实施（`docs/implementation-plan.md` 第 90 行；决策 D04、D11；
契约 `docs/api/paths/command.yaml`）。分两段落地：MR-1 收 5 条单兵即时指令，MR-2 收全体充弹与覆盖配置。
本节记录本段证据；`docs/review.md` 中 R08 / R21 称「command 业务方法占位、COMMAND 默认 500」
的描述是 2026-10-21 的时点记录，已被本节取代。

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动（下行帧由既有 `DownlinkCommands` 组帧） |
| gateway | 49 | 0/0/0 | 未改动（14B 配置帧的重发缓存早已在 `SerialVestLink` 里） |
| core | 504 | 0/0/0 | 新增编排 `CommandService`（普通类，装配点在 `scds-app` 的 `CommandConfig`） |
| api | 139 | 0/0/0 | `ApiSkeletonSmokeIT` 判据是「非 404」，去 501 不破该判据 |
| app | 318 | 0/0/0 | 含 `CommandHttpIT` 4（MR-1 两例 + MR-2 两例） |
| solver/domain | 0 | 无测试 | 构建成功，不证明算法数值或状态业务 |
| 总计 | **1029** | **0/0/0** | 全 reactor SUCCESS |

模块计数与第 14 节不同处，以本节为准（第 14 节是 T-STOP 时点的记录）。
本节是 **MR-2 时点**的记录，模块计数已被第 16 节（MR-3）取代。

### 15.1 落地范围与口径

8 类指令接口**已全部落地**：判死 / 判活 / 判伤 / 超范围 / 单兵充弹 / 全体充弹 / 覆盖配置 / 手动置位。
本节的 7 条与本段同时交付；第 8 条 `move`（手动置位）随后单独补上，证据见第 19 节。

**顺序即纪律：先发字节，成功后落库。** 契约把「受理」定义为「指令进入发送队列」，而物理下发
一旦发生就无法回滚（决策 D11：不得把 Accepted 当设备执行成功）。因此编排不写 `PENDING` 再走
`markSent` 两段式，而是把已发事实**一次性**落成 `state=SENT` 的行——库里的行恒为「真的发过」，
不会出现「发了但库说 PENDING」或「库里说 SENT 但字节没出去」两种自相矛盾。

`accepted=false` 只有两种成因：**链路未开**与**串口队列满**，两者都**不落库、不写日志**
（D11 的裁定是「false = 未入队」，不是「已受理待重连」）。马甲心跳离线**不拦**：离线只说明网关
这一侧没收到心跳，判死/判活这类指令正是发给它的。

| 指令 | 下行帧 | 队列行 | 审计 |
|---|---|---|---|
| 5 条单兵即时 | 11B 单播 `01 .. <num:2BE> ..` | `state=SENT` | 按 `ExerciseLogType` 记一行 |
| 全体充弹 | 11B 广播 `01 14 FF FF FF <ammo:2BE> <weapon> <sum> AF FA` | `vest_num=0`、`command=RELOAD_ALL` | `RELOAD`，`vest_num` 记 **NULL** |
| 覆盖配置 | 14B `02 00 <num:2BE> <mode> <hp> <ammo1:2BE> <ammo2:2BE> <team> <sum> AF FA` | `command=CONFIG` | **不写**（协议 `LogType` 无 `CONFIG`） |

**全体充弹的「全体」由帧内的 `FF FF FF` 表达，而不是由调用次数表达**：只发一帧，回执 `vestNum=0`
（真实编号下界为 1，0 不会与之混淆）；队列行给 0 是因为 `t_exercise_command.vest_num` 是 `NOT NULL`；
审计的 `vest_num` 记 **NULL**（与 V4 建表注释「与马甲无关的系统级日志为 NULL」一致）。广播路径
**刻意不判编成**：契约 `reloadAllVests` 只登记 400 与 409、**没有 404**，拿编成去卡它等于凭空造
一条契约没有的失败路径。

**覆盖配置的「未传字段保持原值」以本场想定编成为来源**（`t_plan_roster`），不是态势：`UnitState`
本来就没有 `mode` 字段，若拿态势当原值来源，mode 仍需回落编成，等于两个来源混用。参数快照记的是
**合并后的生效值**，不是「只传了 hp」这个原始请求。14B 帧的轮询重发由网关侧既有缓存承担，
故本接口是**覆盖配置**语义、不是即时单次指令。

### 15.2 用例判据（`CommandHttpIT`，app，4）

完整生产入口 + 真实 MySQL + 真实安全链 + `scds.gateway.mode=simulator`（并先断言装配出来的是
`SimulatedLinkAdapter`）；仿真链路 `sentCommands()` 同步记录、不起线程，故下行字节断言是确定性的、
不与时间赛跑。写入用随机想定名与专属编号段（62400+），在 `cleanUp()` 里按 id / 编号精确回收。

1. `commandDownlinkReachesSimulatedLinkAndPersistsSentRowWithAudit`——判死与充弹走真实 HTTP，
   下行字节逐位等于 `DownlinkCommands` 的输出，库里落 `SENT` 行与审计行；超范围指令照发但不写审计。
2. `closedLinkIsAcceptedWithoutEnqueueAndContextFailuresAreContractShaped`——链路未开时**受理但未入队**
   （`accepted=false`、不落库不写日志）；无演习 409；不在编成 404。
3. `reloadAllBroadcastsOneFrameAndAuditsWithoutVestNum`——11B 广播帧只发一帧、回执与队列行的 `vest_num=0`、
   审计 `RELOAD` 一行且 `vest_num IS NULL`（用 `COUNT(*) ... AND vest_num IS NULL` 与「记成 0」区分开）；
   `weapon=TURRET` 为 400，而不是被静默当成 PRIMARY。
4. `setVestConfigMergesWithRosterAndSendsPollConfigFrame`——编成写进 `mode=MANUAL / ammo1=12 / ammo2=7`
   后只传 `hp`，14B 帧逐位等于 `pollConfig(num, MODE_MANUAL, 80, 12, 7, RED)` 的输出，参数快照是合并后的
   生效值，只落队列行不写审计；编成外仍报同一个 `DEVICE_VEST_NOT_FOUND`（未因内部改走编成表而换码）。

### 15.3 边界

`ApiSkeletonSmokeIT` 的 70 条契约路由按「非 404」判定；已落地的指令端点在建线前即按令牌/参数失败
（401 / 400），不会被该判据放过。它的 501 断言用例当时仍用真占位端点 `GET /devices/vests/1/link`
（该端点已由第 22 节接线，501 示例现改用真占位的 `GET /api/v1/replays`）。
`getVestLink` 当时仍 501，已由第 22 节收口（`move` 当时仍 501，已由第 19 节收口）。`CommandService` 刻意不加 `@Transactional`：
物理下发已不可回滚，只把两个库写包成事务会给出一种并不存在的「整体回滚」错觉。

**已知缺口（已在 PLAN 写入侧收口，见第 21 节）**：`t_plan_roster` 的 `hp` / `ammo1` / `ammo2`
在**库层**仍无范围校验（`ck_plan_roster` 只覆盖 `team` / `mode` / `vest_num`，V3 是已应用的历史迁移、
不改），而请求体侧的 `@Min` / `@Max` 又会被「未传时取编成原值」这条路绕过；越界原值（如 hp=300）
会在 14B 组帧时静默截断。故 PLAN 的两条写路径（`replaceRoster` / `importPlan`）在
`PlanService.validate` 里统一拦下越界值。`CONFIG` 也不写审计，故这类覆盖没有留痕。

本段为**代码验收**，不是生产发布批准。

## 16. M3 COMMAND 队列过期扫描（MR-3）

2026-10-27 按 M3 COMMAND 工作包的最后一块实施（`docs/implementation-plan.md` 第 90 行
「过期和审计」；决策 D11 要求区分「未入队」「已发送」「已确认」）。本节记录本段证据，
**其模块计数与总计取代第 15 节的表**。

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动 |
| core | 510 | 0/0/0 | 新增 `ExerciseCommandExpiryTaskIT` 6；仓储新增 `findSentBefore` |
| api | 139 | 0/0/0 | 未改动 |
| app | 318 | 0/0/0 | 未改动；`CommandHttpIT` 4 在 `@EnableScheduling` 真生效下仍通过 |
| solver/domain | 0 | 无测试 | 构建成功，不证明算法数值或状态业务 |
| 总计 | **1035** | **0/0/0** | 全 reactor SUCCESS |

### 16.1 落地范围与口径

**载体**：`scds-core` 的 `ExerciseCommandExpiryTask`（`@Component`），`@Scheduled(fixedDelay = 5000)`。
它只依赖两个 core 仓储，故不必像 `CommandService` 那样把装配点挪到 `scds-app`；
`@EnableScheduling` 仍只在唯一入口 `ScdsNextApplication` 上开启，因此它**在应用内自动跑、
在 `scds-core` 的测试上下文里不会自己跑**（测试手工调 `tick()`），与 `ExerciseHeartbeatTask` 同一约定。

**配置**：`scds.command.sent-ttl`，默认 `30s`。绑定即校验，非正时长**启动失败**——零或负的超时窗
会把每一条刚发出的指令在下一拍就判死，而现场从配置上看不出异常。时间取 `Instant.now()` 而不注入
`Clock`：`Clock` bean 只在 `scds-app` 的 `SituationStateConfig` 里，core 测试上下文没有它，
依赖它会让 504 项 core 测试起不来；「超时」由测试回填 `sent_at` 制造，不需要假时钟。

**扫描范围**：只 `state = 'SENT' AND sent_at < now - ttl`。`PENDING` 在本实现下**不会产生**
（第 15.1 节的「先发字节、一次性落 `SENT`」），而它语义上是「还没发出去、等重连」，判它超时
等于把派发层的责任挪给过期扫描。单拍最多处理 200 条；无逾期指令时只做一次查询即返回，不写库也不打日志。

**动作与顺序**：先写 `SYSTEM` 存证 → 再 `markFinished(EXPIRED, REASON='SENT_TIMEOUT')` → 再
`deleteFinished()` 清理终态行；三者同一事务（方法标了 `@Transactional`）。与收尾路径同源的理由：
队列表不保留历史，**永久证据只有 `t_exercise_log`**，先删后记一旦失败那些字节就永久消失。把存证放在
最前，即便进程崩在中间也只多出一条日志，不会丢证据。`fail_reason` 用 `SENT_TIMEOUT`，与自愈的
`INSTANCE_RESTART` 并列，现场可据此区分「进程重启丢的」与「发下去没有回音」。

**与停演收尾的分工**：收尾走 `markExpiredByExerciseId` + `deleteByExerciseId`（**按场次**，失败原因
`INSTANCE_RESTART`）；本任务走 `markFinished` + `deleteFinished`（**全局 + 时间窗**）。两者守卫同源
（`state IN (PENDING, SENT)`），并发时 `markFinished` 返回 0 只是「这一条已被别的路径推进过」，
不当作故障，也不影响存证（证据由推进它的那条路径负责）。

### 16.2 用例判据（`ExerciseCommandExpiryTaskIT`，core，6）

测试上下文不带 `@EnableScheduling`，任务不会自己跑，故先断言「没调 `tick()` 就没动」，再手工驱动一拍。
「超时」靠回填 `sent_at` 制造，不依赖真实等待。全类跑在测试事务里，断言一律限定到本用例新生成的
`exerciseId`，全库性的 `deleteFinished()` 随事务回滚。

1. `defaultTtl_isThirtySeconds`——`scds.command.sent-ttl` 的默认绑定确为 30s。
2. `tick_expiresOverdueSentCommandAndLeavesSystemEvidence`——60s 前下发的 `SENT` 行被判死：
   队列行消失，且恰有一条 `SYSTEM` 存证，其 `vestNum` 为原马甲号、`operatorName` 为 NULL、
   `downlinkHex` 等于下发原文、`detail` 含队列主键 / 指令名 / 马甲号 / `原状态SENT` / `SENT_TIMEOUT`。
3. `tick_leavesFreshSentCommandInFlight`——5s 前下发的行仍在 `SENT` 且无存证（超时窗 30s 够不到它）。
4. `tick_leavesPendingCommandsAlone`——10 分钟前入队的 `PENDING` 行不动、无存证；
   判据是「只扫 `SENT`」，与「入队时间有多老」无关。
5. `tick_onlyTouchesTheOverdueRowsAndKeepsTheRestOfTheQueue`——120s 前的逾期行与 5s 前的新鲜行
   分属两场演习，只有前者被处理，后者的场次无存证。
6. `tick_isIdempotentAcrossTicks`——连跑两拍只有一条存证：第一拍已把它作废并清出队列，第二拍扫不到它。

### 16.3 边界

**「行消失」是 EXPIRED 的间接证据**：`deleteFinished()` 只删终态（`ACKED` / `FAILED` / `EXPIRED`），
`PENDING` / `SENT` 一律保留；本段没有任何路径会把 `SENT` 改成 `ACKED`，故「原来是 `SENT` 的行现在
查不到」只可能是已被本任务作废。这条推理依赖 `deleteFinished()` 的守卫不自相矛盾，用例 4 从反面
（`PENDING` 不动）做了对照。

本任务在 `scds-app` 的所有 IT 里都会随 `@EnableScheduling` **自动运行**。`CommandHttpIT` 的下行断言
在发出后数秒内完成，30s 的超时窗够不到它，回归已通过。`deleteFinished()` 是**全库口径**，会顺带清掉
别处的终态行——队列本就是瞬态表（见 `ExerciseCommandRepository` 类注释「队列是瞬态的」），
历史由 `t_exercise_log` 承载。

**不做「等回执」**：协议没有回执帧，`ACKED` 至今无人写入（`markAcked` 保留备用），因此本任务的超时是
**队列卫生**语义，不是「真的在等某个 ACK」。现场若要解释「指令为什么变成 EXPIRED」，答案应当是
「发出去后没有回音，按 TTL 收口」，而不是「确认超时」。

`getVestLink`、M3 CALIBRATION、M5 EXPORT 当时仍 501，与本段无关（`move` 当时仍 501，已由第 19 节收口；
`getVestLink` 已由第 22 节收口）。

本段为**代码验收**，不是生产发布批准。

## 17. M3 CAL-1 UWB 室内解算链路

2026-10-27 按 M3 CALIBRATION 的**前置**工作包实施（`docs/implementation-plan.md` 第 91 行；
`docs/design/06-state.md` §4.6「CAL-1 落地补充」、§7 判据）。CALIBRATION 拆为
**CAL-1（解算链路）** 与 **CAL-2（标定本体）** 两段，CAL-1 再拆三步：
CAL-1a 解算器与单测 → CAL-1b 装配接线 → CAL-1c app 端到端。本节记录三段合起来的证据，
**其模块计数与总计取代第 16 节的表**；CAL-2 已在本节之后交付，见第 18 节。

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动（协议字节、成帧规则一字未动） |
| gateway | 49 | 0/0/0 | 未改动 |
| core | 528 | 0/0/0 | CAL-1a 新增 `IndoorPositionResolverTest` 12；`SituationStateServiceTest` +4、`SituationSessionBinderTest` +2 |
| api | 139 | 0/0/0 | 未改动 |
| app | 320 | 0/0/0 | `SituationStateIT` +2（CAL-1c：真装配 + 真库底图 + 真成帧器的 UWB 端到端） |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1055** | **0/0/0** | 全 reactor SUCCESS |

### 17.1 落地范围与口径

**CAL-1a（`IndoorPositionResolver`，`scds-core`）**：把协议 B 的 41B 帧解成**整图**百分比坐标。
每马甲自持 `UwbFilter` + `UwbSolver` 先验，因此它必须是 Spring **单例**；`load(...)` 装载本场
几何（`uwb_meta` 四角 + 图片像素尺寸），`clear()` 清空。三类情况**一律不产出坐标**（只计数）：
UWB 开关关、本场未装载几何、该帧解算降级。**不改 `scds-solver`**：`UwbSolver` 与其几何约定
一字未动，`IndoorPositionResolver` 只做「装载 → 调用 → 二次映射」。

**CAL-1b（装配接线）**：`SituationStateService.dispatch` 的 `case StateEvent.Uwb` 从「只计数」
改为 `applyUwb` —— 解出则写
`GeoPoint(CoordinateSystem.INDOOR, xPct, yPct, 原楼层/点位, ...)` + `positionSource = UWB` +
`signalQuality = usedCount`；解不出**什么都不改**，只累加 `uwbFramesWithoutFix`。
解算几何随开演装载（`SituationSessionBinder` 注入 `BaseMapRepository`，按 `exercise.mapId`
读 `t_map`）；`mapId` 为 null 必须**显式清空**。`beginExercise` 幂等判据后清一次、`endExercise`
首行清一次。`IndoorPositionResolver` 由 `scds-app` 的 `SituationStateConfig` 装配，
`@ConfigurationProperties("scds.uwb")` 恢复绑定前缀（`scds-solver` 刻意零 Spring 依赖）。
**同时修掉一个死配置键**：`application.yml` 原写 `uwb-mode: enabled`，而 `ScdsUwbProperties.mode`
默认 `false`，即解算链路此前**永远不会生效**；改为 `mode: true`。

**CAL-1c（app 端到端）**：`SituationStateIT` 增两条用例，走完整生产入口装配。

### 17.2 用例判据

core（`IndoorPositionResolverTest` 12 / `SituationStateServiceTest` 32 / `SituationSessionBinderTest` 8）
覆盖解算语义：中心帧映射为整图 `(0.525, 0.5640625)`、小位移方向正确、时序倒灌保持上一帧、
每马甲互不串先验、缺锚点/非正长宽/缺图片尺寸**装载即判否**、`clear()` 与换图重建状态；
服务层覆盖「解出写坐标 / 阵亡冻结不更新 / 无几何只计数 / 编成外马甲不参与」。

app（`SituationStateIT`，2）：

1. `uwbFrameResolvesIntoWholeImageIndoorCoordinate`——铺一张 2000×1600、现场 30m×18m 的
   室内底图（`uwb_meta` 四角按「左下 → 右下 → 右上 → 左上」，`bs1→bs2` 为长度基准、
   `bs1→bs4` 为宽度基准），装载带 `mapId` 的演习后注入 41B 帧：断言真成帧器切出的
   `vestNum` / `rangesMm` 与拼入一致，读侧坐标为 `INDOOR`、`xPct/yPct` 在 0.002 内命中整图中心、
   不带 WGS-84、`positionSource = UWB`、`signalQuality = 4`，且 `uwbFramesWithoutFix()` 不增。
2. `uwbFramesWithoutLoadedGeometryAreCountedNotSolved`——`mapId = null` 时同一帧只让
   `uwbFramesWithoutFix()` 加一，坐标仍为 null、来源仍 `NONE`（不臆造坐标）。

### 17.3 边界与不变式

**唯一例外：UWB 41B 帧是测试自拼的**。库里的 `uwb41` 黄金样本是**近场**测距（几十~几百毫米，
解不出 30m×18m 的场），且没有本场编成编号（1/2/4/5）对应的样本；故 `SituationStateIT.uwbFrameHex`
按 `PROTOCOL.md` §6.5 的冻结规则（帧头 `AA 29`、帧尾 `55`、`sum(byte[0..38]) & 0xFF == byte[39]`）
拼写字节，并把「能否被**真**成帧器切出并交给状态机」写进断言作为往返校验。协议 A 的串口帧仍然
一律取黄金样本，不自造字节；UWB 成帧对真实捕获字节的验证由 `UwbLinkTest` / `UwbFramesGoldenSamplesTest` 覆盖。

**未改动的硬约束**：协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
在线窗口（5000ms）、成帧与校验算法、`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值。

**`ff06` 仍只打 DEBUG**：协议 §1.4④ 要求「UWB 连续坐标生效时丢弃 `ff06` 室内点位帧」，
该**判别开关**属 CAL-2（标定分段），本段只把它降为 `log.debug` 并注明「未采用」，
不改变其透传与计数行为。

**在线态不受影响**：本段不引入新的在线判定，仍只由心跳驱动（5000ms），UWB 帧不刷新
`lastHeartbeatAt`；因此「只有 UWB、没有心跳」的马甲不会被判在线——这是既有口径，非本轮回归。

**CAL-2 已收口**：三个标定端点去 501、进程内标定状态机、`V11` 校准列、现场实测长宽覆盖
写回 `uwb_meta` 均已交付，证据见第 18 节。`getVestLink`、M5 EXPORT 当时仍 501，
与本段无关（`move` 当时仍 501，已由第 19 节收口；`getVestLink` 已由第 22 节收口）。

本段为**代码验收**，不是生产发布批准；UWB 盒子经 12001 的连续坐标、容量与长稳属现场待验收。

## 18. M3 CAL-2 UWB 中心基准校准（标定本体）

2026-10-27 按 `docs/implementation-plan.md` 第 91 行的 CALIBRATION 工作包实施，是把
MAP-BASE 分段 E 时期留下的**三端点 501** 收口的那一段。按用户裁定拆三步：
CAL-2a core 状态机 + 单测 → CAL-2b 端点接线 → CAL-2c `V11` 校准列 + app 端到端 + 文档回写。
本节记录三步合起来的证据，**其模块计数与总计取代第 17 节的表**。

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动（协议字节、成帧规则一字未动） |
| gateway | 49 | 0/0/0 | 未改动（`UwbTcpServerLink` 的 41B 帧通路已由 CAL-1 接出） |
| core | 548 | 0/0/0 | CAL-2a 新增 `UwbCalibrationServiceTest` 18；CAL-2c 新增 `BaseMapRepositoryIT` 2（`markCalibrated` 与「清标定不回头改实测值」） |
| api | 139 | 0/0/0 | 未改动 |
| app | 322 | 0/0/0 | CAL-2b 新增 `MapBaseHttpIT.uwbCalibrationEndpointsOverRealHttp`；CAL-2c 新增 `SituationStateIT.uwbCalibrationWritesTapeMeasuresBackThroughTheWriterThread` |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1077** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

### 18.1 落地范围与口径

**CAL-2a（`UwbCalibrationService`，`scds-core`）**：进程内标定状态机（`IDLE` / `RUNNING` /
`SUCCEEDED` / `FAILED`），**不落库**——标定状态是运行态，重启即丢；正因如此，「标定时刻」才必须
落库（CAL-2c 的 `calibrated_at`）才有可查证据。`start/status/clear/onFrame` 四个入口，采样在
`onFrame` 里累计；`finish` 用逐角**中位数**做静止一致性校验（`calibStaticMaxDiffCm`，默认 50.0mm），
再用请求里的卷尺实测值**覆盖**写回 `realLengthCm/realWidthCm`。**采样只承担一致性校验，不反推几何**：
现场量出来的才是真相，UWB 只是用来确认「这个马甲刚才没动」。

**判据顺序固定**（`start`，一处判完）：`uwb_meta` 缺四角 → 404 `MAP_NOT_CALIBRATED`；帧数非正 →
400 `VALIDATION_FAILED`；两个实测值都缺 → 400 `MAP_UWB_META_INCOMPLETE`；实测值非正整数厘米 → 400；
**UWB 链路未开 → 409 `DEVICE_LINK_NOT_OPEN`**；已有进行中的标定 → 409 `MAP_CALIBRATION_IN_PROGRESS`。
「至少要一个实测值」刻意排在「链路未开」之前：前者是**请求自身的合法性**，不该被设备的当前状态掩盖。
`uwbLinkOpen()` 只认 `LinkState.OPEN`（`OPENING` 不算「已开」）。

**CAL-2b（端点接线）**：`MapController` 三端点去 501 —— `POST`（202 受理，响应体是**受理时刻**
的快照，采样仍在写手线程上跑）、`GET`（200，从未标定回 `IDLE` 而非 404：这里「还没开始标定」
是正常状态，与 `getUwbMeta` 的「缺四角骨架 = 缺资源」不同）、`DELETE`（204，**兼作取消**，
正在跑的那次一并丢弃）。新增错误码 `DEVICE_LINK_NOT_OPEN` 按纪律**先登记 openapi 的
`Problem.code` 描述、再回 `ApiErrorCode` 补枚举**，并由 `ApiExceptionHandler` 的「一码一状态」表
映射到 409。`/api/v1/maps/*/uwb-calibration` 的 `POST`/`DELETE` 归 `ADMIN`/`DIRECTOR`。

**CAL-2c（V11 + 端到端）**：新增迁移 `V11__map_calibration.sql`，只给 `t_map` 加一列
`calibrated_at DATETIME(3) NULL`（UTC 挂钟值，NULL = 从未标定）。版本号取 **V11** 而非预留的 V8：
V9/V10 已应用、V8 从未创建，直接补 V8 会成为低于当前版本的未应用迁移并受 Flyway 校验/顺序限制；
**不改任何历史迁移、不开启 out-of-order**（同 V10 口径）。`calibrated_at` 只在
`MapLedgerService.saveUwbMeta` 一处前进（手工 `putUwbMeta` 与标定回写都汇到这里），
因此「`uwb_meta` 齐备 ⟺ `calibrated_at` 非空」；改名、换图型**不刷新**它。该列**刻意不进**
`BaseMapRepository.COLUMNS`、不落到 `BaseMap` 上，所以「有没有落库」只能直查表。

**采样入口挂在唯一写手线程**：`SituationStateService.applyUwb` 里 `calibration.onFrame(...)`
**刻意早于**编成判定（`units.get` 早退）——标定发生在**开演之前**，布场阶段还没有编成，
若放在编成判定之后，标定将永远采不到帧。

### 18.2 用例判据

core（`UwbCalibrationServiceTest` 18 / `BaseMapRepositoryIT` 11，其中 CAL-2c 新增 2）覆盖状态机语义：
未标定几何即拒、帧数非正即拒、两个实测值都缺即拒、非正整数厘米即拒、链路未开 409、重复开标定 409、
采满转 `SUCCEEDED` 且 `progressPct=100`、静止超阈值转 `FAILED`（逐角中位数口径）、
写回失败折叠成 `FAILED` 而**不抛出**（采样在写手线程上，抛出去会打断整条态势流水线）、
`clear` 兼作取消；仓储侧 `markCalibrated` 写时刻、清标定**不**回头改实测值。

app：

1. `MapBaseHttpIT.uwbCalibrationEndpointsOverRealHttp`（HTTP 形状，真生产入口 + 真安全链）——
   无四角时 `POST` → 404 `MAP_NOT_CALIBRATED`；`GET` → 200 `IDLE`/`sampledFrames=0`/`appliedAt` null；
   `PUT uwb-meta` 后 `POST` → 409 `DEVICE_LINK_NOT_OPEN`（默认 `gateway.mode=real` 且未手动开链路），
   复查状态仍是 `IDLE`（失败不留痕）；`DELETE` → 204。请求体 helper 必须带一个卷尺实测值
   （见 18.3）。
2. `SituationStateIT.uwbCalibrationWritesTapeMeasuresBackThroughTheWriterThread`（成功路径端到端）——
   真装配 + 真库底图 + 真成帧器：先 `endExercise()` 证明**标定不必先开演**，自拼 41B 帧经
   `links.injectUwb` 连投 3 帧，断言采满转 `SUCCEEDED`、`progressPct=100`、`appliedAt` 非空；
   再回查 `uwb_meta` —— 卷尺实测长度**覆盖**成 2450（与底图自带的 3000 刻意不同，写回才可辨）、
   未提供的维保持 1800、四角几何 `bs1.xPx` 不被改写；最后直查 `t_map.calibrated_at` 非空。
   日志实证采样确实走在唯一写手线程上（线程名 `[ds-state-writer]`，`UWB 标定完成：… 采样帧数=3`）。

### 18.3 边界与不变式

**HTTP 用例的请求体必须带卷尺实测值**：`start` 的判据顺序是「先查至少一个实测值（400
`MAP_UWB_META_INCOMPLETE`），再查链路（409 `DEVICE_LINK_NOT_OPEN`）」。若请求体只给
`sampleFrames`，用例会先拿到 400、走不到那条 409 —— 那就测错了分支。

**同时更新两处迁移清单守卫**（`TestDatabaseGuardTest.allExistingMigrationsAppliedWithoutChangingHistory`
与 `ApplicationStartupIT` 的同名断言）：这两条断言的是「已应用的迁移版本序列**完全等于**一份字面清单」，
本就是用来拦「历史迁移被偷偷改写」的，因此新增 V11 后必须同步 `"11"`。表数量断言（19）不变——
V11 只加列不加表。

**契约字段名与语义错位（留档不改）**：`UwbCalibrationRequest.bs1ToBs3Cm` 字面是「bs1→bs3」，
而 bs1→bs3 是**对角线**；契约把它描述为「现场卷尺实测**宽度**」。按裁定**保留字段名不动**
（改字段名要动冻结契约与两个客户端），语义以描述为准 = `realWidthCm`（宽度基准 bs1↔bs4）。
详见 [决策清单](decision-register.md)「CAL-2 口径」。

**未改动的硬约束**：协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
在线窗口（5000ms）、成帧与校验算法、`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值；
不改历史迁移、不开启 out-of-order。

**在线态不受影响**：本段不引入新的在线判定，仍只由心跳驱动（5000ms）。

本段为**代码验收**，不是生产发布批准。现场待验收项（连同本段一起挂起，**已在第 27 节收口**）：
`calibrated_at` 的可读性、UWB 盒子经 12001 的静止一致性阈值（50mm）是否适配现场噪声，
以及**底图四角编号须按 `bs1` 左下 → `bs2` 右下 → `bs3` 右上 → `bs4` 左上录入**，
即 `bs4` 必须与 `bs1` 构成宽度基准（`bs1→bs4`）、`bs2` 与 `bs1` 构成长度基准（`bs1→bs2`），
两基准近似正交——标定回写的 `realWidthCm` 对应的正是 `bs1↔bs4` 这条边，编号错了宽度就落错边。

## 19. M3 COMMAND 手动置位（第 8 条指令 `moveVest`）

2026-10-28 按 `docs/implementation-plan.md` 第 108-127 行的 command 工作包实施：8 条指令里
**最后一条**（`moveVest`，契约 `docs/api/paths/command.yaml` 的 `x-internal` 联调端点）。
按用户裁定拆三步：`moveVest-a` core 写入口 + 单测 → `moveVest-b` 楼层点位表读取 → `moveVest-c`
端点接线 + 端到端 + 文档回写。本节记录三步合起来的证据，**其模块计数与总计取代第 18 节的表**。

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动（协议字节、`LogType` 一字未动） |
| gateway | 49 | 0/0/0 | 未改动（手动置位**不发字节**，不经过下行链路） |
| core | 555 | 0/0/0 | 新增 `SituationStateServiceTest` 4、`MapLedgerServiceIT` 3 |
| api | 139 | 0/0/0 | `ApiSkeletonSmokeIT` 判据是「非 404」，去 501 不破该判据 |
| app | 324 | 0/0/0 | 新增 `CommandHttpIT` 2（原 4 → 6） |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1086** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

### 19.1 落地范围与口径

**为什么单独一条**：`moveVest` 与前 7 条不同——它**不下发任何字节**，只把马甲坐标置为本场底图
某个楼层点位表里的那个点（联调/演示用；正式演习禁用）。它因此跨三层：接口层搬 DTO、
`CommandService` 判语境与取坐标、`SituationStateService` 在唯一写手线程上改态势。

**判据顺序固定**（`CommandService.moveVest`，一处判完）：无进行中演习 → 409 `EXERCISE_NOT_RUNNING`；
本场 `BattleMode.BATTLE` → 403 `COMMAND_MOVE_FORBIDDEN`；`floorNum` / `posNum` 任一为 null →
400 `VALIDATION_FAILED`；不在编成 → 404 `DEVICE_VEST_NOT_FOUND`；本场未绑底图 → 404 `MAP_NOT_FOUND`；
点位表里没有这个点 → 404 `MAP_FLOOR_POINT_NOT_FOUND`；最后投事件。

**403 与 `AUTH_FORBIDDEN` 严格分工**（新码 `COMMAND_MOVE_FORBIDDEN`）：后者是「令牌有效但角色不允许」，
换个有权限的账号就能过；前者说的是**这一场演习的模式不允许该动作**，换谁登录都得拒。
前端据此提示「本功能仅联调/训练可用」，而不是引导用户去要权限。

**404 分两码的理由**：`MAP_NOT_FOUND` 是「图都不在」，`MAP_FLOOR_POINT_NOT_FOUND` 是「图在、
点位表里没这个点」。对调用方的处置动作不同（换一张图 vs 把点位表补上），故不折叠成一个码；
但**点位表内部的多种 miss 折叠成同一个 404**：整层没录、点位号不存在、该项缺 `xPct`/`yPct`、
`points` 文本损坏、合法 JSON 但不是数组——对调用方都是同一件事「置位无处可去」。

**写入口径**：`CommandService` 拿到 `(xPct, yPct)` 后调 `SituationStateService.moveVest(...)`，
后者**只在队列上 `offer` 一个 `StateEvent.ManualPosition`**，坐标真正写进内存态是在唯一写手线程
`scds-state-writer` 的 `applyManualPosition` 里（`PositionSource.MANUAL`、`CoordinateSystem.INDOOR`）。
`accepted=false` 只有一种成因：态势侧已停止接收（`!accepting`）。**阵亡马甲不绕冻结**——已有的
「阵亡冻结在最后有效坐标」是硬约束（见 `UnitState` 类注释），手置位不是它的例外。

**不发字节、不落队列、不写审计**：回执 `downlinkHex` 恒为 `null`；不写 `t_exercise_command`
（没有下行帧，写进去只会得到一行永远发不出去的假队列）；不写 `t_exercise_log`——协议 `LogType`
里**没有 `MOVE`**，而协议已冻结、不新增值，故不借用别的类型。

**新错误码按纪律三层登记**：先写进 openapi 的 `Problem.code` 描述（`common.yaml`），
再回 `ApiErrorCode` 补枚举，最后由 `ApiExceptionHandler.statusOf` 登记状态（403 / 404）。

### 19.2 用例判据

core：

- `SituationStateServiceTest`（4）：手置位事件写进 `units` 的坐标与来源、编成外只计数不改态势、
  阵亡不绕冻结、队列不再接收时返回 `false`。
- `MapLedgerServiceIT`（3）：命中点位回它那一项的归一化坐标；整层未录 / 点位号不存在 / 缺坐标 /
  非数组 / `posNum` 非数字全部折叠成空 `Optional`；底图不存在时仍先判 `MAP_NOT_FOUND`。

app（`CommandHttpIT`，真生产入口 + 真 MySQL + 真安全链 + `scds.gateway.mode=simulator`）：

1. `moveVestRepositionsUnitFromFloorPointWithoutTouchingQueueOrAudit`（成功路径）——先建室内底图并
   `PUT floors/{n}/points` 录点位表，再**开演前**经 `PUT /api/v1/plans/{id}` 把 `mapId` 绑到想定上
   （想定一旦被演习占用就禁止改动），开演后 `POST .../move`：回执 `{command:"MOVE", accepted:true,
   downlinkHex:null}`，仿真链路 `sentCommands()` 为空、`t_exercise_command` 与 `t_exercise_log`
   计数不变；再轮询态势（写手线程异步）断言 `positionSource=MANUAL` 与
   `GeoPoint(INDOOR, 0.25, 0.75, floor=3, pos=7)`。
2. `moveVestRefusalsAreContractShaped`（拒绝路径）——点位号不存在与整层未录都是 404
   `MAP_FLOOR_POINT_NOT_FOUND`；缺 `floorNum` / 缺 `posNum` 都是 400 `VALIDATION_FAILED`；
   编成外 404 `DEVICE_VEST_NOT_FOUND`；把想定绑成 `BATTLE` 后开演则 403 `COMMAND_MOVE_FORBIDDEN`。

### 19.3 边界与不变式

**HTTP 用例必须先给想定绑底图**：`startRunningExercise` 建想定时不绑图，而 `moveVest` 的坐标
来源只能是「本场底图 + 该楼层点位表」；不绑图会先拿到 404 `MAP_NOT_FOUND`，走不到想测的分支。

**底图无需标定**：`moveVest` 只读楼层点位表，不读 `uwb_meta`；底图缺四角几何时装配层只记一条告警，
开演与置位照常——这与 CAL-2 的「读数才要求标定」是两回事。

**未改动的硬约束**：协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
在线窗口（5000ms）、校验算法、`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值；
不改历史迁移、不开启 out-of-order。

**仍未交付**：全仓仍抛 501 的只剩 7 条——`ReplayController` 全部 7 条（`listReplays` / `getReplay` /
`getReplayTimeline` / `getReplayStatistics` / `exportReplay` / `getExportTask` / `downloadExport`）；
`DeviceController.getVestLink` 已由第 22 节收口；`ExerciseController.listCurrentExerciseLogs` 已由第 20 节收口
（§19 当时记的「M5 EXPORT 三条」只是这 7 条中的一部分，漏记了 replay 读侧 4 条）。
Flutter 两端未开工。`t_plan_roster` 的 `hp` / `ammo1` / `ammo2` 范围校验已由第 21 节收口（见 15.3）。

本段为**代码验收**，不是生产发布批准。

## 20. 当前演习日志流水（`GET /api/v1/exercise/current/logs`）

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动（本端点不经过下行链路，也不发字节） |
| core | 555 | 0/0/0 | 未新增用例；新增读侧方法 `ExerciseLifecycleService.currentLogs` |
| api | 139 | 0/0/0 | `ApiSkeletonSmokeIT` 判据是「非 404」，去 501 不破该判据 |
| app | 325 | 0/0/0 | 新增 `ExerciseLifecycleHttpIT` 1（原 2 → 3） |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1087** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

> 计数口径：上表按 Maven **模块级汇总**（`[INFO] Tests run:`）取值。若改按
> `target/surefire-reports/TEST-*.xml` 累加，core 会少 21 项——`@Nested` 内部类
> （如 `TrackSamplingTest$Sampler` 13 + `$Writer` 8）不落在其外层类的 XML 里，
> 需要逐个 XML 文件统计才不漏。核对模块总数时以模块级汇总为准。

### 20.1 落地范围与口径

**为什么单列**：这是 `ExerciseController` 最后一个 501 占位（同类的「当前演习」与「当前在线数」
此前已接线）。它读的是 `t_exercise_log`，属**读侧**，与 §19 的手动置位（写侧）无关。

**归属**：接口层不直连 `scds-core` 的仓储（既有取向），故读原语挂在
`ExerciseLifecycleService.currentLogs`（该类已注入 `ExerciseLogRepository`）上，控制器只做
「取列表 → 映射 DTO → 取末条 seq 做 `latestSeq`」。

**游标口径**（`sinceSeq` → `latestSeq` 的推进）：

- `sinceSeq` 为 `null`（不传）＝契约的**最新一页**：取 seq 最大的 `limit` 条，再按 seq 升序
  （走 `ExerciseLogRepository.findLatest`）；非 `null` 走 `findByExerciseIdSince`
  （`seq > sinceSeq` 升序）。
- `latestSeq` 取本批**末条**（列表已升序），与 `SituationController.listHits` 同一口径；
  客户端把它存下来当下次的 `sinceSeq`。
- 库里 `t_exercise_log.seq` 是**全局自增**（不按演习分段），故换了场次游标也不回退；
  本层不判「游标推没推到末尾」，那由客户端拿 `latestSeq` 自己比。

**无演习进行中**：契约这个端点只有 401 / 500，**没有 404**，故不报错也不编造场次——
返回 `200` + `items=[]` + `latestSeq=null`，与态势读侧「无演习返回空列表、不查库」同一取向。

**`limit` 边界**：契约 1..500、默认 100；用 `@Min(1) @Max(500)` 拦在参数校验层
（越界 → 400 `VALIDATION_FAILED`），与「路由没注册」的 404 区分开。

**同名不同形状的两侧 `ExerciseLogEntry`**：领域侧（`scds-domain`）多一个 `exerciseId`、
字段名是 `logType` / `operatorName`；契约 DTO（`scds-api`）没有 `exerciseId`、
字段名是 `type` / `operator`。故控制器里写全名并用 `toLogEntry` 显式映射，
不靠名字巧合（与 `toEntry` 同一处理）。

### 20.2 用例判据

app（`ExerciseLifecycleHttpIT`，真生产入口 + 真 MySQL + 真安全链）：

1. `currentLogsFollowTheSeqCursor`（新增）——依次断言：
   无演习时是空页（`items=[]`、`latestSeq=null`）；开演后**尚未写任何日志**时**仍是空页**
   （证明按「当前场次」过滤，不是把历史行带出来）；种两行后不传 `sinceSeq` 得最新一页
   （`items` 两条且按 seq 升序、`latestSeq` = 末条 seq，且 `type` / `vestNum` / `detail`
   与所种行一致）；`sinceSeq=第一条` 只回第二条、`latestSeq` 随之推进；
   `sinceSeq=第二条` 得空列表 + `latestSeq=null`（客户端据此判断「已是最新」）；
   `limit=1` 只回 seq 最大的那条且仍按升序；`limit=0` → 400（契约边界拦在参数校验层）。
   用例末尾**停演**——本类硬约束「同一时刻至多一场演习」，留下进行中的场次会让同类
   其他用例的「开演前无演习」前置断言失败。

**种行方式**：夹具 `seedLog` 直插 `t_exercise_log` 后以 `MAX(seq)` 读回。
本用例只验 HTTP 接线与游标口径，「谁在何时写日志」由服务层与其调用方覆盖；
用例串行执行，不存在与别场的并发插入。

### 20.3 边界与不变式

**未改动的硬约束**：协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
在线窗口（5000ms）、校验算法、`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值；
不改历史迁移、不开启 out-of-order。本段**未新增错误码**（复用既有 `VALIDATION_FAILED`）。

**只读**：本端点不写 `t_exercise_log`、不写 `t_exercise_command`、不发字节。

本段为**代码验收**，不是生产发布批准。

## 21. PLAN 编成 `hp` / `ammo1` / `ammo2` 范围校验

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动 |
| core | 558 | 0/0/0 | `PlanServiceIT` 新增 3（原 17 → 20） |
| api | 139 | 0/0/0 | 未改动 |
| app | 325 | 0/0/0 | 未改动 |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1090** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

### 21.1 落地范围与口径

**补的是哪一处缺口**：`CommandService.setVestConfig` 的 javadoc 与第 15.3 节都记过——编成的
`hp` / `ammo1` / `ammo2` 在库层与 PLAN 写入侧都没有范围校验，越界值会在 14B 轮询配置帧组帧时
被静默截断（`DownlinkCommands.pollConfig` 的 `d[5] = (byte) hp`），现场表现为「设了参数没生效」。

**边界来源**：`docs/PROTOCOL.md` 下行指令表的 14B 轮询配置帧——`hp` 占 **1 字节**、
`ammo1` / `ammo2` 各占 **2 字节大端**，即 `hp ∈ [0, 255]`、`ammo ∈ [0, 65535]`。与契约
`docs/api/schemas/domain.yaml` 的 `VestConfigRequest` 完全一致——两个模型是同一条帧的两个入口。

**校验点**：`PlanService.validate`——它同时守着 `replaceRoster`（HTTP `PUT`）与 `importPlan`
（模板导入）两条写路径，加在这里一处覆盖两条，脏值不落库。越界抛 400 `VALIDATION_FAILED`
（复用既有错误码，不新增），文案带上马甲编号与字段名，避免操作员在一份几十条的编成里盲找。

**为什么不在库层加 CHECK**：`t_plan_roster` 由 V3 建表，是**已应用的历史迁移**，不改；补 DDL
只能新开一份迁移，而库层没有外键、一致性本来就由应用层保证（V3 注释明说）。本段未新增迁移。

**契约同步**：`domain.yaml` 的 `RosterEntry` 补上 `hp` / `ammo1` / `ammo2` 的 `minimum` /
`maximum`（此前只有 `default`），与 `VestConfigRequest` 对齐——服务端是真正的判据，契约写明边界。

### 21.2 用例判据

core（`PlanServiceIT`，真库真事务）新增 3 条：

1. `replaceRoster_acceptsTheProtocolBounds`——`0/0/0` 与 `255/65535/65535` 都落库成功，
   读回值逐字段等于传入值（读回顺序按「队伍 → 马甲编号」，`BLUE` 在 `RED` 前）。
2. `replaceRoster_withAnHpPastOneByteOrAmmoPastTwoBytes_isRejectedWithoutTouchingTheStoredRoster`——
   `hp=256` 与 `ammo1=65536` 各报一次 400 `VALIDATION_FAILED`；两次拒绝之后库里仍是上一次那份
   （「不产生部分写入」）。
3. `replaceRoster_withANegativeHp_isRejected`——`hp=-1` 报 400；库中编成为空。

### 21.3 边界与不变式

**未改动的硬约束**：协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
在线窗口（5000ms）、校验算法、`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值；
不改历史迁移、不开启 out-of-order。本段**未新增错误码**（复用既有 `VALIDATION_FAILED`）、
**未新增迁移**。

**校验顺序**：先查重（纯内存、不落库）→ 再查台账 → 最后查范围。查重在前的理由与原来一致：
重号必然伴随同一条 `uk_plan_roster` 冲突，先报重号才能给出「哪一号重复」这种可处置的信息。

本段为**代码验收**，不是生产发布批准。

## 22. `getVestLink` 每马甲链路状态（`GET /api/v1/devices/vests/{vestNum}/link`）

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动（本端点不经过下行链路，也不发字节） |
| core | 564 | 0/0/0 | `SituationStateServiceTest` 新增 6（原 36 → 42） |
| api | 139 | 0/0/0 | `ApiSkeletonSmokeIT` 换 4 参构造并把 501 示例改为 `/api/v1/replays`，计数不变 |
| app | 326 | 0/0/0 | `HwLinkHttpIT` 新增 1（原 5 → 6） |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1097** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

> 计数口径同第 20/21 节：按 Maven **模块级汇总**（`[INFO] Tests run:`）取值，
> 按 `target/surefire-reports/TEST-*.xml` 累加会漏掉 `@Nested` 内部类。

### 22.1 落地范围与口径

**补的是哪一处缺口**：`DeviceController.getVestLink` 是全仓最后一条「属 STATE、却被硬件工作包
有意暂缓」的端点——[设计 05](design/05-hw-link.md) §5 的 H6 明示它**不接线**，理由是它返回的是
**每马甲**在线态，权威来源是 core 状态机（[设计 06](design/06-state.md) §S7）而非链路级状态。

**六字段来源（契约 [device.yaml](api/paths/device.yaml) L313-356）**：`vestNum`/`online`/`lastHeartbeatAt` 与
每马甲的 `lastFrameType`/`framesTotal`/`droppedTotal` 全部取自 `SituationStateService` 里该马甲的内部账，
经 `SituationQuery.vestLink(int)` 透出，控制器只做映射。**不复用**链路级 `LinkStatus.framesTotal/droppedTotal`
（链路累计合法/坏帧），两者不是同一口径，不得互相冒充（设计 05 §5）。

**每马甲计数的口径**（本轮定稿，写进 `SituationStateService` 的 javadoc）：

- **`framesTotal` + `droppedTotal` = 本场被本服务看到的该马甲全部上行帧**，两者互斥：
  「收到」计入 `framesTotal`（并刷新 `lastFrameType`），「丢弃」计入 `droppedTotal`。
- **`lastFrameType` 只取五类白名单** `ff03`/`ff04`/`ff05`/`ff07`/`ff0a`——`ff06` 是协议 §1.4④
  要求的「UWB 生效时丢弃的旧室内定位帧」，网关保持哑管道照常上报，由 STATE 侧按业务开关判丢；
  **被丢弃的帧不算「收到」**，故不进 `framesTotal`、也不刷新 `lastFrameType`（`ff06` 恒只增 `droppedTotal`）。
- **命中帧的属主是被击中者**：`ff05` 由**被击中**的马甲上报，故计数记在 `hitVestNum` 上，不是开枪者。
- **阵亡冻结期内到达的帧算丢弃**：记账点放在「帧到了」之后、阵亡（`DIE`）早退**之前**，
  冻结期到达的帧只增 `droppedTotal`、不刷新 `lastFrameType`（设计 06 的冻结口径不被本端点绕开）。
- **每马甲计数随 `beginExercise` 重置**：开演装载时归零，与「本场」同生命周期。
- **UWB 41B 帧不进本组计数**：`ff06` 之外，41B 室内解算帧走 UWB 链路，不参与 serial 计数。

**`online` 的判据是纯窗口**：`lastHeartbeatAt != null && now <= lastHeartbeatAt + 5000ms`——逐字对齐契约
描述「距最后一次心跳 <= 5000ms 为在线」，**不**用 `status == LIVE` 兜底：离线扫描（`scanOffline`）是周期触发
（默认 1000ms）的，状态位可能比窗口落后一拍；按窗口现算不受扫描节拍影响。

**404 与「在台账但未编成」是两个问题**（本轮裁定 D-2）：404 判据是**设备台账**（`VestLedgerService.get`，
与 `getVest` 同口径），即「**能不能查这个马甲**」；台账有、但当前不在本场编成内（未开演 / 非本场）时回
`200` + `online=false` + 零计数，而非 404——「它**此刻在不在上报**」由态势决定。两个问题不合并成同一个状态码。

### 22.2 用例判据

core（`SituationStateServiceTest`）新增 6 条：

1. `vestLinkCountsEveryUplinkFrameTypeAndTracksLastFrameType`——连发 `heartbeat`/`gps`/`hit`/`mortar`/`simNo`
   五类帧后 `framesTotal=5`、`lastFrameType="ff0a"`、`droppedTotal=0`、`online=true`；并断言**另一只马甲**
   `framesTotal=0`（计数不串号）。
2. `indoorLocationFrameIsCountedAsDroppedNotReceived`——只发 `ff06` 时 `framesTotal=0`、`lastFrameType=null`、
   `droppedTotal=1`、`online=false`（`ff06` 恒丢口径）。
3. `framesArrivingWhileDeadCountAsDroppedNotReceived`——先心跳再 `hp=0` 阵亡，隔 10s 再发心跳与 GPS：
   `framesTotal=2`（阵亡前那两次）、`lastFrameType="ff03"`（阵亡后不刷新）、`droppedTotal=2`、
   `lastHeartbeatAt` 停在最后一次**收到**的心跳、`online=false`（冻结期帧算丢弃）。
4. `vestLinkOnlineTracksTheHeartbeatWindow`——心跳后 `online=true`；恰好 `+5000ms` 仍 true（判据是 `>` 5000）；
   再 `+1ms` 转 false（窗口边界逐毫秒）。
5. `vestLinkIsEmptyForVestsOutsideTheRoster`——编成外马甲 `state.vestLink(7)` 为空 `Optional`（由控制器降级成
   200 零计数，见 §22.1 的 D-2）。
6. `beginExerciseResetsPerVestCounters`——有计数后 `beginExercise(OTHER_EXERCISE_ID, ...)`，三项归零、
   `lastHeartbeatAt=null`、`online=false`。

api（`ApiSkeletonSmokeIT`，139，计数不变）：`DeviceController` 构造从 3 参扩到 4 参（新增 `SituationQuery`），
`notImplementedIsReportedAsProblem` 的 501 示例由 `getVestLink` 改用真占位的 `GET /api/v1/replays`。

app（`HwLinkHttpIT`，新增 1）`vestLinkAnswersFromTheStateMachineInsteadOfLinkCounters`——走完整生产入口
（真 MySQL + 真安全链 + `scds.gateway.mode=simulator`）：①编号**从未登记**过 → `404` `DEVICE_VEST_NOT_FOUND`
（404 判据 = 设备台账）；②登记该编号后（未编入任何演习）→ `200` + `online=false` + `lastHeartbeatAt=null`
+ `lastFrameType=null` + 两计数为 0（D-2 的落地）。马甲按专属编号段 `62500` 登记，
`@AfterAll` 按编号精确回收（与 `DeviceLedgerHttpIT` 同一口径）。

### 22.3 边界与不变式

**未改动的硬约束**：协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
在线窗口（5000ms）、校验算法、`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值；
不改历史迁移、不开启 out-of-order。本段**未新增错误码**（复用既有 `DEVICE_VEST_NOT_FOUND`）、
**未新增迁移**。

**只读**：本端点不写库、不写日志、不发字节；它是纯内存态（本场编成的每马甲账）的读侧。

**不得与链路级计数互相冒充**：设计 05 §5 与设计 06 §S7 都就此留下红线——链路级 `framesTotal`/`droppedTotal`
数的是「这条串口/UWB 链路收了多少合法/坏帧」，本端点数的是「本场这只马甲收到了多少帧、最近一帧是什么、
丢了多少」。两者不可互相顶替。

本段为**代码验收**，不是生产发布批准。

## 23. M5 REPLAY R1 复盘读侧（`GET /api/v1/replays` + `GET /api/v1/replays/{replayId}`）

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动 |
| core | 571 | 0/0/0 | 新增 `ReplayServiceIT` 7 条（564 → 571） |
| api | 139 | 0/0/0 | 计数不变：`ApiSkeletonSmokeIT` 仅换替身与路径常量 |
| app | 326 | 0/0/0 | 计数不变：`RoleWritePermissionIT` 仅补一个替身 Bean |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1104** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

> 计数口径同第 20/21/22 节：按 Maven **模块级汇总**（`[INFO] Tests run:`）取值，
> 按 `target/surefire-reports/TEST-*.xml` 累加会漏掉 `@Nested` 内部类。

### 23.1 落地范围与口径

**补的是哪一处缺口**：`ReplayController` 原是全仓唯一「七条方法全抛 501」的控制器。R1 先接线读侧两条——
列表（`listReplays`）与单条（`getReplay`）；轨迹分片 / 统计 / 导出三条留在 R2/R3/R4。

**不另建复盘表**：复盘与演习是**同一行**——`t_exercise` 里 `ended_at IS NULL` 即「当前演习」，
写入 `ended_at` 后同一行即一条复盘记录（V4 迁移 + `ExerciseRepository` 类注释）。故 `replayId` 就是
`exerciseId`，读侧不引入任何新仓储，`ReplayService` 只组装 `ExerciseRepository`。

**`durationSeconds` 复盘口径**：契约 DTO 是 `Long durationSeconds`，其值取自
`Duration.between(startedAt, endedAt).toSeconds()`（**定值**）。这与当前演习（`GET /api/v1/exercise/current`）
用 `Instant.now()` 现算的口径**不是同一件事**，两者不得互相顶替：复盘是一个已封存的区间，其时长必须定值。

**404 判据只有一个**：`REPLAY_NOT_FOUND`。`findById(...)` 命中但 `ended_at IS NULL`（仍在进行中）与
「id 根本不存在」在复盘入口是同一件事——**都不是一条复盘记录**，故合并成同一个 404，不区分两种成因。
`replayId` 语法不合法（非 UUID）同样折叠进 `REPLAY_NOT_FOUND`，取向与 `planId(String)` / `mapId(String)` 一致。

**复用既有错误码**：`REPLAY_NOT_FOUND` 早已登记在 `ApiErrorCode` 与 `common.yaml` 的 `Problem.code` 描述里
（无需新增码、无需改 openapi），R1 只做了「回 `ApiExceptionHandler.statusOf` 登记 404 状态」这一步。

**列表口径**：`findEnded(keyword, from, to, PageRequest)` + `countEnded(...)`，即 `ended_at IS NOT NULL`
的场次按 `ended_at DESC, id DESC` 排序；`keyword` 大小写不敏感模糊匹配 `plan_name`，且 `%`/`_`/`\`
被转义成**字面量**（不当作 LIKE 通配符）。

### 23.2 用例判据

core（`ReplayServiceIT`，新增 7 条，全走真库真事务 `@SpringBootTest @Transactional`）：

1. `list_returnsOnlyEnded_newestFirst`——同一 `plan_name` 前缀下造「1 条进行中 + 2 条已结束」，
   列表只出已结束的 2 条，且按 `endedAt` 倒序（进行中的那条不得出现）。
2. `list_appliesKeywordAndEndedWindow`——`keyword` 与 `[from, to]` 用 `ended_at` 收窄，窗口外的已结束场次被排除。
3. `list_treatsPercentAsLiteralNotWildcard`——`keyword` 里的 `%` 只匹配名字里**真的含 `%`** 的那条，
   不得命中被通配符命中的邻居。
4. `list_pagesWithStableTotalAndNoOverlap`——`total` 跨页稳定，第 1/2 页条目无重叠、无遗漏。
5. `get_endedExercise_returnsFrozenOutcome`——已结束场次返回**冻结**的 `endedAt` 与 `hitCount`/`deadCount`/
   `rosterCount`，且 `durationSeconds == endedAt - startedAt`（定值口径）。
6. `get_runningExercise_isNotFound`——`ended_at IS NULL` 的行 → `REPLAY_NOT_FOUND`。
7. `get_unknownId_isNotFound`——不存在的 id → 同一个 `REPLAY_NOT_FOUND`。

api（`ApiSkeletonSmokeIT`，139，计数不变）：`ReplayController` 构造增一参（`ReplayService`），替身一律抛
占位异常，故 `listReplays`/`getReplay` 在本测试里仍表现为 501（只证明路由注册）；501 示例端点由
`GET /api/v1/replays` 改为**仍是真占位**的 `GET /api/v1/replays/{replayId}/timeline`。
另新增常量 `REPLAY_ID`（合法 UUIDv7）：接线后「拼不出来的 replayId」被折叠成 404，
与「路由没注册」的 404 撞车，占位串会让已注册路由丧失鉴别力（同 `MAP_ID`/`PLAN_ID` 取向）。

app（`RoleWritePermissionIT`，326，计数不变）：`PermissionApplication` 的 `@Import` 清单已含 `ReplayController`，
故补一个 `ReplayService` 替身 Bean 以保持「排掉 DataSource 的授权切片」可启动。

### 23.3 边界与不变式

**未改动的硬约束**：协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
在线窗口（5000ms）、校验算法、`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值；
不改历史迁移、不开启 out-of-order。本段**未新增错误码**（复用既有 `REPLAY_NOT_FOUND`）、
**未新增迁移**、**未新增依赖**。

**只读**：`ReplayService.list` 标 `@Transactional(readOnly = true)`，`get` 亦只读；R1 不写任何表。

**R4 的前置提醒**：`REPLAY_EXPORT_FAILED` 的 409 状态**刻意仍未登记**（走 default → 500，响亮地失败）；
R4 落地导出时必须一并登记，并同步修改把它当「未登记状态」绊线的两处测试——
`MvcProblemIT`（L319-322、L398）与 `ApiExceptionHandlerBusinessTest`（L168、L271-276）。

本段为**代码验收**，不是生产发布批准。

## 24. M5 REPLAY R2 轨迹分片（`GET /api/v1/replays/{replayId}/timeline`）

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动 |
| core | 579 | 0/0/0 | 新增 `ReplayServiceIT` 8 条（571 → 579） |
| api | 139 | 0/0/0 | 计数不变：`ApiSkeletonSmokeIT` 仅换 501 示例端点 |
| app | 326 | 0/0/0 | 未改动 |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1112** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

> 计数口径同第 20/21/22/23 节：按 Maven **模块级汇总**（`[INFO] Tests run:`）取值，
> 按 `target/surefire-reports/TEST-*.xml` 累加会漏掉 `@Nested` 内部类。

### 24.1 落地范围与口径

**补的是哪一处缺口**：R1 接线读侧两条后，`ReplayController` 剩 5 条 501；R2 接线第三条——
轨迹分片（`getReplayTimeline`），统计（R3）与导出/下载（R4）三条仍 501。

**游标必须是 `id`，不是 `t_ms`**：`t_track_sample.id` 是自增代理键且**插入序即采样序**，
用它分片不重不漏；`t_ms` 只保证时间语义，同一毫秒内的多点无法唯一定位起点，**不作游标**。
故分片查询 `tracks.findChunk(replayId, afterId, vestNums, stride, limit)` 的游标参数是 `id` 下界。

**抽稀窗口在全量上算**：`TrackSampleRepository` 的窗口函数在**全量**上编号抽稀
（不带以游标为界的 `id > afterId`），否则每片从 1 重新编号，抽稀相位逐片漂移，
会同时出现重复与漏点；游标仍作用于**抽稀结果集**的 id。

**`nextCursor` 只在「本片正好拉满」时给值**：`samples.size() < limit ? null : Long.toString(末条.id)`。
拉满**不保证**一定还有下一片（可能刚好整除），客户端会多拉一次空片——用一次空请求
换「不必多查一次 COUNT」。这与「客户端拿到非 null 就继续拉」的循环口径配套。

**坏游标响亮失败**：`afterId(cursor)` 对非数字或负值抛 `VALIDATION_FAILED`（400），
**不静默退回 0**——静默退回会让客户端每次都拿到第一片，形成「永远拉第一页」的死循环。
`VALIDATION_FAILED` 早已登记为 400，R2 **未新增错误码**。

**404 判据与 R1 同源**：`requireReplay(replayId)` 复用同一条判据——
「`ended_at IS NULL`（进行中）」与「id 不存在」折叠成同一个 `REPLAY_NOT_FOUND`。

**坐标与时间透出**：`toTrackSample` 把 `xPct`/`yPct`/`lng`/`lat` **按原样透出**，
不用 `coordinateSystem` 做取舍；`t` 取 `tMs` 原值（本就是「相对开演的毫秒偏移」）。

### 24.2 用例判据

core（`ReplayServiceIT`，新增 8 条，全走真库真事务 `@SpringBootTest @Transactional`）：

1. `timeline_pagesByCursorWithoutOverlapOrGap`——5 点、每片 2 个：循环翻页（2/2/1 三片），
   末片没拉满即收尾；已见 id 集合无重复、无遗漏（不重不漏）。
2. `timeline_lastPageWhenExactlyDivisible_endsWithAnEmptyChunk`——4 点、每片 2 个且整除：
   第 1/2 片各 2 条且 `nextCursor` 非 null，第 3 片**空**且 `nextCursor=null`
   （拉满不等于还有下一片）。
3. `timeline_filtersByVestNumsAndThinsByStride`——8 点、只看 `1001`（4 点）再按步长 2 抽稀：
   得 `tMs=200/400` 两条，`nextCursor=null`。
4. `timeline_replayWithoutSamples_returnsEmptyChunk`——无采样点的复盘 → 空片 + `nextCursor=null`。
5. `timeline_isScopedToItsOwnReplay`——两场各造 2/3 点，各自只读到自己的点数（按 `exerciseId` 隔离）。
6. `timeline_runningExercise_isNotFound`——`ended_at IS NULL` 的场次 → `REPLAY_NOT_FOUND`。
7. `timeline_unknownId_isNotFound`——不存在的 id → 同一个 `REPLAY_NOT_FOUND`。
8. `timeline_malformedCursor_isValidationFailed`——`"abc"` 与 `"-1"` 均 → `VALIDATION_FAILED`。

api（`ApiSkeletonSmokeIT`，139，计数不变）：`ReplayController` 的 `getReplayTimeline` 已接线，
替身 `ReplayService` 一律抛占位异常，501 示例端点由 `GET /api/v1/replays/{replayId}/timeline`
改为**仍是真占位**的 `GET /api/v1/replays/{replayId}/statistics`。

### 24.3 边界与不变式

**未改动的硬约束**：协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
在线窗口（5000ms）、校验算法、`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值；
不改历史迁移、不开启 out-of-order。本段**未新增错误码**（复用 `VALIDATION_FAILED` / `REPLAY_NOT_FOUND`）、
**未新增迁移**、**未新增依赖**（`TrackSampleRepository` 早已就位）。

**只读**：`ReplayService.timeline` 标 `@Transactional(readOnly = true)`，R2 不写任何表。

**R4 的前置提醒**：`REPLAY_EXPORT_FAILED` 的 409 状态**刻意仍未登记**（走 default → 500，响亮地失败）；
R4 落地导出时必须一并登记，并同步修改把它当「未登记状态」绊线的两处测试——
`MvcProblemIT`（L319-322、L398）与 `ApiExceptionHandlerBusinessTest`（L168、L271-276）。

本段为**代码验收**，不是生产发布批准。

## 25. M5 REPLAY R3 统计（`GET /api/v1/replays/{replayId}/statistics`）

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动 |
| core | 587 | 0/0/0 | 新增 `ReplayServiceIT` 7 条 + `HitEventRepositoryIT` 1 条（579 → 587） |
| api | 139 | 0/0/0 | 计数不变：`ApiSkeletonSmokeIT` 仅换 501 示例端点 |
| app | 326 | 0/0/0 | 未改动 |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1120** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

> 计数口径同第 20~24 节：按 Maven **模块级汇总**（`[INFO] Tests run:`）取值，
> 按 `target/surefire-reports/TEST-*.xml` 累加会漏掉 `@Nested` 内部类。

### 25.1 落地范围与口径

**补的是哪一处缺口**：R2 接线第三条后，`ReplayController` 剩 3 条 501；R3 接线第四条——
统计（`getReplayStatistics`），导出/下载（R4）三条仍 501。

**数据源两分，不从命中流水反推战损**：队伍战损（`teams`）与排行（`topShooters`）取
`t_exercise_unit` 停演时**冻结的终局台账**（`finalStatus` / `deadAt` / `kills` / `hitsDealt`），
**不**从 `t_hit_event` 命中流水反向统计——崩溃自愈后若冻结列尚未写全，两处口径会给出两套数；
命中部位分布（`hitPartDistribution`）是另一类问题（「打了哪里」在台账里没有），故单查
`t_hit_event` 按 `hit_part` 分组（`HitEventRepository.countByHitPart`）。

**排行只列有战绩者、不截断**：`hitsDealt > 0 || kills > 0` 才入榜；
排序为 `kills` 降序 → `hitsDealt` 降序 → `vestNum` 升序（末位用编号兜底，避免同分者顺序不稳）。
契约 `topShooters` **未给上限**，故不人为截断——截断会让「第 N 名」随实现变化，客户端无法复算。

**存活曲线**：把 `[startedAt, endedAt]` 等分 `timelineBuckets` 段，段首时刻
`t = durationMs * i / buckets`（毫秒，相对开演的偏移）；「存活」定义为 `finalStatus != DIE`——
**`OFFLINE` 计入存活**（离线不等于阵亡，与状态侧口径一致）。每段把**本场出现过的所有队伍**
都填成 0 再累加，保证各段键集一致，客户端不必为缺键补默认。

**`DIE` 而 `deadAt` 为 null 的异常态按「全程阵亡」处理**（`aliveAt` 返回 false）——
宁可少算存活，也不把已知阵亡者当成活人。

**`timelineBuckets` 由控制器兜底校验**：`@RequestParam(defaultValue = "120")` + `@Min(10) @Max(500)`，
越界即 400（框架校验），core 侧不再重复判。

**404 判据与 R1/R2 同源**：`statistics` 先走 `requireReplay(replayId)`——
「`ended_at IS NULL`（进行中）」与「id 不存在」折叠成同一个 `REPLAY_NOT_FOUND`。

### 25.2 用例判据

core（`ReplayServiceIT`，新增 7 条，全走真库真事务 `@SpringBootTest @Transactional`）：

1. `statistics_aggregatesTeamsFromFrozenRoster`——每队按冻结台账汇总 total/live/dead/offline 与
   `hitsDealt`；`OFFLINE` 计入 offline 而非 live/dead。
2. `statistics_ranksShootersByKillsThenHitsThenVest`——同 `kills` 比 `hitsDealt`，
   再同则按 `vestNum` 升序；无战绩者不入榜。
3. `statistics_hitPartDistribution_groupsByPartInEnumOrder`——按 `hit_part` 分组计数，
   与 `HitPart` 枚举顺序一致（`EnumMap`），且**只统计本场**（跨场不串）。
4. `statistics_timeline_splitsDurationAndDropsTheDead`——按 `timelineBuckets` 等分，
   段首时刻 `t = durationMs * i / buckets`；段内阵亡者被剔除、离线者仍在。
5. `statistics_replayWithoutRosterOrHits_returnsEmptySections`——无台账、无命中的复盘 →
   四段全为空列表（不报错）。
6. `statistics_runningExercise_isNotFound`——`ended_at IS NULL` 的场次 → `REPLAY_NOT_FOUND`。
7. `statistics_unknownId_isNotFound`——不存在的 id → 同一个 `REPLAY_NOT_FOUND`。

core（`HitEventRepositoryIT`，新增 1 条）：

8. `countByHitPart_groupsByPartAndStaysScopedToExercise`——两场各造命中，
   各自分组结果只含本场；不存在的 id → 空 Map。

api（`ApiSkeletonSmokeIT`，139，计数不变）：`ReplayController` 的 `getReplayStatistics` 已接线，
替身 `ReplayService` 一律抛占位异常，501 示例端点由 `GET /api/v1/replays/{replayId}/statistics`
改为**仍是真占位**的 `GET /api/v1/replays/{replayId}/exports/{format}`。

### 25.3 边界与不变式

**未改动的硬约束**：协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
在线窗口（5000ms）、校验算法、`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值；
不改历史迁移、不开启 out-of-order。本段**未新增错误码**（复用 `VALIDATION_FAILED` / `REPLAY_NOT_FOUND`）、
**未新增迁移**、**未新增依赖**（`ExerciseUnitRepository` / `HitEventRepository` 早已就位）。

**只读**：`ReplayService.statistics` 标 `@Transactional(readOnly = true)`，R3 不写任何表。

**`TeamSummary` 复用中立线类型**：`teams` 段直接复用 `com.dualx.core.push.TeamSummary`
（原本就住在 `scds-domain`，core 可直接引用），不新造同形 DTO。

**R4 的前置提醒**（与第 24 节同）：`REPLAY_EXPORT_FAILED` 的 409 状态**刻意仍未登记**
（走 default → 500，响亮地失败）；R4 落地导出时必须一并登记，并同步修改把它当「未登记状态」
绊线的两处测试——`MvcProblemIT`（L319-322、L398）与 `ApiExceptionHandlerBusinessTest`（L168、L271-276）。

本段为**代码验收**，不是生产发布批准。

## 26. M5 REPLAY R4 导出与下载（`POST /api/v1/replays/{replayId}/exports` + `GET .../exports/{taskId}` + `GET .../exports/{taskId}/download`）

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动 |
| core | 603 | 0/0/0 | 新增 `ExportTaskServiceIT` 16 条（587 → 603） |
| api | 139 | 0/0/0 | 计数不变：`ApiSkeletonSmokeIT` 换替身/构造/路由值，`ApiExceptionHandlerBusinessTest` 换绊线码 |
| app | 328 | 0/0/0 | 新增 `ReplayExportHttpIT` 2 条（326 → 328）；`RoleWritePermissionIT` 补替身 Bean，`MvcProblemIT` 换绊线码 |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1138** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

> 计数口径同第 20~25 节：按 Maven **模块级汇总**（`[INFO] Tests run:`）取值，
> 按 `target/surefire-reports/TEST-*.xml` 累加会漏掉 `@Nested` 内部类。

### 26.1 落地范围与口径

**补的是哪一处缺口**：R4 之后 `ReplayController` 的七条方法**全部去 501**——本段接线导出/查询/下载三条
（`exportReplay` / `getExportTask` / `downloadExport`），全仓「仍抛 501 的方法」计数由 3 降为 **0**。

**导出内容 = 统计三表 + 编成台账，不含逐点轨迹**（决策，见 §6）。产物由 `ReplayExportReport.build(...)`
纯函数生成：`队伍战损`（队伍,总人数,存活,阵亡,掉线,命中）、`命中排行`（马甲编号,姓名,命中,击杀）、
`命中部位分布`（部位,次数）、`编成台账`（马甲编号,姓名,队伍,终局,阵亡时刻,击杀,命中，按 `vestNum` 升序）。
**不复用轨迹抽稀**——逐点轨迹体量大且与「统计」是两个用途，导出只给汇总口径，
避免把 M5-R2 的游标分片逻辑拖进异步任务。

**格式与写法**：`ExportFormat` 只有 `XLSX`/`CSV` 两种；`XLSX` 出四个命名标签页，`CSV` 出**纵向长表**
（多一列「表类型」，四种表纵向拼接），带 UTF-8 BOM。两种写法都走 R4-A1 抽出的通用写手
`SheetWriter`（`xlsx(List<Sheet>)` / `csv(List<List<String>>)`），取向与 PLAN 模板导出**同一套**
（决策：不引 POI / commons-csv，用 JDK 自带 `java.util.zip` + `javax.xml.stream` 手写；
写用 `inlineStr`、读兼容 `sharedStrings`，见 decision-register L535-578）。

**异步载体 = core `@Scheduled` 扫描执行器**（决策）。`ExportTaskService.tick()` 每 **2s** 一拍，
`findPending(BATCH_LIMIT)` 取旧不取新（防饿死）逐条 `execute(taskId)`。**core 测试上下文不跑 `@Scheduled`**
（`@EnableScheduling` 只在 `scds-app` 的 `ScdsNextApplication` 上开启），故 IT 直接调 `tick()` / `execute()` 驱动。

**状态流转靠「当前态守卫」而非锁**：`ExportTaskRepository` 每个 UPDATE 的 WHERE 都带旧态
（`markRunning` 只从 `PENDING` 推进），影响行数为 0 即「别人已推进」；`execute` **不标 `@Transactional`**
（一边写库一边写文件，只做短事务式的多次提交），`markRunning` 的零影响行数是唯一并发闸门
（`execute_isSingleShot` 用例即验此）。

**受理语义（POST）不返 409**：`request(replayId, format)` 先 `replays.get(replayId)`（复用 R1 的 404 判据
`REPLAY_NOT_FOUND`），再 `findActive` 命中同格式活跃任务则**合并返回**、否则新建 PENDING
（`UuidV7.generate()`）。**「失败后重导」做成新的 202 受理**，而非要求客户端先重置——契约 `exportReplay`
虽列了 409 `REPLAY_EXPORT_FAILED`，但本实现只在 `download` 用 409（决策：只在 download 用 409）。

**`download` 的 409 三合一**：任务未 SUCCEEDED / `expireAt` 已过 / 产物在存储里读不到，
三种成因折叠成同一个 409 `REPLAY_EXPORT_FAILED`（对下载入口它们是同一件事——「现在拿不到」）。

**`downloadUrl` 每次重新签发**：`toAsyncTask` 只在 `state == SUCCEEDED && filePath != null` 时给值，
且**每次调用都重签**（`FileUrlSigner.sign` 是短时效签名，默认 TTL 15 分钟、可在窗口内重复使用）；
URL 形状归接口层组装（`/api/v1/files?path=...&exp=...&signature=...`，含 `URLEncoder` 编码），
与 `MapController.imageUrl` 同形；端口 `FileUrlSigner` 本身不含 URL 组装。

**过期清理**：产物 TTL 由 `scds.export.expire-ttl` 决定（默认 **24h**，决策），`markSucceeded` 时按
`Instant.now(clock).plus(ttl)` 写 `expire_at`；**不新增清理任务**（决策），过期后由 `download` 判 409。

**新增 `@ConfigurationProperties("scds.export")`**（record `ExportProperties`）：`expireTtl` 默认 `24h`，
构造时校验必须为正的时长，否则启动即失败。

### 26.2 用例判据

core（`ExportTaskServiceIT`，新增 16 条，全走真库真事务 `@SpringBootTest @Transactional`，
落盘隔离用 `@DynamicPropertySource` 覆盖 `scds.files.root` 到 `target/test-files/{uuid}`）：

1. `request_createsPendingTask`——首次受理落一条 `PENDING`，返回其 `id`。
2. `request_secondCallMergesIntoTheActiveTask`——同复盘同格式再受理，**合并**到同一活跃任务（不新建第二行）。
3. `request_differentFormatIsNotMerged`——同复盘换格式（`XLSX` ↔ `CSV`）是另一件事，另建任务。
4. `request_runningExercise_isNotFound`——进行中（`ended_at IS NULL`）的场次 → `REPLAY_NOT_FOUND`。
5. `tick_rendersXlsxAndStoresIt`——`tick()` 驱动后任务转 `SUCCEEDED`、`fileName` 以 `.xlsx` 结尾、
   产物已落 `files` 存储。
6. `tick_rendersCsvWithBom`——`CSV` 产物以 UTF-8 BOM（`EF BB BF`）开头。
7. `execute_isSingleShot`——同一任务重复 `execute`，`markRunning` 第二次返回 0 行即**让位**，
   不重复渲染、不重复落盘。
8. `tick_skipsReplaysThatNoLongerExist`——受理后复盘行被删（先删子表 `t_exercise_unit` 再删 `t_exercise`），
   渲染期 `requireReplay` 抛错 → 任务转 `FAILED`，不把异常吞成成功。
9. `get_unknownTask_isNotFound`——不存在的 taskId → `REPLAY_NOT_FOUND`。
10. `get_taskOfAnotherReplay_isNotFound`——taskId 属于**另一条**复盘 → 同一个 `REPLAY_NOT_FOUND`
    （不串场次）。
11. `download_beforeCompletion_isConflict`——任务未 `SUCCEEDED` 即下载 → 409 `REPLAY_EXPORT_FAILED`。
12. `download_expiredFile_isConflict`——直改列把 `expire_at` 拨到过去（此时任务已 `SUCCEEDED`，
    `markSucceeded` 的守卫会拒绝，故走 `jdbc` 直更新）→ 下载 409。
13. `download_missingFile_isConflict`——产物路径在存储里读不到 → 下载 409（与 11/12 同码）。
14. `report_csvIsOneLongTableWithTableTypeColumn`——`CSV` 是纵向长表，含「表类型」列把四张表区分开。
15. `report_xlsxHasFourNamedSheets`——`XLSX` 有四个标签页，页名与四表对应。
16. `report_rosterIsSortedAndBlanksAreEmpty`——编成台账按 `vestNum` 升序，空值渲染成空串（非 `null` 字面量）。

api（`ApiSkeletonSmokeIT`，71 条，计数不变）：`ReplayController` 构造扩为三参
（`ReplayService` / `ExportTaskService` / `FileUrlSigner`），`ExportTaskService` 替身一律抛占位异常，
故三条导出端点在本测试里仍表现为 501（只证明**路由注册**）；501 示例端点由导出查询端点的
`{taskId}` 占位（值从 `t1` 改为合法 UUID 常量 `TASK_ID`）承载——非法 UUID 会被 `taskId(String)`
折叠成 `REPLAY_NOT_FOUND`，不再是「真占位」。

api（`ApiExceptionHandlerBusinessTest`，23 条，计数不变）：`statusOf` 已登记 `REPLAY_EXPORT_FAILED → 409`，
原把它当「未登记码」绊线（断言 500）的用例改抛**唯一仍未登记**的
`DEVICE_ANCHOR_NOT_CALIBRATED`，仍验「未登记业务码走 default 响亮 500 而非猜状态」。

app（`MvcProblemIT`，27 条，计数不变）：同源改动——`businessUnregisteredMappingStillFailsLoudly`
与 `Endpoints.business()` 由 `REPLAY_EXPORT_FAILED` 换 `DEVICE_ANCHOR_NOT_CALIBRATED`。

app（`RoleWritePermissionIT`，65 条，计数不变）：`ReplayController` 构造需 `ExportTaskService`，
测试上下文补一个 `stub(ExportTaskService.class)` Bean。

app（`ReplayExportHttpIT`，新增 2 条，全走真 Boot + 真安全链 + 真 MySQL + 真落盘，
落盘隔离用 `MapPropertySource` 覆写 `scds.files.root` 到 `target/replay-export-http-files/{uuid}`，
并**显式给 `scds.files.sign-secret` 一个固定测试密钥**（见 17 条的签名窗口过期断言）；
**不**直接调 `tick()`，靠应用自己的 `@Scheduled` 执行器推进以证明端到端）：

17. `xlsxExportOverRealHttp`——开演 → 停演（得到复盘）→ 进行中受理/详情都是 404 `REPLAY_NOT_FOUND`
    → 停演后受理 202（PENDING、`fileName`/`downloadUrl`/`expireAt`/`errorMessage` 全 null）
    → 等执行器渲染到 SUCCEEDED（`progressPct=100`、`fileName` 以 `.xlsx` 结尾、`expireAt` 在未来、
    `downloadUrl` 以 `/api/v1/files?path=` 开头且含 `&exp=`/`&signature=`）
    → 带 Bearer 下载 200、`Content-Type` 为 xlsx、`Content-Disposition` 为 `attachment` 且含文件名、
    正文以 `PK\x03\x04` 开头；同一 `expireAt` 在两次查询间**定值**
    → 用 `downloadUrl` **免 Bearer** 取回**同一份字节**且 `Content-Type` 为 `application/octet-stream`
    （两条通道按不同口径判型：按域路由看后缀、签名路由嗅真实字节）
    → 签名篡改 403 `FILE_URL_INVALID`、两条导出路由不带 Bearer 都是 401
    → **签名窗口过期**：用固定测试密钥按契约（`路径 + '\n' + exp` 的 HMAC-SHA256）重签同一 URL，
    先以「未过期」（`exp=now+300s`）样本断言 200 且取回**同一份字节**（自证重算算法与服务端一致，
    排除下一条 403 是「签错」而非「过期」），再以「已过期」（`exp=now-60s`）样本断言 403 `FILE_URL_INVALID`。
18. `csvExpiryAndFailureOverRealHttp`——CSV 产物 `Content-Type` 为 `text/csv` 且以 UTF-8 BOM 开头；
    把 `expire_at` 拨到过去后下载 409 `REPLAY_EXPORT_FAILED`；
    受理后删掉复盘行 → 任务落 FAILED（`errorMessage` 含 `REPLAY_NOT_FOUND`），查询仍 200 而**下载**才 409；
    拿另一条复盘的 id 查本任务 → 404（不串场次）；不存在的 taskId / 非 UUID 的 taskId / 不存在的复盘
    一律 404 `REPLAY_NOT_FOUND`；缺 `format` 与枚举外的 `format` 都是 400 `VALIDATION_FAILED`。

> 第 17 条**刻意不断言「二次受理合并到同一 taskId」**：应用内的 `@Scheduled` 可能恰在这两次请求之间
> 把首个任务推到 SUCCEEDED，此后「再受理」本就该是一条新任务，断言相等会变成靠运气的用例；
> 合并语义的确定性验证在 core 的 `ExportTaskServiceIT.request_secondCallMergesIntoTheActiveTask`。

### 26.3 边界与不变式

**未改动的硬约束**：协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
在线窗口（5000ms）、校验算法、`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值；
不改历史迁移、不补 V8、不开启 out-of-order。本段**未新增迁移**（复用 V5 的 `t_export_task`）、
**未新增依赖**（XLSX/CSV 手写走 JDK 自带）、**只读态**由 `@Scheduled` 执行器推进。

**唯一新增错误码登记**：本段只做了「回 `ApiExceptionHandler.statusOf` 登记 `REPLAY_EXPORT_FAILED → 409`」；
该码与 `REPLAY_NOT_FOUND` 早在 R1/R2 时已登记在 `ApiErrorCode` 与 `common.yaml` 的 `Problem.code` 描述里，
**未改 openapi、未新增码**。

**端到端覆盖已补齐**：R4 的三端点既有 `ApiSkeletonSmokeIT`（路由注册）与 `ExportTaskServiceIT`（服务层），
也有 `ReplayExportHttpIT`（真 Boot + 真安全链 + 真落盘 + 签名 URL 取回）。原先挂起的
`downloadUrl` **签名本身过期**这条 HTTP 用例已在 `ReplayExportHttpIT.xlsxExportOverRealHttp` 补齐
（2026-10-28）：签名窗口到期的处置动作与「签名被篡改」同为 403 `FILE_URL_INVALID`，
构造方式见第 17 条。**构造口径**：签名器持 `Clock.systemUTC()`、HTTP 用例推不动时钟，故在
**测试侧按契约独立重算**签名（固定测试密钥 + `路径 + '\n' + exp` 的 HMAC-SHA256、Base64URL 无填充），
并用「未过期」正例自证重算与服务端一致；**未改验签逻辑、未引入可注入 Clock 到生产**。

本段为**代码验收**，不是生产发布批准。

## 27. M3 CAL-2 现场验收收口（`calibrated_at` 可读性）

2026-10-28 对第 18 节 §18.3 挂起的三项现场待验收项收口。CAL-2 交付时 `t_map.calibrated_at`
（V11 校准列）只有写点、没有读点：现场无法从产品面确认「标定到底生效了没有、什么时候生效的」。
本段给的正是这条读取路径，并把另两项判据（50mm 静止阈值、四角编号）固化进验收清单。

命令（仓库根）：`mvn test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动 |
| core | 605 | 0/0/0 | 新增 `BaseMapRepositoryIT.findCalibratedAt_*` 2 条（603 → 605） |
| api | 139 | 0/0/0 | 计数不变：`MapDetail` 加字段、`MapController.toDetail` 传值，无新增用例 |
| app | 328 | 0/0/0 | 计数不变：`MapBaseHttpIT.floorPointsAndUwbMetaOverRealHttp` 扩断言 |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1140** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

> 计数口径同第 20~26 节：按 Maven **模块级汇总**（`[INFO] Tests run:`）取值，
> 按 `target/surefire-reports/TEST-*.xml` 累加会漏掉 `@Nested` 内部类。
> 第 26 节的计数表漏列了 `scds-protocol`（19 条），本节的表把它补齐，
> 故「1140」不是「1138 + 2」——1138 里本就含这 19 条，两项相抵后只多 2 条（core 603 → 605）。

### 27.1 落地范围与口径

**契约先行**：`docs/api/schemas/domain.yaml` 的 `MapDetail` 新增可空 `calibratedAt`
（`type: [string, 'null']`、`format: date-time`），位置紧随 `calibrated`。纯新增、向后兼容：
不读该字段的客户端不受影响。**未新增错误码、未改任何既有字段的语义与必填性**。

**读取分三层，各自只动一处**：

1. `BaseMapRepository.findCalibratedAt(Collection<UUID>)`——一条 `IN` 查询回答整页，
   返回 `mapId → 时刻`，未标定的行被 `calibrated_at IS NOT NULL` 滤掉（键缺失即「从未标定」）。
2. `MapLedgerService.MapView` 加第 4 个分量 `calibratedAt`；`list` 整页一次取回，`get` 单件取回。
3. `MapDetail` 加字段，`MapController.toDetail` 传 `view.calibratedAt()`。

**为什么不把 `calibrated_at` 并进读取投影**：并进 `COLUMNS` 就得给领域记录 `BaseMap` 加第 11 个
分量，全仓二十多处 `new BaseMap(...)` 都要跟着改；而在列表页逐行补一次查询又是 N+1——正是
`MapView` 当初「一次读完三个值」要避免的那件事。两条都不取，改走「行投影不动 + 额外固定一条 SQL」。

**写点未动**：`calibrated_at` 仍然只由 `MapLedgerService.saveUwbMeta` 一处写入
（手工 `putUwbMeta` 与中心基准校准都汇到这里），本段只加了读，没有第二个写点。

### 27.2 用例判据

**core（`BaseMapRepositoryIT`，13 条，603 → 605 中新增 2 条）**：
`markCalibrated`（只前进不回头、未知 id 零影响）等原 11 条不动，新增：

12. `findCalibratedAt_returnsOnlyStampedRows`——建「已标定 / 未标定 / 不存在」三个 id，
    只给第一个 `markCalibrated`；断言结果 `containsOnlyKeys(已标定)` 且时刻等于写入值。
    即：未标定的与不存在的**同样缺席**，调用方补 `null` 时不必区分「没这行」与「没标过」。
13. `findCalibratedAt_emptyIdsReturnEmptyMap`——空入参直接回空表、不发 SQL（空 `IN ()` 是语法错误）。

**app（`MapBaseHttpIT.floorPointsAndUwbMetaOverRealHttp`，计数不变，扩断言）**：
真 Boot + 真 MySQL + 真安全链，走真实 TCP：

- `PUT uwb-meta` **之前**：`GET /maps/{id}` 的 `calibrated=false` 且 `calibratedAt` 为 `null`
  ——两个字段同源，不该出现「一个有一个没有」；
- `PUT uwb-meta` **之后**：`calibrated=true`，`calibratedAt` 可解析为 `Instant` 且落在
  「刚刚」的窗口内（`Instant.now()-5s .. +5s`，服务端时钟，故只断量级）；
- **列表页**（`GET /maps?size=200`，走整页批量读）里同一行的 `calibratedAt` 与详情**逐字相等**
  ——两处口径若分叉，现场在列表上看到「已标定」却拿不到时刻。

### 27.3 边界与不变式

**两个字段同源但不等价**：`calibratedAt` 非空 ⇒ `calibrated=true`（`saveUwbMeta` 先校验齐备再写）；
反之**不成立**——V11 之前就已标定的存量行是「`uwb_meta` 齐备但时刻为 `null`」，那是「当时没记」，
不是「没标过」。该列**不随改名、换图型刷新**（`updated_at` 走 `ON UPDATE`，表达不了这件事）。

**未改动的硬约束**：协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、
在线窗口（5000ms）、校验算法、`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值；
不改历史迁移（**不补 V8**）、不开启 out-of-order、**未新增依赖**。

本段为**代码验收**，不是生产发布批准。

### 27.4 现场验收清单（三项挂起项的处置）

| 挂起项 | 处置 | 现场判据 |
|---|---|---|
| `calibrated_at` 可读性 | **本段收口**：`GET /maps/{id}` 与列表都回 `calibratedAt` | 标定后取详情：`calibrated=true` 且 `calibratedAt` 非空；重启服务再取，时刻**仍在**（进程内标定会话那时已丢，这是唯一残留的证据） |
| 50mm 静止阈值是否适配现场噪声 | **无需改码**：阈值取自配置键 `scds.uwb.calib-static-max-diff-cm`（默认 5.0 → 50mm） | 开标定时看服务端日志的「静止阈值=…mm」；若现场噪声导致误判 FAILED，读 `UwbCalibrationStatus.message`——它回的是「bs* 测距偏离中位数 X mm，超过阈值 Y mm」，按 X 的实际量级调这个配置键，不要改代码 |
| 底图四角编号 | **按裁定不动代码**，只入本清单 | 必须按 `bs1` 左下 → `bs2` 右下 → `bs3` 右上 → `bs4` 左上录入，即 `bs1↔bs2` 是**长度**基准、`bs1↔bs4` 是**宽度**基准，两基准近似正交。编号错时四角仍能通过保存校验（只拦缺角 / 越界 / 三点共线 / 长宽非正整数），但长宽会落到错边——保存后用 `GET /maps/{id}/uwb-meta` 核对 `realLengthCm` 是否确实对应 `bs1↔bs2` 那条边 |

## 28. M3 LIFE 网关下行接线（开演逐台 14B + 开始帧 / 停演结束帧）

2026-10-28 收口第 876 行起「LIFE 分段（二）执行边界」里**有意留下**的那条未闭合项
（本文件第 972 行原文：「网关下行未接线」）。此前开演只做库写入与态势装载、停演只写终局，
契约 `startExercise` 步骤 ③④（逐台 14B 轮询配置帧、7B 开始帧）与 `stopExercise` 步骤 ③（7B 结束帧）
**一个字节都没发**——`scds-gateway` 的 `DeviceLinkAdmin.sendVestCommand` 早已就绪，缺的只是调用方。
本段把这条链接上，并按用户裁定拆 A（core 接线）/ B（api `replayId` + 契约）/ C（core 用例）/ D（app 端到端）
四段实施。

命令（仓库根）：`mvn -o test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动（`DownlinkCommands` 的 14B/7B 冻结字节一字未动） |
| gateway | 49 | 0/0/0 | 未改动（`sendVestCommand` 通路已由 HW-LINK 段交付） |
| core | 610 | 0/0/0 | 新增 `ExerciseLifecycleServiceDownlinkTest` 5（605 → 610） |
| api | 139 | 0/0/0 | 计数不变：`ExerciseController.stopExercise` 补 `replayId`，无新增用例 |
| app | 331 | 0/0/0 | 新增 `ExerciseDownlinkHttpIT` 3（328 → 331） |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1148** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

> 计数口径同第 20~27 节：按 Maven **模块级汇总**（`[INFO] Tests run:`）取值。
> 较第 27 节基线 1140 净增 **8** = core `+5`（段 C）+ app `+3`（段 D），两处新增即本段全部新增。

### 28.1 落地范围与口径

**接线落点（`scds-core` 的 `ExerciseLifecycleService`）**：`startExercise` / `stopExercise` 的
**事务尾段**各挂一个私有方法（`sendStartDownlink` / `sendFinishDownlink`），编排顺序固定为
**先做完库写入与态势装载 → 再逐台发 14B → 最后发 7B 开始帧**（停演则是**先冻结终局 → 再发 7B 结束帧**）。
`ExerciseLifecycleService` 是 `@Service`，构造器末两参注入 `DeviceLinkAdmin`（domain 端口）与
`InstanceProperties`；core 仍需保持「无 main 的库」边界，故 `DeviceLinkAdmin` 的接线对象由 `scds-app` 装配。

**四条口径（用户逐项裁定，均取推荐项）**

| 项 | 裁定 |
|---|---|
| 下发时机 | **同事务尾段下发**——先落库、再发硬件。物理副作用（已发字节）无法由 JDBC 撤回，故**只留痕、不回滚**：发送抛异常时 `catch` 后 WARN + 写 `t_exercise_log(SYSTEM)` 存证，方法**照常返回**、事务**照常提交**（响应仍 201 / 200），也**不发布半启动态** |
| 链路未开 | **静默跳过**：`DeviceLinkAdmin.snapshot()` 里没有 `SERIAL_VEST` 且 `state=OPEN` 时，只打 WARN，**不发字节、不写日志**（这是布场前的正常路径，不是失败）。**第 29 节已收窄**：开演侧升为 409 硬前置后，此分支只兜「门禁检查与下发之间链路被并发关闭」的窗口 |
| 广播马甲编号 | 全体下行的 7B 帧用占位编号 `BROADCAST_VEST_NUM = 0`（`ExerciseLifecycleService` 常量）；写日志时该字段记 **`NULL`**，不写 0——0 会被误读成「1 号马甲」 |
| 停演响应 `replayId` | `stopExercise` 的响应补 `replayId = outcome.exerciseId()`：停演即开一场可复盘的回放，`replayId` 就是本场 `exerciseId`（**发结束帧 + 补 `replayId=exerciseId`** 一并落地）。契约 `StopExerciseResult.replayId` 的既有字段语义不变，构造器仍是 3 参 |

**组帧复用现成编码，零新增字节逻辑**：14B 轮询配置帧走 `DownlinkCommands.pollConfig(vestNum, mode, hp, ammo1, ammo2, team)`、
7B 开始帧走 `DownlinkCommands.startBattle(battleMode)`、7B 结束帧走 `DownlinkCommands.finishBattle()`。
`PollMode` → `MODE_AUTO`/`MODE_MANUAL`、`BattleMode` → `BATTLE_MODE_TRAIN`/`BATTLE_MODE_BATTLE`
各只有一个私有映射方法（`modeCode` / `battleModeCode`），**不新增枚举、不改 `DownlinkCommands` 的冻结常量**。

**未新增错误码、未新增迁移、未接线权限**：本段是**出站**方向，不产生新的请求校验分支，
故 `Problem.code`、`ApiErrorCode`、`ApiExceptionHandler.statusOf` 三层登记表**一字未动**。

### 28.2 用例判据

**core（`ExerciseLifecycleServiceDownlinkTest`，5 条，纯 Mockito 单测）**：
以 `DeviceLinkAdmin` 桩 + `InOrder` 断言「发了什么、按什么顺序、失败时留不留痕」。
逐帧与 `DownlinkCommands` 现编码**逐字节比对**（`Hex.encode` 后的字符串相等），不写死 hex 字面量。

1. `startSendsPollConfigPerVestThenStartBattleInOrder`——开演后**逐台各一条 14B**、**最后一条 7B 开始帧**
   且 `vestNum = 0`；末尾用计数断言 `verify(links, times(3)).sendVestCommand(anyInt(), any())` 证明
   **别无他发**（顺序由上面的 `InOrder` 保证，不用 `verifyNoMoreInteractions` 与桩调用纠缠）。
2. `startBattleFrameFollowsTheRequestedMode`——请求 `BattleMode` 变则开始帧字节跟着变（TRAIN/BATTLE 两种模式各验一次）。
3. `startDownlinkFailureLeavesEvidenceAndStillStartsTheExercise`——桩令发送抛 `RuntimeException` 时，
   `startExercise` **不抛异常**（用 `AtomicReference` 捕获返回行，断言非 null 且 `exercises.insert` 被调用），
   并断言 `t_exercise_log(SYSTEM)` 里有一条含失败详情的存证。**这是 D11 的核心判据**：硬件失败不得把开演
   变成异常——那会回滚一场事实上已经开始、字节也已经发出去的演习。
4. `startIsRejectedWith409WithoutSendingOrLoggingWhenVestLinkIsClosed`——`snapshot()` 三条链路全非 OPEN 时，
   开演报 409 `DEVICE_LINK_NOT_OPEN`，零 `sendVestCommand`、零日志、**零 `exercises` 交互**。
   （**第 29 节已把本用例由「仍开演」反转为「被 409 拒下」**：链路未开从静默跳过升为开演硬前置。）
5. `stopSendsFinishBattleAfterFreezing`——停演先冻结终局，再发**唯一一条** 7B 结束帧。

**app（`ExerciseDownlinkHttpIT`，3 条，真装配）**：真 Boot + 真 MySQL + 真安全链 + 真 HTTP，
且把 `scds.gateway.mode` 置为 `simulator`（`SimulatedLinkAdapter` 不起线程、不碰物理串口，
下行**同步记录**，`sentCommands()` 的先后即发送先后，故字节断言是确定性的、不与时间赛跑）。
专属马甲编号段 `62500~62599`（与 DeviceLedger 62100+ / Plan 62200+ / ExerciseLifecycle 62300+ / Command 62400+ 错开）。

1. `startDownlinkSendsPollConfigPerVestThenStartBattle`——开演后共 **3 条**：`sent.get(2)` 是 `vestNum=0`
   的开始帧，前两条**按马甲编号取帧**比对（`containsExactlyInAnyOrder` 验两台、`hexTo(sent, vestNum)`
   定位 14B）。**顺序无关**是刻意的：编成读取走 `PlanRosterRepository.findByPlanId` 的
   `ORDER BY team, vest_num`，BLUE 先于 RED——**该排序不是契约**，用例不得依赖下标。
2. `stopDownlinkSendsFinishBattleAfterFreeze`——清空已记录字节后停演，断言 1 条 `finishBattle()`、
   `replayId == exerciseId`。
3. `startIsRejectedAs409WithoutBytesOrLogsWhenVestLinkIsClosed`——不手动开串口链路时，开演被
   409 `DEVICE_LINK_NOT_OPEN` 拒下：`sentCommands()` 为空、`exerciseCountOf(planId) == 0`、
   读侧仍是「没有演习」。（**第 29 节把本用例由「开演停演都成功」反转为「被 409 拒下」**，
   并把「停演侧链路已关仍是 200」拆成独立用例
   `stopDownlinkIsSkippedWithoutBytesOrLogsWhenVestLinkWasClosedMeanwhile`，故本类 3 → 4 条。）

两条成功路径用例都断言 `totalLogCount(exerciseId) == 0`：**正常下发不写存证日志，只有失败才写**
（站内日志是「事故留痕」，不是「操作流水」）。

### 28.3 边界与不变式

**本段有意未做（附理由）**

- **开演链路的「就绪硬前置」本段未纳入**（→ **第 29 节已落地**）：契约 `startExercise` 的 409 分支里
  「马甲串口链路未开启导致无法下发配置」（`DEVICE_LINK_NOT_OPEN`）本段**未实现**——它不是一条判据，
  而是一次波及面很大的改造：一旦前置，所有既有的「未开链路直接开演」用例都要先手动开串口链路才能继续跑。
  按裁定**单列为下一原子任务**，本段只把「链路未开」处理成**静默跳过**（见 28.1）。
  故契约 409 描述里那句已就地标注「**未实现**：链路就绪 409 未交付」——第 29 节起该句已删。
- **单实例保护 L6 已收口，不在「未做」之列**（LIFE 分段（一））：结论是它落在**进程管理层**
  （`environments.md` §6.4），应用侧三件（开演 `claim(owner_instance)`、2000ms 心跳、`uk_exercise_running`
  唯一索引）**已具备且有 IT 覆盖**，**无 Java 改动**（D05 禁中间件与库级锁）。此处原先写「L6 未做」属口径失准，
  已订正；现场判据「起动第二进程必留自愈 WARN 收尾即视为部署违规」见 §6.4 与第 30 节。
- **硬件确认（ACK）不在本段**：本段只保证「字节发出去了」，不等待、不解析设备执行结果——
  「已发送」≠「已确认」，这正是 D11 要区分的那条线（详见 [decision-register](decision-register.md) D11）。

**未改动的硬约束**：协议冻结字节（`DownlinkCommands` 全部编码一字未动）、端口号
（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、在线窗口（5000ms）、校验算法、
`scds-solver` 全部算法一行未改；协议 `LogType` 未新增值；不改历史迁移（**不补 V8**）、
不开启 out-of-order、**未新增依赖**。

**仿真通过不等于现场通过**：`SimulatedLinkAdapter` 只证明「控制器 → 服务 → 组帧 → 端口」这条链在装配上
是通的、字节是对的；真实串口的时序、重发与设备响应仍属现场待验收。

本段为**代码验收**，不是生产发布批准。

### 28.4 现场验收清单（本段新增的待验收项）

| 待验收项 | 现场判据 |
|---|---|
| 开演出站字节真的到了马甲 | 开演后看 `scds-gateway` 日志：逐台 14B 轮询配置帧 + 末尾一条 7B 开始帧；马甲端确认轮询按 200ms 起振、初始 `hp`/`ammo` 与编成一致 |
| 停演出站字节真的到了马甲 | 停演后看 7B 结束帧；马甲端确认停止上报、进入结束态 |
| 链路未开时开演被拒 | 布场前（未开串口）直接开演：409 `DEVICE_LINK_NOT_OPEN`、**无任何下行字节**、不留半场次（**第 29 节已把它从「静默跳过 + 201」升为硬前置**） |
| 硬件失败的留痕 | 制造一次发送失败（拔串口/关闭链路），开演仍 201；`GET /api/v1/exercise/current/logs` 里应有一条 `SYSTEM` 日志，`downlinkHex` 是原样组帧的十六进制 |

## 29. M3 LIFE 开演链路就绪 409 硬前置（`DEVICE_LINK_NOT_OPEN`）

2026-10-28 收口第 28 节**有意留下**的那条未闭合项（见 28.3 第一条与 28.1 的「链路未开」行）。
上一段把链路未开处理成**静默跳过**，留下的缺口是**假成功**：字节进了一个无人消费的队列、接口仍回 `201`，
现场看到的是一场「配置根本没发出去」的演习，且要到复盘时才发现。本段把它改成**开演硬前置**：
开演前马甲串口链路必须已 `OPEN`，否则 409。

按用户 4 项裁定拆 A（core 生产代码）/ B（core 两个测试类）/ C（三个 app IT 改造）/ D（`ExerciseDownlinkHttpIT`）
四段实施，期间由全量回归又暴露两个未列入预估的 app IT（`CommandHttpIT` / `ReplayExportHttpIT`），一并改造。

命令（仓库根）：`mvn -o test`。本地 MySQL `scds-local-mysql` healthy，
IT 只跑隔离库 `scds_next_test`（`full-startup-test` profile + `.env.test`），不碰开发库。

| 模块 | 测试数 | 失败/错误/跳过 | 说明 |
|---|---:|---|---|
| protocol | 19 | 0/0/0 | 未改动 |
| gateway | 49 | 0/0/0 | 未改动（只被调用方多问了一次「链路开没开」，字节逻辑一字未动） |
| core | 611 | 0/0/0 | `ExerciseLifecycleServiceIT` 12 → 13（链路未开 409）；`ExerciseLifecycleServiceDownlinkTest` 仍 5 条，其中一条语义由「仍开演」反转为「被 409 拒下」 |
| api | 139 | 0/0/0 | 计数不变：`DEVICE_LINK_NOT_OPEN` 早在三层登记表里，无新增码、无新增用例 |
| app | 333 | 0/0/0 | `ExerciseLifecycleHttpIT` 3 → 4、`ExerciseDownlinkHttpIT` 3 → 4；`CommandHttpIT`(6) / `ReplayExportHttpIT`(2) / `DeviceSelfHttpIT`(3) / `PushCrashRecoveryIT`(1) 计数不变、只改前置 |
| solver/domain | 0 | 无测试 | `scds-solver` 冻结算法未改动 |
| 总计 | **1151** | **0/0/0** | 全 reactor SUCCESS，`mvn_exit=0` |

> 计数口径同第 20~28 节：按 Maven **模块级汇总**（`[INFO] Tests run:`）取值（按
> `target/surefire-reports/TEST-*.xml` 累加会漏掉 `@Nested` 内部类）。
> 较第 28 节基线 1148 净增 **3** = core `+1`（段 B）+ app `+2`（段 C/D），两处新增即本段全部新增；
> 其余改动是既有用例的**语义反转与前置补齐**，不改计数。

### 29.1 落地范围与口径

**判据落点（`scds-core` 的 `ExerciseLifecycleService.startExercise`）**：插在「编成为空」判据之后、
`Instant at = Instant.now()`（写库起点）之前，判据与既有下发前防御**共用同一个私有方法 `vestLinkOpen()`**
——两处若各写一套，迟早会在「什么算就绪」上分叉。

**判据顺序（用户裁定①：请求内容问题先报、运行环境前置后报）**

| 序 | 判据 | 码 / 状态 |
|---|---|---|
| 1 | 启动自愈门禁 | `INSTANCE_NOT_READY` / 503 |
| 2 | 想定不存在 | `EXERCISE_PLAN_NOT_FOUND` / 404 |
| 3 | 编成为空 | `EXERCISE_ROSTER_EMPTY` / 400 |
| 4 | **马甲串口链路未开** | `DEVICE_LINK_NOT_OPEN` / 409 |
| 5 | 插库（唯一索引 `uk_exercise_running`） | `EXERCISE_ALREADY_RUNNING` / 409 |

第 4 条**排在写库之前**，故被它拒下的请求**不留任何半启动态**（无演习行、无编成快照、无态势装载）。

**四条口径**

| 项 | 口径 |
|---|---|
| 就绪判据 | 只认 `LinkState.OPEN`：`OPENING` 不算已就绪。`snapshot()` 里存在 `kind=SERIAL_VEST` 且 `state=OPEN` 的链路才算就绪 |
| 既有「静默跳过」分支 | **保留为防御**（用户裁定③）：`sendStartDownlink` 里「链路未开就整段不下发、只 WARN」不删——它兜的是「门禁检查与下发之间链路被并发关闭」这一窗口。正常情况下走不到 |
| 停演 | **不设门禁**：卡死的场次必须随时停得掉，故 `stopExercise` 的 409 仍只表示 `EXERCISE_NOT_RUNNING`；停演侧链路被并发关闭仍走静默跳过 |
| 错误码 | **复用 `DEVICE_LINK_NOT_OPEN`**（用户裁定④所承的既有裁定）：它早已在 `ApiErrorCode`、`ApiExceptionHandler` 的 409 分组与 `docs/api/schemas/common.yaml` 中登记，本段**不新增**任何码、任何迁移，三层登记表一字未动 |

**契约订正**：`docs/api/paths/exercise.yaml` 的 `startExercise` 步骤叙述由 4 步改成 5 步（链路就绪成为步骤②），
409 段删去「**尚未交付**：…目前**未实现**」整段，改列为「已有演习进行中 **或** 链路未开」两种 409，
并写明「两者都排在写库之前」与「判据只认 `OPEN`；停演不设此门禁」。

### 29.2 用例判据

**core（`ExerciseLifecycleServiceIT`，12 → 13）**

1. `startExercise_withVestLinkClosed_isDeviceLinkNotOpen`——想定与编成都合法，只把桩快照摆成
   `SERIAL_VEST CLOSED`：`startExercise` 抛 `DEVICE_LINK_NOT_OPEN`，且
   **`exercises.findRunning()` 仍为空**——判据排在插库之前，被拒的请求不留半启动态。
2. `@BeforeEach openVestLink()`（用户裁定②）——本类**其余 12 条**都要先能开演，故在 `@BeforeEach` 里令桩
   `snapshot()` 返回一条 `SERIAL_VEST OPEN`；**不改** `TestCoreApplication` 的全局默认
   （它的桩语义仍是「链路未开」，且类 javadoc 已就地说明）。用例内可再 `when(...)` 覆盖成本用例要的态。

**core（`ExerciseLifecycleServiceDownlinkTest`，5 条，语义反转）**

- `startIsRejectedWith409WithoutSendingOrLoggingWhenVestLinkIsClosed`（原
  `startDownlinkIsSkippedWithoutSendingOrLoggingWhenVestLinkIsClosed`）——链路未开时断言
  **抛 409 `DEVICE_LINK_NOT_OPEN`**，并 `verify(links, never()).sendVestCommand(...)`、
  `verifyNoInteractions(logs)`、**`verifyNoInteractions(exercises)`**（连库都没碰）。
  本类其余 4 条不变（含 D11 的「发送失败仍开演」核心判据）。

**app（`ExerciseLifecycleHttpIT`，3 → 4；真装配 + 仿真网关）**

- `startWithVestLinkClosedIsRejectedAs409`——显式关链路后开演，得真实 HTTP 的
  409 `DEVICE_LINK_NOT_OPEN`（Problem 的 `type` 指向 `/api/v1/exercise/current`），并断言
  `GET /api/v1/exercise/current` 仍 `running=false`、`t_exercise` 里该想定无未结束行。
- `bootFullApplication` 注入 `scds.gateway.mode=simulator`（用户裁定④）：本机没有 COM 口，
  真链路模式开不了串口，链路开关必须由本类自己摆布。
- 其余 2 条成功路径用例在方法首行 `openSerial()`——成功路径现在**必须先开链路**。

**app（`ExerciseDownlinkHttpIT`，3 → 4）**

- `startIsRejectedAs409WithoutBytesOrLogsWhenVestLinkIsClosed`——链路未开时开演 409，
  `sentCommands()` 为空、`exerciseCountOf(planId) == 0`、读侧仍无演习。
- `stopDownlinkIsSkippedWithoutBytesOrLogsWhenVestLinkWasClosedMeanwhile`——**新增**，把原用例里
  「停演侧链路已关」的覆盖**原位保住**：开演（链路已开）→ 关链路 → `clearSentCommands()` →
  停演仍 `200`、零字节、零日志。停演不设门禁，这条必须继续立着。

**app（既有 IT 的前置补齐，用户裁定④的波及面）**

| 类 | 改法 |
|---|---|
| `DeviceSelfHttpIT` | `@BeforeAll` 在 `seedPlanAndRoster()` 与 `startExercise()` 之间插 `openSerial()` |
| `PushCrashRecoveryIT` | 换代后的**第二个进程**开演前补 `next.openSerialLink()`（第一代本来就开了） |
| `CommandHttpIT` | 两个夹具 `startRunningExercise` / `startRunningExerciseOnMap` 内先 `openSerial()` 再开演；开演后 `links.clearSentCommands()`；`closedLinkIsAcceptedWithoutEnqueue…` 改为开演后立刻 `closeSerial()` |
| `ReplayExportHttpIT` | `@BeforeAll` 加 `scds.gateway.mode=simulator`（原先走 `real` 默认值，本机开不了串口），两处开演前加 `openSerial()` |

**本段才暴露的两点（值得记下，都是「前置一改，测试的前提全变」）**

- **开链路端点不是幂等**：链路已 `OPEN` 时再 `POST /api/v1/devices/links/serial` 回 409
  （`DEVICE_LINK_ALREADY_OPEN`）。而一个用例里可能开演两次、或同类用例之间互不隔离，
  故各 IT 的 `openSerial()` 夹具一律**先 `closeSerial()` 再 POST**（关链路端点才是幂等的）。
- **开演自身会下发字节**：链路一开，14B 轮询配置帧 + 7B 开始帧就进了 `sentCommands()`。
  凡按**条数**断言下行的用例（`CommandHttpIT`）必须在开演后 `clearSentCommands()`，
  否则计数的起点从「开演」变成了「用例自己发的指令」——这是本段第一次全量回归 6 条失败的直接原因。

### 29.3 边界与不变式

**为什么是 409 而不是 400 / 500**：请求本身完全合法，缺的是**运行环境的前置条件**，
与「已有演习进行中」同属「当前状态不允许这次操作」，故同为 409；且它**不新增错误码**。

**停演刻意不设门禁**：门禁的目的是防止「配置根本没发出去的假演习」被开起来；
停演没有任何对称的风险，反而卡死的场次必须**随时停得掉**。开演严、停演宽，是这一对端点的既定取向。

**仍未落地（本段不涉及）**

- **硬件确认（ACK）**：本段只保证「要么早拒、要么字节发出去」，不等待、不解析设备执行结果（D11 的边界不变）。
- **Flutter 两端工程、T-PROD 部署**：与本段无关。

**同库单实例保护（L6）** 已由 LIFE 分段（一）收口为**进程管理层**职责（`environments.md` §6.4，**无 Java 改动**）；
`ExerciseLifecycleService` 类 javadoc 原先那句「仍未落地」已随之订正为「属进程管理层、本类无相关代码改动」。详见第 30 节。

**未改动的硬约束**：协议冻结字节（`DownlinkCommands` 全部编码一字未动）、端口号（HTTP 11001 / UWB TCP 12001）、
轮询周期（200ms）、在线窗口（5000ms）、校验算法、四角编号口径、`scds-solver` 全部算法一行未改；
不改历史迁移（**不补 V8**）、不开启 out-of-order、**未新增依赖**、**未新增错误码**。

**仿真通过不等于现场通过**：链路就绪判据验的是 `snapshot()` 的装配与状态流转，
真实串口下「插上即 OPEN」「拔掉即 CLOSED」的时序仍属现场待验收。

本段为**代码验收**，不是生产发布批准。

### 29.4 现场验收清单（本段新增的待验收项）

| 待验收项 | 现场判据 |
|---|---|
| 未开串口就开演被拒 | 布场前（不点开链路）直接开演：409 `DEVICE_LINK_NOT_OPEN`，`GET /api/v1/exercise/current` 仍 `running=false`，`t_exercise` 无残留未结束行 |
| 已开串口正常开演 | 点开链路后再开演：201，且 `scds-gateway` 日志里逐台 14B 轮询配置帧 + 末尾一条 7B 开始帧 |
| 门禁与下发之间的并发窗口 | 开演请求发出后、下发前立刻拔串口/关链路：响应仍 201（不因硬件回滚），服务端 WARN + 一条 `SYSTEM` 存证日志——这是 29.1 保留的防御分支 |
| 停演不受门禁约束 | 关掉链路后停演：仍 200，场次正常冻结收尾（停演不设门禁） |
| 真串口链路就绪判据 | 拔掉再插上串口：`GET /api/v1/devices/links/serial` 反映的状态应与物理插拔一致（`OPENING` 期间不得被当成已就绪） |

## 30. L6 单实例保护口径订正（部署前提，不是代码任务）

LIFE 分段（一）的 §L6 已裁定：同库单实例保护落在**进程管理层**（`environments.md` §6.4），
应用侧三件（开演 `claim(owner_instance)`、2000ms 心跳（守卫带 owner）、`uk_exercise_running` 唯一索引）
**已具备且有 IT 覆盖**，**无 Java 代码可改**（D05 禁分布式中间件与库级锁）。但 `ExerciseLifecycleService`
类 javadoc 与本文档 §28.3 / §29.3 仍把它写成「未做 / 仍未落地」，三处口径互相矛盾。2026-10-28 按用户裁定
**以 §6.4 的「已结论」为准**统一订正，只动 javadoc 与文档，**不新增代码、不新增测试、不新增依赖**。

### 30.1 订正清单

| 位置 | 原表述 | 现表述 |
|---|---|---|
| `ExerciseLifecycleService` 类 javadoc | 「仍未落地：同库单实例保护（L6）——它会牵动既有集成测试，单列后续任务」 | 「同库单实例保护（L6）**不在本类，属进程管理层**」——列明应用侧三件已就位、自愈不区分 owner、D05 的三条「不做」、**本类无相关代码改动** |
| 本文档 §28.3 | 「**单实例保护 L6 未做**」 | 「L6 **已收口，不在「未做」之列**」，并指向 §6.4 与本第 30 节 |
| 本文档 §29.3 | 「仍未落地」清单里含 L6 | 从该清单**移除** L6，改为单列一段「已收口为进程管理层职责」 |
| `decision-register` 网关下行段 | 「仍未落地：同库单实例保护（L6）」 | 「**已收口，不在本清单**」，指向 LIFE 分段（一）§L6 与文末「L6 口径订正」 |

### 30.2 为什么不是一条代码任务

- **应用侧三件已就位并有 IT**：开演 `claim(owner_instance)` 落库、2000ms 心跳（守卫带 owner）、
  `uk_exercise_running` 唯一索引（V4 生成列 `running_flag`）。三件事在 `ExerciseLifecycleServiceIT`
  里有覆盖。它们保证「**一场之内**」不自相矛盾。
- **它们挡不住的是什么**：第二个进程启动时，`ExerciseLivenessRecover` 的自愈「**一律收尾、且不区分 owner**」，
  会把第一个进程正在跑的场次收尾成 `endReason=CRASH_RECOVERED` / `fail_reason='INSTANCE_RESTART'`，
  并作废其残留指令。库与应用都没有能自动识别「另一台正在跑」的机制。
- **为什么不在应用层解决**：D05 明确禁止引入分布式中间件、库级会话锁、`GET_LOCK` 与租约；且态势模型、
  链路句柄与 UWB 标定上下文都**只在内存里**，接管本就不可能，故该前提只能在进程管理层保证。

### 30.3 现场验收清单（本项新增的待验收项）

| 待验收项 | 现场判据 |
|---|---|
| 第二进程被进程管理层拦下 | 起动第二个指向**同一客户库**的业务进程：应被进程管理层**直接拒启**。`--scale`、把同一数据卷挂进第二个 compose 项目、同机第二份原生服务配置指向同一库，均属**违规形态** |
| 第二进程误收尾的判定 | 若第二进程仍起来了，启动日志**必然**出现自愈 WARN 收尾记录（`endReason=CRASH_RECOVERED`、`fail_reason='INSTANCE_RESTART'`）——**出现即视为部署违规**，不是「自愈生效」 |
| 「起来了但开不了演」 | 停机重启后若开演被 503 `INSTANCE_NOT_READY` 拒：先查启动日志的自愈结果，**不要重试**（门禁在自愈跑完前不放行） |
| 升级顺序 | 升级窗口必须**先停唯一业务进程、再启新进程**；不得并行起两份 |

### 30.4 未改动

`environments.md` §6.4 本身**无需改**（四形态要求与现场判据已在其中）；协议冻结字节、端口号
（HTTP 11001 / UWB TCP 12001）、轮询周期（200ms）、在线窗口（5000ms）、校验算法、四角编号口径、
`scds-solver` 全部算法、`ApiErrorCode` 与 `ApiExceptionHandler` 均一字未动；
**未新增依赖、未新增迁移、未新增错误码**。

本段为**口径订正与部署前提确认**，不是生产发布批准；第二进程拒启属进程管理层的现场行为，仍待现场验收。

## 31. R4 遗留收口：`downloadUrl` 签名窗口过期的 HTTP 用例

第 26 节 §26.3 挂起过一条未覆盖项：`downloadUrl` 的**签名本身过期**（R4 只验了产物 TTL 过期与签名被篡改
两条，签名窗口过期只在 core 的 `HmacFileUrlSignerTest` 里用 `MutableClock` 覆盖）。2026-10-28 补上 HTTP 层用例。

### 31.1 构造口径（为什么不推时钟）

签名器 `HmacFileUrlSigner` 持 `Clock.systemUTC()`，HTTP IT 共用真实应用上下文，**测试推不动时钟**，
而真实 TTL 是 15 分钟（`scds.files.sign-ttl`），等不起、也不该靠 sleep。故在**测试侧按契约独立重算**一份签名：
固定测试密钥（`@BeforeAll` 显式给 `scds.files.sign-secret`，≥32 字符，否则应用启动即失败）
＋ 被签串 `相对路径 + '\n' + exp` 的 HMAC-SHA256、Base64URL 无填充——与 `HmacFileUrlSigner` 同口径，
但**不由被测代码产出**，因此是一份独立判据。

**先正例自证再验过期**：重算算法必须先用「未过期」样本证明与服务端一致，否则过期样本的 403 可能只是
「签错了」。故断言顺序为：未过期样本 200（且取回同一份字节）→ 已过期样本 403 `FILE_URL_INVALID`。

### 31.2 用例判据（在既有方法内扩断言，不新增用例）

`ReplayExportHttpIT.xlsxExportOverRealHttp`（第 26 节第 17 条）在「签名被篡改 403」之后新增两段：

1. 用测试侧重算的签名构造 `exp = now + 300s` 的 URL → **200**，正文与按域下载通道**同一份字节**
   （自证重算与服务端一致）。
2. 同法构造 `exp = now - 60s` 的 URL → **403 `FILE_URL_INVALID`**——签名正确、仅过期，
   与「签名被篡改」共码（契约刻意不拆分：客户端处置动作相同，区分只会泄露路径是否存在）。

新增测试侧辅助：`resignWithExp(String signedUrl, long exp)`（只换 `exp` 与 `signature`，`path` 参数原样沿用原 URL 已编码片段）
与 `hmac(String relativePath, String exp)`。**计数不变**：在既有方法内扩断言，全量仍 **1151** 项（0/0/0）。

### 31.3 未改动

验签逻辑（`HmacFileUrlSigner.verify`）、`FileController`、`ApiErrorCode`（复用既有 `FILE_URL_INVALID`）、
`ApiExceptionHandler.statusOf`、openapi、迁移、依赖**均一字未动**；**未引入可注入 `Clock` 到生产**
（仅测试侧重算）；协议冻结字节、端口号、轮询周期、在线窗口、校验算法、`scds-solver` 亦未动。

本段为**代码验收**，不是生产发布批准。

## 32. M6 CLIENT-BASE 段 0：Flutter 工程脚手架（D13 一次性例外）

客户端开工前的最后两个前置 **D12 / D13** 于 2026-10-28 裁定（见 [decision-register](decision-register.md) 文末
「D12 / D13 裁定与 M6 CLIENT-BASE 段 0」）。本段**只做脚手架，不含业务**。

### 32.1 生成口径

```powershell
flutter create --org com.dualx --project-name scds_client --platforms=android,windows clients/scds_client
```

- 采用 D13 授权的**仅一次**脚手架例外，目标目录 `clients/scds_client` 与文件清单固定；
- 依赖**只有模板默认**（`flutter` + `cupertino_icons` + `flutter_lints`），**未新增第三方依赖**；
- 磁盘写 50 文件，**git 跟踪 45 文件**；`build/`、`.dart_tool/`、Gradle wrapper 等由模板自带 `.gitignore` 排除；
- 平台为 `android,windows` 两端（D12 的双入口）：`android/**` 的 `MainActivity.kt` 落在 `com.dualx.scds_client`；
  `windows/**` 为 CMake + runner C++（**本机缺 Visual Studio，不构建**）。

### 32.2 验收证据（客户端验收层的最小门禁）

| 项 | 命令 | 结果 |
|---|---|---|
| 静态分析 | `flutter analyze` | `No issues found!` |
| 组件冒烟 | `flutter test` | `All tests passed!`（模板默认 Counter widget test） |
| 环境 | `flutter --version` / `flutter doctor` | Flutter 3.47.7 stable / Dart 3.13.5；`flutter doctor` 报缺 Visual Studio（Windows 桌面构建不可用） |

### 32.3 边界与未覆盖

- **未做真实联调**：尚无 HTTP 客户端、无登录、无后端对接，故不产生后端侧计数变化；
- **计数**：本地全量仍 **1151** 项——脚手架是 Dart 侧资产，**不在 Maven reactor 内**；
- **未改动**：后端模块、协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期、在线窗口、
  校验算法、四角编号口径、`scds-solver`、迁移与错误码**均未动**；
- **本机限制**：Windows 端**不能原生构建**（缺 VS 的 Desktop development with C++ 工作负载，安装需另授权）；
  Android 端可构建，模拟器 `zaa_pixel6` 可用但**本轮未启动**。

本段为**代码验收（脚手架层）**，不是生产发布批准，也不代表客户端已联调。

## 33. M6 CLIENT-BASE 段 1：共享契约 / 通信基座（Dart 侧，不含测试）

段 0 脚手架就位后，段 1 落**共享契约与通信基座**——后端契约在客户端的唯一镜像点，
后续段 2（登录）到段 5（指挥端骨架）都复用它。**本段只写实现，测试按裁定留给段 1b**。

### 33.1 交付物（3 文件，`clients/scds_client/lib/shared/api/`）

| 文件 | 内容 | 契约来源 |
|---|---|---|
| `api_contract.dart` | `kDefaultHost` / `kHttpPort = 11001` / `kApiPrefix = /api/v1` / `kWsPrefix = /ws/v1` / `kProblemMediaType`；`ApiErrorCode` 常量族（AUTH/EXERCISE/COMMAND/DEVICE/MAP/PLAN/REPLAY/SITUATION/FILE 与通用码） | `docs/api/openapi.yaml`（`servers`、前缀注释）+ `docs/api/schemas/common.yaml`（`Problem.code` 枚举说明） |
| `api_problem.dart` | `ApiProblem`（`type`/`title`/`status`/`code`/`detail`/`instance`/`traceId`/`errors`）、`FieldError`、`ApiException` | `common.yaml` 的 `Problem` 组件（`required: [status, code, traceId]`） |
| `api_client.dart` | `ApiResponse`（保留原始字节，供二进制产物下载）、`ApiClient`（`get/post/put/delete/send`、`resolve`/`api`、`Bearer` 注入、`close`） | openapi `servers` 的 `http://{host}:11001` |

### 33.2 判据（实现口径）

- **只依赖 `dart:io`**：目标平台为 Android / Windows，`HttpClient` 够用，**未新增第三方依赖、未动 `pubspec.yaml`**；
- **不臆造缺省值**：`ApiProblem.tryParse` 对缺必填字段、非 JSON、非对象的正文一律返回 `null`；
  客户端不把「不合契约的错误页」伪装成合法 Problem；
- **非 2xx 不吞**：`send` 在非 2xx 时解析 Problem 并抛 `ApiException(statusCode, problem)`，
  调用方据 `code` 分支（`problem == null` 时只能依据状态码处置，`code` 回落为 `HTTP_<status>`）；
- **错误码常量只作镜像**：`ApiErrorCode` 是 `common.yaml` 的逐字复制，**不新增、不改名**；
  语义边界的注释（如 `COMMAND_MOVE_FORBIDDEN` 与 `AUTH_FORBIDDEN`、`DEVICE_LINK_NOT_OPEN` 与
  `DEVICE_LINK_ALREADY_OPEN`）随之带上，避免客户端误用；
- **二进制安全**：`ApiResponse.body` 是 `List<int>`，`text`/`json` 只是便利视图，产物下载不走文本解码。

### 33.3 未做与边界

- **本段无测试**（按裁定拆为段 1b：用本地 `HttpServer` 打桩验契约解析、`Bearer` 注入与
  非 2xx → `ApiException` 映射）；因此本段**只有静态门禁**：`flutter analyze` → `No issues found!`；
- **未接后端、未做登录**（段 2）；`main.dart` / `widget_test.dart` 仍是模板原样（装配留段 4）；
- **未改动**：后端任何模块、协议冻结字节、端口号、轮询周期、在线窗口、校验算法、四角编号口径、
  `scds-solver`、迁移与错误码；本段是 Dart 侧资产、**不进 Maven reactor**，Maven 全量计数仍 **1151** 项。

本段为**代码验收（客户端基座层）**，不是生产发布批准，也不代表客户端已联调。

## 34. M6 CLIENT-BASE 段 1b：通信基座契约测试（Dart 侧，本地 HttpServer 打桩）

段 1 落实现、段 1b 落**测试**——把段 1 三文件的对外行为钉死，后续段 2~5 改动基座即会红。
测试**不打桩框架内部**，而是起一个本机 `HttpServer`，断言**真实 HTTP 行为**（请求头、正文编码、
状态码与非 2xx → `ApiException` 的映射）。

### 34.1 交付物（1 文件）

| 文件 | 内容 |
|---|---|
| `clients/scds_client/test/shared/api/api_client_test.dart` | 14 用例：路径拼接 / 请求构造 / 非 2xx 统一抛 `ApiException` / `ApiProblem.tryParse` 不臆造缺省值 |

`setUp` 起 `HttpServer.bind(InternetAddress.loopbackIPv4, 0)`（**随机端口**，`baseUri` 用 `server.port`），
各用例覆写 `handler` 决定响应；`tearDown` 关 `ApiClient` 与 `server`。

### 34.2 用例与判据

| 组 | 用例 | 判据 |
|---|---|---|
| 路径拼接 | `api()` 带 `/api/v1` 前缀并编码查询参数 | `uri.path == '$kApiPrefix/auth/login'`、`queryParameters['mode'] == 'local'` |
| 路径拼接 | `resolve()` 补齐前导斜杠 | `api/v1/auth/me` → `/api/v1/auth/me` |
| 路径拼接 | 契约常量与后端硬约束一致 | `kHttpPort == 11001`、`kApiPrefix == /api/v1`、`kWsPrefix == /ws/v1`、`kProblemMediaType == application/problem+json` |
| 请求构造 | GET 200 JSON 解析 + `Accept` 覆盖 `problem+json` | `json['username'] == 'commander'`、`accept` 含 `kProblemMediaType`、无 token 时无 `Authorization` |
| 请求构造 | POST `jsonBody` 的 Content-Type / UTF-8 | `contentType.mimeType == application/json`、`charset == utf-8`、正文可被 `utf8.decode` + `jsonDecode` 还原 |
| 请求构造 | `bearerToken` 非空才注入，空串不注入 | `Authorization == Bearer access-token-1`；空串时 `isNull` |
| 请求构造 | 二进制正文原样返回 | `application/octet-stream` 的 `[0x89,0x50,0x4E,0x47,0x00,0xFF]` 逐字节相等 |
| 非 2xx | Problem 正文带出 code/status/traceId | `statusCode == 409`、`code == EXERCISE_ALREADY_RUNNING`、`message` 含 `traceId` |
| 非 2xx | `VALIDATION_FAILED` 带出字段级 errors | `isValidationFailure`、`errors.single.field == 'planId'`、`message == '不能为空'` |
| 非 2xx | 非 Problem 正文回落 | 代理 HTML 502 → `problem == null`、`code == 'HTTP_502'` |
| 非 2xx | 2xx 不抛（含 204 空正文） | `statusCode == 204`、`body` 空、`json == null` |
| tryParse | 合法 Problem 正常解析 | `code`/`status` 正确、`errors` 为空 |
| tryParse | 缺必填（`traceId`）返回 null | 返回 `null` |
| tryParse | 非 JSON / 非对象 / 空正文一律 null | `not json` / `[1,2,3]` / `[]` 均 `null` |

### 34.3 实现坑（已修，留档）

- **打桩响应必须显式声明 charset**：`HttpResponse` 在无 `charset` 时按 **latin1** 编码，写含中文的
  Problem 正文（如「不能为空」）会抛 `Contains invalid characters`，服务端不回响应，客户端表现为
  **15s 超时**（而非报错）。修法：`ContentType(type, subtype, charset: 'utf-8')` + `add(utf8.encode(...))`。
  （注：`ContentType.change` 在 `dart:_http` 中**不存在**，不能拿来改 charset。）
- 捕获断言用 `expectApiFailure(future)` 助手（捕获 `ApiException`，未抛时 `fail` 报真实状态码），
  避免 `.then(onError: (e) => e as ApiException)` 在收到非 `ApiException` 时强转失败掩盖真实错误。

### 34.4 门禁与边界

| 项 | 命令 | 结果 |
|---|---|---|
| 静态分析 | `flutter analyze` | `No issues found!` |
| 基座契约测试 | `flutter test test/shared/api/api_client_test.dart` | `All tests passed!`（**14** 例） |

- **计数不变**：Dart 侧资产、**不进 Maven reactor**，Maven 全量仍 **1151** 项；
- **未做真实联调**（仍无后端对接、无登录，属段 2~段 5）；
- **未改动**：后端模块、协议冻结字节、端口号、轮询周期、在线窗口、校验算法、四角编号口径、
  `scds-solver`、迁移与错误码。

本段为**代码验收（客户端基座测试层）**，不是生产发布批准，也不代表客户端已联调。

## 35. M6 CLIENT-BASE 段 2：auth 域模型与客户端（账号 + 设备登录），内存会话存储

段 1 的基座就位后，段 2 落**第一个业务域客户端**——auth。按当次裁定取
「**账号 + 设备两端登录**」、「**内存会话存储**」、「**测试拆段 2b**」。

### 35.1 交付物（3 文件）

| 文件 | 内容 | 契约来源 |
|---|---|---|
| `lib/shared/api/auth_models.dart` | `AuthUser`（`userId`/`username`/`roles` + 可选 `displayName`/`permissions`/`lastLoginAt`）、`TokenPair`（`accessToken`/`expiresIn` + 可选 `refreshToken`/`user`）、`DeviceTokenPair`（`deviceToken`/`expiresIn`） | `docs/api/schemas/domain.yaml`（`User` / `TokenPair` / `DeviceTokenPair`，逐字对齐必填集） |
| `lib/shared/api/auth_api.dart` | `AuthApi`（持 `ApiClient`）：`login` / `refresh` / `logout` / `me` / `deviceLogin` | `docs/api/paths/auth.yaml`（`login` / `refreshToken` / `logout` / `getCurrentUser` / `deviceLogin`） |
| `lib/shared/auth/session_store.dart` | `AccountSession`（`accessToken`/`expiresAt` + 可选 `refreshToken`/`user`）、`DeviceSession`（`vestNum`/`deviceToken`/`expiresAt`）、`SessionStore` 抽象端口、`InMemorySessionStore` | 客户端侧模型（由 `TokenPair`/`DeviceTokenPair` 派生，非契约） |

### 35.2 判据（实现口径）

- **不臆造缺省值**：`AuthUser.fromJson` 对必填字段缺失抛 `FormatException`（`roles` 形状不符时
  `_stringList` 抛，非静默丢弃）；`permissions` 缺失按空列表（契约未列为必填，且只用于前端显隐）；
- **绝对时刻在客户端换算**：服务端只给相对剩余秒数 `expiresIn`，`AccountSession.fromTokenPair`
  /`DeviceSession.fromTokenPair` 用**注入的 `now`（默认当前 UTC）** 换算成 `expiresAt`——
  客户端**不据相对秒数猜测服务端时钟**；
- **端口与实现分离**：`SessionStore` 是抽象接口，`InMemorySessionStore` 是进程内实现
  （重启即丢、**不落盘**、**不得把令牌写进日志**），为后续「平台安全存储」只换实现留位；
- **两类会话分开**：账号会话与设备会话各存各的，`clearAll()` 一次清空（切换实例/退出登录/顶替提示后调用）；
- **不吞错、不重试、不缓存令牌**：`AuthApi` 只做「拼路径 + 解析契约模型」，非 2xx 由 `ApiClient`
  抛 `ApiException`（失败如 `AUTH_BAD_CREDENTIALS` 401 / `AUTH_DEVICE_NOT_BOUND` 401 / 限流 429），
  **登录失败不区分账号不存在与密码错误**（契约明确）；
- **logout 无请求体**：`POST /auth/logout` 只带 `Bearer`，回 204；accessToken 无状态不可撤销，
  由短有效期兜底。

### 35.3 未做与边界

- **本段无测试**（按裁定拆为段 2b：本地 `HttpServer` 打桩验四/五方法与模型解析），
  故门禁只有静态分析：`flutter analyze` → `No issues found!`；
- **未接 UI、未装配入口**：`main.dart` / `widget_test.dart` 仍是模板原样（登录页与入口装配留段 3~/段 4）；
- **安全存储未落**：`InMemorySessionStore` 不落盘，**不用于现场长期登录**（重启需重登）；
  平台安全存储（Android Keystore / Windows DPAPI）与「账号/实例切换清理」留后续段，**需另行授权引依赖**；
- **未改动**：后端任何模块、协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期、在线窗口、
  校验算法、四角编号口径、`scds-solver`、迁移与错误码；本段是 Dart 侧资产、**不进 Maven reactor**，
  Maven 全量计数仍 **1151** 项。

本段为**代码验收（客户端 auth 域层）**，不是生产发布批准，也不代表客户端已联调。

## 36. M6 CLIENT-BASE 段 2b：auth 客户端基座测试（Dart 侧，本地 HttpServer 打桩）+ 前缀缺陷修复

段 2 按裁定把测试拆到段 2b。补测过程中**抓到并修复一个真实缺陷**：`ApiClient` 的
`get`/`post`/`put`/`delete` 经 `send` → `resolve()` 拼接，**不含 `kApiPrefix`**，
导致 `AuthApi` 五端点实际请求 `/auth/login` 等**无 `/api/v1` 前缀**的路径（现场会 404）。
段 1b 的用例只断言方法 / 头 / 正文、**未断言请求路径**，故未暴露。

### 36.1 交付物（4 文件：1 修复 + 3 测试）

| 文件 | 变更 | 内容 |
|---|---|---|
| `lib/shared/api/api_client.dart` | 改 | `send` 由 `resolve(path, query)` 改为 `api(path, query)`：`get`/`post`/`put`/`delete` 统一带 `/api/v1`，业务域客户端只传业务相对路径 |
| `test/shared/api/auth_models_test.dart` | 新 | auth 域契约模型解析边界（10 例） |
| `test/shared/api/auth_api_test.dart` | 新 | `AuthApi` 五端点路径 / 请求体 / `Bearer` / 解析 / 错误映射（8 例） |
| `test/shared/auth/session_store_test.dart` | 新 | `AccountSession`/`DeviceSession` 派生与 `InMemorySessionStore`（6 例） |

提交拆分：`54e830b`（fix，1 文件）+ `d770556`（test，3 文件），守「原子 ≤3 文件」。

### 36.2 用例与判据

| 分组 | 用例 | 判据 |
|---|---|---|
| 模型（10） | `AuthUser` 全字段 / 可选缺失 / `roles` 非数组 → `FormatException` / 缺 `userId` → 抛错；`TokenPair` 必填+嵌套 `user` / 可选缺失 / `expiresIn` 小数收 int / 缺 `expiresIn` → 抛错；`DeviceTokenPair` 必填 / 缺必填 → 抛错 | **不臆造缺省值**：必填缺失或形状不符即抛，可选缺失为 `null`；`permissions` 缺失按空列表 |
| 端点（8） | `login`：`POST` + 路径 `/api/v1/auth/login` + 正文 `username`/`password` + **不带** `Authorization`；401 `AUTH_BAD_CREDENTIALS` 带码；`refresh`：正文只带 `refreshToken`，401 `AUTH_TOKEN_EXPIRED`/`AUTH_TOKEN_INVALID` 分别带码；`logout`：**无请求体** + `Bearer` + 204 不抛；`me`：`GET` + `Bearer` → `AuthUser`；`deviceLogin`：正文 `vestNum`/`secret`，401 `AUTH_DEVICE_NOT_BOUND` 带码 | 路径**逐字**对齐契约（带 `/api/v1`）；非 2xx 统一由 `ApiClient` 抛 `ApiException`（`code` 稳定） |
| 会话（6） | 注入 `now` → `expiresAt = now + expiresIn`（UTC）；不注入 → 以当前 UTC 起算；`DeviceSession` 保留 `vestNum`/`deviceToken`；`InMemorySessionStore` 初始为 `null` / 写入后可读回且两类互不覆盖 / `clearAll` 一次清空 | **绝对时刻在客户端换算**；账号 / 设备两类**分开存** |

### 36.3 缺陷留档（业务请求缺 `/api/v1` 前缀）

- **现象**：打桩记录到 `AuthApi.login` 的请求路径为 `/auth/login`（期望 `/api/v1/auth/login`）；
- **根因**：`ApiClient.send` 用 `resolve()` 只补前导斜杠，**只有 `api()`** 才拼 `kApiPrefix`，
  而 `get`/`post`/`put`/`delete` 都走 `send`——前缀从未被接上；
- **契约依据**：`docs/api/openapi.yaml` 的 **51 条 REST 路径全部在 `/api/v1` 下**（含 `/api/v1/files`），
  故 `send` 统一走 `api()` 是安全的、且与「业务域客户端只传相对路径」的分层一致；
- **修复**：`send` 内 `resolve(path, query)` → `api(path, query)`（1 行）；
- **回归**：段 1b **14 例**未受影响（其用例不含路径断言，均以 `handler` 按状态码回包）。

### 36.4 门禁与边界

- **客户端门禁**：`flutter test` → `All tests passed!`（**39 例** = 段 1b 14 + 本段 24 + 模板 1）；
  `flutter analyze` → `No issues found!`；
- **Maven 侧无增减**：本段产物全在 `clients/scds_client`（Dart），**不进 reactor**，全量仍 **1151 项**（0/0/0）；
- **未改动**：后端任何模块、协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期、在线窗口、
  校验算法、错误码、迁移、`scds-solver`；**未新增依赖**（测试仍只用 `dart:io` + `flutter_test`）。

本段为**客户端测试验收（auth 域层）**，不是生产发布批准；测试用 `HttpServer` 打桩，**不代表已与后端联调**。

---

## 37. M6 CLIENT-BASE 段 3：连接配置（3a）+ 态势契约模型（3b）+ 态势只读四端点（3c）

段 3 按裁定拆成 **3a / 3b / 3c** 三段，每段各提 **1 笔代码笔**（守「原子 ≤3 文件」）；
docs 统一在本节一次回写。范围是**「连接配置 + 自身态势只读」**，
**不含 WebSocket、不含定时轮询调度器**——双通道调度与本地缓存聚合留待段 4 与 UI 装配一起接线。

### 37.1 交付物（7 文件 = 3 段 × (源文件 + 测试)）

| 段 | 提交 | 文件 | 内容 |
|---|---|---|---|
| 3a | `1fc329a` | `lib/shared/config/connection_config.dart`(新) | `ConnectionConfig`：构造即校验（空白地址 / 越界端口抛 `ArgumentError`）、默认值与 `kDefaultHost`/`kHttpPort` 对齐、`baseUri`/`display` |
| 3a | `1fc329a` | `lib/shared/config/connection_config_store.dart`(新) | `ConnectionConfigStore` 端口 + `InMemoryConnectionConfigStore`（**不落盘**） |
| 3a | `1fc329a` | `test/shared/config/connection_config_test.dart`(新) | 10 例 |
| 3b | `c5c1e70` | `lib/shared/api/situation_models.dart`(新) | `SituationEnvelope`/`UnitState`/`HitEvent`/`TeamSummary`/`GeoPoint`/`HitEventPage` + 8 个契约枚举 |
| 3b | `c5c1e70` | `test/shared/api/situation_models_test.dart`(新) | 15 例 |
| 3c | `6b58f93` | `lib/shared/api/situation_api.dart`(新) | `SituationApi` 四只读端点 |
| 3c | `6b58f93` | `test/shared/api/situation_api_test.dart`(新) | 9 例 |

### 37.2 用例与判据

| 段 | 用例 | 判据 |
|---|---|---|
| 3a（10） | `defaults` 与契约常量一致且 `baseUri` = `http://127.0.0.1:11001`；现场地址 `host:port` 呈现；地址前后空白被裁剪；空白地址（空串 / 纯空格）抛 `ArgumentError`；端口 0 / 65536 抛 `ArgumentError`；端口 1 / 65535 合法；存储初始 `read` 为 `null`（回落 `defaults`）/ 写入可读回 / 二次写入覆盖 / `clear` 回 `null` | 默认值**逐字**等于契约常量；**构造即校验**（用户输入边界）；端口是硬约束，不提供改端口语义 |
| 3b（15） | 全量快照（必填 + `units` + `teamSummary` + `hits`）解析；增量（`changes`/`removedVestNums`）且未出现的可空集合落空列表；`running=false` 且 `exerciseId` 为 `null`；缺必填 `serverTime` → `TypeError`；`UnitState` 全字段（室内坐标 / 运动 / 心跳 / `deadAt`）；离线未定位 `position` 显式 `null`；未知 `status` → `FormatException`；缺 `revision` → `TypeError`；`GeoPoint` 空对象全 `null`；室外双坐标并存；危险物命中（`shooterNum=65535`、`shooterWeaponKind=GRENADE`）；缺 `seq` → `TypeError`；未知 `hitPart` → `FormatException`；`TeamSummary` 仅 `team` 必填；`HitEventPage` 的 `items`/`latestSeq` 与 `items` 缺省为空列表 | **不臆造缺省值**：必填缺失 → `TypeError`、形状不符与**未知枚举字面量** → `FormatException`、可空集合缺省 → 空列表；`GeoPoint` 契约未声明必填故全可空 |
| 3c（9） | `getSituation` 不带 `sinceRevision` → 路径 `/api/v1/situation` 且**查询串为空**、`Bearer` 注入、回全量；带 `sinceRevision`/`hitsLimit`/`includeSnapshotOnFirst=false` → 查询串逐项拼出；409 `SITUATION_REVISION_TOO_OLD` 带码；`getVestState` → `/api/v1/situation/vests/14`；404 带出服务端 `code`；`getTeamSummary` 直回数组（含空数组）；`listHits` 带 `sinceSeq`/`limit`；不带参数时不拼查询串 | 路径**逐字**对齐契约（自动带 `/api/v1`）；查询串**只拼非 null 参数**，缺省交由服务端默认值；非 2xx 统一 `ApiException`（`code` 稳定） |

### 37.3 口径要点

- **连接配置**：默认 `127.0.0.1:11001`（与 `servers` 一致）；现场主机 IP 由用户在设置页覆盖；
  存储**端口与实现分离**（`ConnectionConfigStore`），平台持久化（文件 / SharedPreferences）
  待后续授权引依赖后再换实现，本段不引任何第三方依赖（仍只 `dart:io`）。
- **同构承诺**：`situation_models.dart` 一套类型同时服务 HTTP Poll 与 WS 帧（硬约束），
  故 WS 落地时**不新增第二套解析**。
- **换代判据**：`SituationEnvelope.epoch` 与本地保存值不一致即视为服务端重启，
  本地 `sinceRevision` 作废、改全量重拉；`sinceRevision` 超窗则服务端回 409
  `SITUATION_REVISION_TOO_OLD`（Poll）/ close 4000（WS，段 4 落地）。
- **本段不做**：WS 连接、轮询调度、revision 缓存聚合、界面（均在段 4）。

### 37.4 门禁与边界

- **客户端门禁**：`flutter test` → `All tests passed!`（**73 例** = 段 1b 14 + 段 2b 24 + 段 3 34 + 模板 1）；
  `flutter analyze` → `No issues found!`；
- **Maven 侧无增减**：本段产物全在 `clients/scds_client`（Dart），**不进 reactor**，全量仍 **1151 项**（0/0/0）；
- **未改动**：后端任何模块、协议冻结字节、端口号（HTTP 11001 / UWB TCP 12001）、轮询周期、在线窗口、
  校验算法、`docs/api/**` 契约、迁移、`scds-solver`；**未新增依赖**（测试仍只用 `dart:io` + `flutter_test`）。

本段为**客户端契约层验收**，不是生产发布批准；测试用 `HttpServer` 打桩，**不代表已与后端联调**。


