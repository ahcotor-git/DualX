# 测试策略、结果与发布门禁

> 核查日期：2026-10-21。代码基线：`da02622`；测试结束 12:58:42 +08:00。
> 本文区分自动测试、人工HTTP实测和待验证项目，不使用测试总数代替业务覆盖率。
> 第1至6节保留交接基线历史；SEC-01修复及13:36:58 +08:00的新验证结果见第7节。
> SEC-02字段/时间校验的新验证记录见第8节，后续结果不覆盖历史证据。
> ERR-01见第9节；TEST-01数据库隔离及521项最新全量结果见第10节。
> M2 LEDGER台账/基站/绑定与681项最新全量结果见第12节。

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
| T-PUSH | 同构恢复 | 固定屏障首帧、订阅间隙、window超窗、跨代epoch、RESYNC、PING | 未实现 |
| T-BACKPRESSURE | 背压 | 慢客户端独立、满队列、全量后不回退、线程退出、30s无上行关闭 | 未实现 |
| T-STOP | 生命周期 | HTTP/WS/串口/TCP/DB队列完整关闭并立即重启 | 只人工启动/停止基础HTTP |
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
