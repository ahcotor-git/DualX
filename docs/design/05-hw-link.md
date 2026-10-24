# 设计 05 —— 硬件链路（HW-LINK 分段方案）

> 编写日期：2026-10-23，Asia/Shanghai。本文是 **D06 / D10 开工前的分段方案**，不是已实现证明。
> 协议字节以 [PROTOCOL.md](../PROTOCOL.md) 为唯一权威；契约以 [api/openapi.yaml](../api/openapi.yaml) 为准。
> 本文不改变任何冻结字节、算法参数或端口号（HTTP 11001 / UWB TCP 12001）。

## 1. 问题

### 1.1 `scds-gateway` 是空模块

[pom.xml](../../scds-gateway/pom.xml) 已声明 jSerialComm 2.11.0、Netty 与 protocol/solver/domain 依赖，
但**没有任何 Java 源码**。因此 `DeviceController` 的 7 个链路端点全部为 `NotImplementedException`（501），
`DeviceLinkAdmin` 与 `HardwareEventSink` 两个端口都没有实现，也没有调用方。

### 1.2 端口与契约的三处不一致（review.md R15）

| # | 不一致 | 位置 |
|---|---|---|
| ① | 端口注释写「已打开则先关闭再重开」，契约却定义已开返回 409 `DEVICE_LINK_ALREADY_OPEN` | [DeviceLinkAdmin.java](../../scds-domain/src/main/java/com/dualx/core/port/DeviceLinkAdmin.java#L23-L33) vs [device.yaml](../../docs/api/paths/device.yaml#L484-L489) |
| ② | 契约 `LinkOpenUwbRequest.dataSource` 允许 `SERIAL`，端口只有 `openUwbTcpServer` | [LinkOpenUwbRequest](../../docs/api/schemas/domain.yaml#L520-L541) vs 端口方法集 |
| ③ | 旧系统「开演才 listen」与契约「HTTP 手动开链路」并存 | 旧基线行为 vs `linkUwb` / `linkSerial` 路径 |

### 1.3 上行链路当前没有业务消费者

`HardwareEventSink` 的实现在 **STATE** 工作包（未开工）。网关解出完整帧后必须回调该端口，
因此本工作包能交付的上限是：**链路生命周期 + 链路状态 + 下行发送**；上行帧到端口边界为止。
这条边界必须写进完成定义，不能把「网关能解帧」说成「态势可用」。

## 2. 现状事实（已核对）

| 项 | 事实 |
|---|---|
| 链路种类 | `LinkKind` = `SERIAL_VEST` / `UWB_TCP_SERVER` / `UWB_SERIAL`，与契约同名 |
| 链路状态 | `LinkState` = `CLOSED` / `OPENING` / `OPEN` / `ERROR`；`OPENING` 是为「打开耗时数百毫秒」准备的必要中间态 |
| 配置 | `LinkConfig` 只存配置不存运行态；`t_link_config` 一行一种，最多三行；`enabled` 语义是「开机自动打开」 |
| 仓储 | `LinkConfigRepository` 已实现（`findByKind` / `findAll` / `insert` / `update`），并已有 IT |
| UWB 串口参数 | 现有 IT 用 `LinkConfig(UWB_SERIAL, "COM3", 460800, false, …)` → UWB 串口默认 **460800**，与马甲串口 115200 不同 |
| 错误码 | `DEVICE_LINK_ALREADY_OPEN`、`DEVICE_LINK_SERIAL_BUSY` 已在 `ApiErrorCode` |
| 协议侧 | `SerialFramer`（含粘包/半包四分支）、`UwbFramer`（`feed`/`remainder`/`reset`/`split`）、`DownlinkCommands`（12 条下行字节）、`ProtocolConstants`（200ms 轮询、5000ms 在线窗口、12001、115200、COM7）均已就绪 |
| 常量归属 | `HEARTBEAT_ONLINE_WINDOW_MS = 5000` 属**在线判定**，归 STATE；网关不判在线 |

## 3. 设计目标与非目标

**目标**

1. 交付可运行的链路生命周期：枚举串口、开关马甲串口、开关 UWB（TCP_SERVER 与 SERIAL 两种模式）。
2. 链路运行态（`LinkStatus`）由网关实时提供，是 HTTP 与后续 WS 的同一份真源。
3. 下行发送：把 `DownlinkCommands` 编好的字节可靠地送进物理链路，队列有界且不阻塞调用方。
4. 上行接收：读线程只做「分片 → 成帧 → 校验 → 回调 sink」，不做落库、解算或阻塞写。
5. 无硬件时可显式切换到仿真模式，让状态机、端点接线与上下行路径能被自动测试覆盖。

**非目标（本工作包不做）**

- 不实现 `HardwareEventSink`（STATE 范围）。
- 不实现 `watchLinks` 的 WS 推送与 `LinkStatusPushPort`（依赖 D07 的链路快照口径）。
- 不做 `getVestLink`（每马甲在线态与计数属 STATE；见 §5 的 H6 说明）。
- 不做 UWB 标定采样（`startUwbCalibration` 等三端点仍为 501，属 MAP-BASE/后续）。
- 不改协议字节、端口号、轮询周期或校验算法。

## 4. 方案

### 4.1 端口边界修订（H1）

| 项 | 口径 |
|---|---|
| 重开语义 | **对齐契约**：目标链路已处于 `OPEN`/`OPENING` 时,`open*` 直接抛 `BusinessException(DEVICE_LINK_ALREADY_OPEN)`（409），**不隐式先关再开**。`close*` 幂等，未打开时也无副作用 |
| 端口方法 | 新增 `LinkStatus openUwbSerial(LinkConfig config)` 与 `closeUwbSerial()`。UWB 两种模式**互斥**：任一模式已开时，另一种 `open` 同样返回 `DEVICE_LINK_ALREADY_OPEN` |
| 端口占用 | 同一物理串口同一时刻只允许一条链路持有。已被本进程其它链路占用，或打开时被系统拒绝 → `DEVICE_LINK_SERIAL_BUSY`（409）。**两种 409 的区别是「谁占着」**：已开是同一逻辑链路的重复请求，占用是物理资源冲突 |
| `snapshot()` 形状 | 对三种 `LinkKind` **恒各返回一条**：未配置过即 `state=CLOSED`、`target=null`。这样双通道载荷稳定，客户端不必区分「缺席」与「关闭」 |
| 契约同步 | 仅改 `device.yaml` 中 `openUwbLink` 的描述（补「SERIAL 与 TCP_SERVER 互斥，已开返回 409」），**不改路径、字段名或状态码集合** |

### 4.2 线程模型（H2）

```
串口读线程 / Netty worker         业务侧
  字节流 ──► SerialFramer/UwbFramer ──► HardwareEventSink（实现方自行入有界队列）
  发送：调用方 ──► sendVestCommand ──► 有界发送队列 ──► 单写线程 ──► 物理链路
```

- **读路径绝不阻塞**：`HardwareEventSink` 的实现约定已经写明「不得阻塞读线程」；网关侧不做任何 `join`/落库/同步等待。
- **写路径必须有界**：`sendVestCommand` 只入队即返回；队列满时按 §4.5 的溢出策略处理，不静默丢高优先级指令。
- **线程归属**：链路句柄自己持有读线程/写线程与 Netty 资源，`close*` 必须能中断它们并释放句柄（可重复调用）。
- 本工作包**不引入虚拟线程**：D10 的线程选型需实测，先按平台线程实现并把线程数走配置，留出后续验证空间。

### 4.3 马甲串口链路（H3）

| 项 | 口径 |
|---|---|
| 打开 | `jSerialComm` 按 `portName` + `baudRate`(默认 115200) + 流控（默认 false）打开；失败按原因折成 `DEVICE_LINK_SERIAL_BUSY` 或 `LinkState.ERROR` |
| 帧源 | 协议 A 是 **ASCII 文本 HEX**，交给 `SerialFramer`；粘包/半包四分支（含丢弃垃圾前缀、连续未闭合清缓存）沿用协议库行为 |
| 轮询 | 按 `POLLING_INTERVAL_MS=200` 下发轮询配置帧。轮询是**链路自持行为**，不依赖 core 逐个触发 |
| `ff06` | 网关**照常上报**，由 core 在 UWB 生效时丢弃（协议 §1.4④）。网关保持哑管道，不把业务开关下移 |
| 关闭 | 停轮询 → 停读线程 → 关闭端口 → 句柄归 `CLOSED`；计数清零 |

### 4.4 UWB 链路（H4）

| 模式 | 口径 |
|---|---|
| `TCP_SERVER` | Netty 监听 `host`(默认 `0.0.0.0`) + `port`；**端口硬约束 12001**，请求里给别的端口 → 400 `VALIDATION_FAILED`（不改硬约束）。`setKeepAlive(true)`、连续 3 分钟无字节打 WARN 但**不主动断链**（现场换基站/重启造成的短暂静默，断链比静默更难排查；H4 实施记录），断链交由运维或重启 |
| 单连接汇聚 | 新连接**踢出**旧连接（旧连接不残留），`fdCount` 反映当前接入数 |
| `SERIAL` | 与 TCP 模式**共用同一个 `UwbFramer`**，不新增专用成帧逻辑；波特率默认 460800 |
| 成帧 | 41B 固定帧，帧头 `AA 29`、帧尾 `55`、校验 `sum([0..38])&0xFF`；错帧由 framer 内部重同步丢弃，**不抛异常、不中断链路**。⚠ `UwbFramer` 只对外给合法帧、不回报逐位判定，故 `droppedTotal`/`crcErrorTotal` 恒为 0——不把字节数冒充坏帧数（H4 实施记录） |
| 语义 | 解出的 `UwbFrame` 直接回调 `onUwbFrame`；解算（`scds-solver`）在 core 侧，网关不做几何计算 |

### 4.5 链路状态机与计数

- `LinkRegistry`（gateway 内）持有 `LinkKind → 链路句柄`，`snapshot()` 由各句柄实时汇总，**不落库**（`LinkStatus` 落库即失真）。
- 迁移：`CLOSED → OPENING → OPEN`；打开失败 → `ERROR`（带 `errorMessage`）；`close` 可到达 `CLOSED`（幂等）。
- 计数（`framesTotal` / `droppedTotal` / `crcErrorTotal` / `lastFrameAt`）自本次 `OPEN` 起累计，`close` 后归零。
- 状态变化回调 `onLinkStateChanged`（sink）。在 STATE 与 D07 就绪前，**该回调只落日志**，不推送、不入队。
- 溢出策略（初值，全部走配置）：轮询/状态类低优先帧溢出可丢；判死、判活、开演、停演、充弹等指令**不丢**——队列满则拒绝并把失败返回调用方，由上层决定重试或告警。

### 4.6 配置项与仿真模式（H5，D10 口径）

统一收在 `GatewayProperties`（前缀 `scds.gateway.*`），**所有容量与超时都是可配置项**：

| 键（拟） | 默认 | 说明 |
|---|---|---|
| `scds.gateway.mode` | `real` | `real` / `simulator`；`simulator` 仅供开发与自动测试 |
| `scds.gateway.serial.send-queue-capacity` | 10 | 沿用旧系统发送队列上限，**不是生产容量结论** |
| `scds.gateway.serial.send-queue-overflow` | `REJECT` | `REJECT` / `DROP_LOW_PRIORITY` |
| `scds.gateway.uwb.tcp-backlog` / `worker-threads` | 待定 | 容量候选，进配置便于现场调整 |
| `scds.gateway.auto-open-on-startup` | `false` | 是否按 `t_link_config.enabled` 开机自动开链路 |

> ⚠ **D10 未闭合声明**：上表数值是**设计候选**，不是容量结论。现场人数/基站数/客户端数/帧率/SLA 与
> 备份 RPO/RTO 未给出前，不得据本表宣称容量达标。§7 的验收判据里，容量项一律标注「待现场验证」。

仿真模式（`scds.gateway.mode=simulator`）用进程内 `SimulatedLinkAdapter` 替换物理链路：
可注入确定的帧字节、可断言 `sendVestCommand` 收到的字节，用于自动测试 §7 的链路与上下行判据。
**生产必须显式切回 `real`**，仿真通过不得写成现场通过。

### 4.7 与 STATE / LIFE 的衔接

- 网关解帧后调 `HardwareEventSink`。在该端口有实现前，app 装配提供一个**显式的日志型 sink**（`LoggingHardwareEventSink`），
  并在启动时打 WARN 说明「上行帧当前无业务消费者」——用显式日志暴露边界，而不是让帧静默消失。
- 开演检查链路就绪（D06 推荐口径）由 **LIFE** 在 `startExercise` 中调用 `snapshot()` 判断，网关不感知演习。
- `enabled` 的开机自动打开与「HTTP 手动开链路」并存：前者只影响启动时是否自动 `open`，不影响手动端点。
- 旧系统「开演才 listen」**不再沿用**：链路由操作员/开机配置开启，开演只做就绪检查（按下表 ③）。

## 5. 落地清单（分段与文件白名单）

每段 ≤3 个修改文件，段间可独立验收。

> **状态（2026-10-23）**：**H0~H7 均已完成**，实施记录见本节末尾（H6 因文件数限制拆成 H6a~H6d）。
> 本表是原始分段计划，文件的最终落点与两处偏差见实施记录。

| 段 | 内容 | 文件（白名单） |
|---|---|---|
| **H0** | 本决策收口（0 代码） | `docs/design/05-hw-link.md`（新）、`docs/decision-register.md` |
| **H1** | R15 语义收口：端口注释对齐 409、新增 `openUwbSerial`/`closeUwbSerial`、契约描述同步 | `DeviceLinkAdmin.java`、`docs/api/paths/device.yaml` |
| **H2** | 链路状态机与注册表（纯 Java，无硬件耦合，可单测） | `scds-gateway` 新增 `LinkRegistry`、`LinkHandle`、`LinkStatusSnapshot` |
| **H3** | 马甲串口适配器（打开/关闭/读线程→`SerialFramer`→sink、轮询与发送队列） | `SerialVestLink`、`SerialVestLinkOpener`、单测 |
| **H4** | UWB 适配器（Netty TCP_SERVER + 串口 SERIAL，共用 `UwbFramer`） | `UwbTcpServerLink`、`UwbSerialLink`、单测 |
| **H5** | `GatewayProperties` + 仿真模式 `SimulatedLinkAdapter` | `GatewayProperties`、`SimulatedLinkAdapter`、单测 |
| **H6** | `DeviceController` 接线（`getLinks` / `listSerialPorts` / 开关串口 / 开关 UWB 共 6 条；`getVestLink` 仍 501） | `DeviceController`、`LinkOpenUwbRequest` 校验、骨架测试同步 |
| **H7** | 端到端 IT（真实回环 TCP 12001 + 仿真串口，走完整生产入口） | `scds-app` 新增 `HwLinkHttpIT`、必要的测试支撑 |

**H6 的一处必须记明**：契约的 `getVestLink` 返回的是**每马甲**在线态（`lastHeartbeatAt`/`lastFrameType`/`framesTotal`），
其权威来源是 core 的状态机（「与态势域 VestStatus 一致」），**不是链路级状态**。
在 STATE 就绪前该端点保持 501，本工作包不接线、不用链路计数冒充每马甲计数。

### H1~H7 实施记录（2026-10-23）

H6 实际拆成 4 段（每段文件数仍 ≤3）：

| 段 | 内容 | 文件 |
|---|---|---|
| **H6a** | 链路两错误码登记进 `ApiExceptionHandler` 的状态映射——**不登记则 409 会静默落成 500**（映射表刻意不按前缀猜码） | `ApiExceptionHandler`、`ApiExceptionHandlerBusinessTest` |
| **H6b** | 真链路复合适配器：把三种真链路聚合成**一个** `DeviceLinkAdmin`，本身不含业务判定 | `RealDeviceLinkAdmin`、`RealDeviceLinkAdminTest` |
| **H6c** | 控制器接线 6 条（`getVestLink` 仍 501）+ 两处骨架/权限测试同步 | `DeviceController`、`ApiSkeletonSmokeIT`、`RoleWritePermissionIT` |
| **H6d** | app 装配层：按 `scds.gateway.mode` 在真链路与仿真之间二选一、日志型 sink、yml 落 `scds.gateway.*` | `config/GatewayConfig`、`config/LoggingHardwareEventSink`、`application.yml` |

**与上表的两处偏差（据实记录）**

1. **`LinkOpenUwbRequest` 未改**。SERIAL 缺 `serialPortName` 已由网关的 `requirePortName` 折成
   400 `VALIDATION_FAILED`（真链路与仿真共用同一判据）；在 api 层再加一层校验，等于把同一条规则
   维护两遍，且一旦两处口径分叉，会出现「网关说不行、控制器说行」的哑火。TCP 端口硬约束 12001
   同理由网关 `portOf` 判 400，不下移到控制器。
2. **装配层落在 `scds-app`（不在 H6 原白名单里，属必要补充）**。`scds-api` 不依赖 `scds-gateway`、
   `scds-gateway` 保持零 Spring 依赖，两条约束合起来只剩 app 能同时看见「领域端口」与「真链路实现」
   （§4.1 与 `RealDeviceLinkAdmin` 类注释）。`GatewayProperties` 因此由
   `@Bean @ConfigurationProperties("scds.gateway")` 绑定——该路径走 setter，属性类必须是可变
   JavaBean 而不是 `record`。

**仍未接入（§4.6 的 D10 声明不变）**：`scds.gateway.auto-open-on-startup` 当前无消费者；
`scds.gateway.uwb.tcp-backlog` / `worker-threads` 未进 Netty 启动参数。

#### H7 端到端 IT（2026-10-23 晚）

新增 `scds-app/src/test/java/com/dualx/HwLinkHttpIT.java`（5 例，1 文件）。走完整生产入口
（真 MySQL、真安全链、真 HTTP），把 `DeviceController` 的 6 条链路端点接到**真实装配出来的**
`DeviceLinkAdmin` 上——两侧各自单测通过不等于这条链通，本类验的就是「通」。

| 上下文 | `scds.gateway.mode` | 证明什么 |
|---|---|---|
| 仿真 | `simulator`（测试内覆盖，仅此上下文） | 恒三条快照按声明序；开→`OPEN`／重开 409 `DEVICE_LINK_ALREADY_OPEN`；UWB 两模式互斥 409；请求非 12001 → 400 `VALIDATION_FAILED` 且不真的监听；同一串口被另一条链路持有 → 409 `DEVICE_LINK_SERIAL_BUSY`；`close` 幂等且计数归零；UWB 关闭按当前模式分流（返回 `UWB_SERIAL` 而非恒取 TCP）；`serial-ports` 已接线（非 501）；`getVestLink` 仍 501 |
| 真链路 | **不覆盖**，走 `application.yml` 的 `real` 默认值 | 装配出的 bean 是 `RealDeviceLinkAdmin`（不是仿真适配器）；UWB TCP Server 在硬约束 **12001** 上真实监听，回环客户端一次写出**两帧粘连**的黄金样本后 HTTP 快照为 `framesTotal=2`、`fdCount=1`；`close` 后状态回 `CLOSED`、计数清零、**12001 真的释放**（再次绑定可成功） |

**两处如实记明**

1. **12001 被占用时该用例以 `Assumptions` 跳过**，而不是改端口重试——契约把 12001 定为硬约束，
   「换个端口也能跑」正是本工作包要禁掉的行为（同 `UwbTcpServerLink` 类注释）。跳过会在
   surefire 报告里可见（`Skipped`），本次实测为 `Skipped: 0`，即真实回环确实执行了。
2. **仿真上下文覆盖 `scds.gateway.mode` 只发生在测试内**（`MapPropertySource` 加在最前，
   优先级高于 `application.yml`），并以「装配出的 bean 是 `SimulatedLinkAdapter`」为断言兜底：
   若覆盖失效，用例会立刻失败，而不会静默拿真链路当仿真跑。

**本工作包仍不能证明**：真串口 115200 与马甲固件对拍、真实 UWB 盒子经 12001 的连续坐标、
D10 容量与长稳 / RPO / RTO——见下方「现场待验收」，属现场项。

## 6. 待裁定

| 项 | 说明 |
|---|---|
| D07 依赖 | 链路状态的 WS 推送（`watchLinks` / `LinkStatusPushPort`）需先定「数组 vs 对象」的同构口径，本工作包不做 |
| D10 容量 | 队列上限、Netty 线程数、长稳时长与备份 RPO/RTO 需现场数据；本工作包只落可配置项 |
| 溢出策略默认值 | `REJECT` 与 `DROP_LOW_PRIORITY` 哪个作默认，取决于现场对「丢轮询帧」与「拒高优指令」的容忍度，需操作口径确认 |
| 真硬件验收 | 串口/UWB 盒子的首次实装对拍必须在现场做，自动测试不能替代 |

## 7. 验收判据

**自动测试（本工作包已证明）**

> 覆盖来源：`scds-gateway` 单测（括号内为方法名）+ `scds-app` 的 `HwLinkHttpIT`（走完整生产入口的端到端）。
> 勾选表示已有自动化用例覆盖；未勾项属现场项，见下方「现场待验收」。

- [x] 状态机迁移完整：`CLOSED→OPENING→OPEN`、打开失败 `ERROR`、`close` 幂等
      —— `LinkRegistryTest#openGoesThroughOpeningThenOpen` / `#failedPhysicalOpenReleasesThePortAndLandsOnError` / `#closeIsIdempotentAndResetsCounters` / `#countersRestartFromZeroOnEachOpen`；`UwbLinkTest#unbindableHostLeavesTheLinkInErrorAndReleasesTheSlot`；`SerialVestLinkTest#readFailureLandsOnErrorAndNotifiesSink`；`HwLinkHttpIT#serialOpenReopenIsConflictAndCloseIsIdempotent`
- [x] 已开重开返回 409 `DEVICE_LINK_ALREADY_OPEN`（不隐式重开）
      —— `LinkRegistryTest#reopeningAnOpenLinkIsConflictAndDoesNotSilentlyReopen` / `#openingDuringOpeningIsAlsoConflict`；`SimulatedLinkAdapterTest#serialOpenExposesRealStateAndReopenIsAConflict`；`HwLinkHttpIT#serialOpenReopenIsConflictAndCloseIsIdempotent`
- [x] 物理串口被占用返回 409 `DEVICE_LINK_SERIAL_BUSY`
      —— `LinkRegistryTest#onePhysicalSerialPortCannotBeHeldByTwoLinks`；`SerialVestLinkTest#openFailureIsReportedAsSerialBusyAndReleasesThePort`；`HwLinkHttpIT#sameSerialPortCannotBeHeldByTwoLinks`
- [x] UWB TCP 端口硬约束 12001：请求其它端口 → 400，**不会真的监听别的端口**
      —— `UwbLinkTest#requestedPortOtherThanHardConstraintIsRejected`；`RealDeviceLinkAdminTest#uwbTcpPortIsPinnedToTheHardConstraint`；`SimulatedLinkAdapterTest#uwbTcpServerRejectsPortOtherThanHardConstraint`；`HwLinkHttpIT#uwbPortIsHardConstrainedAndModesAreMutuallyExclusive`
- [x] 同一物理串口不能被两条链路同时持有
      —— `LinkRegistryTest#onePhysicalSerialPortCannotBeHeldByTwoLinks`；`HwLinkHttpIT#sameSerialPortCannotBeHeldByTwoLinks`
- [x] 粘包/半包/垃圾前缀/错校验：经网关后到达 sink 的帧与 `SerialFramer` 逐帧一致
      —— `SerialVestLinkTest#halfPacketsAreReassembledBeforeReachingSink` / `#stickyFramesArriveAsTwoFramesInOrder`；`SimulatedLinkAdapterTest#halfSampleIsHeldUntilCompleted` / `#stickySampleIsFramedAndCountedThroughTheSimulator`
- [x] 41B UWB 帧（含多帧粘连）解出与 `UwbFramer` 一致；两种模式共用同一成帧路径
      —— `UwbLinkTest#tcpServerDeliversFramesFromLoopbackClient` / `#halfPacketOverTcpIsReassembled` / `#corruptedFrameIsDroppedWithoutBreakingTheLink` / `#serialModeUsesTheSameFramerAndSink`；`SimulatedLinkAdapterTest#uwbFramesReachSinkThroughTheSameFramer`；`HwLinkHttpIT#realModeListensOnHardPortAndCountsLoopbackFrames`（真 12001 回环、两帧粘连 → `framesTotal=2`）
- [x] 下行字节与 `DownlinkCommands` 输出**逐字节一致**（含校验与 `AF FA`）
      —— `SerialVestLinkTest#pollConfigIsSentImmediatelyAndResentEveryCycle`；`RealDeviceLinkAdminTest#sendVestCommandGoesThroughTheVestLink`；`SimulatedLinkAdapterTest#sentCommandsAreRecordedVerbatimAndEmptyPayloadIsRejected`
- [x] `snapshot()` 恒返回三种 `LinkKind` 各一条；`close` 后计数归零
      —— `LinkRegistryTest#snapshotAlwaysCarriesEveryKindAsClosed` / `#closeIsIdempotentAndResetsCounters`；`RealDeviceLinkAdminTest#snapshotHasThreeKindsAndStartsClosed`；`SimulatedLinkAdapterTest#closeIsIdempotentAndSnapshotAlwaysHasThreeKinds`；`HwLinkHttpIT#linksSnapshotIsAlwaysThreeKinds`
- [x] 仿真模式可注入帧并断言下行字节；`real` 模式下仿真适配器不装配
      —— `SimulatedLinkAdapterTest#injectingFramesOnAClosedLinkIsRejected` / `#listedSerialPortsComeFromTheInjectedFixture` / `#designDefaultsAreRealModeAndMatchTheDesignTable`；`HwLinkHttpIT`（仿真上下文断言 bean 为 `SimulatedLinkAdapter`；real 上下文断言 bean 为 `RealDeviceLinkAdmin`）

**现场待验收（本工作包不能证明）**

- [ ] 真实串口 115200 下与马甲固件的上下行对拍
- [ ] 真实 UWB 盒子经 12001 接入后的连续坐标链路
- [ ] 容量、长稳、备份 RPO/RTO（D10）

## 8. 与 PROTOCOL.md 的关系

本文**不修改** `PROTOCOL.md`。协议字节、校验、粘包分支、`ff03` 长短版、41B 结构与
`uwb_meta` 结构全部沿用冻结文档；若实现与协议文档冲突，以 `PROTOCOL.md` 为准并先改文档再改代码。
