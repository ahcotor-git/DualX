# 设计 03c —— 边缘账号身份同步（包 6 前置方案）

> 状态：**纯设计，未实现** | 起草日期：2026-11-16（段 92） | 不改代码 / 不改库 / 不新增迁移 / 不改 `.env` / 不生成密钥
> 关联：[03-auth.md](03-auth.md) §4.2 / §4.4 / §4.8 / §4.10；[03b-token-capabilities.md](03b-token-capabilities.md) §7（边缘账号身份缺口）/ §10.5（工作包 6 依赖项）。
> 段 92 已裁定身份策略取「**边缘预置 / 同步最小账号身份与角色快照，`/auth/me` 复用本地查库语义**」，**不**采纳「令牌携带签名身份快照」。
> 本文只把该裁定落成三项前置的**可执行前置方案**：**相同 UUID 约定 / 初始化同步工具 / 失效与陈旧容忍策略**，并复述**不得声称**的边界。
> 本文**不构成开工、提交或推送授权**（§7）。

---

## 0. 结论摘要（决策点 → 推荐项）

| 编号 | 决策点 | 推荐项 |
|---|---|---|
| A1 | 本地建号（`AdminAccountBootstrap`）与云端 UUID 约定的关系 | **按部署形态二选一**：CLOUD 形态**不**本地建号（`scds.admin.*` 两项留空），改由同步工具按云端 UUID 预置；PRIVATE / 单机形态保留本地建号。**禁止**「本地建号后再改主键对齐云端」。 |
| A2 | 云端 UUID 的来源 | 运维在云端导出**离线「账号快照文件」**，边缘一次性导入。**不**在运行时调云端查询。 |
| A3 | UUID / 用户名冲突的检测与报错形态 | 任一冲突或非法条目 → **整体拒绝 + 非零退出 + 逐条报告**；**不**碰撞匹配、**不**静默覆盖。 |
| B1 | 同步工具形态 | **独立运维导入命令**，显式配置快照路径才执行；**不**随启动静默同步。 |
| B2 | `password_hash` 如何处置 | 写「**不可登录占位散列**」（复用现有 dummy-hash 思路）；**禁止**复制云端口令散列。 |
| B3 | 导入的写入语义 | 按 UUID upsert：新 UUID 则 insert；已存在则更新 `display_name` / `roles` / `enabled`；**不改** `password_hash`、**不改** `username`；用户名冲突即拒绝。 |
| B4 | 是否新增迁移 | **否**；用现有 `t_account` / `t_account_role`，字段已齐备。 |
| C1 | 快照落后时 `me` 返回什么 | **本地库现态**；不做新鲜度校验、**不**隐式回源。 |
| C2 | 本地 `enabled=false` 的处置 | 沿用 `me` 现有语义（→ 401）；**不**声称 accessToken 即时撤销。 |
| C2-2 | 授权时是否强制回查本地 `enabled` | **本轮不做**（超出本方案范围，且与无状态令牌语义冲突）；仅列为待议项。 |
| C3 | 云端删号的处置 | 快照无删除指令则**不动**本地行；有删除标记则置 `enabled=false`，**不**物理删除。 |
| C4 | 导入的原子性与失败形态 | **单事务、全量**；任一条失败**整体回滚**。 |
| E1 | 同步工具归属哪个工作包 | **单独开包**；包 6 只收口 AUTH-HTTP 端点，避免文件数膨胀。 |

> **（2026-11-16 裁定）** 上表**全部推荐项逐项采纳**（用户裁定「裁定 03c 决策点（按 §0 推荐项逐项裁定）」）。
> 故下文各节标题下的「推荐」**即为已裁定口径**；**唯 §4.2 的 C2-2（授权回查本地 `enabled`）仍为待议项**，
> 须与 D04 权限规则对齐后再定。裁定记录见 [decision-register.md](../decision-register.md)（段 92 工作包 6 前置方案条）。

---

## 1. 背景与问题

云端签发账号令牌、边缘只验签（[03b](03b-token-capabilities.md) §2）。但 `GET /api/v1/auth/me` 需要**本地** `t_account` + `t_account_role` 才能返回完整 `User`：

- [AuthController.java](file:///e:/DualX/scds-next/scds-api/src/main/java/com/dualx/api/auth/AuthController.java#L57-L61) —— `getCurrentUser` = `toUser(authService.me(accountId(jwt)))`；`accountId(jwt)` 直接把 JWT `sub` 当账号 UUID 解析。
- [AuthService.java](file:///e:/DualX/scds-next/scds-core/src/main/java/com/dualx/core/auth/AuthService.java#L205-L210) —— `me(accountId)` = `accounts.findById(accountId).filter(Account::enabled).orElseThrow(invalidToken)`。
- `User` 五要素（见 [AuthController.toUser](file:///e:/DualX/scds-next/scds-api/src/main/java/com/dualx/api/auth/AuthController.java#L116-L121)）：`username` / `displayName` / `roles` / `permissions` / `lastLoginAt`；JWT 只稳定携带 `sub` / `roles`。

**因此：云端令牌验签通过 ≠ 边缘已有同 UUID 账号，也不等于能返回完整 `User`。** 本文的全部内容就是把「边缘如何拥有与云端同 UUID 的最小账号身份」这件事定死。

---

## 2. 相同 UUID 约定

### 2.1 硬约束（为什么必须同 UUID）

`accountId(jwt)` 直接取 `sub` 当 `t_account.id` 查库，**没有**用户名回退、**没有**映射表、**没有**补默认值。故：

> **边缘 `t_account.id` 必须逐字等于云端签发实例写入 `sub` 的账号 UUID。**

不满足该等式，该账号的云端令牌在边缘 `/auth/me` 恒为 401（`AUTH_TOKEN_INVALID`）。这是一条**硬等式**，不是"建议对齐"。

UUID 形态沿用库约定（[V1__auth.sql](file:///e:/DualX/scds-next/scds-core/src/main/resources/db/migration/V1__auth.sql) 文件头第 1 条）：业务主键一律 **UUIDv7**、`CHAR(36)`、应用侧生成。

### 2.2 现状冲突（本方案要消除的具体缺口）

现存**唯一建号路径**是 [AdminAccountBootstrap.java](file:///e:/DualX/scds-next/scds-core/src/main/java/com/dualx/core/auth/AdminAccountBootstrap.java)：

- 读 `scds.admin.*`（`username` / `password` / `displayName`）；
- `id = UuidV7.generate()` —— **本地生成**，与云端账号 UUID 无关；
- 只建不覆盖；两项全空即"CLOUD 形态，由云管下发账号"的预期状态。

**冲突点**：本地建出来的 UUID 是「边缘自己的随机 UUIDv7」，与云端权威 UUID 不可能相同。于是形如「边缘已用 `admin` 建号 → 云端 admin 令牌到边缘 → `sub`(云端 UUID) 在 `t_account` 查不到 → 401」。

**部署侧放大**：[deploy.sh](file:///e:/DualX/scds-next/deploy/minipc/deploy.sh) 首启生成 `.env`（`SCDS_ADMIN_USERNAME=admin` + 随机口令）并写 `scds.admin.*`；[verify.sh](file:///e:/DualX/scds-next/deploy/minipc/verify.sh) 断言 `t_account` 计数 = 1、角色 `ADMIN`、`username='admin'`。也就是说**默认出现的是 PRIVATE 形态的一本地 admin**。若现场目标为 **CLOUD 形态**，必须**不**生成该本地 admin（`.env` 不配 `SCDS_ADMIN_*`），否则会得到一个 UUID 与云端不一致的"影子 admin"，它的云端令牌在边缘永远 401。**这是发布前必须显式选择的部署形态**（见 A1）。

### 2.3 决策点

**A1 —— 本地建号与云 UUID 约定的关系**

| 选项 | 说明 |
|---|---|
| **① 按形态二选一（推荐）** | CLOUD 形态：`scds.admin.*` 两项留空（既有预期状态，`AdminAccountBootstrap` 直接返回）+ 由同步工具按云端 UUID 预置。PRIVATE / 单机形态：保留本地建号，不接入云端账号。二者**互斥**，由部署形态决定。 |
| ② 删除 / 禁用 `AdminAccountBootstrap` | 破坏 PRIVATE 形态与现有 `deploy.sh` / `verify.sh`；不采纳。 |
| ③ 本地建号后「改主键对齐云端」 | **禁止**：改主键会孤立 `t_account_role`、`t_audit_log.account_id` 引用；且等价于"碰撞后再改派"，与 §7 禁令冲突。 |

**A2 —— 云端 UUID 的来源**

| 选项 | 说明 |
|---|---|
| **① 离线账号快照文件（推荐）** | 运维在云端导出账号快照（见 §3.2），边缘一次性导入。可离线、可审阅、无隐式云端调用。 |
| ② 边缘运行时调云端 API 拉取 | **禁止**：断网隐式调云端；且违背"不引入云端查询适配器"。 |
| ③ 令牌携带签名身份快照 | **已裁定不采纳**（[03b](03b-token-capabilities.md) §7）。 |

**A3 —— 冲突检测与报错形态**

- 校验每条：`id` 为**合法 UUID**（建议进一步要求**版本位 = 7**，与 V1 约定一致；边缘运行时 `UUID.fromString` 只要求合法 UUID，不校验版本，故此为约定层校验）。
- 冲突判定（任一命中即拒绝）：
  1. 同一 `id` 在快照内重复；
  2. `id` 已存在于本地但 `username` 不同；
  3. `username` 已被**另一个** `id` 占用（注意 `t_account.username` 为 `utf8mb4_0900_ai_ci`，**大小写 / 重音不敏感**，`Admin` 与 `admin` 视为冲突）。
- 报错形态：**逐条列出**冲突原因 + **非零退出**；**绝不**静默覆盖、**绝不**按用户名匹配改写 UUID、**绝不**为无法解析的条目补默认角色。
- 边缘本地已存在、但快照中不存在的 UUID（如 PRIVATE 形态建号）属**脏数据**：工具**只报告、不改派、不删除**，交人工处置。

---

## 3. 初始化同步工具

### 3.1 形态（决策点 B1）

**推荐：独立运维导入命令。** 从**文件**读账号快照，在**单事务**内写入 `t_account` + `t_account_role`。

- 触发方式：显式配置项（拟名 `scds.identity.import-file=<path>`，**非秘密**）；**仅当配置了路径才执行**，不配则完全不动作。
- **不**随启动静默同步、**不**轮询云端、**不**在请求路径上触发。
- 导入完成后应在配置中移除该路径，避免每次启动都"重放"一次快照（重放虽幂等，但会覆盖本地对 `roles` / `enabled` 的本地改动，见 B3）。

| 备选 | 说明 |
|---|---|
| 纯手工 SQL | 不推荐：易错、无校验、绕过业务语义。 |
| 随启动自动同步 | 不推荐：与"断网"和"启动无副作用"冲突。 |

### 3.2 快照文件结构（拟议，非契约）

```json
{
  "schemaVersion": 1,
  "source": "customer-account-authority",
  "generatedAt": "2026-11-16T00:00:00Z",
  "accounts": [
    { "id": "0190…（UUIDv7）", "username": "zhang", "displayName": "张三",
      "roles": ["DIRECTOR"], "enabled": true }
  ]
}
```

- **不含任何口令 / 散列字段**：云端口令散列**不进边缘**（§7 明令）。
- `roles` 取值必须落在契约枚举 `ADMIN / DIRECTOR / OPERATOR / VIEWER` 内（库侧 [CHECK 约束](file:///e:/DualX/scds-next/scds-core/src/main/resources/db/migration/V1__auth.sql) 会拒非法值；本工具须**先校验并在报告中点名**，而非让整批撞 CHECK）。
- `displayName` 允许为空（`t_account.display_name` 可 NULL）。
- `source` / `generatedAt` 仅供人工审阅与溯源，**不**参与 `me` 的任何判定（见 C1）。

### 3.3 口令占位（决策点 B2）

`t_account.password_hash` 为 `NOT NULL`（BCrypt）。云端账号在边缘是"只验令牌、不本地口令登录"的形态，但又**不得**复制云端口令散列。

**推荐：写入「不可登录占位散列」** —— 一个语法合法、但**永不匹配任何口令**的 BCrypt 串。这正是代码中已有的思路：[AuthService.DUMMY_PASSWORD_HASH](file:///e:/DualX/scds-next/scds-core/src/main/java/com/dualx/core/auth/AuthService.java#L50-L53)（明文随机、从不作为口令被接受，仅用于常量时间陪跑）。

- 纵深防御意义：即使有人拿云端 `username` 去边缘 `/auth/login` 尝试，占位散列也使校验必然失败。（CLOUD 形态下 `login` 端点本就 404，占位是第二道关。）
- 落地提示：该 dummy 常量当前是 `AuthService` 的 `private static final`；同步工具需要时，应把它**提升为共享常量 / 工具类**（属实现细节，不在本文定稿）。

| 备选 | 说明 |
|---|---|
| 写空串 `""` | 不推荐：非合法 BCrypt 形态。 |
| 不写 `password_hash` | 不可行：违反 `NOT NULL`。 |

### 3.4 写入语义（决策点 B3）

对快照中每个账号，按 **UUID** 处理：

1. `findById(id)` **不存在** → 校验 `existsByUsername(username)` 无冲突 → `insert`（含占位口令）。
2. `findById(id)` **已存在** → 校验其 `username` 与快照一致 → 更新 `display_name`、`enabled`，并 `accountRoles.replaceAll(id, roles)`（沿用 [AdminAccountBootstrap](file:///e:/DualX/scds-next/scds-core/src/main/java/com/dualx/core/auth/AdminAccountBootstrap.java) 的角色写法，使**快照成为角色权威**）。
3. 任一冲突（A3）→ **整体拒绝**。

**不**触碰：`password_hash`（本地口令是本地资产，云端快照无此字段）、`username`（改名不在本工具范围，须单独裁定）、快照外的本地行（**不删除**）。

> **副作用提示**：`replaceAll` 会让**边缘本地对 `roles` 的改动在下一次导入时被快照覆盖**。这与"快照为权威"一致，但要求角色变更**走云端**；需要"边缘可本地加角色"的诉求须单独裁定。

### 3.5 是否新增迁移（决策点 B4）

**推荐：不新增迁移。** 现有 `t_account` / `t_account_role` 字段已齐备（`id` / `username` / `display_name` / `password_hash` / `enabled` / 时间戳；角色键 + CHECK）。工具只做**数据写入**，不碰 DDL。

- 若将来需要"快照来源 / 导入时间戳"做陈旧判定（C1 明确**不需要**），**须单独授权**加迁移；本方案**不要求**。
- 严守 [03b](03b-token-capabilities.md) §8 末：不改已应用 V1~V11、不默认加 V10+。

---

## 4. 失效与陈旧容忍策略

### 4.1 快照落后时 `me` 返回什么（决策点 C1）

**推荐：返回本地库现态，语义与现状完全一致。**

- `me` = `findById(sub).filter(enabled)` → 命中返 `User`（`roles` / `permissions` / `displayName` 全部取自本地），未命中或禁用 → 401。
- **不**引入"快照新鲜度"判定、**不**在 `me` 里回源云端、**不**在响应里加"可能已过期"标记。
- 理由：这是 §7 已裁定的"复用现有本地查库语义"；任何新鲜度校验都需要新列 / 新语义，且与"不断网隐式调云端"矛盾。

### 4.2 本地 `enabled=false`（决策点 C2 / C2-2）

- **C2 推荐**：沿用现有语义 —— [AuthService.me](file:///e:/DualX/scds-next/scds-core/src/main/java/com/dualx/core/auth/AuthService.java#L205-L210) 已 `.filter(Account::enabled)`，禁用账号 `me` → 401。同步工具把云端 `enabled` 写进本地，于是"云端禁用传播到边缘"**仅在下一次同步后生效**。
- **不得**声称 accessToken 可即时撤销：已签发的**无状态** accessToken 在其 **30min** 有效期内仍可通过验签（[03b](03b-token-capabilities.md) §2）。
- **C2-2（待议）**：其他业务端点当前**不**回查本地 `enabled`（JWT 验签通过即按 `roles` / `permissions` 授权）。若要"本地禁用即时影响所有端点"，须在安全链新增本地回查过滤器 —— **超出本方案范围，且与无状态令牌语义相冲突**。本轮**不做**；`me` 是唯一反映本地 `enabled` 的出口。此项须与 **D04 权限规则**对齐后再议（[03b](03b-token-capabilities.md) §7 末）。

### 4.3 云端删号（决策点 C3）

**推荐：不物理删除。** 快照未带删除指令 → 本地行**不动**；若快照带删除标记 → 置 `enabled=false`。理由：物理删除会孤立 `t_audit_log.account_id` 等引用；`enabled=false` 已足以让 `me` 401。是否传播"删除"语义由运维 / 产品口径裁定。

### 4.4 导入的原子性与失败形态（决策点 C4）

**推荐：单事务、全量。** 全部条目校验通过才提交；任一条冲突 / 格式错 → **整体回滚** + 非零退出 + 逐条报告。避免"部分账号已导入、部分没有"的半吊子状态——那种状态既难排查，又会让"哪些账号在边缘可用"变成不可复现的事实。

---

## 5. 明确不得声称的事（红线复述）

1. `me` 对未知 `sub` 返回 **401 是现有服务语义**；**不得**据此声称"云边身份闭环完成"，**不得**拿本方案或任何"只做账号控制器本地映射"的实现作为**云边部署可用验收**（[03b](03b-token-capabilities.md) §7 末）。
2. **禁止**：按用户名碰撞匹配、临时创建管理员、复制云端口令散列、无法解析字段补默认 `ADMIN`、断网时隐式调用云端查询（§7 明令）。
3. **不得**声称 accessToken 即时撤销；云端禁用 / 删号的传播**只在下次同步后**生效，且**只对 `me`（及以本地为准的判定）**生效。
4. **不得**声称 deviceToken 可替代账号令牌，或可用于账号端点（[03b](03b-token-capabilities.md) §6）。
5. 本方案**不改变** `login` / `refresh` / `logout` 语义；`refresh` 仍**只改云端权威库**，边缘**无离线 refresh 回退**（[03-auth](03-auth.md) §4.8）。
6. CLOUD 形态下 `scds.admin.*` 两项留空是**预期状态**（[AdminAccountBootstrap](file:///e:/DualX/scds-next/scds-core/src/main/java/com/dualx/core/auth/AdminAccountBootstrap.java) 既有注释），**不得**据此报错或判定部署失败。

---

## 6. 与工作包 6 / 7 的边界

### 6.1 包 6（AUTH-HTTP）落地清单

按 [03b](03b-token-capabilities.md) §10.5，包 6 的文件候选是：

- [`AuthController.java`](file:///e:/DualX/scds-next/scds-api/src/main/java/com/dualx/api/auth/AuthController.java) —— `me` 映射。**现状已完整**（`login` / `refresh` / `logout` 已拆至 `AccountAuthController`，`me` 与 `device/me` 在 `AuthController` 且只验签不签发）；本方案**不要求**改其映射逻辑。
- [`AuthService.java`](file:///e:/DualX/scds-next/scds-core/src/main/java/com/dualx/core/auth/AuthService.java) —— **如需**才动。按本方案 C1 / C2，`me` 语义**保持不变**，故**预期无需改动**。

即：**包 6 本身几乎不因本方案增加代码**；本方案真正的产出（同步工具）是**独立新件**。

### 6.2 决策点 E1 —— 同步工具归属

| 选项 | 说明 |
|---|---|
| **① 单独开包（推荐）** | 包 6 保持"端到端 HTTP 鉴权收口"的窄范围；同步工具（快照解析 + 校验 + 事务写入）另开工作包，独立做文件白名单与测试。 |
| ② 并入包 6 | 会让包 6 文件数膨胀（突破「每原子任务 ≤3 文件」纪律），不推荐。 |

无论哪种，均须按纪律**逐段授权**，并另行给出该包的**文件白名单 + 测试计划 + 验收判据**。

---

## 7. 本方案不构成授权（复述）

- 本文**只是设计前置**：不改代码、不改库、不新增迁移、不改已应用 V1~V11、不改 `.env`、不生成密钥、不重置数据库、不清库删卷。
- 上方所有决策点为**待裁定项**，推荐项**仅为建议**；须逐项裁定后方可开工。
- 落地须另行**逐段授权**（每包开工前列文件白名单及测试），提交与推送**另需逐次授权**（[03b](03b-token-capabilities.md) §10.7）。
