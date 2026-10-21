# D03：设备绑定与单兵登录凭据（决策稿）

> 状态：**已裁定**（2026-10-21 用户对 D03-1..D03-7 逐项裁定，见
> [decision-register.md 第 6 节](../decision-register.md)）。裁定口径：只认 BCrypt 绑定密钥、
> 本阶段不做绑定接口、其余语义按推荐采纳。
> 校验侧（DEVICE-AUTH）**已实施**：`DeviceAuthService` + `LoginThrottle` 的 `DEVICE` 口径 +
> `POST /api/v1/auth/device/login` 接线；写入侧仍按 D03-2 的 A 方案，本阶段不做。
> 依据：已应用的 V6/V9 结构、当前契约与代码、[03-auth.md](03-auth.md)。
> 与 [03b-token-capabilities.md](03b-token-capabilities.md) 的关系见第 6 节：
> 03b 的"云端账号签发 / 边缘只验签"前提已被用户后续的**本地统一签发**决定取代。

## 1. 结论摘要

| 事项 | 结论 |
|---|---|
| 是否需要新迁移 | **不需要**。`t_vest` 已有 `bind_secret_hash` / `bound` / `sim_no` / `device_session_sid` / `device_login_at`，写入路径也已就绪 |
| 是否需要新配置键 | 建议**不新增全局开关**（见 D03-1）；若坚持"由部署模式决定密钥来源"则需新增配置 |
| 需要新增契约的 | **仅绑定密钥的写入入口**（新增/换绑/解绑），属独立决策，见 D03-2 |
| 建议的推进切分 | DEVICE-AUTH 只交付**校验侧**（登录、SID、错误码）；写入侧归 M2 LEDGER。DEVICE-AUTH 用测试与部署工具直接写库取得绑定数据 |
| 单兵登录失败口径 | 未登记 / 已退役 / 未绑定 / 密钥错 **统一 401 `AUTH_DEVICE_NOT_BOUND`**，不区分 |
| 顶号语义 | 沿用"写新 SID 即顶掉旧令牌"，第一版**不引入 SID 缓存**，库即权威 |

## 2. 现状核查（证据）

### 2.1 数据结构已就绪

- `t_vest`（[V6__device.sql](file:///e:/DualX/scds-next/scds-core/src/main/resources/db/migration/V6__device.sql#L24-L44)）：
  `bind_secret_hash VARCHAR(100) NULL`（BCrypt 摘要）、`bound TINYINT DEFAULT 0`、`sim_no`、`enabled`。
- `t_vest` 会话两列（[V9__auth_tokens.sql](file:///e:/DualX/scds-next/scds-core/src/main/resources/db/migration/V9__auth_tokens.sql#L57-L59)）：
  `device_session_sid CHAR(36) NULL`、`device_login_at DATETIME(3) NULL`。
- 领域模型 [Vest.java](file:///e:/DualX/scds-next/scds-domain/src/main/java/com/dualx/core/domain/Vest.java#L20-L33) 已含 `bindSecretHash` / `bound` / `simNo`。

### 2.2 写入与校验代码已存在

- [VestRepository.java](file:///e:/DualX/scds-next/scds-core/src/main/java/com/dualx/core/repository/VestRepository.java#L62-L111)
  的 `insert` / `update` **已经写** `bind_secret_hash` 与 `bound`——只是没有任何调用方传非空值进来。
- [VestDeviceSessionRepository.java](file:///e:/DualX/scds-next/scds-core/src/main/java/com/dualx/core/repository/VestDeviceSessionRepository.java)
  已实现 `findSessionSid` / `openSession` / `clearSession`。
- [DeviceTokenClaims.java](file:///e:/DualX/scds-next/scds-domain/src/main/java/com/dualx/core/port/DeviceTokenClaims.java)
  定义 `sub=vestNum`、`sid=sessionId`；[RsaTokenSigner.java](file:///e:/DualX/scds-next/scds-api/src/main/java/com/dualx/api/security/RsaTokenSigner.java#L119-L123)
  已按 `aud=scds-device`、TTL 12h 签发。
- 错误码 [ApiErrorCode.java](file:///e:/DualX/scds-next/scds-domain/src/main/java/com/dualx/core/error/ApiErrorCode.java#L22)
  已有 `AUTH_DEVICE_NOT_BOUND`、`DEVICE_VEST_NOT_FOUND`、`DEVICE_VEST_NUM_CONFLICT`。

### 2.3 契约与接线缺口（D03 的正题）

- 登录契约存在：[DeviceLoginRequest](file:///e:/DualX/scds-next/docs/api/schemas/domain.yaml#L85-L98) = `{vestNum, secret}`；
  [authDeviceLogin](file:///e:/DualX/scds-next/docs/api/paths/auth.yaml#L98-L131) 声明 401 = `AUTH_DEVICE_NOT_BOUND`。
- **没有任何绑定密钥的写入入口**：[device.yaml](file:///e:/DualX/scds-next/docs/api/paths/device.yaml) 的 operation
  只有 `listVests / createVest / getVest / updateVest / deleteVest / getVestLink / listAnchors / upsertAnchor /
  getLinks / listSerialPorts / openVestSerial / closeVestSerial / openUwbLink / closeUwbLink / watchLinks`。
  [VestDeviceUpsertRequest](file:///e:/DualX/scds-next/docs/api/schemas/domain.yaml#L383-L400) 没有 `bindSecret`、
  也没有 `bound`、`simNo`。**即：按当前契约，没人能造出一个可登录的设备。**
- 接线仍是占位：[AuthController.java](file:///e:/DualX/scds-next/scds-api/src/main/java/com/dualx/api/auth/AuthController.java#L38-L51)
  的 `device/login` 与 `device/me` 抛 `NotImplementedException`（501）。
- 鉴权已就位：[SecurityConfig.java](file:///e:/DualX/scds-next/scds-api/src/main/java/com/dualx/api/security/SecurityConfig.java#L90-L103)
  已放行 `POST /api/v1/auth/device/login`，且 `/api/v1/auth/device/**` 要求 `AUD_scds-device`。

### 2.4 一个必须先解决的矛盾（安全）

[VestDevice](file:///e:/DualX/scds-next/docs/api/schemas/domain.yaml#L350-L381) 的响应里
**明文暴露 `simNo`**（任何拥有 `device:read` 的角色，含 VIEWER，都能读到）。
而 V6 注释与 [DeviceLoginRequest](file:///e:/DualX/scds-next/docs/api/schemas/domain.yaml#L95-L98) 都把
"SIM 号"列为绑定密钥的候选来源。**若采纳 SIM 作密钥，等于把口令印在台账详情里**——
一次 `GET /api/v1/device/vests/{vestNum}` 即可拿到登录凭据。这是 D03-1 的核心约束。

## 3. 待决项与选项

### D03-1 密钥来源：BCrypt 绑定密钥 / SIM 号

| 选项 | 内容 | 影响 |
|---|---|---|
| A（推荐） | 只认 `bind_secret_hash`（BCrypt）。`sim_no` 仅作台账信息，永不作凭据 | 不需要新配置；SIM 明文暴露的问题自然消解；现场需分发一次绑定密钥 |
| B | 每设备按列显式优先级：`bind_secret_hash` 非空则校验它，否则校验 `sim_no`（常量时间比较） | 无需全局开关，但**必须同时**把 `sim_no` 从台账响应中移除或掩码，否则口令可读 |
| C | 全局配置 `scds.device.binding-secret-source=bind-key\|sim-no` | 引入"配置说用 SIM、设备行里却存了密钥"的双真相；与 03b"不用部署模式推导能力"的取向相悖 |

推荐 A。若现场确实只能以 SIM 号交付，请选 B 并接受"必须改 `VestDevice` 响应"这一连带改动（改名掩码属于契约变更，需单独登记）。

### D03-2 绑定密钥的写入入口

| 选项 | 内容 | 影响 |
|---|---|---|
| A（推荐） | **本阶段不做接口**：DEVICE-AUTH 只做校验侧；绑定/换绑/解绑的契约在 M2 LEDGER 一并交付；DEVICE-AUTH 的集成测试与部署工具直接写库 | 契约零扩张；DEVICE-AUTH 可立刻开工；但"指挥台能绑定设备"要等 LEDGER |
| B | 新增 `POST /api/v1/device/vests/{vestNum}/binding`（绑定/换绑）+ `DELETE .../binding`（解绑），ADMIN 权限 | 需扩契约与 `openapi.yaml` 操作计数（66 → 68）；LEDGER 交付内容前移 |
| C | 复用 `PUT /api/v1/device/vests/{vestNum}`，在 `VestDeviceUpsertRequest` 上加只写 `bindSecret` | 契约最小；但把"改姓名"与"重设口令"混在同一请求，误操作即全设备换密 |

推荐 A。若你要求"指挥台首版就能绑定"，则选 B（C 的高危语义不建议）。

### D03-3 `bound` 的含义

- 现状：`bound=0` 表示"设备是好的，只是还没被某个单兵 App 认领"。
- **推荐**：密钥校验通过即视为认领，登录成功时置 `bound=1`。理由：认领凭据就是密钥本身，要求指挥台先手工置位等于多一道无用前置，且现场换机时无人操作。
- 备选：必须 `bound=1` 才允许登录（相当于预授权）。
- **需你确认**：是否接受"首次成功登录自动 `bound=1`"。这会改变 `/device/vests` 列表里 `bound` 的语义（从"管理员登记过"变为"有人登录过"）。

### D03-4 换绑 / 解绑 / 注销时的会话与密钥处置

| 动作 | 建议处置 | 理由 |
|---|---|---|
| 换绑（重设密钥） | 同事务写新 `bind_secret_hash` + `device_session_sid = NULL`，保留 `bound=1` | 旧令牌立刻失效；设备仍属已认领，只是换了凭据 |
| 解绑 | `bound = 0` + `device_session_sid = NULL`，**保留** `bind_secret_hash` | 免得重绑时重新分发密钥；若连密钥一起清，重绑必须走完整分发流程 |
| 注销（`deleteVest` 逻辑删除） | `enabled = 0` + `device_session_sid = NULL` | 已退役设备不得保留有效会话 |

**需你确认**：解绑是否清密钥（上表建议"不清"）。另外，换绑/解绑是否需要把旧密钥使用者踢出（即清 SID）——上表建议"必须清"。

### D03-5 SID 校验是否加缓存

- 推荐：**不加缓存**。每次请求按主键读一次 `t_vest.device_session_sid`；设备规模上限 1800，主键读成本可忽略，换来"顶号即时生效"这一无歧义语义。
- 03b 提到的"提交后主动失效 SID 缓存"是**有缓存才需要**的动作；不引缓存则不需要该机制，也不必引入跨进程失效协议。
- 若未来因容量引入缓存，属 D10（容量与队列）范围，须同时定义 TTL 与失效协议。

### D03-6 设备登录的限流口径

- 现状：[LoginThrottle.java](file:///e:/DualX/scds-next/scds-core/src/main/java/com/dualx/core/auth/LoginThrottle.java#L131-L143) 的口径只有 `ACCOUNT` 与 `CLIENT_IP` 两种。
- 推荐：复用同一组件，新增一个 `DEVICE` 口径（键 `vest:<num>`，阈值同账号 5 次 / 5 分钟）；`CLIENT_IP` 口径继续共用。
- **需你确认**：阈值是否沿用账号口径（5 次 / 5 分钟），还是另定。

### D03-7 响应与错误码（不建议改契约）

- 保持 [DeviceTokenPair](file:///e:/DualX/scds-next/docs/api/schemas/domain.yaml#L100-L108) = `{deviceToken, expiresIn}` 不变；**不**在响应里回显 `vestNum`/`sid`。
- 失败矩阵（推荐，全部落在既有错误码内，`openapi.yaml` 无需新增）：

| 情况 | HTTP | code |
|---|---|---|
| `vestNum` 不在台账 / `enabled=0` / 未绑定且无密钥 / 密钥错 | 401 | `AUTH_DEVICE_NOT_BOUND` |
| `vestNum` 缺失或越界（<1 或 >65532）、`secret` 空 | 400 | `VALIDATION_FAILED` |
| 触发限流 | 429 | `RATE_LIMITED` + `Retry-After` |
| `device/me`（等态势服务） | 501 | `NOT_IMPLEMENTED` |

理由：登录接口不应同时充当台账探测器，"设备是否存在"与"密钥是否正确"必须不可区分——与账号侧 `AUTH_BAD_CREDENTIALS` 同一逻辑。

## 4. 若采纳推荐项，DEVICE-AUTH 的数据流

```text
POST /api/v1/auth/device/login  {vestNum, secret}
  1. 限流检查（DEVICE 口径 vestNum + CLIENT_IP 口径）        → 429
  2. 契约校验：vestNum ∈ [1,65532]、secret 非空               → 400
  3. 读 t_vest(vest_num)：不存在 / enabled=0 / bind_secret_hash 为空 → 401
  4. BCrypt.matches(secret, bind_secret_hash)                 → 401
  5. 生成 sid(UUIDv7) → openSession(vestNum, sid, now) 且置 bound=1（同一 UPDATE）
     · 影响行数 0 → 401（读后被删/被退役）
  6. signDeviceToken{sub=vestNum, sid, aud=scds-device, TTL=12h}
  7. 返回 {deviceToken, expiresIn=43200}
```

要点：**先落库再到客户端**（令牌离开进程前，库里必须已是同一个 sid）；不校验"演习是否在跑"（设备登录属边缘本地事务）；不产生 `t_refresh_token` 行。

后续请求用 `deviceToken`：`SecurityConfig` 按 `AUD_scds-device` 放行 `device/**`，业务侧再比对 `sid` 与 `t_vest.device_session_sid`（不等的请求按 `AUTH_TOKEN_INVALID` 拒绝）。

## 5. 影响与遗留

- 若采纳 D03-2 选项 A，则**本阶段不改任何契约**，DEVICE-AUTH 的三个文件白名单可在一段内完成（例如 `DeviceAuthService`、`AuthController` 的 device/login 分支、以及一个 IT）。
- 若采纳选项 B，则本阶段需要契约 + 控制器 + 服务 + 测试，必须拆成 ≥2 个原子任务。
- `device/me` 仍保持 501（依赖 STATE 与 PUSH-MODEL），本阶段不动。
- 03b 的"双密钥 / 双 decoder / 只验签实例"整章在本地统一签发下**不再是目标形态**；本次不删不改该文件，待你决定是否另开任务标记 superseded。
- 03b §5（[R05 精确 404 出口](file:///e:/DualX/scds-next/docs/design/03b-token-capabilities.md#L128-L134)）只在"能力可关闭"时才需要；本地统一签发下账号与设备签发都常开，**不构成 DEVICE-AUTH 的前置**。

## 6. 待批准清单（请逐项确认）

1. D03-1 密钥来源：选 A（只认 BCrypt 绑定密钥）／B（按列回退到 SIM，需同时掩码响应）／C（全局开关）。
2. D03-2 写入入口：选 A（本阶段不做接口，写入归 LEDGER）／B（新增 binding 端点）／C（并入 updateVest）。
3. D03-3 首次登录是否自动置 `bound=1`。
4. D03-4 解绑是否保留密钥；换绑/解绑是否必须清 SID。
5. D03-5 确认不引入 SID 缓存。
6. D03-6 设备登录限流阈值（沿用 5 次 / 5 分钟？）。
7. 是否授权按选定的 A/A 方案开工 DEVICE-AUTH（预计文件白名单：`scds-core` 新增 `DeviceAuthService`、`scds-api` 改 `AuthController`、`scds-core` 新增 `DeviceAuthServiceIT`）。
