# 马甲通信协议冻结文档 (PROTOCOL.md)

> **文档性质**：❄️ **冻结区**。本文件描述的所有帧格式、字段偏移、校验算法在重构中**必须逐字节保持兼容**。
> 重构后的新架构（`scds-protocol` 模块）必须能通过本文件定义的黄金样本回归测试。
>
> **来源**：100% 从现有代码逆向还原（`scds-new`），非猜测。
> **权威性**：**现有代码的解析实现即协议标准**（2026-10-20 定）。`d:\Code\sbdzxt02\马甲 含室内定位  协议.xls` 仅作历史参考，**若与本文冲突以本文为准**（见 §6）。
> **生成日期**：2026-09-28 ｜ **§6 定稿日期**：2026-10-20
> **对应版本**：SCDS-UWB-v1.5-20260923-TCP-SERVER-SUPPORT-P4-5

---

## 0. 协议总览

系统与马甲之间存在**两套完全独立的帧协议**，不可混淆：

| 协议 | 载体 | 编码 | 帧长 | 帧头 | 帧尾 | 校验 |
|------|------|------|------|------|------|------|
| **A. 马甲串口协议** | 主串口（马甲链路） | **文本 HEX**（ASCII） | 变长 11~18B | `FF0X`（ASCII） | `AFFA`（ASCII） | 1 字节累加和 |
| **B. UWB 定位协议** | UWB 串口 或 TCP 12001 | **二进制** | 固定 **41B** | `0xAA 0x29` | `0x55` | 1 字节累加和 |

> ⚠️ **关键区分**：协议 A 是文本（收到的是 ASCII 字符 `'f','f','0','3'`），协议 B 是纯二进制。两者绝不能混用同一个 Framer。

---

## 1. 协议 A：马甲串口协议（文本 HEX 帧）

### 1.1 链路参数

| 项 | 值 | 来源 |
|----|----|----|
| 默认串口 | `COM7` | [application.yml L84](file:///d:/Code/sbdzxt02/backend/application.yml#L84) |
| 波特率 | `115200` | [application.yml L85](file:///d:/Code/sbdzxt02/backend/application.yml#L85) |
| 流控 RTS/CTS | `false` | [application.yml L86](file:///d:/Code/sbdzxt02/backend/application.yml#L86) |
| 轮询周期 | `200ms` | [application.yml L49](file:///d:/Code/sbdzxt02/backend/application.yml#L49) |
| 轮询超时 | `200ms` | [application.yml L50](file:///d:/Code/sbdzxt02/backend/application.yml#L50) |
| 发送队列上限 | `10` | [application.yml L51](file:///d:/Code/sbdzxt02/backend/application.yml#L51) |
| 接收缓存重试上限 | `10` | [application.yml L52](file:///d:/Code/sbdzxt02/backend/application.yml#L52) |

### 1.2 通用帧结构（上行）

```
偏移:  0      1      2        3    4       ...
     ┌──────┬──────┬──────┬───────────┬─────────────┐
     │ 0xFF │ 0x0X │ ？？  │  载荷...   │ 校验 │ AF FA │
     └──────┴──────┴──────┴───────────┴──────┴───────┘
      └─ 帧类型 ─┘  └保留┘                └─ 帧尾 ─┘
```

| 偏移 | 长度 | 字段 | 说明 |
|------|------|------|------|
| `[0]` | 1B | 帧头高 | 固定 `0xFF` |
| `[1]` | 1B | 帧类型 | `0x03`/`0x04`/`0x05`/`0x06`/`0x07`/`0x0A` |
| `[2]` | 1B | ✓ 保留字节 | 语义**忽略**（代码不解析），但**参与校验和**。见 §6.1 |
| `[3..4]` | 2B | 马甲编号 | Big-Endian（`substring(6,10)` 解析） |
| `[5..]` | 变长 | 载荷 | 按帧类型定义（见 §1.4） |
| `[n-4]` | 1B | **校验和** | 校验字节位置见 §1.3 |
| `[n-2,n-1]` | 2B | 帧尾 | 固定 `0xAF 0xFA` |

### 1.3 校验算法（**上行**）

**规则**：`AFFA` 前一位 = 前面所有字节的累加和（溢出取低 8 位）

```java
// 来源：AbstractDataHandler.dataVerification()
int position = dataStr.indexOf("affa") / 2 - 1;   // 校验字节下标
byte sum = 0;
for (int i = 0; i < position; i++) sum += data[i];  // 累加（byte 自动溢出 = &0xFF）
return sum == data[position];
```

**校验字节位置速查**：

| 帧 | 总长 | 校验字节下标 | 求和范围 |
|----|------|-------------|---------|
| `ff03` 短版 | 13B | `[10]` | `[0..9]` |
| `ff03` 长版 | 15B | `[12]` | `[0..11]` |
| `ff04` | 16B | `[13]` | `[0..12]` |
| `ff05` | 11B | `[8]`  | `[0..7]` |
| `ff06` | 16B | `[13]` | `[0..12]` |
| `ff07` | 12B | `[9]`  | `[0..8]` |
| `ff0a` | 18B | `[15]` | `[0..14]` |

### 1.4 上行帧详解

#### ① `ff03` 心跳/状态帧（**最核心**）

正则（代码）：`^(ff03)\w{18,22}(affa)$` → 两种变长版本

| 偏移 | 短版 13B | 长版 15B | 字段 | 解析 |
|------|---------|---------|------|------|
| `[0..1]` | ✓ | ✓ | 帧头 | `FF 03` |
| `[2]` | ✓ | ✓ | 保留（语义忽略） | 见 §6.1 |
| `[3..4]` | ✓ | ✓ | 马甲编号 | `substring(6,10)` 16进制 |
| `[5..6]` | ✓ | ✓ | **主武器弹药** | `substring(10,14)` 16进制 |
| `[7]` | ✓ | ✓ | **HP** | `substring(14,16)` 16进制 |
| `[8..9]` | ✓ | ✓ | **副武器弹药** | `substring(16,20)` 16进制 |
| `[10]` | 校验 | **楼层号** | 长版 `substring(20,22)` |
| `[11]` | `AF` | **位置编号** | 长版 `substring(22,24)` |
| `[12]` | `FA` | 校验 | — |
| `[13..14]` | — | `AF FA` | — |

> **变长判定**：代码用 `data.length >= 15` 区分（[HeartbeatHandler L50](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/battle/handler/HeartbeatHandler.java#L50)）。
> **兼容性**：短版不带楼层/点位，长版带。重构后的解析器必须同时支持两种长度。

**语义**：这是**轮询应答**帧。后端每 200ms 下发一次轮询指令（§2），马甲回本帧上报当前 HP/弹药/位置。

#### ② `ff04` 室外 GPS 帧

正则：`^(ff04)\w{24}(affa)$` → 16B

| 偏移 | 字段 | 解析 | 单位 |
|------|------|------|------|
| `[3..4]` | 马甲编号 | `substring(6,10)` | — |
| `[5..8]` | **经度（WGS-84）** | `substring(10,18)` ÷ **1000000** | 度 |
| `[9..12]` | **纬度（WGS-84）** | `substring(18,26)` ÷ **1000000** | 度 |

> 后端收到后做 **WGS-84 → 百度坐标（BD-09）** 转换（`Point.getBdLng()/getBdLat()`），两个坐标系都存（`lng/lat` = 百度，`wgs84Lng/wgs84Lat` = 原始）。

#### ③ `ff05` 击中记录帧（**击杀判定核心**）

正则：`^(ff05)\w{14}(affa)$` → 11B

| 偏移 | 字段 | 解析 | 说明 |
|------|------|------|------|
| `[3..4]` | **被击中者编号** | `substring(6,10)` | |
| `[5..6]` | **开枪者/武器编号** | `substring(10,14)` | 见下方特殊值 |
| `[7]` | **击中部位** | `substring(14,16)` | 见 §5.4 部位映射 |

**特殊编号（杀伤物，非马甲）**：
- `65535` = `0xFFFF` 手雷
- `65534` = `0xFFFE` 爆炸物
- `65533` = `0xFFFD` 地雷
- `1801~4800` 区间 = 各类重武器/载具（见 §5.5）

#### ④ `ff06` 旧室内定位帧（**UWB 模式下被丢弃**）

正则：`^(ff06)\w{24}(affa)$` → 16B

| 偏移 | 字段 | 解析 |
|------|------|------|
| `[3..4]` | 马甲编号 | `substring(6,10)` |
| `[5..8]` | 室内坐标 X（width） | `substring(10,18)` |
| `[9..12]` | 室内坐标 Y（height） | `substring(18,26)` |

> ⚠️ **行为约束**：[IndoorLocationHandler L28-31](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/battle/handler/IndoorLocationHandler.java#L28-L31) —— 当 `UWB_MODE=enabled` 时**直接丢弃**本帧。这是室内定位方案从"点位编号"演进到"UWB 连续坐标"的历史遗留，重构时必须保留此开关语义。

#### ⑤ `ff07` 迫击炮/曲射火力帧

正则：`^(ff07)\w{16}(affa)$` → 12B

| 偏移 | 字段 | 解析 | 单位 |
|------|------|------|------|
| `[3..4]` | 发射者编号 | `substring(6,10)` | — |
| `[5..6]` | **方位角** | `substring(10,14)` | 度 |
| `[7..8]` | **射程** | `substring(14,18)` | 米 |

**杀伤判定**：由发射者当前 WGS-84 坐标 + 方位角 + 射程算出**落点坐标**，然后遍历所有马甲，若 `距离 - 20m < 0` 则判死（`MORTAR_DEAD_DISTANCE = 20`）。

#### ⑥ `ff0a` SIM 号上报帧

正则：`^(ff0a)\w{28}(affa)$` → 18B

| 偏移 | 字段 | 解析 |
|------|------|------|
| `[3..4]` | 马甲编号 | `substring(6,10)` |
| `[5..14]` | **SIM 卡号** | `substring(10,30)`（10 字节） |

### 1.5 粘包 / 半包处理（**必须重写为可测试实现**）

现有实现 [InstructionProcessThread.java L68-L120](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/battle/thread/InstructionProcessThread.java#L68-L120) 用字符串查找切包：

```
startFrame = dataStr.indexOf("ff0")
endFrame   = dataStr.lastIndexOf("affa")
```

分 4 种情况：
1. `startFrame==0 && endFrame==len-4` → 完整单帧，直接处理
2. `startFrame==0 && endFrame < len-4` → 完整帧 + 尾部半包，切出完整帧，剩余存入 `leftData`
3. `startFrame>0 && endFrame==len-4` → 头部垃圾 + 完整帧，切出后**丢弃垃圾**
4. 其他 → 存入 `leftData` 累积，连续 10 次未闭合则清空缓存

**重构要求**：
- 必须实现为**纯函数式 Framer**（输入字节流 → 输出帧列表 + 残留缓冲），无副作用
- 必须能对 §7 的粘包/半包黄金样本逐帧通过
- **不得**再用 `indexOf` 字符串查找（性能与正确性双差）

---

## 2. 协议 A：马甲串口下行指令（二进制帧）

> ⚠️ **注意**:下行是**二进制**，上行是**文本 HEX**。方向不同，格式不同。

### 2.1 通用结构

```
┌────────────┬──────────┬────────┬────────┐
│  命令载荷   │  校验 1B │ 0xAF   │  0xFA  │
└────────────┴──────────┴────────┴────────┘
       校验 = sum(前面所有字节) & 0xFF
```

**与上行校验的差异**：上行校验字节在 `AFFA` 之前**紧邻**；下行校验字节同样在 `AFFA` 之前紧邻。两者算法一致（累加取低 8 位）。

来源：[DataFrameUtils.java](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/utils/DataFrameUtils.java)

### 2.2 下行指令全表

| 指令 | 字节序列（HEX） | 长度 | 用途 |
|------|----------------|------|------|
| **轮询配置** | `02 00 <num:2BE> <mode> <hp> <ammo1:2BE> <ammo2:2BE> <team> <sum> AF FA` | 14B | 每轮下发配置（HP/弹药/队伍/模式） |
| **演习开始** | `00 06 FF <battleMode> <sum> AF FA` | 7B | 开始实兵演习 |
| **演习结束** | `00 0B FF FF <sum> AF FA` | 7B | 结束演习 |
| **判死** | `00 09 FF <num:2BE> <sum> AF FA` | 8B | 强制马甲阵亡 |
| **判活** | `00 08 FF <num:2BE> <sum> AF FA` | 8B | 复活/判活 |
| **判伤** | `00 A1 FF <num:2BE> <sum> AF FA` | 8B | 判伤 |
| **全体充弹** | `01 14 FF FF FF <ammo:2BE> <weapon> <sum> AF FA` | 11B | 全员充弹 |
| **单兵充弹** | `01 14 FF <num:2BE> <ammo:2BE> <weapon> <sum> AF FA` | 11B | 单人充弹 |
| **超出范围预警** | `00 0D FF <num:2BE> <sum> AF FA` | 8B | 室内越界提醒 |
| **刺杀开始** | `00 00 03 FF 00 02 AF FA` | 8B | 刺杀模式开始（校验硬编码） |
| **刺杀结束** | `00 00 03 FF 01 03 AF FA` | 8B | 刺杀模式结束（校验硬编码） |
| **刺杀轮询** | `00 01 03 <num:2BE> <sum> AF FA` | 8B | 刺杀模式轮询 |

**字段说明**：
- `<num:2BE>` = 马甲编号，Big-Endian 2 字节
- `<mode>` = 0 自动 / 1 手动
- `<team>` = 队伍码，见 §5.2
- `<ammo:2BE>` = 弹药数，Big-Endian 2 字节
- `<weapon>` = 武器槽位，2=主武器 / 3=副武器
- `<battleMode>` = 0 训练 / 1 演习

> ⚠️ **实现范围裁剪（2026-10-20 决策）**：上表末尾 3 条**刺杀（Bayonet）指令**（刺杀开始 / 刺杀结束 / 刺杀轮询）对应的模块**不进 C 端**，新架构**不必实现**。
> 但字节定义**仍完整保留在本表**——保住协议文档的完整性，将来若要恢复功能可直接复用。同理：`t_bayonet_*` 表与 `/socket/BayonetBattle` 均不在实现范围。

---

## 3. 协议 B：UWB 定位二进制帧（**固定 41 字节**）

### 3.1 帧结构

```
偏移  0    1    2      3  4  5  6    7 ...................... 38   39     40
    ┌────┬────┬────┬──────────────┬──────────────────────────┬──────┬──────┐
    │ AA │ 29 │ ？？│  vestNum LE  │  8 × range(mm) LE int32   │ 校验 │  55  │
    └────┴────┴────┴──────────────┴──────────────────────────┴──────┴──────┘
     └─ HEAD ─┘  └保留┘   (4B)              (32B)              (1B)   TAIL
```

| 偏移 | 长度 | 字段 | 说明 |
|------|------|------|------|
| `[0..1]` | 2B | **HEAD** | 固定 `0xAA 0x29` |
| `[2]` | 1B | ✓ 保留字节 | 语义**忽略**，但**参与校验和**。见 §6.4 |
| `[3..6]` | 4B | **马甲编号** | **Little-Endian** uint32 |
| `[7..38]` | 32B | **8 路测距** | 8 × Little-Endian int32，**单位毫米（mm）** |
| `[39]` | 1B | **校验和** | `sum(byte[0..38]) & 0xFF` |
| `[40]` | 1B | **TAIL** | 固定 `0x55` |

**第 i 路基站测距的字节偏移**：`7 + 4*i`（i = 0..7），Little-Endian。

**校验算法**：
```java
int sum = 0;
for (int i = 0; i <= 38; i++) sum += data[i] & 0xFF;
if ((sum & 0xFF) != (data[39] & 0xFF)) { /* 丢弃 */ }
```

**来源**：[UwbFrameFramer.java L20-L23](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/uwb/framer/UwbFrameFramer.java#L20-L23) + [UwbLocationHandler.java L20-L29](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/battle/handler/UwbLocationHandler.java#L20-L29)

### 3.2 接入方式（两种，配置切换）

| 模式 | 配置值 | 说明 |
|------|--------|------|
| 串口 | `data-source: SERIAL` | UWB 盒子接本机串口（第 3 路串口） |
| TCP | `data-source: TCP_SERVER` | 后端作为 TCP Server 监听 `0.0.0.0:12001`，盒子主动连入 |

**TCP 模式参数**（[application.yml L139-L144](file:///d:/Code/sbdzxt02/backend/application.yml#L139-L144)）：
- `tcp-server-host: 0.0.0.0`
- `tcp-server-port: 12001`
- `tcp-read-timeout-ms: 20000`（Socket.setSoTimeout）
- `tcp-idle-warn-min: 3`（连续 N 分钟无字节 → WARN 日志）
- 稳定性要求：`setKeepAlive(true)` + 单连接全局汇聚（新连接踢出旧连接）

> ⚠️ **两种模式共用同一个 `UwbFrameFramer`**，不新增 TCP 专用成帧逻辑。

### 3.3 数据处理链

```
原始字节 → UwbFrameFramer.feed() → 41B 帧列表
         → UwbLocationHandler → ranges[8] (mm)
         → UwbSolver.solve2D() → 像素坐标（Huber-LM 13种子点）
         → UwbFilter（中值滑窗 + EMA 35cm/帧步限）
         → vest.uwbXPct / uwbYPct → 前端渲染
```

---

## 4. 其他通信协议（非必改，但需知晓）

| 协议 | 端点 | 用途 |
|------|------|------|
| HTTP REST | `:11001` | 业务接口（Knife4j 文档 `/doc.html`） |
| WebSocket | `/socket/ShootingBattle` | 桌面端实时推送 |
| WebSocket | `/socket/ShootingBattleApp` | 移动端实时推送（1000ms 定时） |
| WebSocket | ~~`/socket/BayonetBattle`~~ | ~~刺杀实时推送~~ —— 🔴 **不在实现范围**（刺杀模块已决策不进 C 端） |
| MQTT | ~~可配置~~ | ~~备用设备链路（Eclipse Paho）~~ —— 🔴 **不在实现范围**（MQTT 已决策移除） |

---

## 5. 枚举与常量映射表

### 5.1 马甲状态（`Constant`）

| 常量 | 值 | 含义 |
|------|----|----|
| `OFF_LINE_STATUS` | 0 | 离线 |
| `LIVE_STATUS` | 1 | 存活 |
| `DIE_STATUS` | 2 | 阵亡 |

**在线判定**：`now - lastHeartbeatMs <= 5000ms`（[ShootingVestModel.HEARTBEAT_ONLINE_WINDOW_MS](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/shootingvest/domian/ShootingVestModel.java#L105)）

### 5.2 队伍映射（`Constant.TEAM_MAP`）

| 队伍名 | 字节码 |
|--------|-------|
| red | `0x01` |
| blue | `0x02` |
| orange | `0x03` |
| yellow | `0x04` |

### 5.3 日志类型（`Constant`）

| 常量 | 值 | 含义 |
|------|----|----|
| `START_BATTLE_TPYE` | 0 | 演习开始 |
| `SHOT_LOG_TYPE` | 1 | 击中 |
| `DIE_LOG_TYPE` | 2 | 阵亡 |
| `LIVE_LOG_TYPE` | 3 | 复活/判活 |
| `INJURE_LOG_TYPE` | 4 | 判伤 |
| `RELOAD_LOG_TPYE` | 5 | 换弹 |

### 5.4 击中部位映射（`HitRecordModel.SOLIDER_SHOT_PART_MAP`）

**实兵演习（单兵）**：

| 部位码 | 含义 |
|--------|------|
| 0 | 未知 |
| 1 | 头部 |
| 2 | 胸部 |
| 3 | 左手 |
| 4 | 右手 |
| 5 | 腹部 |
| 6 | 背部 |
| 7 | 左腿 |
| 8 | 右腿 |

**载具（已注释未启用）**：`ARMORED_CAR_SHOT_PART_MAP` = 0 未知 / 1 前 / 2 后 / 3 左前 / 4 左后 / 5 右前 / 6 右后

> ⚠️ **注意区分**：刺杀（Bayonet）模块有**独立**的部位配置表 [t_bayonet_hit_area_config](file:///d:/Code/sbdzxt02/scds-new/src/main/resources/db/migration/V2.0__bayonet.sql)，用户可在 UI 自定义，**不复用**本表。
> 来源：[HitRecordModel.java L12-L24](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/battle/domain/HitRecordModel.java#L12-L24)

### 5.5 重武器/载具编号段（`HitRecordHandler.hitObject()`）

| 编号区间 | 名称 |
|---------|------|
| 1801~2000 | 35榴弹 |
| 2201~2400 | 80火 |
| 2401~2600 | 120火 |
| 3001~3200 | 步战车 |
| 3201~3400 | 突击车 |
| 3801~4000 | 装甲输送车 |
| 4201~4400 | 红箭73 |
| 4401~4600 | 迫击炮 |
| 4601~4800 | 火力点 |
| 65533 | 地雷 |
| 65534 | 爆炸物 |
| 65535 | 手雷 |

**`PEOPLE_OR_OBJECT_TYPE = 1800`** 为分界线：`> 1800` 视为武器/载具，`<= 1800` 视为单兵。

### 5.6 战斗数值常量

| 常量 | 值 | 说明 |
|------|----|----|
| `MORTAR_DEAD_DISTANCE` | 20 | 迫击炮致死半径（米） |
| `BUFFER_SIZE` | 5000 | 串口缓冲 |
| `HIGH_PRIORITY` | 0 | 高优先级指令（判死等） |

---

## 6. ✅ 协议定稿说明（**以现有解析实现为准 · 2026-10-20 定**）

> **决策**：本协议的**权威来源就是现有代码的解析实现**，不再依赖 `马甲 含室内定位  协议.xls` 人工核对。
> 理由：现有实现是**经实机联调验证过**的（170 个历史日志真实帧可对拍），而 `.xls` 只是硬件厂商的原始说明。
> 若日后拿到 `.xls` 且与本文冲突，**一律以本文（= 现有实现）为准**。
>
> 原来的 6 项"待人工核对"已逐项从代码中查实并定稿如下 —— **本协议已无待确认项，不再阻塞开工**。

### 6.1 `[2]` 保留字节（协议 A 所有帧）

| 问题 | 代码事实 | **定稿** |
|------|---------|---------|
| 第 3 字节是什么？ | 全代码库**无任何**语义读取（不参与任何字段解析） | **语义忽略**，新实现跳过不解析 |

⚠️ **但必须注意**：它**参与校验和**（校验从 `[0]` 累加到校验字节前一位，`[2]` 在范围内）。所以重组帧时 `[2]` 必须取**真实值**，不能填 0。
> 来源：[AbstractDataHandler.dataVerification()](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/battle/handler/AbstractDataHandler.java#L15-L23)

### 6.2 `ff03` 短版（13B）/ 长版（15B）判定条件

| 问题 | 代码事实 | **定稿** |
|------|---------|---------|
| 何时长 / 何时短？ | 无固件版本判断，**纯按帧长度分支**：`data.length >= 15` 走长版 | 新实现**同样按长度分支**，两版都必须支持 |

- 短版 13B：不含 楼层号 / 位置编号，此时**沿用上一帧的旧值**（不清零）
- 长版 15B：额外携带 `[10]` 楼层号、`[11]` 位置编号
> 来源：[HeartbeatHandler.java L50-L53](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/battle/handler/HeartbeatHandler.java#L50-L53)

### 6.3 未实现的帧类型

| 问题 | 代码事实 | **定稿** |
|------|---------|---------|
| `ff01/ff02/ff08/ff09` 存在吗？ | [DataHandlerContext](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/battle/handler/DataHandlerContext.java#L11-L18) 只注册 **6 种**，其余一律落 `DefaultHandler` 丢弃 | **只实现这 6 种，其余丢弃**（建议加计数器便于排障） |

| 已实现（帧类型码） | Handler |
|------------------|---------|
| `0x03` | `HeartbeatHandler` 心跳/状态 |
| `0x04` | `OutdoorGPSHandler` 室外 GPS |
| `0x05` | `HitRecordHandler` 击中记录 |
| `0x06` | `IndoorLocationHandler` 旧室内定位（UWB 模式下丢弃） |
| `0x07` | `MortarHitAreaHandler` 迫击炮/曲射 |
| `0x0A` | `SimNoRecordHandler` 模拟编号 |

> 寄存器编号不连续（缺 01/02/08/09）是硬件历史原因，**不影响实现**。

### 6.4 UWB 41B 帧 `[2]` 保留字节

与 6.1 完全同理：**语义忽略，但参与校验和**（校验累加 `[0..38]`，见 §3.1）。
> 来源：[UwbLocationHandler.java L23](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/battle/handler/UwbLocationHandler.java#L23)

### 6.5 8 路测距 ↔ 基站编号对应关系（**固定，不可调换**）

**定稿：严格一一对应** —— 测距数组下标 `i`（0-based）⇔ 基站 `bs(i+1)`（1-based）。

| 测距数组下标 | 帧内字节偏移 | 对应基站 | 必填性 |
|-------------|-------------|---------|--------|
| `ranges[0]` | `[7..10]` | `bs1` | **必填**（矩形一角） |
| `ranges[1]` | `[11..14]` | `bs2` | **必填** |
| `ranges[2]` | `[15..18]` | `bs3` | **必填** |
| `ranges[3]` | `[19..22]` | `bs4` | **必填** |
| `ranges[4]` | `[23..26]` | `bs5` | 可选（扩展） |
| `ranges[5]` | `[27..30]` | `bs6` | 可选（扩展） |
| `ranges[6]` | `[31..34]` | `bs7` | 可选（扩展） |
| `ranges[7]` | `[35..38]` | `bs8` | 可选（扩展） |

**几何约束（关键，易错）**：`bs1~bs4` 必须是**矩形四角**，编号顺序就是绕矩形一圈，且顺序有物理含义：
- `bs1 ↔ bs2` 的像素距离 → **长度基准** `lenPx`
- `bs1 ↔ bs4` 的像素距离 → **宽度基准** `widPx`
- 两条基准向量必须**近似正交**（`bs4` 与 `bs1` 相邻，即「左下 → 右下 → 右上 → 左上」绕一圈）。
  若把 `bs4` 放在 `bs1` 的**对角**（如左上 → 右下），`UwbSolver.Geometry` 的反解会算出非正的
  尺度平方，直接判 `HELD_META_INVALID`，该图**永远解不出室内坐标**。

> **更正记录（2026-10-27）**：本节原写「`bs1 ↔ bs3` 的像素距离 → 宽度基准」，与冻结的
> `UwbSolver.Geometry`（读 `anchors[0]/[1]/[3]`）及旧系统 `UwbV15RegressionTest` 的可解样例
> （`bs1` 左下 / `bs2` 右下 / `bs3` 右上 / `bs4` 左上）均不符，已按实现更正为 `bs1 ↔ bs4`。
> 协议字节、端口号、校验算法均未改动——本次只更正文档对既有实现的描述。

> 来源：[UwbLocationHandler.java L29](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/battle/handler/UwbLocationHandler.java#L29)（`leInt(data, 7+4*i)`）
> + [UwbSolver.java L80](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/uwb/solver/UwbSolver.java#L80)（`anchors[0]/[1]/[3]`）

### 6.6 `uwb_meta` JSON 结构（**已定稿**）

```jsonc
{
  "bs1": { "xPx": 120,  "yPx": 1565 }, // 必填，整数像素坐标；矩形左下
  "bs2": { "xPx": 1980, "yPx": 1565 }, // 必填；右下（bs1↔bs2 = 长度基准 lenPx）
  "bs3": { "xPx": 1980, "yPx": 240 },  // 必填；右上
  "bs4": { "xPx": 120,  "yPx": 240 },  // 必填；左上（bs1↔bs4 = 宽度基准 widPx）
  "bs5": { "xPx": 1050, "yPx": 1565 }, // 可选，可为 null 或不写
  "bs6": null, "bs7": null, "bs8": null,
  "realLengthCm": 3000,                // 必填，> 0
  "realWidthCm": 1800                  // 必填，> 0
}
```

> **示例已更正（2026-10-27）**：原示例按「左上/右上/左下/右下」编号，使 `bs1↔bs4` 退化为对角线，
> 照它录入的底图会被 `Geometry` 判 `HELD_META_INVALID`。现改为与解算器一致的四角顺序，
> 取值仍为同一张 2000×1600 像素、30m×18m 的场地。

**缩放与换算规则**（[UwbSolver.Geometry](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/uwb/solver/UwbSolver.java#L58-L104) + [toImagePct](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/uwb/solver/UwbSolver.java#L287-L300)）：

| 规则 | 公式 |
|------|------|
| 包围盒 | `minX/maxX/minY/maxY` **只统计 bs1~bs4**（bs5~bs8 不参与） |
| 物理尺寸 | `length=(maxX-minX)*sx`，`width=(maxY-minY)*sy`（`sx/sy` 由 `realLengthCm/realWidthCm` 与 `lenPx/widPx` 联合反解） |
| 像素→百分比 | `xPct=(minX + x*(maxX-minX)) / imageWidth`（Y 同理），**不能写反** |

**校验位置**：[MapService.validateUwbMetaSchema()](file:///d:/Code/sbdzxt02/scds-new/src/main/java/com/hitqz/scds/biz/map/service/MapService.java#L467-L510)
- `bs1~bs4` 缺失或 `xPx/yPx` 非整数 → 抛异常（Controller 转 **400**）
- `bs5~bs8` 可以缺省或为 `null`（若写了则 `xPx/yPx` 必须是整数）
- `realLengthCm` / `realWidthCm` 必须为正整数厘米

### 6.7 已作废项：`ff05` 击中强度（**非阻塞**）

该项原属刺杀评分，刺杀模块已决策不进 C 端（[ASSET-INVENTORY §6-1](file:///d:/Code/sbdzxt02/ASSET-INVENTORY.md)），**无需确认**。

---

## 7. 📸 黄金样本（**已从历史日志提取完成**）

> **状态更新（2026-10-20）**：原计划现场抓包，实际已改为**从 170 个历史日志中提取**，效果更好且可重复。
>
> **提取脚本**：[tools/extract_golden_samples.ps1](file:///d:/Code/sbdzxt02/tools/extract_golden_samples.ps1)
> **产物目录**：[samples/](file:///d:/Code/sbdzxt02/samples) —— `golden_samples.json`（含解析结果=期望值）、`sticky_packets.json`、`summary.json`

### 7.0 已提取结果（真实数据）

| 帧类型 | 唯一帧数 | 校验通过 | 说明 |
|--------|---------|---------|------|
| `ff03` 心跳 | 99 | 82 | 17 条校验失败 = **真实坏帧，可作负样本** |
| `ff04` GPS | 3000（已截断） | 3000 | 真实经纬度 118.71°E / 25.07°N |
| `ff05` 击中 | **3** | 3 | 击杀判定回归的关键样本 |
| **UWB 41B** | 3000（已截断） | 3000 | 帧头/帧尾/校验全部通过 |
| **粘包** | 300 | — | 一包多帧场景 |
| `ff06` / `ff07` / `ff0a` | **0** | — | ⚠️ 日志中不存在，需合成样本 |

**样本用法**：
- `checksumOk = true` → 正向样本（解析器必须解出相同字段值）
- `checksumOk = false` → 负向样本（解析器必须**丢弃**）
- `sticky_packets.json` → 粘包切分回归

### 7.1 缺失帧已用合成样本补齐

`ff06`（旧室内定位）、`ff07`（迫击炮）、`ff0a`（SIM 上报）在全部 170 个日志中**零出现**，已按 §1.4 字段表 + §1.3 校验算法**合成**：

**产物**：[samples/synthetic_frames.json](file:///d:/Code/sbdzxt02/samples/synthetic_frames.json)

| 帧 | HEX | 长度 | 期望解析值 |
|----|-----|------|-----------|
| `ff06` | `ff06000001000004240000021242affa` | 16B | vestNum=1, width=1060, height=530 |
| `ff07` | `ff07000003010e05dcf9affa` | 12B | vestNum=3, angle=270, distance=1500 |
| `ff0a` | `ff0a0000028986001234567890123404affa` | 18B | vestNum=2, simNo=89860012345678901234 |

> ⚠️ 合成样本仅验证**解析器内部一致性**，不能替代真实设备联调。首次实装时仍需按 §7.2 补抓真帧。

### 7.2 原始采集清单（现场补抓时参考）

| 文件名 | 内容 | 采集方法 |
|--------|------|---------|
| `samples/ff03_short.hex` | 单条 13B 心跳帧 | 串口抓包，过滤 `ff03` + 长度 26 hex 字符 |
| `samples/ff03_long.hex` | 单条 15B 心跳帧（带楼层/点位） | 同上，长度 30 hex 字符 |
| `samples/ff04_gps.hex` | GPS 帧 + 已知经纬度标注 | 户外模式，记录 GPS 真值对照 |
| `samples/ff05_hit.hex` | 击中帧（含单兵击中 + 手雷击中两种） | 实弹/模拟触发 |
| `samples/ff07_mortar.hex` | 迫击炮帧 + 落点真值 | 记录发射点/方位/射程/实际落点 |
| `samples/ff0a_sim.hex` | SIM 上报帧 | — |
| `samples/multi_frame.hex` | **一包多帧**（粘包） | 连续快速上报 |
| `samples/split_frame.hex` | **半包分片**（人为拆分） | 用分片工具或高波特率制造 |
| `samples/bad_checksum.hex` | **校验错误帧** | 人为篡改 1 字节 |
| `samples/garbage_prefix.hex` | **垃圾前缀 + 完整帧** | 覆盖粘包处理分支 3 |

### 7.2 UWB 样本

| 文件名 | 内容 |
|--------|------|
| `samples/uwb_41b_multi.hex` | 多帧连续 41B 二进制流（含正常/错帧混杂） |
| `samples/uwb_known_position.json` | 配合上一条：马甲在已知物理坐标时的 8 路测距值 + 期望解算输出 |

### 7.3 期望产物（回归测试基线）

```
samples/
├── *.hex                          # 原始字节流
├── expected_frames.json           # 每份样本期望解析出的帧列表（帧类型+字段值）
└── expected_solve.json            # UWB 样本期望解算坐标（用于 UwbSolver 对拍）
```

**验收标准**：重构后的 `scds-protocol` 模块跑这些样本，**解析结果必须逐字段等于 `expected_frames.json`**；`UwbSolver` 输出必须**逐位**等于 `expected_solve.json`。

---

## 8. 采集操作脚本（现场执行）

```powershell
# 前提：先备份当前串口原始日志
# 现有代码已把原始帧打在日志里，可用 grep 提取

# 1. 从日志中提取所有 ff03 帧（含前后文）
Select-String -Path "logs\scds*.log" -Pattern "开始处理数据帧\[(ff03)" |
    ForEach-Object { $_.Line } | Out-File -Encoding utf8 samples\ff03_all.txt

# 2. 提取所有帧类型统计（确认实际出现的帧种类）
Select-String -Path "logs\scds*.log" -Pattern "数据帧\[(ff0[0-9a-f]{1})" -AllMatches |
    ForEach-Object { $_.Matches.Groups[1].Value } |
    Group-Object | Sort-Object Count -Descending |
    Format-Table Name, Count -AutoSize

# 3. 提取 UWB 41B 帧（UwbFrameFramer 的 OK 行）
Select-String -Path "logs\scds*.log" -Pattern "UWB-RX.*data=([0-9a-f]+)" -AllMatches |
    ForEach-Object { $_.Matches.Groups[1].Value } | Out-File -Encoding utf8 samples\uwb_all.txt
```

> ⚠️ **重要**：日志提取只能覆盖"后端已成功解析"的帧，**无法覆盖**半包/错帧/垃圾前缀场景（这些帧被丢弃了）。完整样本必须靠**串口抓包工具**（如 SSCOM / 友善串口调试助手）在物理层抓原始字节流。

---

## 9. 重构实现检查清单

新 `scds-protocol` 模块完成后，必须逐项确认：

- [ ] 6 种上行帧均能正确解析（含 `ff03` 长短两版）
- [ ] 上行校验算法与现有一致
- [ ] 12 种下行指令字节序列与现有**逐字节一致**
- [ ] 粘贴/半包 4 种分支行为与现有一致（含"丢弃垃圾前缀"）
- [ ] 41B UWB 帧解析正确（Little-Endian 8 路测距，单位 mm）
- [ ] UWB 校验与帧头/帧尾严格判定
- [ ] `ff06` 在 UWB 模式下被丢弃的行为保留
- [ ] 所有枚举/常量映射表与原值一致（§5）
- [ ] 通过 §7 全部黄金样本回归
- [ ] §6 各项已按实现定稿落地（含 `[2]` 保留字节参与校验、`ff03` 长短版、8 路 ↔ bs1~bs8 固定对应、`uwb_meta` schema）

---

> **文档维护**：任何协议变更必须先更新本文档，再改代码。本文档是协议的**唯一权威来源**。
