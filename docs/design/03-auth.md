# 设计 03 —— 鉴权（云签发 / 边缘离线验签）

> 状态：**待评审**　|　前置依赖：无　|　涉及 DDL：V9
> 关联：解决评审遗留 **D4**（鉴权落点），并给出 **D5**（`profile` 两个正交维度）在鉴权切面上的答案。
> 本文档只定义鉴权的实现结构；契约需回写之处单列在 §6。

---

## 1. 问题

### 1.1 现象

契约把认证模型写得很清楚，但代码里**一个落点都没有**：

| 应有之物 | 现状 |
|---|---|
| Spring Security 依赖 | **全库无**（各 `pom.xml` 既无 `spring-boot-starter-security`，也无 `spring-boot-starter-oauth2-resource-server`、`jjwt`、`nimbus-jose-jwt`） |
| 免鉴权白名单 | **无任何定义**（`docs/` 全目录搜不到白名单/公钥/JWKS 相关约定） |
| 6 个 `/api/v1/auth/*` 端点 | 全部 `throw new NotImplementedException(...)` → 501 |
| 所有业务端点 | 全部无鉴权即可访问（骨架期占位，返回 501） |
| 两个 WebSocket 通道 | **完全无鉴权**，且 `setAllowedOriginPatterns("*")` |

### 1.2 三个没被回答的设计问题

契约只回答了"用什么"（云签发 JWT、边缘离线验签），没回答：

1. **公钥从哪来、怎么轮换、无外网怎么办？** 契约 §auth.yaml 头注释只写"边缘网关用公钥离线验签"，未定义公钥的分发与更新路径。
2. **私有化单机（PRIVATE）谁签发？** 该模式没有云管进程。契约注释"私有化无外网时依然可用（只依赖公钥）"描述的是**边缘验证云管先前签发的令牌**，但单机部署下登录动作本身也需要一个签发者。
3. **Spring Security 的 401/403 形状与契约不符。** 契约要求所有失败都是 `application/problem+json`（含 `code` + `traceId`），而 Spring Security 的默认响应是空体或 Boot 的 error JSON，且**它的异常发生在 DispatcherServlet 之前，`ApiExceptionHandler`（`@RestControllerAdvice`）拦不到**。

> 结论：D4 是阶段 4 的第一阻塞项——网关一开工就要连 WS、要去调受保护接口，鉴权不落地就没法端到端验证。

---

## 2. 现状事实（已核对）

| 事实 | 出处 |
|---|---|
| 非对称验签模型：云管签发、边缘只依赖公钥 | [auth.yaml](../../docs/api/paths/auth.yaml#L1-L5)、[openapi.yaml](../../docs/api/openapi.yaml#L197-L209) |
| `bearerAuth` 与 `deviceToken` **同走 `Authorization: Bearer`**，服务端按 `aud` 区分 | openapi.yaml L197-L209 |
| 契约声明 `accessToken` 短有效期（建议 30min）；`refreshToken` 长有效期**且可撤销** | openapi.yaml L198-L199、auth.yaml L66 |
| 契约声明 `logout` 使 refreshToken 失效；accessToken 不可撤销、靠短有效期兜底 | auth.yaml L66 |
| 契约声明 deviceLogin **重复登录使旧令牌失效（单端在线）** | auth.yaml L93-L95 |
| `TokenPair.accessToken` 描述含 **`tenantId`** | [domain.yaml](../../docs/api/schemas/domain.yaml#L28) —— 与已裁定的"每客户独立实例"**矛盾，属残留** |
| `User.permissions` 是细粒度权限点，前端据此显隐 | domain.yaml L60-L65 |
| `Role` 固定四个：`ADMIN` / `DIRECTOR` / `OPERATOR` / `VIEWER` | [Role.java](../../scds-domain/src/main/java/com/dualx/core/domain/Role.java)、[V1__auth.sql](../../scds-core/src/main/resources/db/migration/V1__auth.sql#L44) |
| `t_account.password_hash` 注明 **BCrypt，绝不可逆** | V1__auth.sql L26 |
| `t_vest.bind_secret_hash` 也是 BCrypt；用 SIM 号作密钥时该列为空，**两者由部署配置决定、不并存** | [V6__device.sql](../../scds-core/src/main/resources/db/migration/V6__device.sql#L15-L18) |
| `ApiErrorCode` 已有 4 个 `AUTH_*`，**没有任何 403 类错误码** | [ApiErrorCode.java](../../scds-api/src/main/java/com/dualx/api/error/ApiErrorCode.java#L14-L18) |
| 契约已有 `Forbidden` / `TooManyRequests` 响应声明，但**只写了描述、没有 code 名** | [common.yaml](../../docs/api/schemas/common.yaml#L76-L99) |
| `TraceIdFilter` 已把 traceId 写进 MDC + request attribute；`ApiExceptionHandler` 从 attribute 取它填 `Problem.traceId` | [TraceIdFilter.java](../../scds-api/src/main/java/com/dualx/api/support/TraceIdFilter.java)、[ApiExceptionHandler.java](../../scds-api/src/main/java/com/dualx/api/error/ApiExceptionHandler.java#L80-L88) |
| `scds-api` 目前对 `scds-core` 单向依赖 | [scds-api/pom.xml](../../scds-api/pom.xml#L20-L24) |

---

## 3. 设计目标与非目标

**目标**

1. 定死令牌形态：算法、claim 集合、有效期、**`aud` 如何区分账号令牌与设备令牌**。
2. 定死公钥来源，且保证「**无外网也能启动、也能验签**」。
3. 定死免鉴权白名单，且**默认拒绝**（不是默认放行再逐个收紧）。
4. 让 401/403 也长成 `ApiProblem`（含 `code` + `traceId`），与其余失败响应同形。
5. 定死 WS 握手的令牌传递方式，并明确设备令牌能否连 WS。
6. 定死 `refreshToken` 可撤销与设备令牌单端在线的**实现**（契约确实要求了，但没人说怎么落）。
7. 不因鉴权引入新的中间件（无 Redis、无外部授权服务器）。

**非目标**

- 不做 OAuth2 授权码 / 第三方登录 / SSO。现场就两类登录：指挥台账号、单兵终端。
- 不做云管侧的账号管理界面（云管不在本仓库范围）。
- 不做多租户（已裁定每客户独立实例）。
- 不做权限点的数据库化（见 §4.5）。
- 不做 accessToken 的主动吊销（契约已明确"不可撤销，短有效期兜底"）。

---

## 4. 方案

### 4.1 依赖选型

**采用 `spring-boot-starter-oauth2-resource-server`**（版本由 Boot 3.5.7 BOM 统一管理，传递引入 Spring Security 6.5.x 与 `nimbus-jose-jwt`）。

- 得到 `NimbusJwtDecoder`：RS256/ES256 验签、`kid` 选择、`exp`/`nbf`/时钟偏移全部现成。
- 得到 `JwtAuthenticationToken` + `Converter<Jwt, AbstractAuthenticationToken>`，身份直接来自 claim。
- **不引入授权服务器**（`spring-security-oauth2-authorization-server`）：本项目只需要"验签 + 自有 login 端点自行签发"，不需要 OIDC 那一整套发现/注册文档。

否决的选项：

| 选项 | 否决理由 |
|---|---|
| `io.jsonwebtoken:jjwt` | 不在 Boot BOM，需自管版本；且要手写认证过滤器，等于重做 `resource-server` 已有的东西 |
| 手写 `OncePerRequestFilter` + 手工解 JWT | 验签、时钟偏移、`alg=none` 混淆攻击、`kid` 选择全部要自己防——这类代码写对了没人夸，写错了是事故 |
| 内存 session + Cookie | 与契约的 Bearer 模型不符，且私有化部署常跨设备 |

**必须付的成本**：`spring-boot-starter-security` 会把 filter chain 套在**所有**请求（含 `/ws/**`、`/actuator/**`）前面，因此白名单与 401/403 形状都要显式处理——见 §4.4 与 §4.6。

### 4.2 令牌形态

三种令牌，全部走 `Authorization: Bearer`，靠 `aud` 区分：

| 令牌 | 谁签发 | `aud` | 有效期 | 撤销方式 |
|---|---|---|---|---|
| `accessToken`（账号） | 本进程（PRIVATE）或云管（CLOUD） | `scds-edge` | **30min** | 不做（无状态） |
| `refreshToken`（账号） | **永远是本进程** | `scds-refresh` | **14d** | 落库可撤销（§4.8） |
| `deviceToken`（单兵终端） | **永远是本进程** | `scds-device` | **12h** | 单端在线（§4.9） |

> `refreshToken` 与 `deviceToken` 一律由边缘实例自己签发：它们直接挂钩**边缘本地库**（`t_refresh_token`、`t_vest` 绑定关系），云管不该、也无法代替边缘管理这两件事。只有账号 `accessToken` 在 CLOUD 模式下需要云管签（见 §4.3）。

**claim 集合**（固定，不随意扩充）：

```
iss   scds.edge.id（部署时确定的实例标识）
sub   账号令牌 = accountId(UUIDv7)；设备令牌 = vestNum
aud   见上表
exp / iat / nbf
jti   UUIDv7。accessToken 也带（便于日志串联）；refreshToken 的 jti 即库主键
roles [ADMIN, DIRECTOR, ...]   仅账号令牌
sid   仅设备令牌：设备会话 ID（§4.9）
```

**算法**：`RS256`（RSA-2048）。理由：JDK 原生支持、密钥可直接落 PEM、跨语言客户端（Flutter 侧只需验签时可任意实现）。`ES256` 亦可接受，但需两边确认曲线与 DER/P1363 编码约定——**除非有明确收益，不要换**。

**`aud` 校验不可省**：契约说"同走 Bearer、按 aud 区分"，那么"用设备令牌调指挥台接口"必须被拒（403）。这是本设计里唯一的横向越权防线。

### 4.3 公钥来源（"离线验签"的真正落地）

**裁定：验签器永远只依赖本地公钥，启动过程绝不联网。**

```yaml
scds:
  auth:
    jwt:
      issuer:          ${SCDS_JWT_ISSUER:scds-edge}
      signing-enabled: ${SCDS_JWT_SIGNING:true}     # 见 4.3.2
      public-key:      ${SCDS_JWT_PUBLIC_KEY:}      # PEM 内联（多把则按 kid 依次列出）
      public-key-file: ${SCDS_JWT_PUBLIC_KEY_FILE:} # 或从文件读
      private-key:     ${SCDS_JWT_PRIVATE_KEY:}     # 仅 signing-enabled=true 时需要（已 gitignore）
      jwks-uri:        ${SCDS_JWKS_URI:}            # 可选；留空 = 永不联网
      jwks-refresh-ms: 3600000
```

装配规则：

- **禁止使用** `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` 自动装配——它在启动时会去拉 JWKS，**无外网即启动失败**，正好违反本设计目标第 2 条。
- 自己 `@Bean JwtDecoder`：
  - 配了 `jwks-uri` → `NimbusJwtDecoder.withJwkSetUri(...)`，并挂一个**失败只记日志、不降级为拒绝**的刷新任务；
  - 否则 → 用本地公钥构造 `JWKSource`（支持多把，按 `kid` 命中）→ `new NimbusJwtDecoder(jwkSource)`。
- 公钥轮换：`kid` 匹配天然支持「新旧公钥并存」。运维动作 = 把新公钥追加进本地 PEM 链 → 云管再切签名密钥。

#### 4.3.1 PRIVATE 模式谁签发

**边缘实例自己持有签名私钥，自签自验。** 私钥来源二选一，由部署决定：

- 部署时导入（`SCDS_JWT_PRIVATE_KEY`，与 `.env` 同一保护级别）；
- 首次启动自动生成密钥对，并把**公钥指纹 + 输出路径**打进启动日志，由运维归档。

**绝不在代码里内置默认私钥**——那等于所有客户共用一把钥匙。

#### 4.3.2 签发能力是"能力"开关，不是"环境"开关（D5 的切面）

| 配置 | 回答的问题 | 取值 |
|---|---|---|
| `scds.deployment-mode` | **环境**：这台机器是云端实例还是私有化单机 | `CLOUD` / `PRIVATE` |
| `scds.auth.jwt.signing-enabled` | **能力**：本进程是否具备签发账号 accessToken 的能力 | `true` / `false` |

**不要在代码里写 `if (deploymentMode == PRIVATE)` 来判签发能力**：PRIVATE 完全可以只验签（令牌由运维统一在别处签好），CLOUD 也完全可以在某客户的边缘上打开签发（离线演练场景）。两者正交，各配各的。

落地形态：签发走端口，能力开关决定装配哪个实现——

```java
package com.dualx.core.port;

/** 令牌签发端口：核心侧只描述"要签什么"，算法与密钥细节留在适配器。 */
public interface TokenSigner {
    /** 本进程是否具备签发能力（CLOUD 模式且不由本进程签时为 false）。 */
    boolean enabled();

    String signAccessToken(AccessTokenClaims claims);
    String signRefreshToken(RefreshTokenClaims claims);
    String signDeviceToken(DeviceTokenClaims claims);
}
```

- `enabled() == false` 时，`POST /api/v1/auth/login|refresh|logout` **不注册**（404），客户端应按 baseUrl 指向的实例能力选择登录端点。
- `/api/v1/auth/me` 与两个设备端点**两种模式都在**（它们不涉及签发）。
- 实现类（`RsaTokenSigner`）落在 `scds-api`（本项目暂无 `scds-infra` 模块，签名与弹簧安全同属"接口适配层的基础设施"）。若日后拆出 `scds-infra`，把它迁过去即可。

### 4.4 免鉴权白名单（默认拒绝）

```
permitAll：
  POST  /api/v1/auth/login          仅 signing-enabled=true 时存在
  POST  /api/v1/auth/refresh        同上
  POST  /api/v1/auth/device/login   恒在（设备登录属边缘本地事务）
  GET   /actuator/health            探活
  GET   /error                      Boot 内部错误转发

authenticated：
  anyRequest()                       其余一切，含 /ws/v1/** 与 /actuator/info|metrics
```

补充约定：

- **`/actuator/info`、`/actuator/metrics` 需要 `ADMIN`**（指标会泄露内部结构与容量，不对外开放）。
- **OPTIONS 预检一律放行**：Flutter 桌面/安卓不发 `Origin`，CORS 实际不生效；但一旦将来接 Web 控制台，预检被拦会导致"连登录都发不出去"这种极难排查的现象。
- **白名单是"只增不改"清单**：任何新增 `permitAll` 必须回到本文档登记一行。理由——鉴权漏洞几乎从不是"写错了判断"，而是"某次调试顺手放行了一个端点，然后再也没人删"。

### 4.5 角色 → 权限点

`User.permissions` 是契约要求的细粒度权限点，前端据此显隐按钮。

**裁定：角色到权限点的映射是代码里的静态常量表，不进数据库。**

- 理由：角色只有 4 个，权限点集合小且随代码演进。进库会引入**权限点漂移**——库里多出一条代码不认识的权限点、或代码新增权限点而库里没配，两者都只能在运行时炸。
- 映射（`RolePermissions`，放 `scds-core`）：

| 角色 | 权限点 |
|---|---|
| `ADMIN` | 全部 |
| `DIRECTOR` | `exercise:*`、`command:*`、`situation:read`、`replay:*`、`map:read`、`device:read` |
| `OPERATOR` | `command:*`、`situation:read`、`device:link:*`、`map:read` |
| `VIEWER` | `situation:read`、`replay:read`、`map:read`、`device:read` |

- **`permissions` 不进 JWT**，只由 `/api/v1/auth/me` 返回。理由：令牌体积，以及权限点会随版本变化——把它冻在 30 分钟的令牌里，改权限就得等令牌过期；而角色正好相反（变化极少，且变了本来就该重登）。
- 服务端方法级校验：`@EnableMethodSecurity` + `@PreAuthorize("hasRole('DIRECTOR')")`，**先只加在必要的写接口上**（开演/停演、指挥下发、链路开关、账号管理），不在阶段 4 一次性铺满 70+ 端点——铺满了会掩盖"哪条规则真的在起作用"。

### 4.6 401 / 403 必须长成 `ApiProblem`

这是本设计里**最容易漏**的一点：

> `ApiExceptionHandler` 的 `@RestControllerAdvice` 拦不到 Spring Security 在 filter chain 中抛出的认证/授权异常——异常发生在 DispatcherServlet **之前**。因此必须显式替换两个出口：

```java
http.exceptionHandling(e -> e
    .authenticationEntryPoint(apiProblemEntryPoint)      // 401
    .accessDeniedHandler(apiProblemAccessDeniedHandler)); // 403
```

映射规则：

| 场景 | HTTP | `code` |
|---|---|---|
| 无 `Authorization` 头 | 401 | `AUTH_TOKEN_INVALID` |
| 签名/格式/`aud` 不匹配 | 401 | `AUTH_TOKEN_INVALID` |
| `exp` 已过（`JwtExpiredException`） | 401 | `AUTH_TOKEN_EXPIRED` |
| 已认证但角色/权限不足 | 403 | `AUTH_FORBIDDEN` ← **需新增** |
| 已认证但 `aud` 用错（设备令牌调指挥台接口） | 403 | `AUTH_FORBIDDEN` |

两个 handler 必须**复用 `ApiProblem` 与 `TraceIdFilter` 的 attribute**，保证 `traceId` 与日志一致——否则第 4.6 节之外的那些 401 会变成"没有 traceId 的孤立错误"，现场报障时对不上日志。

### 4.7 WebSocket 握手鉴权

- **令牌传递方式：`Authorization: Bearer <accessToken>` 请求头。** Flutter 的 `dart:io` `WebSocket.connect(..., headers: {...})` 支持自定义头。
- **不支持 query 参数传令牌。** URL 会进 access log、反向代理日志、浏览器历史与 Referer；现场系统一旦接 Nginx，令牌就等于被写进了磁盘。若将来必须支持浏览器端，应另开"一次性 ticket"接口（短时效、单次），而不是把长期令牌塞进 URL。
- Spring 的 `DefaultHandshakeHandler` 会把握手请求的 principal 带进 `WebSocketSession.getPrincipal()`，因此 `scds-api` 的 WS handler 能在连接建立后拿到身份——**这正是设计 02 §4.6 的会话表要记录的"谁连着"**。
- **设备令牌不允许连 WS**（403 `AUTH_FORBIDDEN`）。理由：契约里 WS 帧体是**统一的** `SituationEnvelope`，没有任何"按会话裁剪字段"的定义；而设备令牌按 `situationVest` 的说明是"服务端按授权范围裁剪可见字段"。若允许设备令牌连态势 WS，就必须在扇出时逐会话过滤——这与设计 02 §4.7 的均匀广播直接冲突。单兵终端用 `GET /api/v1/situation/vests/{vestNum}` + `GET /api/v1/auth/device/me`（两者契约都已定义裁剪）。
- 握手认证失败返回 **401 + ApiProblem，不升级协议**（契约 `wsSituation` 已声明 401）。

### 4.8 `refreshToken` 可撤销（V9）

契约要求 refreshToken 可撤销，意味着它**不能是无状态 JWT**——必须能查。

```sql
CREATE TABLE t_refresh_token (
    id          CHAR(36)    NOT NULL COMMENT 'UUIDv7，即 JWT 的 jti',
    account_id  CHAR(36)    NOT NULL,
    token_hash  CHAR(64)    NOT NULL COMMENT 'SHA-256(令牌原文) 的十六进制；绝不存原文',
    issued_at   DATETIME(3) NOT NULL,
    expires_at  DATETIME(3) NOT NULL,
    revoked_at  DATETIME(3)     NULL,
    replaced_by CHAR(36)        NULL COMMENT '滚动刷新时指向新令牌 id，用于链路追溯',
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    KEY ix_refresh_account (account_id, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '账号刷新令牌（可撤销）';
```

- **只存 hash**：库被拖走也无法直接冒用令牌。
- **滚动刷新**：`refresh` 时旧行置 `revoked_at = now`、`replaced_by = 新 id`，再发新令牌。
- **重放检测**：若收到的 refreshToken 已被撤销过 → 判定为"令牌被盗后重放"，**撤销该账号整条 refreshToken 链**并返回 `AUTH_TOKEN_INVALID`。这是 RFC 6819 的标准做法，代价是现场偶发"两边同时刷新"会踢掉一方——但这种情况本来就该重登。
- **存库而非内存**：现场机器可能被断电重启，把 refreshToken 放内存等于"每次断电全体重新登录"，与现场作业方式不符。
- 清理：过期的行不主动删；由设计 01 的调度器顺带日清一次（`expires_at < now - 7d`），或暂不清理（量级极小）。

### 4.9 设备令牌单端在线（V9）

契约要求"重复登录使旧令牌失效（单端在线）"。**不新建表**，在 `t_vest` 上加两列：

```sql
ALTER TABLE t_vest
    ADD COLUMN device_session_sid CHAR(36)    NULL COMMENT '当前有效的设备令牌会话 ID；登出/换绑置 NULL',
    ADD COLUMN device_login_at    DATETIME(3) NULL COMMENT '最后一次设备登录时刻';
```

- `deviceLogin` 成功 → 生成 `sid = UUIDv7`，写入 `device_session_sid`，令牌带 `sid` claim。
- 校验（`aud = scds-device`）时比对 `claim.sid == t_vest.device_session_sid`，不等即 `AUTH_TOKEN_INVALID`。
- 好处：**重复登录天然顶掉旧令牌**（旧令牌的 `sid` 与库中不再相等），无需定时任务、无需额外的"会话表"。
- 代价：每个设备令牌请求多一次库读。用进程内小缓存（`vestNum → sid`，TTL 30s）挡住；重新登录/登出时**主动失效该键**，TTL 只是兜底。
  - 缓存只缓存"sid 映射"，**不缓存"令牌有效"这个结论**——否则登出后仍会有一段时间可用。

### 4.10 登录限流（契约 429）

契约已声明 429，实现**不引入新依赖**（不用 Caffeine、不用 Redis）：进程内滑动窗口。

| 维度 | 阈值 | 锁定时长 |
|---|---|---|
| 账号（`username`） | 5 次失败 / 5 分钟 | 5 分钟 |
| 客户端 IP | 30 次失败 / 5 分钟 | 5 分钟 |

- 只对**失败**计数，成功即清零（仅在未锁定状态下）。
- **锁定期内在校验口令之前直接拒绝**（返回 429）——锁定期不因"这次口令正好对了"而放行，否则攻击者靠撞对一次即可解锁。
- IP 取值：优先 `X-Forwarded-For` 的最右一跳（现场若接 Nginx 才有意义），否则 `remoteAddr`。
- 失败响应统一 `AUTH_BAD_CREDENTIALS`，**不区分账号不存在与密码错误**（契约明确要求）。锁定期的响应用 429。
- 计数器放在 `scds-core` 的 `LoginThrottle`，时钟可注入，便于单测。

### 4.11 口令散列

- 用 `BCryptPasswordEncoder`（引入 security starter 后自带），**不要自己实现**。
- `t_account.password_hash` 与 `t_vest.bind_secret_hash` 都用它（V1/V6 已如此注明）。
- cost 取默认 10：设备登录在现场可能**集中发生**（一次演习开始，几十台终端同时登录），cost 太高会在登录端点排队，反而影响开演。

---

## 5. 落地清单

**根 `pom.xml` / `scds-api/pom.xml`**

- 新增 `spring-boot-starter-oauth2-resource-server`（版本由 BOM 管）

**`scds-domain`**

- `com.dualx.core.port.TokenSigner`（含 `enabled()`）
- claim 载体：`AccessTokenClaims` / `RefreshTokenClaims` / `DeviceTokenClaims`

**`scds-core`**

- `AuthService`（login / refresh / logout / me）
- `DeviceAuthService`（deviceLogin / deviceSelf）
- `LoginThrottle`、`RolePermissions`、`RefreshTokenRepository`、`VestDeviceSessionRepository`（`t_vest` 两列的读写）

**`scds-api`**

- `SecurityConfig`：filter chain、白名单（§4.4）、`@EnableMethodSecurity`、两个 problem 出口（§4.6）
- `JwtDecoderConfig`：本地公钥优先、`jwtDecoder` 自建、**禁止** `jwk-set-uri` 自动装配
- `RsaTokenSigner implements TokenSigner`（§4.3.2）
- `ApiProblemAuthenticationEntryPoint` / `ApiProblemAccessDeniedHandler`
- `DeviceSidValidator`（§4.9 的校验与缓存）
- `AuthController`：去掉 6 个 501，接 `AuthService` / `DeviceAuthService`
- `TraceIdFilter` 无需改动（已就位），但**必须在 Security 过滤器之前**生效——需确认其 `Order`（见 §6 Q9）

**`scds-core` migration**

- `V9__auth_tokens.sql`：`t_refresh_token` + `t_vest` 两列 + `ck_*` 约束

---

## 6. 待裁定 / 需回写契约

| # | 问题 | 建议 |
|---|---|---|
| **Q1** | CLOUD 模式下边缘实例是否注册 `/auth/login|refresh|logout`？ | **不注册（404）**，由 `signing-enabled=false` 驱动。客户端按 baseUrl 指向的实例决定登录端点。若不接受 404，替代方案是注册但返回 501——但那与"骨架期占位"的 501 语义冲突，更难区分 |
| **Q2** | 需新增错误码 `AUTH_FORBIDDEN`（用于设备令牌越权、角色不足） | **新增**，同时在契约 `common.yaml#/Forbidden` 的描述里登记 code 名（该响应目前只有描述、无 code 名） |
| **Q3** | `TokenPair.accessToken` 描述里的 `tenantId` 与"每客户独立实例"矛盾 | **删除该词**。`accessToken` 载荷不再有 tenantId（与"不做多租户"的裁定一致） |
| **Q4** | 三种令牌的有效期：access 30min / refresh 14d / device 12h | 采纳。理由：30min 是契约建议值；14d 覆盖一次集训周期；12h 覆盖单日演习——设备令牌不该跨天存活 |
| **Q5** | 初始管理员账号从哪来（Flyway 空库起步） | V9 插一条 `admin` 账号，**密码散列由部署脚本生成并写入 `.env`**，不在迁移文件里硬编码任何默认密码。需确认此做法（否则只能硬编码一个 `admin/admin`，属事故级） |
| **Q6** | 权限点集合是否要整理成完整清单（§4.5 只给了通配级别） | 建议单独出一份 `docs/design/03a-permissions.md` 或在契约里补 `permissions` 枚举；**本设计不阻塞于它**（`permissions` 只影响前端显隐） |
| **Q7** | WS 是否允许设备令牌连接 | **不允许**（403）。理由见 §4.7：会与设计 02 的均匀广播冲突。若单兵终端确有实时态需求，正解是给它一条按会话裁剪的独立通道，而不是放开现状势 WS |
| **Q8** | `@PreAuthorize` 是否全面铺开 | **先只加在必要的写接口上**（开演/停演、指挥、链路开关、账号管理）。铺满 70+ 端点会掩盖"哪条规则真的在起作用"，且阶段 4 会频繁改接口 |
| **Q9** | `TraceIdFilter` 与 Spring Security 过滤链的先后顺序 | 需在落地时确认 `TraceIdFilter` 注册在 Security 之前，否则 401/403 的 `Problem.traceId` 会为空——而 401 恰恰是最需要 traceId 的场景 |
| **Q10** | `sid` 校验缓存 TTL 30s | 采纳；但需确认"登出后立即失效"对现场是否足够（缓存键主动失效已覆盖同进程登出，跨进程/断电重启无缓存） |

---

## 7. 验收判据

1. **401 形状**：无令牌访问 `/api/v1/exercise/current` → 401，`Content-Type: application/problem+json`，含 `code=AUTH_TOKEN_INVALID` 与**非空** `traceId`，且该 traceId 能在日志里搜到（证明 filter chain 之前的异常也带追踪号）。
2. **过期与无效可区分**：过期令牌 → `AUTH_TOKEN_EXPIRED`；签名错误的令牌 → `AUTH_TOKEN_INVALID`。
3. **越权被拒**：用设备令牌访问 `/api/v1/exercise/current` → **403** `AUTH_FORBIDDEN`（`aud` 防线生效）。
4. **白名单正确**：遍历契约全部路径，逐个不带令牌调用——白名单内 3 个端点不返回 401，其余**一律 401**（不许有"忘了加保护"的漏网端点）。
5. **令牌重放检测**：同一个 `refreshToken` 连用两次 → 第二次失败，且该账号此前签发的全部 refreshToken 均被撤销。
6. **设备单端在线**：终端 A 登录后，终端 B 用同一 `vestNum` 登录 → A 的令牌在下一次请求即被拒（不等缓存过期）。
7. **离线可启动可验签**：断网（无外网）状态下启动 → 成功；用断网前签发的有效令牌访问受保护接口 → 200。**反证**：若误用了 `jwk-set-uri` 自动装配，本项会失败。
8. **WS 握手**：不带 `Authorization` → 401 且不升级；带设备令牌 → 403；带账号令牌 → 101 且 `session.getPrincipal()` 非空。
9. **限流**：同一账号连续 5 次错误口令 → 第 6 次返回 429；用正确口令在第 6 次也仍是 429（锁定期不因口令正确而放行）。
10. **签发能力开关**：`signing-enabled=false` 启动 → `/auth/login` 返回 404，而 `/auth/me` 与 `/auth/device/login` 正常。
