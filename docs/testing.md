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
`getVestLink`/`getLinks`/`listSerialPorts` 与串口/UWB 开关仍为 501——`scds-gateway` 无主源码。
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
- `ApiSkeletonSmokeIT`（api，71）：`device/me` 不再是占位，501 形状改用仍为占位的 `devices/vests/{n}/link`。

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
`getVestLink` 仍为 501（`lastFrameType`/`framesTotal`/`droppedTotal` 在 `UnitState` 无数据源，单列任务）。
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
`getVestLink` 与 COMMAND / CALIBRATION 仍未落地，与本段无关。
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

8 类指令接口已落地 **7 条**：判死 / 判活 / 判伤 / 超范围 / 单兵充弹 / 全体充弹 / 覆盖配置。
`move`（手动置位）仍为 501——它依赖地图置位写入口，单列任务。

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
（401 / 400），不会被该判据放过。它的 501 断言用例仍用真占位端点 `GET /devices/vests/1/link`。
`move` 与 `getVestLink` 仍 501，与本段无关。`CommandService` 刻意不加 `@Transactional`：
物理下发已不可回滚，只把两个库写包成事务会给出一种并不存在的「整体回滚」错觉。

**已知缺口（本次不拦，单列任务）**：`t_plan_roster` 的 `hp` / `ammo1` / `ammo2` 在库层与 PLAN 写入侧
都没有范围校验（`ck_plan_roster` 只覆盖 `team` / `mode` / `vest_num`），而请求体侧的 `@Max` 又会被
「未传时取编成原值」这条路绕过；越界原值（如 hp=300）会在 14B 组帧时静默截断。`CONFIG` 也不写审计，
故这类覆盖没有留痕。

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

`move`（手动置位）、`getVestLink`、M3 CALIBRATION、M5 EXPORT 仍 501，与本段无关。

本段为**代码验收**，不是生产发布批准。

## 17. M3 CAL-1 UWB 室内解算链路

2026-10-27 按 M3 CALIBRATION 的**前置**工作包实施（`docs/implementation-plan.md` 第 91 行；
`docs/design/06-state.md` §4.6「CAL-1 落地补充」、§7 判据）。CALIBRATION 拆为
**CAL-1（解算链路）** 与 **CAL-2（标定本体）** 两段，CAL-1 再拆三步：
CAL-1a 解算器与单测 → CAL-1b 装配接线 → CAL-1c app 端到端。本节记录三段合起来的证据，
**其模块计数与总计取代第 16 节的表**；CAL-2 尚未开工。

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

**CAL-2 仍未完成**：三个标定端点去 501、进程内标定状态机、`V11` 校准列、现场实测长宽覆盖
写回 `uwb_meta`。`move`（手动置位）、`getVestLink`、M5 EXPORT 仍 501，与本段无关。

本段为**代码验收**，不是生产发布批准；UWB 盒子经 12001 的连续坐标、容量与长稳属现场待验收。
