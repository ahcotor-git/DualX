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
| D06 / D10 | 2026-10-23 用户逐项裁定（4 项，见下「D06 / D10 执行边界」）：UWB SERIAL **首版一并交付**；已开重开**对齐契约返回 409**；**做**无硬件仿真适配器（profile 显式开启）；D10 **先落可配置项并标注未验证**。**仅裁定口径与分段方案，`scds-gateway` 尚无源码，实现未开始** |
| D05、D07、D08、D11-D13 | 待确认 |
| 业务批量实施 | 未授权 |
| 创建隔离数据库 | 已批准在scds-local-mysql创建scds_next_test及scds_test；不创建新容器，不改开发.env |
| 安装 VS | 未授权 |
| Flutter 批量生成例外 | 未授权 |
| 提交/推送 M2 LEDGER 成果 | 用户本轮明确授权“提交 LEDGER 并尝试推送”。**已落地 5 个本地提交**（`86e9b71` 契约、`a95bcf3` 服务、`9c3ab09` 接线+权限、`74162ca` 测试、`e8258a0` 文档），叠加既有 4 个后 `main` 领先 `origin/main` **9 个提交**；推送**两次均因网络失败**（`Connection was reset` / 无法连接 github.com:443），**远端未更新**，不得视为已入远端。不推导后续自动提交授权 |
| 提交/推送 M2 MAP-BASE A1/A2 与 B1-B3 | 用户当次选择“提交并尝试推送”与“B2 + B3 连做”。**已落地 5 个本地提交**（`c8c1d5d` 契约 A1/A2、`867bd4c` 裁定登记、`8e1adbd` B1 存储端口与实现、`55739f1` B2 签名 URL、`7846030` B3 单测与配置）。`867bd4c` 那次推送已成功入远端；B1-B3 三次推送**均因网络失败**（`Connection was reset` / 无法连接 github.com:443），`main` 领先 `origin/main` **3 个提交**，**远端未更新** |
| 提交/推送 M2 MAP-BASE 分段 C 与“演习中删图”裁定 | 用户当次选择“提交并重试推送”与“一并拦截，复用 MAP_IN_USE”。**已落地 3 个本地提交**（`3787254` C1 错误码与契约登记、`e8006b6` C2 台账服务与想定/演习引用判据、`8b9de5c` C2 用例），叠加既有 4 个后 `main` 领先 `origin/main` **7 个提交**；本轮推送**两次均因网络失败**（`Recv failure: Connection was reset` / 无法连接 github.com:443），**远端未更新**，不得视为已入远端。`C` 段已跑通全量 `mvn -B verify`（716 项，protocol 19 / core 316 / api 100 / app 281） |
| 提交/推送 M2 MAP-BASE 分段 D | 用户当次选择“分两段提交并重试推送”与“下一步 E 楼层点位 + UWB 标定”。**已落地 2 个本地提交**（`43e6b87` D1 底图错误码登记状态表、`91779bd` D2 底图 CRUD 接线）。本轮推送**成功**：`867bd4c..91779bd main -> main`，随后 `git fetch` 确认 `main` 与 `origin/main` 无差异，**上表第 108、109 行所称“远端未更新”至此消解**（A1/A2 至 C 段的累计积压一并入远端）。D 段已跑通全量 `mvn -B -o verify`（718 项，protocol 19 / core 316 / api 102 / app 281）。注：本轮首跑 verify 时 Docker Desktop 已退出（`scds-local-mysql` 为 `Exited (255)`），重启 Docker 与容器后复跑方全绿——真实库用例的失败先查容器再查代码 |
| 提交/推送 M2 MAP-BASE 分段 E | 用户当次选择“按 3 个语义提交并推送”与“先继续 F 段，推送稍后重试”。**已落地 3 个本地提交**（`d7854c7` 契约、`f99ce9e` 服务接线、`7fb24d2` 用例），叠加 D 段后 `main` 领先 `origin/main` **3 个提交**；推送**因网络失败**（`Recv failure: Connection was reset`），**远端未更新**。E 段已跑通全量 `mvn -o verify`（734 项） |
| 提交/推送 M2 MAP-BASE 分段 F | 用户当次裁定四项口径（见下「MAP-BASE 分段 F」），并选择“按语义提交并尝试推送”。**已落地 4 个本地提交**（`1dd8880` 契约、`efae3a8` 存储落盘、`0f7b1e5` 文件端点接线、`e690c9c` 用例）。F 段已跑通全量 `mvn -o verify`（**749 项**：protocol 19 / core 346 / api 103 / app 281）。推送**未成功**（`Recv failure: Connection was reset`，EXIT=128），`main` 领先 `origin/main` **7 个提交**，**远端未更新**，不得视为已入远端 |
| M2 MAP-BASE 分段 G（端到端 IT + 文档回写） | 用户当次选择“进入 G 段”，随后授权“分 2 个语义提交并重试推送”。**已落地 2 个本地提交**（`d2213ff` fix 楼层点位落库形状、`107b692` 端到端 IT + F/G 决策回写），叠加 F 段后 `main` 领先 `origin/main` **9 个提交**；推送**因网络失败**（`Failed to connect to github.com:443 after 21141 ms`，EXIT=128），**远端未更新**，不得视为已入远端。G 段已跑通全量 `mvn -o verify`（**751 项**：protocol 19 / core 346 / api 103 / app 283） |
| M2 测试库残留清理 | G 段首跑失败留下 1 行 `t_map` + 1 行 `t_map_floor`（`cleanUp` 因列名写错而`BadSqlGrammar`，两处 `DELETE` 都未生效），导致 `BaseMapRepositoryIT` 的**全局行数断言**（`expected 1 but was 2`）失败。已按本记录 D14 口径定位到具体 UUID 并**只删自身残留行**（`t_map`/`t_map_floor` 现均为 0），未清库、未删卷、未动其它账号数据 |
| M3 LIFE-DDL（V10 迁移 + 领域与契约同步） | 用户当次选择“LIFE 分段（演习生命周期 + 自愈）”，随后取消 LIFE 范围追问，视为按 D05 推荐口径推进。**本段只做 DDL 与类型边界**：V10 迁移、`ExerciseEndReason`、领域 `ExerciseLogType`（含 `SYSTEM`）、日志读写的枚举切换、契约补 `SYSTEM` 与 `endReason`。服务与自愈流程**未开始**。全量 `mvn -o verify` 全绿（**757 项**：protocol 19 / core 352 / api 103 / app 283）。**已落地 2 个本地提交**（`4933e4a` 迁移+领域+契约、本次决策回写提交），叠加 G 段后 `main` 领先 `origin/main` **11 个提交**；推送**因网络失败**（`RPC failed; curl 55 Send failure: Connection was reset`，EXIT=1），**远端未更新**，不得视为已入远端。细节见下「LIFE-DDL 执行边界」 |

| 提交/推送 M3 HW-LINK（H0~H7） | 用户当次授权“提交推送并继续”。**已落地 3 个语义提交并成功推送**（`18a1358` 设计+决策回写、`c85df04` 网关实现+单测、`450794b` 端点接线+端到端 IT），`git push` 输出 `4efd272..450794b  main -> main`，工作树干净。H7 的 `HwLinkHttpIT` 5 例全通过（`Skipped: 0`），全量 `mvn test` 通过（scds-app 290 例，8 模块 BUILD SUCCESS）。细节见下「D06 / D10 执行边界」的实施进度与提交状态 |
| D06 / D10（STATE 前置） | 2026-10-24 用户逐项裁定 4 项（见下「STATE 分段（一）执行边界」）：**做**增量环形窗口（不做 epoch/跨代）；5000ms 在线判定用**定时扫描产生跳变**；事件处理用**单消费者事件循环**；无硬件验证**复用 `scds.gateway.mode=simulator`**。**仅裁定口径与分段方案，S1~S8 均未开始**（后续进度见下「STATE 分段（一）执行边界」的实施进度：现 S1~S8 已完成） |

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

### MAP-BASE 分段 F 口径（2026-10-21/22，图片落盘 + 签名文件下载）

用户逐项裁定（均取推荐口径）：

| 项 | 裁定 |
|---|---|
| 取文件的路由形状 | **通用 `GET /api/v1/files`**，不给每类资源各配一条下载路径 |
| 查询参数命名 | **`path` / `exp` / `signature`** |
| 验签层级 | **`SecurityConfig` 放行该路径 + 接口层验签**（不放进过滤链，避免过滤器依赖 `FileUrlSigner` 而污染安全链的职责） |
| 验签失败返回码 | **新增 `FILE_URL_INVALID`（403）**，与「签名对但对象不存在」的 404 分开 |
| `image_width/height` 来源 | **服务端解析图片头**（不信任客户端上报，也不按文件扩展名判断） |

落地口径：

- **签名覆盖 `relativePath + '\n' + exp`**，URL-safe Base64 无填充；比较走
  `MessageDigest.isEqual`（恒定时间）。`verify` **先判 `exp` 是否已过**再算签名，过期即 false。
  同一 URL 在 TTL 内可重复使用（D09-② 原文就是「短时效**可重复**签名 URL」，不是一次性消费）。
- **Content-Type 按真实字节定**：文件端点读文件后嗅探图片头决定 `image/png|jpeg|webp`，
  不采信 multipart 的 `Content-Type`、也不看文件名后缀。
- **`LocalFileStore`**：`put` 落盘到 `maps/<mapId>/<uuidv7><ext>`；替换底图的顺序是
  **校验 → `store.put` 新文件 → `maps.update` 改行 → `discard` 删旧文件**（新文件先落地、
  旧文件后删；`discard` 失败只 WARN，不让一次清理失败回滚已提交的换图）。
- **读图的两种失败折叠为同一 404 `MAP_IMAGE_MISSING`**：行内 `image_path` 为空，与行内有路径但
  磁盘文件已丢，对客户端是同一件事，不给两套码。
- **`GET /api/v1/files` 免 Bearer 但**：`path` 仍受签名约束（不能拿它当任意文件读取入口）；
  上传底图后的回显 `imageUrl` 是签名 URL，供 `<img>` 直接拉取（D09-②）。
- **`HmacFileUrlSigner` 与 `LocalFileStore` 均为 `@Component`**，端口在 `scds-domain`
  （`FileStore` / `FileUrlSigner`），实现与配置在 `scds-core`（`scds.files.*`）。
- **`FileController` 只做验签 + 读文件**，不做权限判断：授权来自签名本身。

⚠ **一处实现偏离，需知悉**：`FileStorageProperties` 对 `sign-secret` 采取的是
**未配置 → 本次启动随机生成并打 WARN**（显式配了但短于 32 字符才启动失败），
这与 D09-⑤ 原文「未配置则**启动失败**」不一致。取此口径的理由是：本机开发与自动测试
（`MapBaseHttpIT`、`ApiSkeletonSmokeIT` 等）不应被迫先手配密钥，而 TTL 只有 15 分钟，
重启导致旧签名 URL 失效的影响可忽略；「显式填弱值」才是真正要拦的错误信号。
**生产部署仍须在 `.env` 显式提供 `SCDS_FILE_SIGN_SECRET`**。若要求与 D09-⑤ 严格一致
（未配置即启动失败），需单独裁定后改 `FileStorageProperties` 与本记录。

本段新增/更新的契约与文档：`api/paths/files.yaml`（新建）、`openapi.yaml`、
`api/paths/map.yaml`（`uploadMapImage` 补 413/415、`getMapImage` 补 `image/webp`）、
`api/schemas/domain.yaml`、`api/schemas/common.yaml`、`design/03-auth.md` §4.4（白名单登记）。

测试证据：`ImageHeadersTest` 8、`MapLedgerServiceIT` 35（含 6 条图片用例）、
`ApiSkeletonSmokeIT` 71（含 `/api/v1/files?path=…&exp=…&signature=…`）、`RoleWritePermissionIT` 63。
本段另**删除了 `MaxUploadSizeExceededException` 处理器**（改由 `MvcProblemIT` 的
`actualContainerMultipartLimit` 以**真实 TCP 2KB 上传**断言 413 兜底），删后该 27 项全过，
故判定该删除成立、无需恢复。

### MAP-BASE 分段 G 口径（2026-10-22，端到端 IT + 文档回写）

分段 G 的目标是**用真实链路走一遍 F 段的产物**，而不是再加服务层用例。
新增 `scds-app` 的 `MapBaseHttpIT`：真实 Boot 入口（`ApplicationStartupSupport.start`）
+ 真实 MySQL（`scds_next_test`）+ 真实安全过滤链 + 真实 TCP + 真实 `multipart/form-data`，
专属编号段 + `cleanUp()` 精确回收，并通过
`environment.getPropertySources().addFirst(new MapPropertySource(...))`
**只覆盖 `scds.files.root` 一项**（不动 `.env.test` 与其它已有配置）。

两个用例覆盖的形状：

1. `mapImageAndSignedUrlOverRealHttp`：未上传 → 404 `MAP_IMAGE_MISSING`；上传 37×19 PNG
   （**刻意非正方形**：宽高写反在正方形图上分辨不出）断言回填宽高与 `imageUrl` 前缀；
   **不带 Bearer** 取签名 URL → 200 + `image/png` + 字节相等；签名错 → 403 `FILE_URL_INVALID`
   且 `instance` 为 `/api/v1/files`（取 requestURI，不含查询串）；带 Bearer 走域内路由；
   假图片（文件名 `fake.png`、正文非图片）→ 415；换 JPEG 后**旧签名 URL 落 404、新 URL 回
   `image/jpeg`**；删底图 → 204 且连带删文件。
2. `floorPointsAndUwbMetaOverRealHttp`：未标点时读回**空列表**（不是 404）；PUT 后读回；
   未标定 → 404 `MAP_NOT_CALIBRATED`；保存标定后 `calibrated=true` / `floorCount=1`；
   `bs2` 越界 5000 → 400 `MAP_UWB_META_INCOMPLETE`；删图收尾。

**本段暴露并修复的真实端到端缺陷（F 段遗留）**：
`MapController.replaceFloorPoints` 原把**整个请求包体** `{"points":[…]}` 写入
`t_map_floor.points`，而读回路径 `listFloorPoints` 按**裸数组**解析（`t_map_floor.points`
的冻结结构就是 `[{posNum,xPct,yPct,label}]`）→ `MismatchedInputException` → 500。
服务层 IT 直接喂裸数组文本，故从未暴露；只有让 HTTP 层把「请求体形状 → 落库形状」串起来才会现形。
**修复**：落库改为 `write(body.points())`（响应仍回显请求体——这是一次全量替换，
回读还要面对 MySQL 对 JSON 列的键序重排）。**教训：API 形状与库形状必须同形，
且这只能在端到端路径上验证。**

IT 自身也修了两处：`cleanUp` 原写 `DELETE FROM t_map WHERE map_id = :mapId`，
而 `t_map` 主键列名是 **`id`**（`map_id` 只在 `t_map_floor` 上）→ `BadSqlGrammarException`；
以及楼层点位断言改为**先断言状态码再解析**，否则 500 的响应体是 Problem 对象、
`path("points")` 得到 `MissingNode`、`isEmpty()` 一样通过，会把 500 当成「空列表」放行。

本段结果：`mvn -o test -pl scds-app -am -Dtest=MapBaseHttpIT` → **Tests run: 2, Failures: 0, Errors: 0**；
随后全量 `mvn -o verify` → **BUILD SUCCESS，751 项全绿**
（protocol 19 / core 346 / api 103 / app 283；app 由 F 段 281 增至 283 = 本段新增 2 条 IT）。

**首跑全量 verify 曾失败，但不是代码回归**：`scds-core` 的 `BaseMapRepositoryIT` 两项
（`findByType_filtersByEnum`、`findAll_ordersNewestFirstAndPaginates`）报
`expected 1 but was 2`。根因是 IT 首跑失败时 `cleanUp` 抛出 `BadSqlGrammarException`，
**两处 `DELETE` 都没执行**，把 1 行 `t_map` + 1 行 `t_map_floor` 留在 `scds_next_test` 里；
而 `BaseMapRepositoryIT` 断言的是**全表行数**，看到外来行即失败。
定位方式：失败信息里带出了残留行的完整内容（UUID、时间戳 `2026-10-21T17:01:15Z` = 首跑时刻），
据此确认是自家残留而非他人数据，只按 UUID 删该行（删后两张表均为 0 行），未清库、未删卷。
**教训：断言全表行数的 IT 与「会往同一张表写数据的其它 IT」共存时，任何一个 IT 的清理失败
都会把它人的用例打红；排查顺序应是「先看残留数据是谁留下的」再怀疑代码。**
本段仍为 501 的 UWB 校准三端点与 `device/me` 不在范围内，未触碰。

### LIFE-DDL 执行边界（2026-10-22，M3 演习存活自愈第一步）

依据 D05（`docs/design/01-exercise-liveness.md`）与用户对「LIFE 分段」的裁定。
本段**只做「库形状 + 类型边界 + 契约」三件事**，不含任何服务、调度或自愈流程——
即：把「僵尸演习」这件事在数据层「装得下」，尚不能「做」。

**1. 迁移 V10（`V10__exercise_liveness.sql`）**

| 改动 | 口径 |
|---|---|
| `t_exercise.owner_instance` | 持有本场运行权的进程实例标识，`NULL` 表示历史行或创建时未记录 |
| `t_exercise.heartbeat_at` | 该实例最后一次心跳（UTC），只用于**告警分级与措辞**，不参与接管判据 |
| `t_exercise.end_reason` | `NORMAL` / `CRASH_RECOVERED`，进行中为 `NULL`，由 `ck_exercise_end_reason` 约束 |
| `ix_exercise_running_owner (ended_at, owner_instance)` | 支撑「按实例找进行中的行」 |
| `ck_exercise_log_type` | **重建**为含 `SYSTEM`（原 V4 定义只有 6 个协议值） |

版本号取 **V10**（不是设计文档原文的 V8）：V8 已被跳过且不补历史版本，迁移 `V9__auth_tokens.sql` 已入库，
按「只增不改、不 out-of-order」口径，下一个可用版本就是 V10。

**互斥 ≠ 存活**：`uk_exercise_running`（生成列 `running_flag` 上的唯一索引）保证**同一时刻至多一场**
进行中，但它管不住「进程崩了而 `ended_at` 没写上」——那行仍然占着那个唯一位置，新的开演会被
`EXERCISE_ALREADY_RUNNING` 拒绝。`owner_instance` / `heartbeat_at` 回答的是另一个正交问题：
**这行还有没有人在管**。这也是为什么自愈必须先收尾再放行新开演，而不是简单删行。

**2. `SYSTEM` 为什么不加进 `scds-protocol.LogType`**

`scds-protocol.LogType` 是**带字节 code（0~5）的协议帧类型**，属冻结边界；给它加一个没有帧的值，
等于让协议库去表达一件协议里不存在的事（影响上游固件对接方）。因此：

- 协议侧**不动**；
- 新增领域枚举 `com.dualx.core.domain.ExerciseLogType` = 协议 6 值 + `SYSTEM`，
  以 `of(LogType)` / `protocol()` 显式双向映射，不靠名字巧合；`protocol()` 对 `SYSTEM` **抛异常**
  而不是返回假值——调用方拿到异常说明这条路走错了；
- `ExerciseLogEntry.logType`、`ExerciseLogRepository` 的 JDBC 映射与 `scds-api` 的
  `dto.situation.ExerciseLogEntry.type` 同步切到该枚举（D05 Q3 的「须同时对齐」）；
- 单测 `ExerciseLifecycleTypesTest` 守「协议枚举冻结为 6 值」，IT
  `ExerciseLogRepositoryIT.append_systemEntry_survivesTheDatabaseCheckConstraint`
  真写库再读回——**库侧 CHECK 与 Java 枚举是两处独立声明，单测只能证明枚举里写得下**。

**3. 契约**

- `common.yaml → LogType` 枚举补 `SYSTEM`，并注明它**没有 `x-codes` 条目**（不对应任何协议帧），
  客户端遇到它不可假设必有 `vestNum` / `downlinkHex`（D05 Q2 / Q3）。
- `domain.yaml → Exercise` 补 `endReason`（进行中为 null / 缺席），
  `paths/exercise.yaml` 的 `stopExercise` 200 响应补同名字段。
- `endReason` 进契约的理由：否则前端无法把「异常中断」告诉操作员，
  而僵尸场次**仍出现在复盘列表**（D05 Q1），列表要能按此打标签。

**本段暴露的一处遗漏**：V10 需要同步的迁移清单断言有**两处**（`scds-core` 的
`TestDatabaseGuardTest` 与 `scds-app` 的 `ApplicationStartupIT`），
首跑 `mvn -o verify` 只改了前者，`ApplicationStartupIT` 报
`containsExactly("1"…"7","9")` 失败（2 条，因参数化 signing true/false）。
**教训：`flyway_schema_history` 的版本清单断言不止一处，新增迁移后应全局搜 `installed_rank` 而不是只改手边那个。**

**本段结果**：全量 `mvn -o verify` → **BUILD SUCCESS，757 项全绿**
（protocol 19 / core 352 / api 103 / app 283；core 由 G 段 346 增至 352 =
`ExerciseLifecycleTypesTest` 5 条 + `ExerciseLogRepositoryIT` 新增 1 条）。
`t_exercise` 无数据迁移动作（三列均可空），**未清库、未删卷、未改既有账号**。

**本段未做（属 LIFE 后续）**：`ExerciseLifecycleService`、`ExerciseLivenessRecover`（`ApplicationRunner`，`@Order(10)`）、
`ExerciseHeartbeatTask`（`@Scheduled`）、`scds.instance-id` 与恢复门禁、
心跳/按原因收尾/按场次清理残留指令的仓储方法、`Exercise` 领域与 API DTO 的 `endReason`、
`ExerciseController` 14 个 501 端点接线、`ApiExceptionHandler` 补登记
`EXERCISE_NOT_RUNNING`(409) / `EXERCISE_PLAN_NOT_FOUND`(404) / `EXERCISE_ROSTER_EMPTY`(400)。
LIFE 的 PLAN / STATE / 硬件三项前置亦均未就绪（后两者仍为 501，`scds-gateway` 尚无 Java 源码）。

**提交与推送**：本段落地 2 个本地提交（`4933e4a` + 本次决策回写提交），`main` 领先 `origin/main` **11 个提交**；
推送失败于 `RPC failed; curl 55 Send failure: Connection was reset`（EXIT=1），**远端未更新**。

### LIFE 分段（一）执行边界（2026-10-23，M3 收尾地基 + 启动自愈）

依据 D05（`docs/design/01-exercise-liveness.md`）与用户对「LIFE 分段（演习生命周期 + 自愈）」的裁定。
本段把「僵尸演习」从 LIFE-DDL 的**装得下**推进到**能自动收尾**，共 5 个提交：

| 提交 | 内容 |
|---|---|
| `1e95480` | 仓储补运行权占用与活跃性：`markEnded` 记原因、`claim`、`heartbeat`（守卫带 `owner_instance`） |
| `3571dce` | `ExerciseCommandRepository` 补按场次收尾：`findResidualByExerciseId` / `markExpiredByExerciseId` / `deleteByExerciseId` |
| `7aa1a93` | `ExerciseLifecycleService.freezeAndClose`（冻结结局 + 作废残留 + 存证，单事务）与 `ExerciseUnitRepository.countDeadByExerciseId` |
| `44adb97` | `ExerciseLivenessRecover`（`ApplicationRunner`，`@Order(10)`）与 `ExerciseRepository.findRunningLiveness` |
| `677cc25` | `ExerciseHeartbeatTask`（`@Scheduled(fixedDelay=2000)`）与 `InstanceProperties`（`scds.instance-id`） |

**1. `dead_count` 的口径（本段唯一的业务裁定）**

崩溃时内存态已丢失，无从知道「谁是阵亡」。定为**只数态势状态机已冻结的终局**
（`t_exercise_unit.final_status = 'DIE'`），不从致命命中或判死指令反推：阵亡是运行态判定，
权威来源是 STATE 状态机，`freezeAndClose` 只负责把它的结论数一数。因此**崩溃自愈的场次
`dead_count` 通常为 0**——那是如实反映「终局尚未冻结」，不是统计错误。
正常停演与自愈**共用同一份代码**，只靠 `ExerciseEndReason` 区分，避免两套冻结口径各自漂移。

**2. 收尾为什么必须是一个事务**

`findPending` 是**全局**口径（不带 `exercise_id`）。若在「冻结结局」与「按场次清空残留指令」
之间中断，那些 `PENDING`/`SENT` 会被派发给**下一场**演习的马甲。
故顺序固定为「先读原文 → 写 SYSTEM 存证 → 标记 EXPIRED → 按场次删除」，同一事务：
先删后记一旦失败，下行原文就永久消失。

**3. 一处必须记下的实现事实：作废标记在收尾后不可观测**

设计 §4.4 第 2 步要求把残留指令标记 `EXPIRED` + `fail_reason='INSTANCE_RESTART'`，
第 3 步又要求按场次删除残留指令——两步在同一事务内连续执行，结果是**队列表里看不到 `EXPIRED` 行**，
`fail_reason` 只是事务内的中间态，**永久证据只有 `t_exercise_log` 的 SYSTEM 记录**。
首版 IT 把断言打在 `fail_reason` 上（`expected "INSTANCE_RESTART" but was null`），
改为「本场次队列行数归零 + SYSTEM 日志含 `INSTANCE_RESTART` 与下行原文」。
这不是实现偏离设计，而是设计的必然结果：队列表不保留历史，证据由日志承载。

**4. 自愈规则与失败姿态**

- **只要启动时发现「进行中」的行，一律收尾**，包括「就是本实例、心跳还很新」的快速重启（§4.1：态势模型、
  链路句柄、UWB 标定上下文都只在内存里，且现场物理状态已前进，接管不可能）。
  `graceMs` 本段未实现——按设计它只影响日志措辞与告警分级、不改变动作，日志里已带出 `heartbeat_at` 供现场对时。
- 恢复失败**不吞异常**：启动直接失败，比「起来了却开不了演」更早被运维发现，是门禁的最强形态。
- 心跳任务**绝不自动收尾**（§4.5）：写入失败只记 ERROR；真在跑的场次被误停的代价远大于僵尸多存活一段。
  可观测指标待议题 05 定案，本段不引入 Micrometer 计数。
- 无进行中场次时心跳只做一次轻量查询即返回，不写库、不空转写 IO。

**5. 本段结果**：全量 `mvn -o verify` → **BUILD SUCCESS，777 项全绿**
（LIFE-DDL 段基线 757 → 本段末 777）。`t_exercise` 无数据迁移动作，**未清库、未删卷、未改既有账号**。
推送：`677cc25` 已成功推送，`main` 与 `origin/main` **一致（领先 0）**。

**本段未做（属 LIFE 后续，附理由）**：

- **恢复完成门禁**：与 `startExercise` 同批落地才成立——没有开演入口，就没有可被拒绝的动作。
- **`Exercise` 领域与 API DTO 的 `endReason`**：契约已在 LIFE-DDL 段落补齐，但领域映射**推迟到端点接线时**：
  当前无任何消费者（`ExerciseController` 14 个端点仍为 501），而 `Exercise` 是 record，加组件会牵动
  4 个测试类的构造调用——提前做只是无收益的构造点改动。
- **`ExerciseController` 14 个 501 端点接线 + `ApiExceptionHandler` 补登记**
  `EXERCISE_NOT_RUNNING`(409) / `EXERCISE_PLAN_NOT_FOUND`(404) / `EXERCISE_ROSTER_EMPTY`(400)：
  被 PLAN / STATE / HW-LINK 三项前置阻塞（想定与态势仍为 501，`scds-gateway` 尚无 Java 源码）。

### PLAN 分段（一）执行边界（2026-10-23，M2 想定）

依据 M2 工作包 PLAN（`docs/implementation-plan.md`：想定 CRUD、编成整体替换、CSV/XLSX 模板和整文件导入，
前置 LEDGER、MAP-BASE、D04）与用户对「PLAN 想定」的裁定。本段把 `exercise.yaml` 的想定 9 个端点
从 501 占位接到 `PlanService`，并按「每个原子任务 ≤3 文件」分成 6 段推进。

| 段 | 内容 |
|---|---|
| T1 | `ApiErrorCode` 新增 `PLAN_NOT_FOUND`(404) / `PLAN_IN_USE`(409)；`ApiExceptionHandler.statusOf` 逐项登记；`common.yaml` 的 `Problem.code` 补两行 |
| T2 | `PlanRosterRepository.countByPlanIds`（成批补齐 `Plan.rosterCount`，避免逐行 COUNT 的 N+1；空入参提前返回，`IN ()` 是语法错） |
| T3 | `PlanService`（list/get/create/update/delete/roster/replaceRoster + 三条编成校验）与 `PlanServiceIT`（17 项） |
| T4 | `PlanRosterSheet`（自研最小 XLSX + CSV 编解码）与 `PlanImportIT`（16 项）；`PlanService` 追加 `downloadTemplate`/`importPlan`/`planName` |
| T5 | `ExerciseController` 想定 9 端点接线（4 个当前演习端点仍为 501） |
| T6 | `PlanHttpIT`（2 项端到端）与本文档回写 |

**1. 模板口径（用户裁定）**：**中文表头 + 示例行**。表头 `马甲编号/姓名/队伍/血量/弹夹1/弹夹2/轮询模式`；
必需列只有 `马甲编号` 与 `队伍`，其余整列可缺席（缺席即取契约默认 100/30/30/AUTO）。队伍与轮询模式
中英文都认（`RED/红/红队`、`AUTO/自动`）。模板第 2 行的示例行，导入时**只跳过第一个数据行且必须整行逐格全等**——
操作员删不删示例行都能导入，而后面真有人填出一模一样的内容也不会被静默吞掉。CSV 写出带 UTF-8 BOM：
不带 BOM 的中文表头在 Windows 版 Excel 里按 GBK 打开是乱码，而这份模板恰恰是给 Excel 填的。

**2. 导入口径（用户裁定）**：**只导编成，其余用默认值**——`battleMode`=TRAIN、`mapId`/`remark` 留空，
导入后在编辑页补；想定名优先用表单 `name`，没给才退回文件名（去路径、去扩展名）。

**3. 不引新依赖**：XLSX 用 JDK 自带 `java.util.zip` + `javax.xml.stream` 手写（XLSX 就是一个装着 XML 的 zip），
写用 `inlineStr`、读兼容 `sharedStrings`（操作员用 Excel 另存一次就会改成共享表形式）；CSV 手写 RFC 4180 子集。
不引 POI / commons-csv。**M5 的 EXPORT 复用同一取向**。

**4. 两处只有实现后才看得见的坑**
- **格式按字节嗅探，不看扩展名与请求 `Content-Type`**：`PK\x03\x04` 即 XLSX，含 NUL 即二进制（旧版 `.xls`），
  其余按 CSV 文本解。`PlanHttpIT` 的分片刻意声明 `application/octet-stream`。
- **解析器必须禁 DTD 与外部实体**（`SUPPORT_DTD=false` / `IS_SUPPORTING_EXTERNAL_ENTITIES=false`）：
  上传的文件来自外部，不禁掉就等于给「模板」开了一条读文件或打内网的路。

**5. 契约的「不产生部分写入」靠事务边界保证**：解析 → 三条校验 → `plans.insert` → `replaceByPlanId`
全在一个 `@Transactional` 内。校验排在写之前，才不会出现「删了旧的、新的没过校验」那种在现场表现为
「编成忽然空了」的中间态。这是本段唯一加 `@Transactional` 的三处之一（另两处：删想定连带删编成、导入落库）。

**6. `t_plan_roster` 无外键，一致性由应用层背书**：删除想定在同一事务内顺带清编成（否则留下指向死想定的行，
而那张表没有任何入口能再被读到）；反向不做——不因编成存在而拒绝删除想定。

**7. 接线后必须同步的两个既有骨架测试（本段实际踩到）**
- `ApiSkeletonSmokeIT`：想定路径参数从占位串 `p1` 换成**语法合法的 UUID**。理由与底图 `MAP_ID` 同一取向——
  接线后「拼不出来的 planId」被折叠成 404，而该测试的判据恰是「非 404」，用占位串会让已注册路由看起来像没注册。
- `RoleWritePermissionIT`：其切片 `PermissionApplication` 排掉了 DataSource，需补 `PlanService` 替身
  （与 `MapLedgerService` 等同一写法，一律抛占位异常）。

**8. 本段结果**：全量 `mvn -o verify` → **BUILD SUCCESS，812 项全绿**
（LIFE 段基线 777 → 本段末 812，新增 `PlanServiceIT` 17 + `PlanImportIT` 16 + `PlanHttpIT` 2 = 35）。
未清库、未删卷、未改既有账号、未动已应用迁移（无新迁移需求）。
`SecurityConfig` **本段未修改**——想定 / 编成的写权限规则在 D04 时已就位（POST `/api/v1/plans`、
`/api/v1/plans/import`；PUT `/api/v1/plans/*`、`/api/v1/plans/*/roster`；DELETE `/api/v1/plans/*`）。

**9. 一件必须记下的事实：代码已就绪但尚未提交**

本段 6 段的改动**全部停在本地工作树**（未提交、未推送），故本文档不列提交号。落地前工作树为脏，
清单见下一段会话的开始处。

**本段未做（附理由）**：

- **`PLAN_IN_USE` 的 HTTP 场景靠库层夹具铺出来**：`startExercise` 仍是 501，没有开演入口就造不出
  「想定正在演习中」的真场景。`PlanHttpIT` 直接插一行进行中的 `t_exercise`（并在用例内收尾），
  为的是验证新登记的 `PLAN_IN_USE` 确实折成 409 而不是落 500。**这条夹具在 `startExercise` 接线后应替换为真实调用。**
- **当前演习 4 个端点（`getCurrentExercise` / `startExercise` / `stopExercise` / `listCurrentExerciseLogs`）
  仍为 501**：属 LIFE 后续，需接 `ExerciseLifecycleService` 与网关下行。
- **`Exercise.heartbeatAt` 未并入领域对象**：`ExerciseRepository.findRunningLiveness` 仍单独读，
  理由见该方法注释（本次接线不需要它）。
- **客户端（M6）与 STATE / HW-LINK 未开始**：PLAN 的消费方是 Windows 指挥台，属 M6。

### D06 / D10 执行边界（2026-10-23，HW-LINK 前置决策）

依据 [设计 05](design/05-hw-link.md)（本段新增）。这是**开工前的分段方案**，不是实现：
`scds-gateway` 目前**只有 `pom.xml`，零 Java 源码**，`DeviceLinkAdmin` 与 `HardwareEventSink` 两个端口均无实现。

**用户逐项裁定（4 项）**

| 项 | 裁定 |
|---|---|
| UWB SERIAL 首版范围 | **首版一并交付 SERIAL**（不是只做 TCP_SERVER）。端口新增 `openUwbSerial`/`closeUwbSerial`；两种 UWB 模式互斥，任一已开时另一种 `open` 返回 409 `DEVICE_LINK_ALREADY_OPEN` |
| 已开重开语义（R15 核心冲突） | **对齐契约返回 409**，改掉 `DeviceLinkAdmin` 里「已打开则先关闭再重开」的注释，**契约不动**。理由：客户端要能区分「我要开的已经在开」与「中途断过又重连」 |
| 无硬件仿真适配器 | **做**，`scds.gateway.mode=simulator` 显式开启；供开发与自动测试验证状态机、上下行与端点接线。生产必须显式切回 `real`，**仿真通过不得写成现场通过** |
| D10 容量参数 | **先落可配置项 + 明确标注未验证**：协议已冻结常量（200ms 轮询、5000ms 在线窗口、12001、115200、UWB 串口 460800）照搬；队列容量、Netty 线程数等用设计候选值并全部走 `scds.gateway.*` 配置，文档写明**不是生产容量结论**，待现场人数/基站/客户端/帧率/SLA 与 RPO/RTO 数据再定 |

**本段新增的分段方案**

设计文档 [design/05-hw-link.md](design/05-hw-link.md) 把 HW-LINK 拆为 H0~H7 共 8 段（每段 ≤3 文件）：

| 段 | 内容 |
|---|---|
| H0 | 本决策收口（0 代码） |
| H1 | R15 语义收口：端口注释对齐 409 + 新增 `openUwbSerial`/`closeUwbSerial` + 契约描述同步 |
| H2 | `LinkRegistry` + 链路状态机与计数（纯 Java，可单测） |
| H3 | 马甲串口适配器（jSerialComm：打开/关闭/读线程→`SerialFramer`→sink、200ms 轮询、有界发送队列） |
| H4 | UWB 适配器（Netty TCP_SERVER + 串口 SERIAL，共用同一 `UwbFramer`） |
| H5 | `GatewayProperties` + 仿真模式 `SimulatedLinkAdapter` |
| H6 | `DeviceController` 接线（6 条：`getLinks`/`listSerialPorts`/开关串口/开关 UWB） |
| H7 | 端到端 IT（真实回环 TCP 12001 + 仿真串口，走完整生产入口） |

**方案里三处必须记明的判断**

1. **`getVestLink` 本工作包不接线，保持 501**。契约里它返回的是**每马甲**在线态
   （`lastHeartbeatAt`/`lastFrameType`/`framesTotal`，且明文写「与态势域 VestStatus 一致」），
   权威来源是 core 的状态机而非链路级状态。在 STATE 就绪前，**不得用链路计数冒充每马甲计数**。
   因此 H6 接线的是 6 条而非常被误数的 7 条。
2. **上行链路当前没有消费者**：`HardwareEventSink` 的实现属 STATE（未开工）。在它有实现前，
   app 装配提供一个**显式的日志型 sink**并在启动时打 WARN，用日志暴露边界，而不是让帧静默消失。
   这不是可省略的说明，而是本工作包的完成定义上限——「网关能解帧」≠「态势可用」。
3. **`ff06` 的丢弃留在 core**：协议 §1.4④ 要求 UWB 生效时丢弃旧室内定位帧，但这是业务开关，
   网关保持哑管道（照常上报），由 STATE 侧按 UWB 是否生效决定丢弃。

**本段未做**（H0 收口时的状态）：`watchLinks` 的 WS 推送与 `LinkStatusPushPort`（依赖 D07 的数组/对象同构口径）；
UWB 标定三端点（仍 501）；`scds-gateway` 的任何代码。

**实施进度（2026-10-23 晚）**：H1~H7 已完成——`scds-gateway` 现有 10 个主源码类
（`LinkRegistry`/`LinkSlot`/`SerialChannel`/`JSerialChannel`/`SerialVestLink`/`UwbTcpServerLink`/
`UwbSerialLink`/`GatewayProperties`/`SimulatedLinkAdapter`/`RealDeviceLinkAdmin`），
装配层落在 `scds-app` 的 `com.dualx.config`（`GatewayConfig` + `LoggingHardwareEventSink`），
`DeviceController` 的 6 条链路端点已接线、`getVestLink` 仍 501。
H7 的端到端 IT 为 `scds-app` 的 `HwLinkHttpIT`（5 例）：仿真上下文验全部链路语义，
real 上下文在硬约束 12001 上做真实回环并核对 `framesTotal`/`fdCount` 后验证端口释放。
上句「`scds-gateway` 的任何代码」描述的是 H0 收口时的状态，已不再成立；
H6 的拆分与两处偏差、H7 的覆盖映射见 [design/05-hw-link.md](design/05-hw-link.md) §5 实施记录。
全量 `mvn test` 通过（scds-app 290 例，8 模块 BUILD SUCCESS）。

**本工作包仍不能证明**（不等于可宣称已验收）：真串口 115200 与马甲固件对拍、真实 UWB 盒子经
12001 的连续坐标、D10 容量与长稳/RPO/RTO——全部属现场验收。

**提交状态（2026-10-24 更新）**：H0~H7 的全部文件**已按 3 个语义提交并入远端**——
`18a1358` docs(hw-link)、`c85df04` feat(gateway)、`450794b` feat(device)；
`git push` 输出 `4efd272..450794b  main -> main`，推送成功，工作树干净。

---

### STATE 分段（一）执行边界（2026-10-24，M3 态势状态所有者）

依据 [设计 06](design/06-state.md)（本段新增）。这是**开工前的分段方案**，不是实现：
`HardwareEventSink` 仍无实现，四个态势端点与 `getVestLink` 仍为 501。

**用户逐项裁定（4 项，均取推荐口径）**

| 项 | 裁定 |
|---|---|
| revision 增量范围 | **做增量环形窗口**：单写 revision + 内存环形窗口 + `sinceRevision` 增量 + 超窗 409 `SITUATION_REVISION_TOO_OLD`；窗口容量走配置。**不做 epoch/跨代**（D08 留 PUSH-MODEL） |
| 5000ms 在线判定驱动 | **定时扫描产生跳变**：按可配置周期（默认 1000ms，必须 < 5000）扫描最后心跳，越界即 `LIVE→OFFLINE` 并留一条 `STATE_CHANGE` 采样点。不用读取时惰性判定（轨迹里必须有离散的离线时刻） |
| 事件处理线程模型 | **单消费者事件循环**：有界队列 + 1 个工作线程单写全部态势态，内部无锁；容量/溢出走 `scds.state.*` 配置（D10 候选值，标注未验证）。不引入虚拟线程（D10 要求先实测选型） |
| 无硬件验证与容量口径 | **复用 `scds.gateway.mode=simulator`** 注入帧做端到端 IT，不新增第二套开关；状态侧容量、采样周期、位移阈值全部走 `scds.state.*` 并标注「非生产容量结论」 |

**本段新增的分段方案**

设计文档 [design/06-state.md](design/06-state.md) 把 STATE 拆为 S1~S8 共 8 段（每段 ≤3 文件）：
S1 载荷下沉（`CombatRole`/`UnitState` 由 api 搬入 `com.dualx.core.push`，拆 S1a/S1b/S1c）
→ S2 有界队列与单消费者循环 → S3 单一所有者核心（在线/冻结/命中/计数）
→ S4 采样判定与批量落库 → S5 revision 环形窗口与增量 → S6 与 LIFE 衔接（装载编成、停演终局）
→ S7 core 读侧 `SituationQuery` → S8 core 端到端 IT。

**载荷与读侧归属收口（2026-10-24，对齐设计 02）**

分段方案初稿曾拟在 `scds-domain` 另造 `SituationSnapshot`/`UnitSituation`/`SituationDelta`，
并把 `SituationQuery` 做成 domain 端口；开工前核查发现这与既有
[设计 02](design/02-push-ports.md) §4.3/§5 冲突——后者已裁定把
`SituationEnvelope`/`UnitState`/`TeamSummary`/`HitEvent`/`CombatRole` **下沉**到 `com.dualx.core.push`、
`SituationQuery` 落在 **scds-core**，并明确否决「双模型 + 映射函数」乙案。
两套并行会留下重复类型与一次多余搬迁，故收口如下（用户已跳过该提问，按既有设计自决）：

1. STATE 只下沉**状态所有者真正持有**的两个类型 `CombatRole`、`UnitState`（S1a~S1c）。
   其余 `SituationEnvelope`/`TeamSummary`/`HitEvent`（线载荷）仍按设计 02 在 **M4** 下沉。
2. `SituationQuery` 是 **core 读侧**（不是 domain 端口）；STATE 交付它，M4 直接接线，不新增映射层。
3. **HTTP 4 端点、`getVestLink`、WS 通道与背压补齐属 M4**（[实施计划](implementation-plan.md) M4
   「domain 载荷和推送 SPI、Poll 查询、链路同构」）。STATE 门禁不变（单一状态所有者、有界事件处理、
   5000ms、冻结、命中与批量轨迹），S7/S8 判据在 core 层证明。
4. revision 窗口键沿用设计 02 既有 `scds.situation.revision-window`（原拟 `scds.state.revision-window` **作废**）；
   其余状态侧参数仍用新前缀 `scds.state.*`。
5. `combatRole` 的 `WEAPON`/`VEHICLE` 划分与编号空隙归属**未裁定**：本段只做**原样搬迁**，
   不新造推导规则，S3 填值前先裁定。

**方案里四处必须记明的判断**

1. **不需要新迁移**：`t_track_sample`(V5)、`t_hit_event`(V4)、`t_exercise_unit`(V4) 与所需列/索引已存在。
2. **命中致死采用「回填」口径（本包自定，非裁定项）**：`ff05` 不携带强度（PROTOCOL.md §6.7），
   阵亡权威是马甲自报 `hp`，故命中先落 `lethal=false`，该马甲转入 `DIE` 时回填其最近一条命中为 `true`。
   原基线没有这张判定表；若现场另有定则只改这一处。
3. **停演顺序不可换**：STATE 必须先写全体单位终局再 flush 轨迹，最后才由 LIFE 调 `freezeAndClose`——
   后者读的是库里的 `final_status`（`countDeadByExerciseId`），顺序反了会读到 0 阵亡。
4. **`getVestLink` 的字段跨两层来源**：`online`/`lastHeartbeatAt`/`lastFrameType`/每马甲 `framesTotal` 属 STATE，
   而现有 6 条链路端点的计数属**链路级**（HW-LINK）；两处不得互相冒充。

**本段未做**：`watchSituation`（WS）与 `SituationPushPort`/`LinkStatusPushPort`（PUSH-MODEL/PUSH-WS，依赖 D07/D08）；
`epoch`/跨代恢复（D08）；COMMAND 指令状态机；CALIBRATION；REPLAY；EXPORT。

**实施进度（2026-10-25）**：**S1~S8 已完成**——S4/S5 零件已接线（S4b）、S6 编排已落地、S7 读侧已落地、
S8 装配层与端到端 IT 已落地。

- **S1（S1a~S1c 载荷下沉）**：`CombatRole`/`UnitState` 已搬入 `com.dualx.core.push`，api 旧类已删，
  引用方（`SituationEnvelope`/`SituationController`/`DeviceSelf`）已改指向（`241383d`）。
- **S2（有界事件队列）**：`StateEvent`（上行帧/链路变化/离线扫描同一队列事实）+ `StateEventQueue`
  （非阻塞 `offer`、`REJECT`/`DROP_LOW_PRIORITY` 两种满队列策略、丢弃与拒收计数可观测）（`43e6dc8`）。
- **S3（单一所有者核心）**：`SituationStateService` 已落地——实现 `HardwareEventSink`（只入队，不阻塞读线程）
  + 唯一写手平台线程（`scds-state-writer`）串行处理；在线/离线判定（5000ms）、阵亡冻结与判活、
  命中落库与致死回填（`HitEventRepository.markMostRecentLethal`）、每马甲命中/击杀计数、revision 单写。
  17 例纯单测（仓储 mock + 可控时钟，不起线程）（`c4060ea`）。
  全量 `mvn -o test` 全绿。
- **S4（采样判定 + 批量落库）**：`TrackSampler`（降采样判定：EVENT / STATE_CHANGE / DISPLACEMENT / TIMER
  四类理由，按此优先级短路；每马甲上次采样点、`reset()` 供装载新演习时清底）+ `TrackBatchWriter`
  （内存缓冲，够 `batchSize` 或过 `flushIntervalMs` 由写手线程一次 `insertAll`，停演强制 `flush()`）；
  21 例纯单测（仓储 mock + 可控时基）。全量 `mvn -o test` 全绿。
- **S5（增量环形窗口）**：`SituationRevisionLog` 已落地——保留最近 `scds.situation.revision-window` 条
  **单元级**变化（`ArrayDeque` 尾插、满则去头并记已淘汰 revision），`changesSince(N)` 返回 revision &gt; N 的
  升序变化、`tooOld(N)` 判超窗、`clear()` 供演习切换清窗；9 例纯单测。全量 `mvn -o test` 全绿。

**S3 的口径与未闭合缺口**（不得当成已实现）：

1. **心跳只刷新在线窗口基，不计入 revision**：否则 200ms 一轮的 `ff03` 会让全量单元每拍都「变」，
   S5 的 `scds.situation.revision-window`（1024 条）瞬间被刷空，增量通道失去意义。status 跳变才 +1。
2. **`combatRole` 恒为 `null`**：编号区间→`CombatRole` 的划分仍未裁定（见上文第 5 条）；
   `weaponKind` 已按 `WeaponKind.fromNum` 填。
3. **UWB 坐标解算与 `ff06` 室内定位未接入**：二者需要地图域 `uwb_meta` + `UwbSolver`，属后续分段；
   当前 `UwbFrame` 只计数（`uwbFramesUnhandled`）、`ff06` 只打 DEBUG，室内坐标不产生。
4. **GPS 只落 WGS-84**：`GeoPoint.lng/lat`（BD-09）的换算规则是 `requirements.md` 里未闭合的
   **Q-LEGACY-RULE**，本段不臆造，留空。
5. **`signalQuality` 未填**：其来源是 UWB 解算的可用基站数，随第 3 条一并延后。
6. **停演终局只到内存出口**：S3 的 `endExercise()` 返回 `UnitOutcome` 列表，
   真正调 `ExerciseUnitRepository.freezeOutcome` 落库的编排属 S6。

**S4 的口径与未接线**（不得当成已接线）：

1. **理由优先级短路**：EVENT（由调用方在事件点显式指定）→ STATE_CHANGE（`status` 或 `motion` 与上次采样不同）
   → DISPLACEMENT（位移越阈值）→ TIMER（距上次采样达周期）。**首次观察记 STATE_CHANGE**——一个单元第一次
   进入采样视野时没有可比基准，算作状态确立最贴近事实。
2. **位移阈值的单位**：室内的阈值作用于 `GeoPoint.xPct/yPct` 的归一化平面距离（列名即 0~1，设计 06 §4.7 表里
   写「室内像素」指的是同一对参数，**以 `GeoPoint` 列定义为准**）；室外用 WGS-84 上两点的大地距离（米）。
   两侧不可比（一侧无坐标、室内↔室外切换）时按「有位移」处理，宁可多记不可漏记。
3. **落库失败策略（本包自定，非裁定项）**：失败的一批**留在缓冲里等下次重试**，记 ERROR 与计数，态势状态
   照常演进（不回滚内存态）；缓冲设硬上限，持续失败时丢弃**最旧**的一批并计数，保证内存有界。
   已知取舍：JDBC batch 若异常发生在批次中途，重试会产生少量重复行（`t_track_sample.id` 自增，无法用主键去重）——
   宁可偶发重复也不静默丢点；现场若证明重复不可接受，只改这一处失败策略。
4. **没有独立的 flush 定时线程**：间隔判定在写手线程每次 `add` 时顺带完成（设计 06 §4.4 要求「由事件线程
   一次 `insertAll`」），帧流停下的场景由停演收尾的强制 `flush()` 兜底；多一个定时线程只会多一处并发面。
5. **已接线**：见下文「S4b 接线段」。三个容量参数（`scds.state.track-batch-size` / `track-flush-interval-ms` /
   缓冲硬上限）与采样参数（`scds.state.sample-interval-ms` / `sample-displacement-indoor` / `-outdoor`）
   **仍无默认值**，由装配层绑定，D10 未闭合前不得据其宣称容量达标。

**S5 的口径**：

1. **超窗判据是「`sinceRevision` 小于已淘汰的最新 revision」，不是「小于窗口内最旧的 revision」**：
   revision 只在单元变化时递增、是**稀疏**的，窗口内最旧的条目可能远大于 `sinceRevision`；
   中间被淘汰的条目若都不大于 `sinceRevision`，客户端并没有漏掉它需要的东西。
   例：容量 3、已淘汰 revision 5、保留 8/9/12 → `since=5` 可补齐（`5 ≥ 5`），`since=4` 才超窗。
2. **「已是最新」与「超窗」必须可区分**：`changesSince` 用 `Optional` 表达——空列表 = 已是最新（HTTP 200 空增量），
   空 `Optional` = 超窗（409 `SITUATION_REVISION_TOO_OLD`）。混用会让新连接被无理由拒绝。
3. **窗口不去重**：同一个马甲在窗口内可有多条（它在不同 revision 上各变化过一次）；增量载荷要不要按
   `vestNum` 压成最后一条，属 S7 组装信封的口径，本段不预设（压与不压都收敛到同一终态）。
4. **演习切换必须 `clear()`**：装载新演习时 revision 归零（设计 06 §4.5），不清窗会让上一场的条目以更大的
   revision 留在窗口里、被客户端当成「更新的变化」；切换本身由 `full=true` 帧表达（设计 02 §4.4）。
   ⚠ **两处设计在此冲突**：设计 02 §4.4 写「演习切换 revision **继续递增不回退**」，设计 06 §4.5 写
   「装载时**归零**」。本段依**设计 06** 执行（它是对 STATE 包的专门裁定），冲突留待 PUSH-MODEL 收口。
5. **已接线**：`publish`/`publishEvent` 每记一次单元变化就写一条；心跳值未变走 `refresh`，**不入窗**
   （否则 200ms 一轮的心跳会把窗口刷空）。见下文「S4b 接线段」。

**S4b 接线段（已完成，闭合上文两处「未接线」）**：`TrackSampler`、`TrackBatchWriter`、`SituationRevisionLog`
已接入 `SituationStateService`——构造注入三者，接线点与口径如下：

1. **观察点**：`publish`（每个单元变化）与 `refresh`（心跳值未变）都过一次
   `TrackSampler.onObservation`，由它按 `STATE_CHANGE > DISPLACEMENT > TIMER` 决定记不记。
   `refresh` 也过观察点是必要的：否则静止单元永远拿不到 `TIMER` 采样。
2. **事件点**：`publishEvent` 固定记 `EVENT`，用于**命中**（被击中者）、**阵亡**、**判活**三处；
   命中即使没改变被击中者状态也必须留点，这是 EVENT 不受抑制的意义。
3. **窗口只记变化**：`publish`/`publishEvent` 各写一条 `SituationRevisionLog.record`；
   `refresh` **不写**（它不 bump revision，写进去会把窗口里的真实变化挤掉）。
4. **装载清底**：`beginExercise` 调 `trackSampler.reset()` + `revisionLog.clear()`——`tMs` 与 revision 都已归零，
   留着上一场的采样基准会隔着演习作差、留着上一场的条目会被当成「更新的变化」。
5. **收尾先落库**：`endExercise` 在清内存态前调 `trackWriter.flush()`，末段轨迹不丢在缓冲里；
   与 `freezeOutcome` 的先后（先写终局再 flush）属 S6 的编排，本段只保证「清内存态前先落库」。

**S6（与 LIFE 衔接，已完成）**：`SituationSessionBinder` 已落地（`scds-core`），把「开演装载编成」与
「停演写终局」两个跨包动作收在一处，使 `SituationStateService` 保持纯状态机：

1. **开演**：`onExerciseStarted(Exercise)` 按 `exercise.planId()` 读**想定编成**后调 `beginExercise`。
   ⚠ **与设计 06 §4.6 原话「从 `t_exercise_unit` 读编成」不一致**：`t_exercise_unit` 是终局台账，
   只有 `vest_num/name/team/final_status/...`，**没有**初始 `hp/ammo/mode` 三列；而 `beginExercise` 要的正是
   带这三项的 `RosterEntry`（= `t_plan_roster` 的领域记录）。两份编成在开演时同源（演习快照由 `startExercise`
   从想定编成而来），故取**字段更全的一方**读，**不新造默认值**。该偏离以代码类注释与本节为准，留待回写 §4.6。
2. **停演**：`onExerciseStopping(UUID)` 先校验请求 id 与内存态装载的演习一致（无演习、或 id 不匹配时**不写库**
   并 warn），再 `endExercise()`（内部 flush 轨迹）→ 逐个 `exerciseUnits.freezeOutcome(...)`。**必须在 LIFE 的
   `freezeAndClose` 之前调用**：后者读的正是 `t_exercise_unit.final_status`（`countDeadByExerciseId`），
   顺序反了会把阵亡记成 0。
3. **flush 位置**：设计 06 §4.6 写「先写终局、再 flush 轨迹」，本实现把 flush 收进 `endExercise()`（即终局**之前**）。
   理由是 flush 位置不影响任何读者（`freezeAndClose` 读 `t_exercise_unit` + `t_hit_event`，**不读**
   `t_track_sample`），收进 `endExercise` 可让「停演必 flush」不依赖调用方纪律；两者仍都在 `freezeAndClose` 之前完成。

**S7（core 读侧，已完成）**：`SituationQuery` 已落地（`scds-core`），把「内存态快照 / 增量窗口 /
命中流水 / 队伍汇总」四类读取收在一处，供 `SituationController` 与 WS 补齐逻辑共用；底层为
`SituationStateService.changesSince(long)` 委托原语（读 S5 的 `SituationRevisionLog`）。五处口径：

1. **不组装信封**：`SituationEnvelope` 的组装与 `full/增量` 判定属 M4。契约信封里的 `hits` 该取哪一段区间
   需要客户端已交付的 `seq`，而 HTTP `GET /situation` 只有 `sinceRevision`——`seq` 与 `revision`
   **互不推导**（设计 02 §4.4），故该口径留 M4 收口；单马甲的命中补拉走契约已有的 `GET /situation/hits?sinceSeq=`。
2. **增量不按 `vestNum` 去重**：窗口内同一马甲可有多条（不同 revision 各变过一次）。设计 02 §4.4 的口径是
   **客户端按 revision 去重**，且 WS「从 N+1 起逐帧补齐」需要逐 revision 的原文，压掉就无法逐帧推进。
   这是对 S5 口径第 3 条（「压与不压属 S7 组装信封的口径」）的收口：本段明确**不压**。
3. **超窗语义**：`changesSince` 用 `Optional` 区分——空列表 = 已是最新（M4 应回 200 空增量），
   空 `Optional` = 超窗（M4 应回 409 `SITUATION_REVISION_TOO_OLD`）。本段只证语义与 `code`，HTTP 状态码由 M4 映射。
4. **队伍汇总**：只含有编成的队伍、按 `Team` 声明序；`hitsDealt` 是该队成员**打出的**命中数之和
   （与 `t_exercise_unit.hits_dealt` 同口径）。
5. **未装配**：`SituationQuery` 与 `SituationStateService` 一样**不加** `@Component`——现在加了会因找不到
   `SituationStateService` bean 而让整个应用上下文起不来；装配随 S8 落地。

**S8（装配层 + 端到端 IT，已完成）**：分两步落地。

- **S8a（装配层）**：新增 `SituationStateConfig`（`scds-app` 的 `com.dualx.config`），把全部态势 bean
  （`clock` / `stateEventQueue` / `trackSampler` / `trackBatchWriter` / `situationRevisionLog` /
  `situationStateService`（`@Bean(initMethod="start", destroyMethod="close")`）/ `situationQuery` /
  `situationSessionBinder` / `offlineScanJob`）收在一处，并绑定 `scds.state.*`（`SituationStateProperties`，绑定期校验）。
  嵌套 `OfflineScanJob` 用 `@Scheduled(fixedDelayString="${scds.state.offline-scan-interval-ms}")` 把
  `StateEvent.OfflineScan` 投进**同一队列**（在线/离线判定与上行帧同一条单消费者事实，
  5000ms 窗口只在这一处随时间产生跳变）。同时删除占位 `LoggingHardwareEventSink`、
  移除 `GatewayConfig.hardwareEventSink()`（`deviceLinkAdmin` 仍注入 `HardwareEventSink`）——
  **唯一** `HardwareEventSink` bean 现为 `SituationStateService`。`application.yml` 落 `scds.state.*`
  九键与 `scds.situation.revision-window: 1024`。`@EnableScheduling` 挂在唯一入口 `ScdsNextApplication`。
- **S8b（端到端 IT）**：`SituationStateIT`（`scds-app`）走完整生产入口装配（真 MySQL、真安全链、真写手线程），
  经**真实网关成帧**注入协议黄金样本帧，断言 **core 读侧** `SituationQuery`。四个用例：①唯一 sink 就是状态机
  且在线扫描生产者已就绪；②无演习时不产生单元、编成外马甲只计数不改状态（`revision` 在停演不清零，
  只断言不被该帧推进）；③黄金样本驱动读侧：心跳→LIVE、GPS→定位（WGS-84 由协议库独立复算，不手抄）、
  命中→落 `t_hit_event` + 每马甲计数 + 队伍汇总、`hp=0`→DIE 冻结坐标并验阵亡终态不被后续心跳复活；
  ④在线扫描超 5000ms 翻 `OFFLINE` 并留 `STATE_CHANGE` 采样，停演收尾写 `t_exercise_unit` 终局与末段轨迹。
  **刻意不覆盖两条（已由 core 单测证明）**：致死回填 `kills`（黄金样本 `ff05` 被击中者 4/5 与 `ff03` 的
  `hp=0` 者 1/2 无交集，拼不出同一马甲的「先命中后阵亡」）与增量超窗 409（装配窗口 1024 端到端刷满不现实，
  语义由 `SituationQueryTest` 证明、HTTP 状态码映射属 M4）。整仓 `mvn -o test` 全绿（app 294）。

**仍未闭合**：HTTP 接线仍未做——四个态势端点（`SituationController`）与 `DeviceController.getVestLink`
**仍为 501**，`SituationEnvelope`/`TeamSummary`/`HitEvent` 线载荷下沉、WS 通道与背压补齐属 **M4**。
装配层虽已把 `beginExercise`/`endExercise` 的**零件**接起来，但生产路径上的**开演/停演调用方**（LIFE 的
`startExercise`/`stopExercise` 真的调 `SituationSessionBinder`）尚未接线，故现下**不得宣称开演/停演已在
生产路径上驱动态势**，也不得宣称轨迹与增量已在生产接口上产出——S8 交付到「装配可见 + core 读侧可断言」为止。

---

### LIFE 分段（二）执行边界（2026-10-25，M3 演习生命周期开停演接线）

依据 [实施计划](implementation-plan.md) M3 LIFE 与 [设计 01](design/01-exercise-liveness.md)（§4.3 实例标识与
心跳、§4.4 启动自愈与部署前提、§4.5 心跳绝不自动收尾、§7 判据 6 条）。LIFE 分段（一）交付了「冻结收尾 +
自愈」的零件，但**开演入口仍是 501**——没有入口就没有可被拒绝的动作，故恢复完成门禁也悬着。本段把
`exercise.yaml` 的当前演习 4 端点里的 3 个（`getCurrentExercise` / `startExercise` / `stopExercise`）从 501
接到 `ExerciseLifecycleService` + `SituationQuery`（`listCurrentExerciseLogs` 的游标口径归 M4 PUSH，仍 501），
并按「每个原子任务 ≤3 文件」分成 L1~L7。

| 段 | 内容 |
|---|---|
| L1 | 错误码与全局异常处理：`ApiExceptionHandler.statusOf` 把 `EXERCISE_ALREADY_RUNNING` 从台账段移出，新设「演习生命周期」段逐项登记 `EXERCISE_PLAN_NOT_FOUND`(404) / `EXERCISE_ROSTER_EMPTY`(400) / `EXERCISE_ALREADY_RUNNING`(409) / `EXERCISE_NOT_RUNNING`(409) |
| L2 | `ExerciseLifecycleService` 起停逻辑：`currentExercise`（读库而不读态势内存态）/ `startExercise` / `insertRunning` / `snapshot` / `stopExercise` / `StopOutcome`（`freezeAndClose` 与 `CloseOutcome` 前段已有，本段只接线） |
| L3 | `ExerciseLifecycleServiceIT`（12 例）：开演成功与 `owner_instance` 落库、404/400/409 三门、停演 200 与「无场次」409、已结束场次再收尾不改写 |
| L4 | HTTP 接线：`ExerciseController` 构造器改三参（`PlanService` / `ExerciseLifecycleService` / `SituationQuery`），接 `getCurrentExercise` / `startExercise` / `stopExercise`；新增 `toExercise` / `onlineCount` / `noExerciseRunning` / `exercisePlanId`；`StopExerciseResult` 增 `endReason`；`ApiSkeletonSmokeIT` 同步（两个替身 bean + 构造签名） |
| L5 | 恢复完成门禁：L5a 契约与枚举 / L5b 载体与发布 / L5c 映射与用例（见下「门禁的三处落地」） |
| L6 | 单实例保护：`docs/environments.md` §6.4（进程管理层职责，**无 Java 代码改动**） |
| L7 | 整仓回归（`RoleWritePermissionIT` 补两个替身 bean）与本文档回写；补 `ExerciseLifecycleHttpIT`（当前演习的端到端 HTTP 覆盖） |

**用户逐项裁定（2 项）**

| 项 | 裁定 |
|---|---|
| 门禁被拒的错误码 | **新增 `INSTANCE_NOT_READY`（503）**，不复用 `INTERNAL_ERROR`(500)：实例本身没坏，只是还没准备好接业务，客户端动作是「稍后重试」而不是翻日志。按契约优先的规矩，先在 `common.yaml` 的 `Problem.code` 登记，再在 `exercise.yaml` 的 `startExercise` 补 503 响应 |
| 门禁状态由谁承载 | **`ExerciseLifecycleService` 持 `AtomicBoolean`**；发布方是 `ExerciseLivenessRecover`，不另设就绪标志、不放到配置或环境变量里 |

**为什么门禁必须存在（本段最核心的一条）**

`ApplicationRunner` 排在 `ApplicationReadyEvent` 之前，但**挡不住已经监听的 HTTP 端口收请求**。启动瞬间存在
这样一个窗口：进程活着、端口通、自愈还没把上次崩溃留下的「进行中」场次收尾。此刻若放行开演，新场次会撞
`uk_exercise_running`，而操作员看到的是一个看着正常、行为说不通的实例。故五条口径：

1. **门禁在 `startExercise` 的方法最前，先于任何查库**。自愈没跑完时实例的库状态本身不可信（上一场残留行还
   在），此时报「想定不存在」只会误导现场；503 的语义是「稍后重试」，优先于 404/400 报出——门禁未放行就谈
   不上请求内容对不对。
2. **门禁只拦开演**：停演与读侧不拦。运维正好要靠 `GET /exercise/current` 看见残留场次并停掉它，把读侧也拦
   住等于把唯一的自救通道关掉。
3. **门禁只进不退**：没有对应的复位方法。自愈在一个进程生命周期里只跑一次，置位后再复位没有真实场景，反而
   会给出「可以退回未就绪」的错觉。
4. **恢复失败不走本码**：那时启动直接失败，进程根本不接受请求——那是门禁的最强形态。本码只覆盖「还在跑、
   但没跑完」这一段。
5. **503 不带 `Retry-After`**：窗口多长取决于残留场次的收尾耗时，编一个秒数反而会让客户端按错误的节奏重试。
   `ApiExceptionHandlerBusinessTest` 显式断言该响应头不存在。

**门禁的三处落地**（缺任一处都不成立）

- **契约与枚举（L5a）**：`common.yaml` 登记 `INSTANCE_NOT_READY`（含与 `INTERNAL_ERROR` 的分工说明）；
  `exercise.yaml` 的 `startExercise` 补 503；`ApiErrorCode` 新增实例级枚举段。
- **载体与发布（L5b）**：`ExerciseLifecycleService.recovered`（`AtomicBoolean`，初值 `false`）+ `markRecovered()`；
  `ExerciseLivenessRecover.run()` 在 `findRunningLiveness()` 处理完之后**无条件**调 `markRecovered()`——
  放在 `ifPresent` 之内就错了，**没有残留场次也是一次完整的自愈**。
- **映射与用例（L5c）**：`ApiExceptionHandler.statusOf` 映射 503；`ApiExceptionHandlerBusinessTest` 的 503 用例；
  新建 `ExerciseLifecycleServiceReadinessTest`。**为什么这个负向面必须手建实例**：`@SpringBootTest` 上下文启动
  即跑完 Runner，永远「已就绪」，拒绝路径在真上下文里走不到。该用例同时断言
  `verifyNoInteractions(plans, planRoster, exercises, units, sessions)`——门禁先于查库是**判据**，不是巧合。

**`onlineCount` 的口径（本段接线时定，写入 `ExerciseController` 注释）**

在线数取**态势读侧**而非库：库里根本没有这一列，在线与否是内存态的事。算法是「全体减离线」，其中 `OFFLINE`
是**唯一的链路离线标记**（由 5000ms 心跳窗口扫描判出），`DIE` 则是马甲自报阵亡、与链路无关——**阵亡马甲仍在
线上报，故仍计在线**。「参演 10 / 在线 8」说的是有几台还在上报，而不是有几台还活着（存活/阵亡的分解在队伍
汇总里）。开演那一刻全体刚被装载成 `OFFLINE`，此刻算出来就是 0（端到端 IT 断言这一点）。

**L6 的结论：单实例保护落在进程管理层，不在应用层**

核查后确认应用侧三件**已具备且有 IT 覆盖**：开演 `claim(owner_instance)`、2000ms `heartbeat`（守卫带
`owner_instance`）、`uk_exercise_running` 唯一索引。**唯一的真实缺口是文档**：设计 01 §4.4 把「启动单实例保护」
指向 `environments.md`，而该节原本缺失，故本段无 Java 代码可改（D05 又禁止引入分布式中间件与库级锁）。
补 §6.4 记录四件事：为什么必须靠进程管理层（自愈「一律收尾」**不区分 owner**，第二进程会把第一进程的活跃场次
误停）、四形态要求（容器固定 `container_name` + `restart: unless-stopped`、禁止 `--scale`；原生服务单实例单元；
升级窗口先停后启；不允许第二节点、无 HA）、现场判据（第二进程起来必留自愈 WARN `CRASH_RECOVERED`/
`INSTANCE_RESTART` → 视为部署违规；「起来了但开不了演」应查启动日志而非重试）、以及**明确不做**（不引中间件、
不带库级会话锁或 `GET_LOCK`、不做租约）。

**L7 的回归破损（本段实际踩到，与 PLAN 段 §7 同一类）**

整仓回归暴露 `RoleWritePermissionIT` 3 例上下文加载失败：`Unsatisfied dependency expressed through constructor
parameter 1: No qualifying bean of type 'com.dualx.core.exercise.ExerciseLifecycleService' available`。根因是
L4b 让 `ExerciseController` 依赖 `ExerciseLifecycleService` + `SituationQuery`，而该 IT 的切片
（`PermissionApplication`）排掉了 DataSource，没有这两个 bean。修法：补两个 `stub(...)` 替身（放行的开演 /
停演 / 读当前演习因此稳定返回 501，都不是 401/403，不影响该类的判据）。**这是接线类改动的连带项**：凡新增
控制器依赖，都要回头检查所有排掉 DataSource 的切片上下文。

**本节取代 STATE 段末的判断**：`startExercise` / `stopExercise` 现已真的调 `SituationSessionBinder`，
「生产路径上的开演/停演调用方尚未接线」一句（STATE 段末）**已不再成立**——开演装载编成、停演写终局的编排
现由 LIFE 的生产入口驱动；STATE 段其余未闭合项（四个态势 HTTP 端点仍 501、线载荷下沉与 WS 归 M4）不变。

**本段结果**：全量 `mvn -o test` → **BUILD SUCCESS，953 项全绿**（`scds-protocol` 19 / `scds-gateway` 44 /
`scds-core` 485 / `scds-api` 109 / `scds-app` 296）。本段的净增为 `ExerciseLifecycleServiceIT` 12 +
`ExerciseLifecycleServiceReadinessTest` 1 + `ExerciseLifecycleHttpIT` 2 + `ApiExceptionHandlerBusinessTest` 1，
其余为 HW-LINK / STATE / PLAN 各段此前已落地的用例。**未清库、未删卷、未改既有账号、未动已应用迁移**
（本段无新迁移需求）。

**本段未做（附理由）**

- **`listCurrentExerciseLogs` 仍 501**：游标口径（`sinceSeq` → `latestSeq` 的推进）归 M4 PUSH。
- **网关下行未接线**：契约 `startExercise` 步骤 ②③（逐台 14B 轮询配置帧、7B 开始帧）与 `stopExercise` 的 7B
  结束帧都属 gateway。故契约 409 描述里「马甲串口链路未开启导致无法下发配置」这一分支**当前不可能触发**，
  409 只来自 `EXERCISE_ALREADY_RUNNING`。
- **门禁的拒绝路径在 HTTP 上不可达**：真上下文里 `ExerciseLivenessRecover` 是 `ApplicationRunner`，启动完成
  即放行；503 由 core 单测（手建未置位实例）与 `ApiExceptionHandlerBusinessTest` 证明，不写成端到端 IT——
  写不出不存在的时序，也不该为它造第二个开关。
- **`PLAN_IN_USE` 的夹具未换成真开演**：PLAN 段留下的债务（原文「这条夹具在 `startExercise` 接线后应替换为
  真实调用」）。本段**有意保留夹具**并改写注释说明理由——`PlanHttpIT` 的判据是授权与校验口径，真开演会把态势
  内存态与终局冻结拖进来（那是 `ExerciseLifecycleHttpIT` 的范围），两处都真开演只会让该类的回收从「按行删」
  变成「还要管内存态」。
- **`Exercise` 的开演返回不含 `endReason`**：契约 `Exercise` schema 无该字段，`endReason` 只出现在停演响应
  （`StopExerciseResult`）；`CRASH_RECOVERED` 只由自愈补写，走本端点恒为 `NORMAL`。
- **设计 02 §4.4（演习切换 revision 继续递增）与设计 06 §4.5（装载归零）的冲突**仍未收口，留 PUSH-MODEL。
- **UWB 解算 / `ff06` 室内定位 / `signalQuality` / BD-09 换算**未接入（承 STATE 段）。

**提交状态**：本段全部改动**停在本地工作树**（未提交、未推送），故本节不列提交号。另 S8 三笔
（`d3761fd` / `43ca2da` / `5594011`）已本地提交但**尚未推送**——`git push` 因 GitHub:443 不可达失败，
`main` 领先 `origin/main` 3 个提交。


