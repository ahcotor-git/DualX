# scds-next 实施前决策清单

> 编写日期：2026-10-21，Asia/Shanghai。基线：da02622。
> 状态：当前批准范围见第6节；其余推荐不是批准。计划见 [implementation-plan.md](implementation-plan.md)。
> 已确认的冻结规则和认证/自愈决定不重新投票，不因本文而失效。

## 1. 授权与确认方式

本轮“授权开始”承接的是整理全量计划及关键决策，没有授权全部业务实施或自动提交推送。
推荐逐阶段确认，避免要求一次回答所有问题：

1. 现在：审阅全量计划，单独批准 SEC-01 的 3 文件实施和测试范围。
2. 安全/认证前：D01-D04；测试数据库前另确认 D14。
3. 硬件/自愈/推送前：D05-D08、D10-D11、D15。
4. 文件/客户端/交付前：D09、D12-D13，补齐 D10 容量和运维指标。

确认记录必须包括决策 ID、选择、限制、日期和用户授权范围；
任何未记录的条目仍是待确认，不能把没有反对视为同意。
提交/推送、安装工具链和修改已有部署凭据仍需单独授权。

## 2. 优先决策

| ID | 问题及证据 | 推荐方案 | 替代和影响 | 阻塞 |
|---|---|---|---|---|
| D01 | 必需 JWT claims、账号身份归一化及请求成本。审查 R02/R03/R18-R20/R27 | 分开确认：先明确三种用途的 exp/sub/aud/issuer 和设备 sid 类型/格式；限流以 DB 账号身份对齐并做并发预算；密码长度/桶容量另列参数 | 不直接全量重写 AuthService；大小写转换不足以自动对齐 MySQL 忽略重音规则。新增字段要求涉及云侧签发兼容性 | SEC-02、AUTH-HARDEN |
| D02 | signing=false目前拒绝三类签发，却又放行设备登录，R06 | 2026-10-21用户确认：账号由云端签发、边缘只验签；设备由边缘本地签发，支持断外网登录 | 独立能力、密钥/issuer与用途信任方案见03b；业务选择已确认，具体配置/兼容及边缘me身份策略待批准 | AUTH-HTTP、DEVICE-AUTH |
| D03 | 当前 device DTO/paths 没有绑定密钥创建、换绑或解绑入口 | 经批准新增 ADMIN 管理的绑定/换绑/解绑契约，密钥只写入散列，不在详情回显；事务提交后主动失效 SID 缓存 | 部署工具管理绑定可少加接口，但需明确运维流程，指挥台不能假装已能绑定。SIM 明文方案的威胁和来源需独立确认 | DEVICE-AUTH、LEDGER、单兵登录交付 |
| D04 | 写接口权限矩阵和单兵可见性尚未明确，R14/R26 | 先做每 operationId 服务端角色矩阵；单兵第一版仅自身，未来队伍范围须独立契约 | 完整“己方全队、敌方仅阵亡”需要授权查询入口和字段裁剪，不是前端拿全量后隐藏。台账/地图/想定写角色不能从读权限猜 | AUTH-HARDEN、业务写接口、DEVICE-SELF |

SEC-01 不依赖 D01-D04；确认首个安全修复后可先完成，不必等待所有产品决策。
D01 推荐不是一揽子批准；具体 claims 清单和限流策略应在对应任务方案中逐项展示。

## 3. 自愈、硬件与恢复

| ID | 问题及证据 | 推荐方案 | 替代和影响 | 阻塞 |
|---|---|---|---|---|
| D05 | V9 已应用、V8 不存在；SYSTEM 和 endReason 未落地，R09-R11 | 下一递增迁移拟用 V10；领域独立 SYSTEM 类型；对外展示 endReason；恢复失败拒绝开演并实施同库单进程保护 | 只内部保存 endReason 可少改响应，但客户端无法区分异常结束。下一版本号须开工前再查，若已被占用则重新确认 | LIFE-DDL、LIFE |
| D06 | UWB SERIAL/TCP、手动开链路与开演绑定、已开重开冲突，R15 | 马甲串口 + TCP 12001 先交付；手动开启链路，开演检查就绪，已开返回契约 409；SERIAL 交付时点须确认 | 若 SERIAL 必须首版实现则增加 SPI/适配器/配置测试；若延后必须明确契约和 UI 的未交付状态。若仅开演才监听须先改契约 | HW-LINK、LIFE |
| D07 | 链路 Poll 是数组、WS 是对象，不满足同构，R12 | 两通道均为完整 List<LinkStatus> 快照，变更时广播列表；不增加链路 revision | 共同信封可加入 version/时间，但需要新增 schema 和客户端协议；两种都须先批准 OpenAPI 变更 | PUSH-MODEL、链路客户端 |
| D08 | revision 跨进程碰撞、超窗握手/close、订阅间隙，R13 | epoch + revision 明确标识代际，HTTP/WS 同步扩展；握手前超窗沿用 409，连接内 RESYNC 超窗发送 full 快照 | 每次重连强制全量可避免新 epoch，但必须同时规定独立 Poll 在服务重启后的恢复，不能只修 WS。升级后 close=4000 需改契约，不默认采用 | PUSH-MODEL、PUSH-WS、CLIENT-RESILIENCE |
| D10 | 容量、事件/发送队列、线程、心跳恢复参数及备份目标未给出，R17/R27 | 先确认现场人数/基站/客户端/帧率和 SLA，再定有界队列、优先级/溢出、虚拟线程验证、长稳和 RPO/RTO | 1024 revision、256 会话队列、30s SID 缓存等设计候选不是生产容量结论；不可把协议帧率或算法参数改小当优化 | STATE、PUSH-WS、EXPORT、RELEASE |
| D11 | 开演契约“整体回滚”不能撤回已发给硬件的字节；指令 accepted=false 文案与未入队含义不一致 | 设计启动准备/发送/提交和失败补偿路径，不发布半启动态；明确“未入队”“已发送”“已确认”边界，按事实提示 | DB 事务只能回滚库，硬件失败补偿也可能失败，需明确告警和重试/人工处理。不得把 Accepted 当设备执行成功 | LIFE、COMMAND、指挥端提示 |

D05 的 SYSTEM 存证后删指令和同事务已确认，不再把保留原指令行作为选项。
恢复门禁、单实例保护的具体实现需新任务方案，不默认引入分布式中间件或数据库会话级锁。
D08 的 epoch 扩展若获批，需明确 query/帧字段、旧客户端兼容和未知代际恢复；本文不直接改 schema。

## 4. 文件、客户端与环境

| ID | 问题及证据 | 推荐方案 | 替代和影响 | 阻塞 |
|---|---|---|---|---|
| D09 | 图片/导出存储、签名 URL、一用性、有效期和文件保留未定义，R24/R27 | 每实例受保护本地文件目录 + domain 存储端口；所有读取经授权或明确签名授权，数据库/文件一起备份 | 对象存储适合独立服务部署但增加依赖和运维。普通可重复签名 URL 不等于“一次性”，若坚持一次性需消费记录和客户端重试设计 | MAP-BASE、EXPORT、RELEASE |
| D12 | 一个 Flutter App 还是两个入口、共享边界、后台行为及外部地图来源未定 | 共享 Dart 契约/通信包 + Android 单兵入口和 Windows 指挥入口；后台恢复强制重新同步；第一版先验证前台会话 | 单工程按角色切换较简单，但权限和页面要清晰隔离。若 Android 要后台常驻，需服务/通知/电池策略和真机测试。室外地图供应商、离线底图和授权不默认决定 | CLIENT-BASE、客户端功能与验收 |
| D13 | Flutter 脚手架生成文件通常超过 3 个；Windows 缺 Visual Studio | 保持每原子任务 <=3 文件，分段引入工程及平台文件；安装 VS C++ 工作负载另授权并核验空间 | 可另行批准仅一次 scaffold 生成的例外，固定目录和文件清单，不包含业务；未批准前不运行批量 flutter create。Windows 编译不可用 Web 冒充 | CLIENT-BASE、Windows 打包 |
| D14 | IT 与开发共库，AuthServiceIT 真实提交，R23 | 先批准独立测试库/最小权限账号或独立容器，明确迁移、配置和清理归属 | 临时沿用开发库须明确允许真实测试写入及 UUID 清理；不能指向生产，不能覆盖现有 .env 或为测试清库删卷 | TEST-01、数据库集成验收 |
| D15 | 契约中的坐标单位、无演习响应和离线冻结表示存在需澄清处 | 保持冻结算法，先核对旧资产/当前 DTO 和仓储，再逐项修订已批准契约说明 | 不在实现中猜 xPx 名称实际是像素还是 0..1；不能给缺失值编造 ID/时间或解除冻结 | MAP-BASE、STATE、CALIBRATION、LIFE |

不需要现在选择所有客户端库。HTTP、路由、状态管理、安全存储和图像解码依赖
在 CLIENT-BASE/MAP-BASE 开工方案中给出版本、平台支持和取舍，不擅自批量加包。
不默认安装 Docker、Visual Studio、Gradle/SDK 组件或修改全局 JAVA_HOME。

## 5. 本轮新增的契约澄清证据

以下是在实施规划中发现的说明/模型缺口，不冒充新增运行时复现，不直接改契约：

- device [路径](api/paths/device.yaml)和 [VestDeviceUpsertRequest](api/schemas/domain.yaml)
  没有绑定 secret/解绑操作，设备认证不能只靠一个 bound 字段完成管理闭环，归 D03。
- [开演](api/paths/exercise.yaml)要求多次硬件发送后整体回滚；物理副作用不能靠 JDBC 回滚撤回，归 D11。
- [CommandAck](api/schemas/domain.yaml)定义 accepted=false 为未入队，却提示“已下发但设备未确认”；
  必须区分发送事实和确认事实，归 D11。
- ~~Anchor/UwbAnchorPoint 的 xPx/yPx 命名为像素，但 schema 限制 0..1，需对齐单位说明，归 D15。~~
  **已闭合（2026-10-21，MAP-BASE 分段 E）**：以像素为准。`UwbAnchorPoint` 去掉 0~1 约束、
  描述改为像素；D15-⑤ 原「归一化 0~1」口径已按此回退。详见下方「MAP-BASE 分段 E」小节。
- getCurrentExercise 无演习时为 running=false，但 Exercise 强制 exerciseId/planId/startedAt；
  无场次时字段可空/缺席与 schema 必需约束需明确，归 D15。
- UnitState.position 说明离线为 null，但冻结要求离线不能解除最后有效坐标；
  schema 本身也未明确接受 null，需明确无有效坐标和阵亡离线的表示，归 D15。
- UnitState.vestNum 最大 65532，同时 combatRole 说明列举 65533-65535 HAZARD；
  明确危险物仅作为 shooter 事件编号还是可作为参演单元，归 D15。
- uploadMapImage 接受 WebP，getMapImage 只列 PNG/JPEG；需明确返回原格式还是转码，
  WebP 解码依赖和安全限制须纳入任务，归 D09/D15。
- AsyncTask.downloadUrl 说明一次性，下载路径又要求 Bearer；MapDetail.imageUrl 为签名 URL。
  授权优先级、token 不入 URL、签名参数、一用性和重试须定清，归 D09。

冻结马甲/UWB 协议与算法不参与这些业务 schema 调整；不能借契约澄清改变字节或计算结果。
旧业务资产在本轮未做逐行算法/业务复核；手动判活保护、迫击炮 20m、300s 断联停演等
仍按 [需求待确认 Q-LEGACY-RULE](requirements.md)处理，归 D11/D15，不能默默继承或删除。

## 6. 当前确认记录

| 项 | 状态 |
|---|---|
| 全量计划与决策文档整理 | 本轮已授权，文档已形成，待审阅 |
| SEC-01、SEC-02、ERR-01 | 分段获批并完成；485项全量通过，证据见testing第7-9节 |
| D01 | JWT必需字段、类型与过期分类已批准实施；**2026-10-21 身份归一化与限流并发预算已裁定并落地**，见下“D01 / D04 执行边界” |
| D14 / TEST-01 | 2026-10-21用户选择同容器独立库推荐方案，批准分段实施；其他决策不因此获批 |
| D02 | 2026-10-21用户明确“确定”：云端账号签发/边缘验签，边缘设备本地签发且支持断外网登录；仅先形成拆分方案，尚未批准具体实现 |
| D03 | 2026-10-21用户逐项裁定7项（见下“D03 执行边界”）；校验侧（DEVICE-AUTH）已完成；**写入侧（绑定/换绑/解绑）已随 M2 LEDGER 落地** |
| D04 | 2026-10-21 逐项裁定（见下“D01 / D04 执行边界”）：服务端写权限矩阵**已落地**，并已覆盖台账/基站的绑定路径；单兵端可见范围拆为独立工作包 DEVICE-SELF，本阶段不做 |
| M2 LEDGER | 2026-10-21 完成台账/基站服务、控制器接线与绑定闭环；引用保护沿用契约，只拦“演习进行中”。见下“LEDGER 执行边界” |
| R27 配置校验 | 2026-10-21 裁定：显式非正值的 TTL、以及 refresh 未严格长于 access，**启动期直接失败**；未配仍回落默认。**已落地** |
| D09 | 2026-10-21 用户裁定：本地受保护目录 + domain 存储端口；短时效可重复签名 + 原格式返回。见下“D09 / D15 执行边界”。**仅裁定口径，实现属 M2 MAP-BASE** |
| D15 | 2026-10-21 用户裁定：保留 `xPx/yPx` 只修描述；无演习/离线/阵亡/危险物按统一口径。见下节。**仅裁定口径，实现后续分段** |
| D05-D08、D10-D13 | 待确认 |
| 业务批量实施 | 未授权 |
| 创建隔离数据库 | 已批准在scds-local-mysql创建scds_next_test及scds_test；不创建新容器，不改开发.env |
| 安装 VS | 未授权 |
| Flutter 批量生成例外 | 未授权 |
| 提交/推送 M2 LEDGER 成果 | 用户本轮明确授权“提交 LEDGER 并尝试推送”。**已落地 5 个本地提交**（`86e9b71` 契约、`a95bcf3` 服务、`9c3ab09` 接线+权限、`74162ca` 测试、`e8258a0` 文档），叠加既有 4 个后 `main` 领先 `origin/main` **9 个提交**；推送**两次均因网络失败**（`Connection was reset` / 无法连接 github.com:443），**远端未更新**，不得视为已入远端。不推导后续自动提交授权 |
| 提交/推送 M2 MAP-BASE A1/A2 与 B1-B3 | 用户当次选择“提交并尝试推送”与“B2 + B3 连做”。**已落地 5 个本地提交**（`c8c1d5d` 契约 A1/A2、`867bd4c` 裁定登记、`8e1adbd` B1 存储端口与实现、`55739f1` B2 签名 URL、`7846030` B3 单测与配置）。`867bd4c` 那次推送已成功入远端；B1-B3 三次推送**均因网络失败**（`Connection was reset` / 无法连接 github.com:443），`main` 领先 `origin/main` **3 个提交**，**远端未更新** |
| 提交/推送 M2 MAP-BASE 分段 C 与“演习中删图”裁定 | 用户当次选择“提交并重试推送”与“一并拦截，复用 MAP_IN_USE”。**已落地 3 个本地提交**（`3787254` C1 错误码与契约登记、`e8006b6` C2 台账服务与想定/演习引用判据、`8b9de5c` C2 用例），叠加既有 4 个后 `main` 领先 `origin/main` **7 个提交**；本轮推送**两次均因网络失败**（`Recv failure: Connection was reset` / 无法连接 github.com:443），**远端未更新**，不得视为已入远端。`C` 段已跑通全量 `mvn -B verify`（716 项，protocol 19 / core 316 / api 100 / app 281） |
| 提交/推送 M2 MAP-BASE 分段 D | 用户当次选择“分两段提交并重试推送”与“下一步 E 楼层点位 + UWB 标定”。**已落地 2 个本地提交**（`43e6b87` D1 底图错误码登记状态表、`91779bd` D2 底图 CRUD 接线）。本轮推送**成功**：`867bd4c..91779bd main -> main`，随后 `git fetch` 确认 `main` 与 `origin/main` 无差异，**上表第 108、109 行所称“远端未更新”至此消解**（A1/A2 至 C 段的累计积压一并入远端）。D 段已跑通全量 `mvn -B -o verify`（718 项，protocol 19 / core 316 / api 102 / app 281）。注：本轮首跑 verify 时 Docker Desktop 已退出（`scds-local-mysql` 为 `Exited (255)`），重启 Docker 与容器后复跑方全绿——真实库用例的失败先查容器再查代码 |

保留已确认 token_hash 刷新、无请求体全账号 logout、device/me 当前留 501、
SYSTEM 同事务存证后删指令、每客户独立实例、公开开发钥仅开发等决定。
后续批准只更新对应条目，不能把本清单一次性标为全部批准。

### D02 执行边界

用户先在“统一签发开关/拆分账号与设备”选择中选“其他”，没有把推荐方案视为批准。
随后明确询问“账号令牌仅由云端签发、设备令牌可在边缘本地签发，
并支持断网设备登录”时，用户回答“确定”，因此确认的是拆分能力业务场景。
先前约定为先形成独立能力及密钥信任方案，审阅后再写实现代码。

本段仅修改本记录、03-auth.md、新增03b-token-capabilities.md，共3份文档。
不改生产代码、OpenAPI、数据库、实际.env/.env.test或部署密钥；不提交推送。
独立账号/设备密钥、issuer/aud绑定、新设备配置缺省关闭和兼容迁移是待批准的技术方案，
不把它们自动标为用户已裁定。边缘auth/me身份来源需单独决定；
不存在云端账号同步实现，不默认扩充JWT或自动创建本地账号。
已有PRIVATE单机显式自签能力不在本轮删除；deployment-mode与签发能力保持正交。
最新TEST-01数据库隔离及完整启动已验收，558项全量通过，见testing第10-11节；
这验证的是原单密钥实现，不能作为双能力已实现或可发布证据。
下一步先审阅[双能力设计](design/03b-token-capabilities.md)，再列最多3文件的实施白名单。

### D14 执行边界

环境段白名单：Initialize-TestDatabase.ps1、忽略的.env.test、本决策记录。
同容器独立schema为逻辑和账号权限隔离，不是数据库引擎/资源隔离。
专用账号只授予测试库SELECT/INSERT/UPDATE/DELETE/CREATE/ALTER/INDEX/REFERENCES，
无全局授权、GRANT OPTION、DROP或开发库权限。复用13306回环监听，不改11001/12001。
首次脚本创建随机独立口令、owner-only ACL凭据；已有同名库/账号/文件均拒绝覆盖，
失败保留凭据供核对，不自动清库、删卷、重置账号或旋转口令。
当前测试库为空，尚未迁移；开发库account/refresh/exercise计数均0（只读基线）。
后续测试配置只读.env.test，不回退开发SCDS_*秘密；初始化前增加固定本机目标/账号门禁。
复用现有V1-V7/V9，迁移只跑新测试库；库默认保留，用例仅清理自身记录，
AuthServiceIT真实提交不伪装为自动回滚。完整app启动验收另分段完成，不扩大本段代码范围。
权限复核修正MySQL库级GRANT的下划线通配符：精确转义scds_next_test，
撤销本轮新建账号的原未转义模式授权；不改开发账号、已有用户或口令。
该收紧只涉及建库脚本、授权回归与本记录三文件，并由SHOW GRANTS核查精确模式。

### D03 执行边界

2026-10-21用户对[决策稿](design/04-device-binding.md)的7项逐条裁定：

| 项 | 裁定 |
|---|---|
| D03-1 凭据来源 | **A：只认 `t_vest.bind_secret_hash`（BCrypt）**；`sim_no` 仅台账信息，不作凭据；不新增配置键 |
| D03-2 写入入口 | **A：本阶段不做绑定接口**。DEVICE-AUTH 只交付校验侧，绑定数据由测试与部署工具直写库 |
| D03-3 `bound` | 首次密钥校验通过即置 `bound=1`（与 sid 同一条 UPDATE，天然原子） |
| D03-4 换绑/解绑/注销 | 换绑：写新 hash + 清 SID，保留 `bound=1`；解绑：`bound=0` + 清 SID，**保留密钥**；注销：`enabled=0` + 清 SID。均属后续 LEDGER 段，本段不实现 |
| D03-5 SID 缓存 | **不引入缓存**，库即权威 |
| D03-6 限流 | 复用 `LoginThrottle`，新增 `DEVICE` 口径（键 `vest:<num>`，阈值沿用 5 次 / 5 分钟）；`CLIENT_IP` 维度与账号登录共用 |
| D03-7 响应/错误码 | `DeviceTokenPair` 形状不变；失败统一 401 `AUTH_DEVICE_NOT_BOUND`，不区分未登记/退役/未绑定/密钥错 |

实施白名单（实际落地5个文件，分两段，每段<=3文件）：
`scds-core` 新增 `DeviceAuthService`、改 `LoginThrottle`、改 `VestDeviceSessionRepository`；
`scds-api` 改 `AuthController`、改 `DeviceLoginRequest`（补 `@Min/@Max`）；
配套测试新增 `DeviceAuthServiceIT`、改 `LoginThrottleTest`、改 `ApiSkeletonSmokeIT`、改 `MvcProblemIT`。

被本段打破并同步修正的既有断言：`MvcProblemIT.validDeviceRequestStillHitsPlaceholder`（501占位→
401业务失败）、`ApiSkeletonSmokeIT` 与 `MvcProblemIT` 的 `AuthController` 构造点。
按D03-6改为“两条链路各写自口径”后，`LoginThrottleTest` 中账号口径的键数断言（100 / 2）保持成立，未被打破。

仍未做：`device/me` 留占位501；绑定/换绑/解绑接口未实现；`signing-enabled=false` 的只验签实例上
`device/login` 仍注册且会因 `requireSigning()` 抛 `IllegalStateException`（500），
是否改为条件注册（对齐账号端点返回404）属独立决策，本段不改。

### D01 / D04 执行边界（2026-10-21，AUTH-HARDEN）

用户逐项裁定：

| 项 | 裁定 |
|---|---|
| D04 写权限矩阵 | **台账/基站（含绑定密钥与 SID 主动失效）= `ADMIN`；想定/编成/演习生命周期/地图/复盘导出 = `DIRECTOR` 及以上；指令 = `OPERATOR` 及以上；链路开关 = `OPERATOR` 及以上**。读一律 `VIEWER` 及以上 |
| D04 单兵端可见范围 | 用户原选“己方全队（含坐标）”，但 `requirements.md` 写明“当前契约只有单马甲查询入口……不得自行扩大可见范围”。**本轮拆为独立工作包 DEVICE-SELF**，只做账号角色写权限矩阵 |
| D01 身份归一化 | **先查库取 `account.id` 当限流键**（`AuthService.login` 在校验口令前按输入用户名查一次账号，仅用于定键；事务内持行锁后按 id 重读）。未命中账号用独立命名空间的共享键 `account:unknown`。代价：锁定期内仍会查一次库 |
| D01 并发预算 | 维持单机阈值（账号 5 次/5 分钟、IP 30 次/5 分钟），补并发预算断言 |
| R27 TTL 校验 | 显式配成 0 或负值 → **启动失败**；`refresh-token-ttl` 必须严格长于 `access-token-ttl`；未配置（`null`）仍回落默认 |

落地白名单与分段（每段 ≤3 文件）：
1. `SecurityConfig`、`RolePermissions`、`RolePermissionsTest` —— 服务端写权限矩阵；`RolePermissions` 的
   `DIRECTOR` 由 `map:read` 校正为 `map:*`（与裁定同源，否则 `/auth/me` 会隐藏 DIRECTOR 本可编辑的地图）。
2. `RoleWritePermissionIT` —— 越权回归 60 项。
3. `AuthService`、`LoginThrottle`、`AuthServiceIT` —— D01 身份归一化。
4. `LoginThrottleTest` —— D01 并发预算断言（+2）。
5. `AuthJwtProperties`、`AuthJwtPropertiesTest` —— R27 配置校验（+3）。
6. `docs/design/03-auth.md`、本记录 —— 登记。

同时修正的两处不一致：
- `RolePermissions` 的 `DIRECTOR` 由 `map:read` 改为 `map:*`：D04 裁定地图**写**权限归 DIRECTOR，
  原权限点会让指挥台隐藏 DIRECTOR 本可编辑的地图按钮。
- `LoginThrottle` 的账号口径 subject 语义由“用户名”改为“库内身份”：`t_account.username` 是
  `utf8mb4_0900_ai_ci`，`Admin` 与 `admin` 在库里是同一行，按原文记失败会让拼写变体把 5 次上限翻倍。

仍未做：单兵端服务端可见范围（DEVICE-SELF，需新增队伍级契约与字段裁剪）；
`device/me` 仍 501；只验签实例上 `device/login` 的 500 属独立决策。
（绑定/换绑/解绑接口已由 M2 LEDGER 补齐，见下节。）
自动测试全绿不等于允许生产发布。

### LEDGER 执行边界（2026-10-21，M2）

本段只交付“台账 / 基站 / 绑定闭环”三块，链路开关仍不在范围内。

| 项 | 落地口径 |
|---|---|
| 合同计数 | 契约仍为 **69 REST + 2 WS**（device 域 17 REST + watchLinks；situation 域 4 REST + watchSituation）。已核对 `paths/` 全部 `operationId`，本段未新增接口，只把既有路径接线 |
| 逻辑注销 | 契约 `deleteVest` = `enabled=0` + 清 SID，**保留绑定密钥**；`VestRepository.deleteByVestNum`（物理删）**不对控制器暴露**（R22） |
| 引用保护（R22 口径） | **只拦“演习进行中”**：此刻设备正被 `t_exercise_unit` 引用，注销会让态势凭空少人 → 409 `EXERCISE_ALREADY_RUNNING`。想定编成（`t_plan_roster`）的引用**不拦**，按契约不新增错误码 |
| 绑定闭环 | 按 D03-4：首次绑定 `POST`（已绑定则 409 `DEVICE_VEST_ALREADY_BOUND`）、换绑 `PUT`（未绑定则 409 `DEVICE_VEST_NOT_BOUND`）、解绑 `DELETE`（`bound=0` + 清 SID，**保留摘要**，幂等）。绑定/换绑/注销都在**同一次提交**内写摘要与清 SID，不留“摘要已换、旧令牌仍有效”的窗口 |
| 写权限（补 D04 缺口） | `POST·PUT·DELETE /api/v1/devices/vests/*/binding` → `ADMIN` **必须单列**：Ant 通配 `*` 只吃一段，原 `PUT/DELETE /devices/vests/*` 覆盖不到 `/vests/1/binding`，漏掉这三条会让绑定密钥静默变成任意已登录账号（含 VIEWER）都能改的字段 |
| `listVests.status` | 契约 `VestStatus` 三值口径：`LIVE` = `last_seen_at` 距今 ≤ 5000ms，`OFFLINE` = 其余，`DIE`（阵亡）属演习运行态、台账层无数据源 → **显式返回空集**，不用“离线”冒充“阵亡” |
| 基站 | `AnchorLedgerService` 只做 `list` / `upsert`。`bs1~bs4` 是矩形四角，契约标为“必开”，关闭请求 → 400 `VALIDATION_FAILED`（**不新增错误码**）。`anchors` 契约只有 400、无 409，故 upsert 语义就是“有则改、无则建” |
| 已知缺口 | `t_anchor` 坐标与 `t_map.uwb_meta` 双源未统一：解算只读 `uwb_meta`，而库内没有“在用底图”定义，故本段**不写 `uwb_meta`**，等地图域落地时一并定 |

契约同步：`VestDevice` 补 `remark` / `enabled`（`enabled` 是注销语义的承载列），新增请求体 `VestBindingRequest`（`secret` 8~128）。

测试证据（本段新增/更新，全部通过）：
`VestLedgerServiceIT` 16、`AnchorLedgerServiceIT` 5、`ApiSkeletonSmokeIT` 70（含 device 域 17 条路由）、
`RoleWritePermissionIT` 63、`DeviceLedgerHttpIT` 2（完整生产入口 + 真实 MySQL + 真实 TCP 的端到端形状）。

本段仍为 501 的路径：`getVestLink`、`getLinks`、`listSerialPorts` 与串口/UWB 开关——
`scds-gateway` 无主源码，没有可用端口可接。
本段提交状态：经用户当次授权，已落地 5 个本地提交（`86e9b71`/`a95bcf3`/`9c3ab09`/`74162ca`/`e8258a0`），
工作树已干净；推送两次均失败，**远端未更新**，见第 6 节对应条目。

### D09 / D15 执行边界（2026-10-21，MAP-BASE 前置）

用户对两个阻塞 MAP-BASE 的待确认项逐项裁定，**均取推荐口径**；本节只登记口径，
实现分别属 M2 MAP-BASE（D09 存储/签名）与后续 STATE / CALIBRATION / LIFE 分段（D15 缺失态）。

| 项 | 裁定口径 |
|---|---|
| D09-① 存储后端 | **每实例受保护本地目录 + `scds-domain` 存储端口**；`t_map.image_path` 仍只存相对路径；数据库与文件一起备份。不引入对象存储 |
| D09-② 读取授权 | 底图与导出文件统一用**短时效（建议 15 分钟）可重复签名 URL**；签名 URL 不带 `Authorization`，供 `<img>`/浏览器直接拉取；换图即删旧文件 |
| D09-③ 有效期与保留 | 导出文件保留 7 天后 `AsyncTask.expireAt` 到期；过期后须重新导出。具体数值在实现方案中再确认 |
| D09-④ WebP | `getMapImage` **原格式返回**（响应 content-type 补 `image/webp`），不做统一转码 |
| D09-⑤ 签名密钥 | **独立 HMAC 密钥** `SCDS_FILE_SIGN_SECRET`，由部署脚本写入 `.env`；签名覆盖 `path + exp`；未配置则**启动失败**（与 R27 TTL 校验同口径）。不复用 JWT 的 RSA 私钥，保证文件访问与令牌信任解耦、可单独轮换 |
| D09-⑥ 存储根与上限 | 根目录 `SCDS_FILES_ROOT`，默认 `${user.home}/.scds/files`（沿用旧系统 `.scds` 约定）；子目录 `maps/`、`exports/`；单图上限 20MB；白名单 PNG/JPEG/WebP，按真实图片头校验而非扩展名 |
| D15-⑤ 坐标命名 | **保留 `Anchor.xPx/yPx` 与 `UwbAnchorPoint.xPx/yPx` 字段名**，且**语义即底图像素**（原点左上），与 `PROTOCOL.md` §6.6 定稿结构、`t_map.uwb_meta`、`scds-solver` 的 `Geometry` 反解同口径。理由：`t_map.uwb_meta` 的 JSON 结构已冻结且字段名就是 `xPx/yPx`，改名等于动冻结结构。<br>⚠ 本行原写的「底图归一化坐标 0~1」**已回退**（原文把字段名当成了归一化语义，与 §6.6 定稿的整数像素示例互斥） |
| D15-⑥ 无演习响应 | 维持 **HTTP 200 + `running=false`**（不是 404）；把 `Exercise` 的 `exerciseId/planId/startedAt` 由 `required` 改为可空，明确无场次时这些字段缺席 |
| D15-⑦ 离线/阵亡 | `UnitState.position` schema 补 `null`；离线且从未定位 → `null`；**阵亡（`deadAt != null`）必须保留最后有效坐标**，并由 `positionSource` 标注来源，不解除冻结 |
| D15-⑧ 危险物 | 危险物（65533-65535）**只在命中事件 `shooterNum` 出现**，`UnitState.vestNum` 保持 `1..65532`，不调整该上限 |

冻结边界：以上不改变马甲/UWB 协议字节与解算结果；`uwb_meta` 的字段名与结构不动。
**已随本裁定同步修订契约（A1/A2）**：`schemas/domain.yaml`（`Exercise.required` 收为 `[running]`、
`Anchor`/`UwbAnchorPoint` 的 `xPx/yPx` 描述、`MapDetail.imageUrl` 签名口径）、
`schemas/situation.yaml`（`UnitState.position` 改 `oneOf: [GeoPoint, null]` 与三态说明、`combatRole` 危险物说明）、
`schemas/common.yaml`（`AsyncTask.downloadUrl` 废止“一次性”措辞）、
`paths/map.yaml`（`getMapImage` 补 `image/webp` 与“原格式返回”，`uploadMapImage` 注明原格式落盘与替换删旧图）。
四份 YAML 已通过 SnakeYAML 解析校验；未改任何路由、请求/响应字段名或 Java 代码。

D09 与 D15 的剩余实现细节（签名 URL 的 `exp` 精度与时钟偏移容忍、清理任务周期与失败重试、
`signalQuality < 2` 时坐标是否下发、换图与 DB 更新的先后与补偿顺序）在 MAP-BASE 开工方案中逐项列出并再确认。

### MAP-BASE 分段 E 口径（2026-10-21，楼层点位 + UWB 标定）

分段 E 开工前勘察出**三处契约内部冲突**，用户逐项裁定如下（均取推荐口径）：

| 冲突 | 裁定 |
|---|---|
| `uwb_meta` 的 `xPx/yPx`：`PROTOCOL.md` §6.6 定稿与 `scds-solver` 按**像素**，而 `schemas/domain.yaml` 的 `UwbAnchorPoint` 按 D15-⑤ 写**归一化 0~1**（含 `minimum/maximum` 断言），二者互斥 | **以像素为准**：`UwbAnchorPoint` 去 0~1 约束、描述改像素，D15-⑤ 描述回退；`UwbSolver` 零改动 |
| `MAP_UWB_META_INCOMPLETE` 在 `paths/map.yaml` 里同时是 `getUwbMeta` 的 404 与 `putUwbMeta` 的 400，与状态表「一码一状态」冲突 | **新增专用码**：`MAP_NOT_CALIBRATED`（404）承载 GET 的「尚未标定」，`MAP_NOT_FOUND` 保留；`MAP_UWB_META_INCOMPLETE` 固定 400 给 PUT 校验失败 |
| `uwb_meta` 的 API 形状（`{anchors:{bs1..}}`）与冻结存储/解算形状（`bs1..bs8` 在顶层）不一致 | **展平为冻结结构**：`UwbMeta` 直接含 `bs1~bs8` + `realLengthCm/realWidthCm`，删掉 `UwbAnchors` 包装。API、库、解算三者同形，零转换、零漂移 |

配套口径：

- **`putUwbMeta` 的 400 分两条路**：字段级缺项/非数值由 Bean Validation 折叠为 `VALIDATION_FAILED`
  （`errors` 逐字段回填）；语义问题（坐标越出底图宽高、四角退化共线、`realLengthCm/realWidthCm`
  非正整数厘米）由服务层判为 `MAP_UWB_META_INCOMPLETE`。
- **不做「实测长宽 vs 像素比例」的严格自洽校验**：§6.6 的定稿示例本身不满足该比例
  （`realLengthCm/宽高像素` 两侧相差约 15%），据此判 400 会把文档给出的合法样例拒掉；
  严格反解仍在 `scds-solver` 的 `Geometry` 里（那里才需要它），保存侧只拦结构性与边界性错误。
- **`replaceFloorPoints` 是单楼层替换**（路径里带 `floorNum`），不是整图替换：
  仓储层补单楼层 upsert，不再借 `replaceByMapId` 的「先删全图再插」。
- **UWB 校准三端点（`startUwbCalibration` / `getUwbCalibration` / `clearUwbCalibration`）继续 501**：
  `scds-gateway` 无 Java 源码，没有可注入的采样端口；库内也没有校准状态表（迁移 V1~V9），
  无法真实实现。`MAP_CALIBRATION_IN_PROGRESS` 与 `MAP_IMAGE_MISSING` 因此**不预登记**到状态表。

