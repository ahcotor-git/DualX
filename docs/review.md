# 当前设计与代码全盘审查

> 审查日期：2026-10-21，Asia/Shanghai。代码基线：`da02622bdca893ddb60910de20efd4301da2116f`。
> 范围：模块依赖、全部契约域、迁移/约束、配置与环境、关键协议/鉴权/生命周期/推送代码、
> 测试集及旧算法迁移差异。包含全量构建和关键HTTP真实复现，不是正式渗透/CVE审计或现场验收。
> 本次只改文档、契约说明与环境模板，不修业务代码，不自动替未确认设计作决定。

> **现状更新（2026-10-27）**：本文是 2026-10-21 的时点审查（基线 `da02622`），只反映当时状态。
> 此后 SEC-01/SEC-02/ERR-01、TEST-01、AUTH-HTTP/DEVICE-AUTH/AUTH-HARDEN、M2 LEDGER、M2 PLAN、
> M2 MAP-BASE、M3 LIFE/STATE/COMMAND 与 M4 PUSH-WS/DEVICE-SELF/T-STOP 各工作包相继落地；
> 表中 R07（`device/me` 与 SID 单端在线）、R08（auth 与 60 条业务方法占位、gateway 零源码）、
> R12（链路两通道根类型）、R13（revision 跨代恢复）、R17（无真实 Security/WS/完整 app 自动测试）、
> R21 中 COMMAND 相关部分（`t_exercise_command` 的状态与 `COMMAND_*` 码映射）**均已被取代**，
> 逐项证据见 [testing.md](testing.md) 第 7~15 节与第 3 节测试矩阵。其余条目未被重新核验，
> 仍以本文为准（尤其 R05、R06、R26 Flutter 客户端、R27 容量与保留）。本文保留为历史记录。

## 1. 结论

项目是可构建的多模块后端基础工程，协议、仓储和部分账号核心能力已验证，
不是可上线的完整实兵演习产品。主要阻塞来自实际安全/错误路径缺陷、
尚未实现的gateway/演习/态势链，以及候选设计与冻结契约的冲突。

309项自动测试全部通过，但其中API冒烟没有加载Security，且“非404”会放过500；
本次真实HTTP复现出多个测试未捕获的问题。不能将green build作为放行依据。

优先级：P0为发布硬阻塞；P1为应先修的可靠性/安全/契约问题；P2为进入相关工作包前补齐。
区分“缺陷”与“计划能力未实现”：后者不认定为本批代码引入回归，但同样不能宣称已完成。

## 2. 已复现缺陷

| ID | 优先级 | 事实、影响与修复方向 | 证据 |
|---|---|---|---|
| R01 | P1 | 指标只保护精确/actuator/metrics，VIEWER访问jvm.memory.used子路径得到200。匹配整个metrics子树并加载真实安全链回归，不能只测根路径403 | [SecurityConfig:99-100](../scds-api/src/main/java/com/dualx/api/security/SecurityConfig.java#L99-L100)、RT-METRICS-DETAIL |
| R02 | P1 | 正确签名、exp已过300s的JWT得到401 INVALID，而目标EXPIRED。EntryPoint只识别Spring JwtValidationException，手搭Nimbus processor还会先处理时间claims。统一时间校验入口或明确异常分类，必须用生产decoder真实过期令牌验证 | [decoder:98-100](../scds-api/src/main/java/com/dualx/api/security/JwtDecoderConfig.java#L98-L100)、[EntryPoint:62-70](../scds-api/src/main/java/com/dualx/api/security/ApiProblemAuthenticationEntryPoint.java#L62-L70)、RT-EXPIRED |
| R03 | P1 | 缺exp的合法签名账号用途JWT通过认证并进501占位。仅安装默认时间validator不等于强制exp存在。新增用途级必需claims、类型和边界验证，至少exp/sub/aud；设备还要sid | [decoder:59-62](../scds-api/src/main/java/com/dualx/api/security/JwtDecoderConfig.java#L59-L62)、RT-CLAIMS |
| R04 | P1 | POST device/login JSON={}得到Boot默认400 Problem，无code/traceId，违反统一错误硬约束。自定义Advice在standalone能工作，不代表与Boot ProblemDetails advice共存时生效；调整异常出口优先级/整合策略，覆盖所有Spring MVC错误 | [ApiExceptionHandler:29-46](../scds-api/src/main/java/com/dualx/api/error/ApiExceptionHandler.java#L29-L46)、[application.yml](../scds-app/src/main/resources/application.yml)、RT-VALIDATION |
| R05 | P1 | signing=false时无令牌POST auth/login得到401，目标404；AuthController无条件注册、过滤链又只在true时放行。条件Controller和“缺席路由也返回404”的授权顺序要一起解决，测带token/不带token两种情况 | [SecurityConfig:92-96](../scds-api/src/main/java/com/dualx/api/security/SecurityConfig.java#L92-L96)、[AuthController](../scds-api/src/main/java/com/dualx/api/auth/AuthController.java)、RT-DISABLED |

上述401/403响应中实际存在X-Trace-Id及Problem.traceId；R04是MVC路径不一致，
不能误判为TraceIdFilter没有执行。复现记录详见 [testing.md](testing.md)。

## 3. 设计与实现发布阻塞

| ID | 优先级/类别 | 事实、影响与下一动作 |
|---|---|---|
| R06 | P0 设计冲突 | “设备登录恒可用”与当前signer false禁用三类签发冲突；设备登录确实需要私钥。先确认独立设备能力还是禁用该端点，不能直接接成500 |
| R07 | P0 未完成安全链 | DeviceAuthService、SID校验/30s缓存主动失效未实现；不存在SID的device token实测进入device/me占位。补单端在线、停用/换绑和事务失败回归，device/me当前仍按裁定留501 |
| R08 | P0 未完成业务 | 6个auth HTTP方法均501；AuthService并未公开接线。其余60个REST业务方法也为占位，gateway为0源码，没有真实演习/态势链 |
| R09 | P0 迁移阻塞 | 本地已经应用V9，V8不存在。直接补V8不能作为正常顺序升级：会成为低于当前版本的未应用迁移并受到Flyway校验/顺序限制。建议确认下一递增V10，禁止改历史迁移或默认开启out-of-order |
| R10 | P0 自愈未实现 | owner_instance/heartbeat/end_reason与SYSTEM日志不支持；原方案“标记但不删除”已被确认决定替代。必须迁移、非协议日志类型、JDBC映射、存证后按场次清理同事务 |
| R11 | P1 启动设计 | ApplicationRunner不是ApplicationReadyEvent之后，更不保证监听端口不收请求；必须恢复门禁。第二进程连同库时无条件收尾会误停活跃场次，单实例保护属于方案前提 |
| R12 | P1 契约冲突 | 链路HTTP数组、WS单对象、候选首帧数组/增量对象不满足同构。须确认统一数组快照或信封，修改契约前不能直接实现不同根类型 |
| R13 | P1 恢复设计 | revision重启归零，旧N与新进程N重叠时仅数值窗口判断会漏掉跨代。须确认epoch或每次重连全量规则；订阅/快照间隙也要并发测试 |
| R14 | P1 授权未完成 | 大多数接口只检查account aud，尚无写权限及设备字段裁剪。RolePermissions是返回给UI的映射，不是服务端强制授权；接口接线前配明确角色矩阵 |
| R15 | P1 网关契约冲突 | DeviceLinkAdmin已打开重开的注释，与契约409 ALREADY_OPEN冲突；契约允许UWB SERIAL而端口仅TCP，HTTP可手动开TCP与旧“开演才listen”规则也需统一 |
| R16 | P1 离线能力描述 | 非空jwks-uri优先远端验签，没有本地降级，jwks-refresh-ms无消费者。严格离线只用本地PEM且jwks-uri空；不得声称多钥PEM链或自动刷新已支持 |
| R17 | P1 测试缺口 | 无真实Security/WS/完整app自动测试；19协议测试不等于solver数值对拍。已人工启动也不等于物理断网、真驱动、现场长稳、生产节点验证 |

主要证据：
[V4](../scds-core/src/main/resources/db/migration/V4__exercise.sql)、
[V9](../scds-core/src/main/resources/db/migration/V9__auth_tokens.sql)、
[DeviceLinkAdmin](../scds-domain/src/main/java/com/dualx/core/port/DeviceLinkAdmin.java)、
[链路契约](api/paths/device.yaml)、
[推送设计](design/02-push-ports.md)、
[设备会话仓储](../scds-core/src/main/java/com/dualx/core/repository/VestDeviceSessionRepository.java)。

## 4. 静态审查确认项与待验证风险

以下有具体静态证据，但本次没有为每项增加运行时复现或生产负载验证。

| ID | 优先级 | 事实与建议 |
|---|---|---|
| R18 | P1 | AuthService限流检查在账号锁之前，多个并发请求可同时通过未锁定判定，再排队做BCrypt，绕过“第6次校验前拒绝”时序。重新核对原子预算/锁定检查位置，补并发IT |
| R19 | P1 | DB使用utf8mb4_0900_ai_ci，账号查找忽略大小写/重音；LoginThrottle键使用原字符串。变体失败分散到不同账号桶，需与DB身份规则一致的归一化/账号ID限流，测试IP阈值不足以替代账号阈值 |
| R20 | P2 | 不存在账号/停用时短路不做BCrypt，存在错误口令时做；统一响应文案仍有时序差异。评估dummy hash及请求成本边界，密码长度约束也未完整定义 |
| R21 | P1 接线前 | BusinessException仅映射AUTH等少数码，EXERCISE/COMMAND等默认500。相关服务落地时逐项映射契约404/409/400，不按前缀猜，增加表驱动用例 |
| R22 | P1 接线前 | 台账删除契约是逻辑删除，VestRepository.deleteByVestNum为物理删除；schema无外键，引用检查与演习期间禁止注销必须由服务负责，不能将仓储方法直接暴露 |
| R23 | P2 测试隔离 | IT与开发共库；AuthServiceIT真实提交且AfterEach清理，异常杀进程可留残数据。建议专用测试库；测试连接默认无rewrite参数，不能作为性能基线 |
| R24 | P1 发布前 | 无生产配置/应用部署制品定义/服务管理脚本、TLS/备份恢复验证；mysql:8.4是可漂移标签，公开dev密钥没有生产拒用门禁。所有生产项需先明确标准后实现 |
| R25 | P2 配置事实 | deployment-mode未消费、硬件开关未实现、UWB配置未绑定Spring bean；solver banner仍有modeEn/uwbEn重复与旧串口标识。不能把这些日志当真实链路状态 |
| R26 | P1 客户端 | 无Flutter工程；单兵队伍视图、持续Poll与安全存储/生命周期未实现，不能开始“正式客户端验收” |
| R27 | P2 容量/保留 | TTL配置缺正值约束，失败限流桶无硬容量上限，refresh记录未定清理期限，队列/客户端上限及RPO/RTO未定。需配置校验与容量试验，不凭量小推导安全 |
| R28 | P1 DTO接线 | AccountSession包含Account.passwordHash；必须手动映射User安全字段，禁止直接返回内部载体或把JWT/口令写日志 |

静态入口：
[AuthService:80-94](../scds-core/src/main/java/com/dualx/core/auth/AuthService.java#L80-L94)、
[LoginThrottle](../scds-core/src/main/java/com/dualx/core/auth/LoginThrottle.java)、
[V1](../scds-core/src/main/resources/db/migration/V1__auth.sql)、
[VestRepository](../scds-core/src/main/java/com/dualx/core/repository/VestRepository.java)、
[AccountSession](../scds-core/src/main/java/com/dualx/core/auth/AccountSession.java)、
[AuthJwtProperties](../scds-api/src/main/java/com/dualx/api/security/AuthJwtProperties.java)。

## 5. 本次已完成的文档修复

| 修订 | 说明 |
|---|---|
| 设计01 | 修正Runner时机、单实例前提、SYSTEM存证再删；登记V8/V9版本阻塞及真实进度 |
| 设计02 | 更新端口清单，登记链路根类型、跨代revision与订阅间隙；修正窗口时间推导 |
| 设计03 | 更新Security/AuthService事实；hash刷新、全端退出、明文首次建号、公开开发钥、单把PEM实际限制 |
| auth契约说明 | 同步已确认logout范围与refresh过期/无效区分；不加请求体、不修改路由/schema |
| common响应结构 | 将Retry-After headers从错误的content内部移到Response Object层级；未改变目标业务语义 |
| .env.example | JDBC示例保留rewrite参数，解释profile激活与运行目录；未改实际.env |
| 交接文档体系 | 需求、架构、环境、测试、审查、snapshot及新对话提示词 |
| 协议原文 | 按原字节复制PROTOCOL.md入本仓库；SHA256校验一致，不编辑冻结内容 |

尚未进行完整OpenAPI专业lint或Flutter/生产环境检查；文档链接及$ref校验记录在快照。
本次文档修改未自动提交/推送，不改变源代码HEAD，不能写成已入远端。

## 6. 可信现状与优化方向

已验证良好基础：gateway/core无直接互依、协议黄金回归、算法迁移仅包名/import差异、
账号刷新独立事务与锁、日志traceId最高优先级、JDBC批量写接口与UTC显式序列化。
这些基础可保留，不需为了“全盘审查”再次全量重写或引新中间件。

优先把安全与错误契约变为真实过滤链测试，再完成账号/设备接线，
而非先扩模块数量或先做UI。之后按迁移、自愈、网关事件源、核心态势、
推送与其它业务闭环推进。合适的优化是边界清晰和可观察的背压，不是未经证据的算法调参。

## 7. 后续任务与验收闭环

| 工作包 | 先处理 | 前置决定 | 必需证据 |
|---|---|---|---|
| SEC-01 | R01指标子树保护 | 无新业务决定 | VIEWER拒绝，ADMIN允许，真实filter链 |
| SEC-02 | R02/R03 decoder与claims | 必需claims清单 | 真实签名、expired/nbf/缺字段/类型错误 |
| ERR-01 | R04与上传/框架异常 | 是否整合Boot advice | 真实MVC400/404/405/上传均code+traceId |
| AUTH-01 | R05/R06/R07/R08 | 设备签发能力 | 条件路由、SID顶替、HTTP登录/退出/me、字段不泄露 |
| AUTH-02 | R18/R19及权限 | 归一化、角色写权限矩阵 | 并发锁定/大小写、设备范围与写授权 |
| LIFE-01 | R09/R10/R11 | 下一迁移号、endReason契约 | 空库/已有V9升级、恢复门禁与存证事务 |
| HW-STATE | R15与状态/硬件源 | 链路模式/启停/容量 | 驱动或仿真、5000ms/FROZEN、下行字节回执 |
| PUSH-01 | R12/R13 | 根类型/epoch/超窗表达 | 同构、首帧无间隙、跨代恢复与背压 |
| PRODUCT | R21-R28 | 文件、客户端、容量与部署 | 业务、Android/Windows、生产/恢复专项 |

每工作包先分解 <=3 文件原子任务，给方案、影响范围与测试口径后等待确认。
不得本轮顺手修代码；具体下一步启动文本见 [next-chat-prompt.md](next-chat-prompt.md)。
