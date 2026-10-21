# 设计 02 —— 服务端推送端口（态势 / 链路状态）

> 状态：**状态所有权原则已确认，推送实现尚未落地** | 核查日期：2026-10-21 | 涉及 DDL：无
> 关联：解决评审遗留 **D2**（`SituationPublisher` 归属与 revision 语义），并补齐 Ports & Adapters 的**第三条边**。
> 本文档只定义推送通道的实现结构，不改契约（`docs/api` 已定稿）；契约与实际不符之处单列在 §6。
> 下文线程、补齐与恢复细节为候选设计，不等于已经实现或逐项获得用户确认。
> 当前只有 PING/PONG 骨架；[审查清单](../review.md) 与 [测试矩阵](../testing.md) 登记未验证内容。

---

## 1. 问题

### 1.1 现象

契约定义了两条 WebSocket 推送通道，但两侧都没有"源"：

```java
// SituationWebSocketHandler.afterConnectionEstablished
// 骨架期不推首帧：服务端还没有 revision 源（属服务层），推空壳反而会让客户端误以为拉到了态势。

// LinkWebSocketHandler.afterConnectionEstablished
// 骨架期不推首帧：链路状态源在网关层，阶段 4 接入后在连接建立时推当前 LinkStatus。
```

两个 handler 目前只实现 `PING`/`PONG`，`RESYNC` 只打一条 warn 日志。对应的 HTTP Poll 侧同样为空：

| 端点 | 现状 |
|---|---|
| `GET /api/v1/situation` | `throw new NotImplementedException("getSituation")` → 501 |
| `GET /api/v1/devices/links` | `throw new NotImplementedException("getLinks")` → 501 |

### 1.2 根因：缺一条边

B 方案（`scds-domain`）已经画出两条边，都是"核心侧定义端口、边缘侧实现"：

| 方向 | 端口 | 定义在 | 谁调 | 谁实现 |
|---|---|---|---|---|
| 下行（核心 → 硬件） | `DeviceLinkAdmin` | `scds-domain` | `scds-core` | `scds-gateway` |
| 上行（硬件 → 核心） | `HardwareEventSink` | `scds-domain` | `scds-gateway` | `scds-core` |

缺的是第三条：**状态变化 → 广播给客户端**。它既不属于核心也不属于网关，而属于接口适配层（`scds-api` 的 WS）。没有这条边，core 产生了态势变化也没有任何合法途径送达 WS——因为 **`scds-api` → `scds-core` 是单向依赖，core 不能反过来依赖 api**。

### 1.3 顺带暴露的类型问题

推送载荷类型目前全部住在 `scds-api`，core 在编译期就拿不到：

| 类型 | 现在的位置 | 问题 |
|---|---|---|
| `SituationEnvelope` | `com.dualx.api.dto.situation` | core 无法构造它 → 端口签名写不出来 |
| `UnitState` / `TeamSummary` / `CombatRole` | 同上 | 同上 |
| `HitEvent` | **两份**：`com.dualx.core.domain.HitEvent` 与 `com.dualx.api.dto.situation.HitEvent` | 字段不一致（domain 版多 `exerciseId` / `tMs`），是库列与服务载荷的差异，但没有任何注释说明这是**有意的** |
| `ExerciseLogEntry` | 同样两份 | 同上 |

---

## 2. 现状事实（已核对）

| 事实 | 出处 |
|---|---|
| 硬件相关现有 3 个端口/载体；此外已有 `TokenSigner` 与 3 个令牌 claim 载体，总计 7 个源文件 | `scds-domain/.../core/port/` |
| `LinkStatus`、`LinkKind`、`LinkState` 已在 `com.dualx.core.domain`（中立层） | `scds-domain/.../core/domain/` |
| `DeviceController` **直接返回** `com.dualx.core.domain.LinkStatus` 与 `com.dualx.core.port.SerialPortInfo`，不套 api DTO | [DeviceController.java](../../scds-api/src/main/java/com/dualx/api/device/DeviceController.java#L103-L111) |
| `SituationEnvelope` 是 HTTP Poll 响应体与 WS 帧体的**同一个** Java 类型 | [SituationEnvelope.java](../../scds-api/src/main/java/com/dualx/api/dto/situation/SituationEnvelope.java) |
| `HardwareEventSink.onLinkStateChanged` 已存在，是链路状态进 core 的既有通道 | [HardwareEventSink.java](../../scds-domain/src/main/java/com/dualx/core/port/HardwareEventSink.java#L26-L27) |
| `WebSocketConfig` 是两条通道的**唯一**装配点；handler 刻意不标 `@Component`（切片测试不装配 `WebSocketConfigurer`，独立 Bean 会导致「有配置无依赖」） | [WebSocketConfig.java](../../scds-api/src/main/java/com/dualx/api/ws/WebSocketConfig.java#L15-L17) |
| 契约：WS 与 Poll **同构**载荷；服务端 30s 无上行主动断开；`clientToServer` 只有 `PING` / `RESYNC` | [situation.yaml](../../docs/api/paths/situation.yaml#L154-L206) |
| 契约：`revision` 超窗返回 `SITUATION_REVISION_TOO_OLD`，客户端改全量拉取 | 同上 L25-L27 / L148-L153 |
| 契约：`replayId` 即 `exerciseId`；停演后进复盘 | [exercise.yaml](../../docs/api/paths/exercise.yaml) |

---

## 3. 设计目标与非目标

**目标**

1. 定义第三条边，使 core / gateway 能在**不依赖 api** 的前提下把变化送达 WS。
2. 把"同构"从**纪律约束**变成**结构保证**：Poll 与 Push 走同一状态源、同一 Java 类型，不靠人写映射函数。
3. 一次定死 revision 语义（作用域、递增粒度、窗口、重启行为、演习切换时的表现）。
4. 推送路径**不得阻塞**上游（core 事件循环 / Netty 读线程），慢客户端不影响其他客户端。
5. 不改契约、不引入新中间件（无 MQ、无 Redis、无消息持久化）。

**非目标**

- 不做进程间广播。已定"每客户独立实例"，进程内 fan-out 即全部。
- 不做离线推送 / 消息补发持久化。断线恢复由 HTTP Poll 兜底（契约已定，WS 可替代 Poll 的假设本身就是违规用法）。
- 不做 WS 的鉴权之外的能力协商。握手鉴权属设计 03，本文档只留出接入点。
- 不改协议层（串口 / UWB）与解算，本设计在帧已被解出**之后**开始。

---

## 4. 方案

### 4.1 第三条边：端口定义在 `scds-domain`

```
                        com.dualx.core.port
        ┌───────────────────────────────────────────────┐
        │ DeviceLinkAdmin      （核心 → 硬件，下行）      │
        │ HardwareEventSink    （硬件 → 核心，上行）      │
        │ SituationPushPort    （核心 → 客户端，下行）    │  ← 新增
        │ LinkStatusPushPort   （网关 → 客户端，下行）    │  ← 新增
        └───────────────────────────────────────────────┘

 scds-core ──调──> SituationPushPort    <──实现── scds-api (SituationBroadcaster → WS)
 scds-gateway ─调─> LinkStatusPushPort  <──实现── scds-api (LinkStatusBroadcaster → WS)
```

新增两个接口（与既有端口同包、同风格，方法体只描述"发生了什么"，不含任何传输细节）：

```java
package com.dualx.core.port;

/** 态势推送端口：核心侧产生变化时调用，由接口适配层广播给 WebSocket 客户端。 */
public interface SituationPushPort {
    /**
     * 广播一帧态势信封（全量或增量由信封的 {@code full} 决定）。
     * <p>实现方约定：本方法在核心侧的事件处理线程上被调用，<b>必须非阻塞、不得抛异常</b>；
     * 任何 I/O 等待都要下沉到实现方自己的发送线程。</p>
     */
    void publish(SituationEnvelope envelope);
}
```

```java
package com.dualx.core.port;

/** 链路状态推送端口：网关侧链路开关变化时调用。载荷类型与 {@code GET /devices/links} 完全一致。 */
public interface LinkStatusPushPort {
    void publish(LinkStatus status);
}
```

**为什么必须在 `scds-domain`**：`scds-api` → `scds-core` → `scds-domain`、`scds-gateway` → `scds-domain`，两条调用链都能看见 `scds-domain`，且不产生任何环。端口定义在 api 则 gateway 看不见，定义在 core 则 gateway 看不见。

### 4.2 D2 裁定：谁持有状态，谁调端口

**判据：端口的调用方，必须是该状态的唯一所有者。** 否则 Poll 与 Push 会各自维护一份"当前值"，同构只能靠人工校准，迟早漂移。

| 域 | 状态所有者 | 依据 | Poll 读侧 | Push 写侧 |
|---|---|---|---|---|
| 链路状态 | **`scds-gateway`** | fd 的 OPEN/CLOSED/ERROR 只有网关知道；`DeviceLinkAdmin.snapshot()` 已返回 `List<LinkStatus>` | `DeviceLinkAdmin.snapshot()` | 网关直接调 `LinkStatusPushPort` |
| 实时态势 | **`scds-core`** | revision 的分配者、阵亡冻结的判定者、队伍汇总的计算者都在核心 | `SituationQuery`（core，见 §5） | core 调 `SituationPushPort` |

两点澄清：

1. **不再有独立的 `SituationPublisher` 组件。** 该名字废除，避免"端口 / 发布器"两套命名。api 侧实现类命名为 `SituationBroadcaster`（明确它是**适配器**，不是状态持有者）。
2. **网关同一次链路变化会通知两处**（`HardwareEventSink.onLinkStateChanged` 与 `LinkStatusPushPort.publish`），这是**两个不同订阅者**，不是重复推送：前者喂 core 的健康检查 / 审计，后者喂客户端。网关内部维护一个 `LinkStateRegistry`，`snapshot()` 读它、变化时写它并扇出，三处读到的必然是同一份数据。

### 4.3 前置改动：推送载荷类型下沉到中立层

端口签名需要 `SituationEnvelope`，而它现在在 api。三个选项：

| 选项 | 做法 | 评价 |
|---|---|---|
| **甲（推荐）** | 把推送载荷下沉到 `scds-domain` 的新包 `com.dualx.core.push`，api 的 Controller 与 WS 直接复用同一类型，删除 api 侧重复 DTO | 与 `DeviceController` 直接返回 `LinkStatus` 的既有做法一致；同构是**类型级**保证 |
| 乙 | 端口只传核心领域事件（`SituationChanged`），api 侧映射成 DTO | 多一套模型 + 一个映射函数；同构退化为纪律约束；Poll 也要走同一映射，白增一层 |
| 丙 | 端口签名用 `byte[]`（core 侧预序列化 JSON） | 要求 core 依赖 Jackson 并知道契约字段名，破坏分层，弃 |

**采用甲。** 落地范围（只搬推送真正需要的，HTTP-only 的分页/列表面不动）：

| 类 | 从 | 到 |
|---|---|---|
| `SituationEnvelope` | `com.dualx.api.dto.situation` | `com.dualx.core.push` |
| `UnitState` | 同上 | 同上 |
| `TeamSummary` | 同上 | 同上 |
| `HitEvent` | 同上 | 同上（**注意与 `core.domain.HitEvent` 的关系，见下**） |
| `CombatRole` | 同上 | 同上 |
| `ExerciseLogEntry` / `ExerciseLogPage` / `HitEventList` | 同上 | **不动**（HTTP-only，不进推送端口） |

> **域内将同时存在两个 `HitEvent`，这是有意的**：
> `com.dualx.core.domain.HitEvent` 是**库实体**（带 `exerciseId` 与 `tMs` 两个库列），
> `com.dualx.core.push.HitEvent` 是**线载荷**（契约 `schemas/situation.yaml#/HitEvent` 不含这两列）。
> 搬迁时必须在这两个类的 Javadoc 里互相指名，写清"字段不同是契约决定，不要顺手统一"。

> `scds-domain` 主代码仅依赖 `scds-protocol`，而搬迁后的载荷只引用 `com.dualx.protocol.*`（`Team`/`VestStatus`/`WeaponKind`/`HitPart`）与 `com.dualx.core.domain.*`（`GeoPoint`/`MotionState`/`PositionSource`）——**不引入任何新依赖**。

### 4.4 revision 语义（一次定死）

| 维度 | 裁定 | 理由 |
|---|---|---|
| **作用域** | `SituationStateService` 单例持有的 `AtomicLong`，**进程内、不持久化** | 进程重启时所有 WS 必然断开，客户端重连按契约"不带 `sinceRevision` → 全量"起步；持久化反而制造"revision 跳了但客户端不知道漏了什么"的歧义 |
| **重启行为** | revision 归 0 重新爬升，**不跨进程连续** | 需在契约描述里补一句（见 §6 Q3） |
| **递增粒度** | **一次"态势变化"+1**，不是"一个字段 +1" | 一次变化 = 同源事件批次处理完毕（例：一个串口帧解出的 1 个马甲状态更新）；同一批产生的所有 `changes` 带**同一个** revision，客户端按 revision 去重即可 |
| **谁分配** | `SituationStateService` 内**单线程**事件循环 | 分配与快照写入同线程 → 不存在两个并发写者拿到同一 revision，不需要 CAS 重试循环 |
| **`UnitState.revision`** | 该单元**最后一次**变化的全局 revision | 客户端用它做乐观并发与"这条是不是新的"判断 |
| **窗口** | 保留最近 `scds.situation.revision-window` 条增量（候选默认 **1024**），环形缓冲 | 覆盖时间由事件速率决定，不由 Poll 周期决定；100 次变化/s 时约覆盖 10.24s。容量须在真实马甲数和频率下压测；超窗返回 409 |
| **命中序号** | `HitEvent.seq` 由库生成，与 revision **互不推导** | 两者一个离散事件序、一个态势版本序，混用会在"只有心跳无命中"时出错 |
| **演习切换** | 开演 / 停演（含 doc 01 的 `CRASH_RECOVERED` 收尾）各推一帧 **`full=true`**，revision **继续递增不回退** | 回退会让客户端无法判断帧的新旧；用全量帧表达"战场清空 / 战场重建"最直观，且不需要新增动作类型 |

### 4.5 连接建立：首帧、补齐、RESYNC

`SituationWebSocketHandler.afterConnectionEstablished` 读取握手 query 的 `sinceRevision`：

| 情形 | 动作 |
|---|---|
| 不带 `sinceRevision` | 推 **1 帧** `full=true` |
| 带 N 且在窗口内 | 从 N+1 起，按窗口**逐帧**推 `full=false`（一帧可含多条变化），直到追上当前 revision |
| 带 N 但已超窗 | 拒绝：**关闭连接**，close code `4000` + reason `SITUATION_REVISION_TOO_OLD`，客户端改为全量拉取（见 §6 Q2） |

`RESYNC`：`{"action":"RESYNC","sinceRevision":N}` 复用**同一套**补齐逻辑；超窗时**直接推一帧 `full=true`**，不新增任何服务端动作类型（信封自带 revision，客户端自然对齐）。这样 `clientToServer` 的枚举保持 `[PING, RESYNC]`，契约不用动。

**链路同构缺口，尚待确认**：HTTP 契约返回 `List<LinkStatus>`，WS 契约却定义单个 `LinkStatus`。
因此“首帧发列表、之后发对象”不是同构实现。候选方案是两端均发送完整列表快照，
并相应调整 `LinkStatusPushPort` 签名；也可定义共同信封，但这两种都需要先确认契约变更。
在确认前不得把现有两个不同根类型描述为“已经同构”。

**订阅与首帧必须原子衔接**：注册会话、读取快照、补齐订阅期间新增事件须协调。
不能仅“读快照然后注册”而丢掉中间变化，也不能“先注册再发首帧”导致增量跑在全量之前。
实现时应通过事件循环屏障或同一发送队列保证首帧与增量的顺序，并补并发测试。

**重启恢复尚有缺口**：只把 revision 归零，客户端携带旧进程的较小 N 重连时，
新进程可能已增长到 N 以上，数值检查会误认为仍在窗口内。需要确认 epoch 标识，
或规定所有重连强制全量并限制 sinceRevision 只在同一连接代内使用。见 §6 Q8。

### 4.6 广播实现（api 侧）

```
WebSocketConfig ──构造──> SituationWebSocketHandler ──注入──┐
                                                          ├──> SituationSessionRegistry
SituationBroadcaster (implements SituationPushPort) ──注入─┘        (ConcurrentHashMap<sessionId, Session>)
```

**为什么把会话表单独拆成 `SituationSessionRegistry` bean**：handler 需要 `register`，broadcaster 需要遍历广播。若让 broadcaster 兼管会话表，则 `WebSocketConfig → handler → broadcaster → handler` 会成环。拆出 registry 后依赖是单向的：`handler → registry ← broadcaster`。

- `SituationWebSocketHandler`：构造参数增加 `registry`；`afterConnectionEstablished` 注册 + 推首帧；`afterConnectionClosed` 注销 + 关闭该会话的发送资源。
- `SituationBroadcaster implements SituationPushPort`：`publish(envelope)` → 遍历 registry 投递。
- 链路侧同构：`LinkSessionRegistry` + `LinkStatusBroadcaster`。
  - 两处 fan-out 骨架相似，但**先各写一份**；只有出现第三处才抽公共基类（重复两遍不值得抽象）。

### 4.7 背压与线程模型

这是本设计真正的坑，且**没有现成约定可循**，必须显式定死。

**约定 1：端口方法非阻塞、不抛异常。**
`publish(...)` 会在 core 的事件处理线程、或网关的 Netty 读线程上被直接调用。任何 I/O 等待都必须下沉到实现方自己的发送线程。实现方内部异常一律自己吞掉并计数（推送失败不该让一帧合法数据丢失、更不该影响上游）。

**约定 2：每会话一个有界发送队列 + 一个虚拟线程。**
`WebSocketSession.sendMessage` 既非线程安全（同一会话并发发送会抛 `TEXT_PARTIAL_WRITING`），又是**同步阻塞**（TCP 缓冲区满时会挂住调用者）。因此：

```
publish() ──offer──> 会话队列 [capacity 256] ──take──> 该会话专属虚拟线程 ──> sendMessage
              │
              └─ offer 失败（队满）→ 丢帧 + 计数（不阻塞、不抛）
```

- 发送线程用 **JDK 21 虚拟线程**（`Thread.ofVirtual()`），每会话一个。会话数在几十量级，成本可忽略；关键收益是**一个慢客户端只阻塞它自己的线程**。
  - 否决"共享单线程 executor"：所有会话共享一个发送线程时，一个写不动的客户端会拖住全体的推送延迟——正好违反本轮目标第 4 条。
- **丢弃策略：丢 oldest 有效，但必须配一个连续性检查。** 单看"丢帧"是安全的（客户端 revision 会跳号），但客户端无从知道跳号意味着漏了中间态。因此会话状态里维护 `lastSentRevision`：
  - 发送前检查 `envelope.revision == lastSentRevision + 1`（或该会话的首帧）；
  - **不连续 → 不推这一帧，改推一帧 `full=true`**（向 core 的 `SituationQuery` 要当前全量），客户端凭该帧的 revision 直接对齐。
  - 这样"丢帧"被自动收敛为"重放全量"，不需要客户端做任何特殊处理，也不需要改契约。

**约定 3：应用层保活。**
契约写明"服务端 30s 无上行会主动断开"。handler 记录 `lastInboundAt`，由一个每 5s 扫描的调度任务关闭超时会话 → **需要 `@EnableScheduling`**（与设计 01 的心跳任务在同一处开启，落在 `scds-app`）。

**观测**：暴露三个计数——活跃会话数、因队满丢弃的帧数、因不连续改推全量的次数。第一项用于发现连接泄漏，后两项用于判断背压是否真的在发生（正常应为 0）。

### 4.8 与优雅停机的关系

`server.shutdown: graceful` 只作用于 HTTP 请求生命周期，**不管理 WS 连接**。

**裁定：停机不广播任何"我要关了"帧，直接关闭连接。** 依据是契约的硬约束本身——"WS 不得替代 Poll，客户端必须持续低频轮询"，因此客户端对断线的处理路径（重连 + Poll 恢复）已被强制存在，再定义一种"优雅关闭帧"只会多一条客户端必须实现的分支。停机时靠 `@PreDestroy` 注销 registry 并中断发送线程即可，避免线程泄漏。

### 4.9 与设计 01 的衔接

| 场景 | 推送表现 |
|---|---|
| 启动自愈收尾僵尸场次（`CRASH_RECOVERED`） | 态势进入 `running=false` → 推一帧 `full=true`。注：此场景通常发生在 WS 尚未连接时，该帧主要是给"同进程内稍后才连上的 api"用的，真正的收益是**下一次全量拉取必然拿到干净状态** |
| 正常停演 | 同上，推 `full=true`（revision 继续递增） |
| 新演习开始 | 推 `full=true` |

---

## 5. 落地清单（按层）

**`scds-domain`**

- 新增 `com.dualx.core.push`：`SituationEnvelope`、`UnitState`、`TeamSummary`、`HitEvent`、`CombatRole`（由 api 搬入；Javadoc 中互相指名与 `core.domain.HitEvent` 的关系）
- 新增 `com.dualx.core.port.SituationPushPort`、`com.dualx.core.port.LinkStatusPushPort`
- 依赖不变（仍仅 `scds-protocol`）

**`scds-core`**

- `SituationStateService`：单线程维护单元状态、分配 revision、维护环形增量窗口；变化后调 `SituationPushPort`
- `SituationQuery`：读侧（全量快照 / `sinceRevision` 增量 / 单马甲 / 队伍汇总 / 命中列表），供 Controller 与补齐逻辑共用
- 注入 `SituationPushPort`，并提供 `NoopSituationPushPort`（`@ConditionalOnMissingBean`），使核心单测与"只要核心不要 WS"的场景不被迫起 WS

**`scds-gateway`**

- 内部 `LinkStateRegistry`：`snapshot()` 的数据源
- 链路状态变化点：写 registry → 调 `HardwareEventSink.onLinkStateChanged` → 调 `LinkStatusPushPort.publish`
- `DeviceLinkAdmin.snapshot()` 读 registry

**`scds-api`**

- `SituationSessionRegistry`、`LinkSessionRegistry`
- 改造两个 handler：注册 / 注销 / 首帧 / 补齐 / 保活（构造参数由 `WebSocketConfig` 注入）
- `SituationBroadcaster`、`LinkStatusBroadcaster`（实现端口）
- `WebSocketConfig`：注入 registry，装配点不变（仍只此一处出现 URL）
- `SituationController`、`DeviceController.getLinks()`：去掉 501，接 core 的 `SituationQuery` / 网关的 `DeviceLinkAdmin.snapshot()`

**`scds-app`**

- 打开 `@EnableScheduling`

**配置项**

```yaml
scds:
  situation:
    revision-window: 1024   # 增量环形窗口条数，超出即 SITUATION_REVISION_TOO_OLD
  push:
    send-queue-capacity: 256  # 每会话待发帧上限，队满丢帧并计数
    idle-timeout-ms: 30000    # 无上行主动断开（契约值，可配不可改语义）
```

---

## 6. 待裁定

| # | 问题 | 建议 |
|---|---|---|
| **Q1** | 载荷下沉的目标包名：`com.dualx.core.push` / `com.dualx.core.view` / 直接塞进 `com.dualx.core.domain`？ | **`com.dualx.core.push`**。`domain` 应保持"领域实体"语义，`push` 明确表达"这是对外推送的线载荷" |
| **Q2** | WS 超窗时的表达：契约写的是 **409 + Problem**，但 WS 在握手成功后无法再返回 HTTP 状态码 | 用 **close code 4000 + reason**；同时**回写契约**补一句"握手后超窗以 close code 表达"。若坚持契约原文，则必须在握手阶段把 `sinceRevision` 作为 query 校验并拒绝升级——但那会让"刚超过窗口"的客户端连不上，体验更差 |
| **Q3** | revision **不跨进程重启连续**，是否接受？ | 候选为重启归零；必须同时解决 Q8 的跨代数值碰撞，不得仅补一句说明就宣称恢复正确 |
| **Q4** | 发送线程模型：**每会话虚拟线程** vs 共享单线程 executor？ | **虚拟线程**。共享单线程下一个慢客户端会拖住全体，与本设计目标第 4 条冲突 |
| **Q5** | 现场客户端数量上界（指挥台数 + 单兵终端数）？ | 影响队列容量与是否要加会话数上限。需要现场数字 |
| **Q6** | 是否要 `NoopSituationPushPort`？ | **要**。否则 `scds-core` 的单元测试必须拉起 WS 上下文，切片测试的成本会立刻反噬 |
| **Q7** | 链路状态是否需要在"值未变"时也周期重推（例如 10s 一次）？ | **不需要**。契约已定义 Poll 兜底，周期重推只会浪费带宽并掩盖真正的推送问题 |
| **Q8** | 旧进程 revision 与新进程 revision 数值重叠如何识别？ | 比较 epoch，或明确重连全量规则；涉及客户端与契约，须先确认 |
| **Q9** | 链路 Poll 数组与 WS 对象如何满足同构硬约束？ | 推荐两端统一完整列表快照；改签名、WS schema 及首帧用例前先确认 |

---

## 7. 验收判据

1. **同构证明**：在停止输入或固定事件屏障的测试中比较 WS 首帧与 HTTP 全量，revision 和内容一致；持续输入时不能要求不同采样时刻的 revision 恒相等。两处使用同一 Java 类型及状态源，链路通道也必须检查 JSON 根类型一致。
2. **补齐正确**：客户端持有"当前 revision − 3"连接 → 依次收到 3 帧 `full=false`，revision 连续无跳号，最终 revision 与全量一致。
3. **超窗恢复**：用"当前 revision − 窗口 − 1"连接 → 被拒绝（close 4000）；客户端改为不带 `sinceRevision` 重连后能拿到全量，**不出现卡在旧 revision 的现象**。
4. **背压隔离**：人为让一个客户端不读取数据（填满其 TCP 缓冲）→ 其他会话的推送延迟不受影响；该客户端恢复后收到一帧 `full=true` 并自动对齐（丢弃计数 > 0，其余会话的计数为 0）。
5. **保活与泄漏**：连续 30s 不发 `PING` → 服务端主动断开；断开后 registry 中该会话已注销，活跃会话数回到真实值。
6. **演习切换**：停演（含进程被杀后的 `CRASH_RECOVERED` 收尾）后，客户端收到的下一帧为 `full=true` 且 `running=false`、`units` 为空。
7. **不阻塞上游**：注入一个"永久阻塞的发送实现"后，core 的事件处理耗时与无推送时相比无可测差异（证明 `publish` 未在调用线程上等待 I/O）。
8. **订阅无缝衔接**：在注册、快照读取与首帧发送之间注入状态变化，不漏事件、不把旧全量排在新增量之后。
9. **跨进程恢复**：让新进程 revision 增长到旧 N 以上后再携旧 N 重连，仍必须恢复全量，不能把另一个进程的 revision 当成当前窗口依据。
