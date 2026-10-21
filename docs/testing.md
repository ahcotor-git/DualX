# 测试策略、结果与发布门禁

> 核查日期：2026-10-21。代码基线：`da02622`；测试结束 12:58:42 +08:00。
> 本文区分自动测试、人工HTTP实测和待验证项目，不使用测试总数代替业务覆盖率。
> 第1至6节保留交接基线历史；SEC-01修复及13:36:58 +08:00的新验证结果见第7节。
> SEC-02字段/时间校验的新验证记录见第8节，后续结果不覆盖历史证据。

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
