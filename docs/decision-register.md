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
- Anchor/UwbAnchorPoint 的 xPx/yPx 命名为像素，但 schema 限制 0..1，需对齐单位说明，归 D15。
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
| D01 | 仅JWT必需字段、类型与过期分类已批准实施；身份归一化和限流预算仍待确认 |
| D14 / TEST-01 | 2026-10-21用户选择同容器独立库推荐方案，批准分段实施；其他决策不因此获批 |
| D02 | 2026-10-21用户明确“确定”：云端账号签发/边缘验签，边缘设备本地签发且支持断外网登录；仅先形成拆分方案，尚未批准具体实现 |
| D03 | 2026-10-21用户逐项裁定7项（见下“D03 执行边界”）；**校验侧（DEVICE-AUTH）已授权并完成实现**，写入侧（绑定接口）本阶段不做 |
| D04-D13、D15 | 待确认 |
| 业务批量实施 | 未授权 |
| 创建隔离数据库 | 已批准在scds-local-mysql创建scds_next_test及scds_test；不创建新容器，不改开发.env |
| 安装 VS | 未授权 |
| Flutter 批量生成例外 | 未授权 |
| 提交/推送当前成果及已有3个提交 | 用户本轮明确授权并完成，远端main与本地64b73e1完整SHA一致；不推导后续自动提交授权 |

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

