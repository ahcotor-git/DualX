# scds-next 全量实施计划

> 编写日期：2026-10-21，Asia/Shanghai。源代码基线：da02622。
> 状态：待用户审阅。本轮授权仅为整理计划和关键决策，不授权业务实施、提交或推送。
> 本文不是新的接口契约，不代表未确认方案已获批准，也不是生产验收证明。

## 1. 范围与依据

目标是完成本仓库的 Java 边缘后端、硬件 gateway、Flutter Android 单兵端和 Windows 指挥端，
以及联调、打包、部署和验收资料。客户端分包方式仍为候选，见决策 D12。
不自动增加云管 License/OTA、Web 控制台、账号注册/找回、SSO 或其他操作系统客户端。
后端独立运行在业务承载节点，不能默认把 Java 服务嵌入 Windows 客户端。

权威入口：

- [交接快照](../snapshot.md)：实现状态、授权和冻结边界。
- [需求](requirements.md)、[架构](architecture.md)、[审查清单](review.md)。
- [环境](environments.md)、[测试策略](testing.md)。
- [OpenAPI](api/openapi.yaml)：唯一 HTTP/WS 契约入口。
- [鉴权](design/03-auth.md)、[自愈](design/01-exercise-liveness.md)、[推送](design/02-push-ports.md)。
- [决策清单](decision-register.md)：未确认项、推荐方案和阻塞阶段。

## 2. 当前基线

| 项 | 2026-10-21 核查事实 |
|---|---|
| Git | main / da02622；本地跟踪 origin/main=3c4f4f4，领先 3 个提交；实际远端未能核验 |
| 用户已有改动 | 6 个已跟踪文件修改、9 个未跟踪文档，暂存区为空；全部保留 |
| 后端 | Java 21、Boot 3.5.7；协议、仓储、AuthService、签验基础已实现 |
| 未完成 | 66 REST 业务方法占位；gateway 无主源码；SID、自愈、态势和推送业务未实现 |
| 数据库 | 本机 MySQL 8.4.11 healthy；V1-V7、V9；18 张业务表加 Flyway 历史表 |
| 自动测试 | 最近全量报告 309 项，失败/错误/跳过均 0；本轮未重跑，不是完整产品验收 |
| Flutter | 3.47.7 stable、Dart 3.13.5；仓库没有 pubspec.yaml |
| Android | SDK 36.0.0、许可证已接受；emulator-5554 / Android 16 可用 |
| Windows | flutter doctor 报缺少 Visual Studio 和 Desktop development with C++ 工作负载 |
| 网络 | GitHub 连通性检查超时；依赖下载、推送仍需逐次验证 |
| 发布 | 不可生产发布 |

本轮没有修改实际 .env，没有安装 Visual Studio、创建数据库、运行迁移或生成 Flutter 工程。
Flutter doctor 的通过项只证明工具发现和基础检查，不证明 App 已构建或联调。
历史文档快照不改写为本轮新证据。

## 3. 不可变约束

1. HTTP/WS 11001、UWB TCP Server 12001；不得为开发方便改变服务端固定端口。
2. WS 已连接仍保持 Poll；态势常规约 2s，WS 兜底不超过 10s；两通道业务载荷同构。
3. 最后心跳距今 <=5000ms 在线；阵亡冻结最后有效坐标和 motion=FROZEN，判活才解除。
4. 马甲 ASCII 上行/二进制下行、UWB 41B、校验、偏移、字节序、算法和参数默认值冻结。
5. gateway 是唯一硬件事件源；gateway/core 不直接互依；共享模型及 SPI 在 domain。
6. RFC3339 UTC、数据库 datetime(3) 存 UTC、t_ms INT、JDBC rewriteBatchedStatements=true。
7. 错误为 RFC7807 Problem，含 code/traceId；字段错误含 errors，不能退化成框架默认错误。
8. refresh 保留 token_hash 定位；logout 无请求体、撤全账号 refresh；access 不主动撤销。
9. 自愈先写 SYSTEM 存证再按场次删残留指令，同事务；不增加协议 LogType。
10. 不改历史迁移、不补低版本 V8、不默认 out-of-order、不清库删卷或覆盖凭据。

## 4. 执行规则

- 每阶段先确认方案；每原子任务的源码、测试、配置、契约和文档合计最多 3 个修改文件。
- 下表是工作包，不是允许一次修改整个包；开工前必须列出具体绝对路径白名单。
- 每个原子任务单独记录需求、契约、批准范围、测试命令、结果、环境和遗留问题。
- 如测试需要第四个文件，先拆为可独立验收的子任务；不得先超限再补手续。
- Flutter 自动脚手架会生成很多文件，不能直接运行 flutter create 绕过限制，见 D13。
- 同一修复最多 5 轮；超过上限先报告失败证据和新方案，不扩范围试错。
- 提交、推送和安装软件均需明确授权；没有授权就保留工作树成果。
- 不以记忆中的旧授权代替当次授权；网络失败不得声称已入远端。
- 不使用生产库运行 IT；新隔离库或容器的创建、凭据及生命周期先确认。
- 无硬件时可以做明确标注的仿真；不得把仿真通过写成现场通过。

建议状态：待决策、待批准、实施中、待验证、代码验收通过、现场待验收、发布验收通过。
不能用一个“完成”同时表示代码、硬件和发布全部通过。

## 5. 工作包与依赖

| 阶段 | 工作包 | 内容和完成门禁 | 前置 |
|---|---|---|---|
| M0 | SEC-01 | metrics 根和子树 ADMIN 授权；真实安全链、真实签名和 Actuator 回归 | 单独批准现有 3 文件方案 |
| M0 | SEC-02 | 过期/无效分类、必需 claims 与类型边界；错签、nbf、issuer、算法回归 | D01 |
| M0 | ERR-01 | MVC、校验、JSON、404/405、上传、媒体类型的统一 Problem | 明确 Boot advice 整合方案 |
| M0 | TEST-01 | 独立测试数据库或容器、数据归属和清理策略、完整应用启动测试 | D14 |
| M1 | AUTH-HTTP | 复用 AuthService 接 login/refresh/logout/me；条件路由、DTO 安全映射 | M0，D02 |
| M1 | DEVICE-AUTH | 设备登录、绑定检查、SID 单端在线、事务失败及主动缓存失效 | D02、D03 |
| M1 | AUTH-HARDEN | 并发限流预算、DB 身份归一化、配置校验和服务端权限 | D01、D04；先验证审查静态风险 |
| M2 | LEDGER | 马甲台账/基站、逻辑注销、引用保护、密钥管理闭环 | M1，D03、D04 |
| M2 | MAP-BASE | 地图 CRUD、图片、楼层点位、引用和文件安全 | M1，D09、D15 |
| M2 | PLAN | 想定 CRUD、编成整体替换、CSV/XLSX 模板和整文件导入 | LEDGER、MAP-BASE，D04 |
| M3 | LIFE-DDL | 下一递增迁移、领域 SYSTEM 类型、owner/heartbeat/endReason 映射 | D05；不改变协议 |
| M3 | HW-LINK | 串口枚举/独占、马甲上下行、UWB TCP、链路状态和释放 | D06、D10；先制定 SPI 分段方案 |
| M3 | STATE | 单一状态所有者、有界事件处理、5000ms、冻结、命中和批量轨迹 | HW-LINK，D10、D11、D15 |
| M3 | LIFE | 开停演、唯一索引、心跳、自愈、恢复门禁和单实例保护 | PLAN、LIFE-DDL、STATE，D11 |
| M3 | COMMAND | 8 类接口、覆盖配置、受理/发送/回执、过期和审计 | LIFE、HW-LINK，D04、D11 |
| M3 | CALIBRATION | UWB 几何校验、中心基准校准、异步进度与取消/清理 | MAP-BASE、HW-LINK，D15 |
| M4 | PUSH-MODEL | domain 载荷和推送 SPI、Poll 查询、链路同构 | STATE，D07、D08 |
| M4 | PUSH-WS | 首帧屏障、补齐、超窗、跨代恢复、背压、保活和停机 | PUSH-MODEL，D08、D10 |
| M4 | DEVICE-SELF | 态势就绪后接 device/me；单兵数据裁剪和越权测试 | STATE、DEVICE-AUTH，D04 |
| M5 | REPLAY | 复盘列表/详情、游标轨迹、抽稀、统计、冻结结局 | LIFE、STATE；UTC 和 t_ms 不变 |
| M5 | EXPORT | CSV/XLSX 异步导出、并发合并、进度、授权下载和过期 | REPLAY，D09、D10 |
| M6 | CLIENT-BASE | Flutter 工程、共享契约模型、网络、Problem、安全存储、身份和配置 | M0/M1，D12、D13 |
| M6 | CLIENT-ANDROID | 设备登录、自身/批准可见范围、地图、生命周期和断线恢复 | DEVICE-SELF、MAP-BASE，D04 |
| M6 | CLIENT-WINDOWS | 指挥台、台账、地图/标定、想定、指令、态势、复盘和导出 | M2-M5；Windows 构建环境补齐 |
| M6 | CLIENT-RESILIENCE | 持续 Poll、WS 同源归并、重连、刷新单飞、退出竞态、缓存隔离 | M4，D08、D12 |
| M7 | RELEASE | 后端和客户端制品、独立密钥、TLS、部署、备份恢复、长稳和现场验收 | 所有强制范围及 D10 决策完成 |

阶段按依赖推进，不将 M3 的物理发送交给数据库事务“自动回滚”。
LIFE 的状态转换、硬件发送和持久化顺序必须在 D11 明确后再实现。
客户端模型/网络可在稳定的鉴权契约就绪后开始；不能先用假数据宣称业务端到端完成。
本计划不要求启动并行子代理，也不自动放宽文件修改白名单。

## 6. 全部契约操作覆盖

以下从现有 paths 文件核对，66 REST + 2 WS，最终逐 operationId 验收。
当前全部列为待功能验收；已有核心服务和骨架不计作 HTTP 功能完成。
若批准新增绑定或身份可见性接口，先更新唯一 OpenAPI，再更新此清单计数。

| 域 | REST 数 | operationId |
|---|---:|---|
| auth | 6 | login、refreshToken、logout、getCurrentUser、deviceLogin、getDeviceSelf |
| exercise | 13 | listPlans、createPlan、getPlan、updatePlan、deletePlan、getRoster、replaceRoster、downloadPlanTemplate、importPlan、getCurrentExercise、startExercise、stopExercise、listCurrentExerciseLogs |
| command | 8 | killVest、reviveVest、injureVest、outOfRangeVest、reloadVest、reloadAllVests、setVestConfig、moveVest |
| situation | 4 | getSituation、getVestState、getTeamSummary、listHits |
| device | 14 | listVests、createVest、getVest、updateVest、deleteVest、getVestLink、listAnchors、upsertAnchor、getLinks、listSerialPorts、openVestSerial、closeVestSerial、openUwbLink、closeUwbLink |
| map | 14 | listMaps、createMap、getMap、updateMap、deleteMap、uploadMapImage、getMapImage、listFloorPoints、replaceFloorPoints、getUwbMeta、putUwbMeta、startUwbCalibration、getUwbCalibration、clearUwbCalibration |
| replay | 7 | listReplays、getReplay、getReplayTimeline、getReplayStatistics、exportReplay、getExportTask、downloadExport |
| WS | 2 个握手 | watchSituation、watchLinks；不计入 REST 数 |

所有写接口均验证权限、输入边界和失败状态；所有列表验证过滤、分页/游标和字段暴露。
moveVest 只供批准的训练/联调范围，正式演习拒绝，不能当作生产定位替代品。
getDeviceSelf 当前阶段留 501 的决定继续保留，到 M4 有态势后才提接线方案。

## 7. Flutter 功能范围

候选结构为共享 Dart 契约/通信包加两个角色入口，最终由 D12 决定。
Flutter 不重新实现 UWB 解算、地图坐标换算、阵亡判定和权限规则。

| 能力 | Android 单兵端 | Windows 指挥端 |
|---|---|---|
| 连接配置 | 实例地址、网络错误和安全提示 | 实例地址、签发能力配置和健康诊断 |
| 身份 | 马甲编号/绑定密钥登录、SID 顶替提示 | 账号登录/刷新/me、无请求体 logout |
| 安全存储 | 平台安全存储，禁止 token/密钥入日志 | 平台安全存储，账号/实例切换清理 |
| 态势 | 自身和批准的数据范围；不连统一态势 WS | 全量/增量、队伍汇总、命中流水、日志 |
| 地图 | 只读显示已批准可见坐标 | 底图/楼层、基站标定、态势叠加 |
| 管理 | 不提供指挥和台账写入口 | 按批准角色提供台账、想定、编成、链路操作 |
| 指令 | 只读状态 | 判死/活/伤、越界、充弹、配置及受理反馈 |
| 复盘 | 不默认授予 | 轨迹分片、暂停/变速/定位、统计、导出 |
| 生命周期 | 切后台/恢复、进程重启、过期重登 | 多窗口/关闭资源、网络恢复、实例切换 |

持续 Poll 约束适用于相应状态通道；设备端当前契约为 HTTP 查询，不擅自开放统一 WS。
Android 后台计时可能受系统限制，必须明确支持前台还是需要后台持续服务，不承诺普通 timer 能后台常驻。
Android 模拟器连接本机服务时验证宿主机路由；可以另行批准仅针对 11001 的 adb reverse，
不把服务端改为其他端口，也不未经批准开放到局域网。
Windows 页面测试不能用 Chrome 替代 Windows 桌面构建验收。

## 8. 验收层级

1. 单元：可控时钟覆盖 4999/5000/5001ms；冻结/判活、队列、游标和归并边界。
2. 数据库：隔离 MySQL；事务失败、并发唯一性、SID 更新、SYSTEM 存证/删指令一致性。
3. 安全/MVC：生产 filter chain 和真实签名 JWT；精确断言状态、Problem 和安全字段。
4. 硬件仿真：经真实 gateway 成帧/发送，不绕过 SPI 直接改状态；黄金字节和算法旧新对拍。
5. 双通道：真实 WS 握手/帧、HTTP Poll、屏障快照、跨代恢复、慢连接、30s 保活和停机。
6. 客户端：flutter analyze/test；Android 模拟器及真机、Windows 原生构建和真实后端联调。
7. 发布：11001/12001、离线启动、生产密钥拒用开发钥、TLS、容量长稳、崩溃与备份恢复。

每层记录真/仿真、commit 和工作树、命令、报告、清理和剩余限制。
BUILD SUCCESS、路由非 404、页面可见、仿真成功不能替代后续层。
集成测试真实提交时清理只针对本轮专属数据，禁止全表清空。

## 9. 首个原子任务 SEC-01

状态：方案已提出，待独立实施批准，不因“授权开始整理计划”自动进入业务实施。

| 文件白名单 | 改动 |
|---|---|
| scds-api/src/main/java/com/dualx/api/security/SecurityConfig.java | GET metrics 根与 /actuator/metrics/** 均要求 ADMIN，保留其他规则 |
| scds-app/src/test/java/com/dualx/security/SecurityMetricsIT.java | 新增生产安全链、生产 decoder、真实 Actuator 自动回归 |
| docs/testing.md | 在现有文档上追加本任务证据，不改历史事实 |

测试上下文采用 Boot Web + AutoConfigureMockMvc，加载生产 SecurityConfig、JwtDecoderConfig、
TraceIdFilter、401/403 handler 和真实指标端点；显式隔离 DataSource/Flyway/建号/硬件。
测试内生成 RSA 密钥，真实签名请求；不使用 standalone、mock decoder 或预置认证。
MockMvc 不绑定 TCP 端口；真实监听端口启动测试属于后续完整应用验收。

- signing=true/false 两种配置均测试，测试签发独立于被测实例能力，不改变设备签发策略。
- 无令牌/错签：根和指标详情 401 / AUTH_TOKEN_INVALID。
- VIEWER/DIRECTOR/OPERATOR：根、详情、tag 查询和不存在的指标子路径 403 / AUTH_FORBIDDEN。
- ADMIN 账号令牌：根和 jvm.memory.used 详情 200，并验证实际指标字段。
- 普通 device/refresh 用途令牌：根和详情 403；不伪造 ADMIN 角色扩大本任务范围。
- health 无令牌仍 200；401/403 正文和 X-Trace-Id 一致，Problem 字段完整。
- 先运行旧规则下应失败的回归，再改规则验证；最后全量 verify。

全量 verify 会包含现有开发库 IT 的真实提交/清理，实施批准时同时明确这一测试范围。
本任务不修 SEC-02、ERR-01、AUTH 接线等其他问题。

## 10. 本轮交付和下一步

本轮仅新增本文件及 decision-register.md，业务代码、契约、快照和实际 .env 不变。
先审阅计划及决策，然后单独批准 SEC-01；其他决策可按阻塞阶段分批确认。
各工作包的具体文件白名单、详细实现和估算在临近开工且前置决策完成后提供，
不虚构整个产品的固定完成日期，也不以计划批准代替逐阶段授权。
