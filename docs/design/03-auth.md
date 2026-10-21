# 设计 03 —— 鉴权（云签发 / 边缘离线验签）

> 状态：**部分实现，尚未形成 HTTP 鉴权闭环** | 核查日期：2026-10-21 | DDL：V9 已应用
> 关联：解决评审遗留 **D4**（鉴权落点），并给出 **D5**（`profile` 两个正交维度）在鉴权切面上的答案。
> 本文档只定义鉴权的实现结构；契约需回写之处单列在 §6。
> 本次已按已确认决策及真实代码修订。当前缺口见 [审查清单](../review.md)；
> 309 项测试通过不等于本节所有验收判据通过，见 [测试矩阵](../testing.md)。
> D02最新业务确认：云端签账号、边缘只验账号并本地签设备，支持断外网设备登录。
> 具体双能力/双密钥方案待批准，见[03b-token-capabilities.md](03b-token-capabilities.md)。
> 原单密钥配置与端口片段保留为现有实现说明，不是双能力方案已落地的证明；
> 最新TEST-01完整启动证据为testing第11节558项，仍未验证双密钥或设备SID业务。

---

## 1. 问题

### 1.1 现象

设计初稿提出鉴权无落点的问题。2026-10-21 当前事实如下：

| 应有之物 | 现状 |
|---|---|
| Spring Security 依赖 | api 已引 resource-server，core 仅引 crypto；版本由 Boot 3.5.7 BOM 管理 |
| 免鉴权白名单 | `SecurityConfig` 已实现；完整过滤链遍历回归尚缺 |
| 6 个 `/api/v1/auth/*` 端点 | 全部 `throw new NotImplementedException(...)` → 501 |
| 账号核心服务 | `AuthService` 的 login/refresh/logout/me 已实现并通过 17 项真实 MySQL IT，尚未接控制器 |
| 业务端点 | 已要求账号用途令牌；服务端写权限矩阵已按域落地（§4.5.1），控制器仍是占位 |
| 两个 WebSocket 通道 | 已要求账号用途令牌；只支持 PING/PONG，真实握手与推送回归尚缺，Origin 仍全放开 |
| 设备单端在线 | V9 两列与会话仓储已实现；设备登录服务、SID 验证器及主动失效尚缺 |
| 启动 | 两种签发开关均已真实启动；本地公钥路径正常。物理断网专项未执行 |

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
| TokenPair 不含租户维度，原 `tenantId` 残留已删除 | [domain.yaml](../api/schemas/domain.yaml) |
| `User.permissions` 是细粒度权限点，前端据此显隐 | domain.yaml L60-L65 |
| `Role` 固定四个：`ADMIN` / `DIRECTOR` / `OPERATOR` / `VIEWER` | [Role.java](../../scds-domain/src/main/java/com/dualx/core/domain/Role.java)、[V1__auth.sql](../../scds-core/src/main/resources/db/migration/V1__auth.sql#L44) |
| `t_account.password_hash` 注明 **BCrypt，绝不可逆** | V1__auth.sql L26 |
| `t_vest.bind_secret_hash` 也是 BCrypt；用 SIM 号作密钥时该列为空，**两者由部署配置决定、不并存** | [V6__device.sql](../../scds-core/src/main/resources/db/migration/V6__device.sql#L15-L18) |
| `ApiErrorCode` 已下沉 domain，含 `AUTH_FORBIDDEN`；core 抛 BusinessException，api 映射 HTTP | [ApiErrorCode.java](../../scds-domain/src/main/java/com/dualx/core/error/ApiErrorCode.java) |
| Forbidden / TooManyRequests 已登记 code；实际 MVC 参数错误仍有绕过自定义出口的路径 | [common.yaml](../api/schemas/common.yaml)、[审查清单](../review.md) |
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
| `accessToken`（账号） | D02目标由云端账号实例签；既有独立单机可显式自签 | `scds-edge` | **30min** | 不做（无状态） |
| `refreshToken`（账号） | 管理该账号刷新记录的签发实例，D02目标为云端 | `scds-refresh` | **14d** | 落库可撤销（§4.8） |
| `deviceToken`（单兵终端） | 本地边缘设备签发实例 | `scds-device` | **12h** | 单端在线（§4.9） |

> 原“refresh一律边缘签”描述不适用于D02已确认的云边场景。账号login/refresh/logout
> 归管理该账号及refresh记录的云端实例，保持token_hash与全端退出语义；
> 设备绑定/SID及设备签发归边缘本地库。不做跨库兑换或断网账号刷新回退。

**claim 集合**（固定，不随意扩充）：

```
iss   当前实现为scds.auth.jwt.issuer；D02拟按账号/设备用途绑定独立issuer，见03b
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

**已确认要求**：离线部署启动、验签不得依赖外网。当前满足此要求的配置是本地 PEM 且 `jwks-uri` 为空。
不能把配置远端 JWKS 的实例也描述成“离线可验签”。
下列配置为当前单密钥实现；双能力目标另见03b第3-4节，尚未修改实际部署配置。

```yaml
scds:
  auth:
    jwt:
      issuer:          ${SCDS_JWT_ISSUER:scds-edge}
      signing-enabled: ${SCDS_JWT_SIGNING:true}     # 见 4.3.2
      public-key:      ${SCDS_JWT_PUBLIC_KEY:}      # 单把 X.509 PEM 内联
      public-key-file: ${SCDS_JWT_PUBLIC_KEY_FILE:} # 或从文件读
      private-key:     ${SCDS_JWT_PRIVATE_KEY:}     # 仅 signing-enabled=true 时需要（已 gitignore）
      private-key-file: ${SCDS_JWT_PRIVATE_KEY_FILE:} # 未加密 PKCS#8 文件
      jwks-uri:        ${SCDS_JWKS_URI:}            # 可选；留空 = 永不联网
      jwks-refresh-ms: 3600000
```

装配规则：

- 自建 `JwtDecoder`，不用 Boot 的 `issuer-uri` OIDC 发现。不能笼统宣称所有 `jwk-set-uri`
  形态都启动联网；本项目禁用自动路径是为了使公钥来源与联网行为显式、可测。
- 本地路径：`PemKeys` 读取单把公钥，`ImmutableJWKSet` + RS256 selector +
  `DefaultJWTProcessor` 装配 `NimbusJwtDecoder`。它没有接受 `JWKSource` 的公开构造器。
- 本地签发与 JWK 均不设 `kid`；换单把 PEM 要重启，不支持追加 PEM 链平滑轮换。
- 配了 `jwks-uri` 时**优先选择远端路径**，首次验签可能联网，没有本地降级实现。
  `jwks-refresh-ms` 目前无消费者，没有文档初稿描述的刷新任务。
- 若生产需要平滑换钥或断网 JWKS 缓存兜底，先设计新旧钥信任窗口与过期策略，再实现及验证。

#### 4.3.1 PRIVATE 模式谁签发

**既有独立单机可显式启用账号签发，自签自验；不由PRIVATE自动推导。**
新确认的D02云边场景中，边缘不持云端账号私钥，仅持独立设备私钥，技术拆分待实施。
生产由部署方提供每客户独立密钥；应用没有自动生成功能。
本机开发按已确认方案使用随仓库公开的 `dev-keys/` 固定测试密钥，严禁对外或生产使用。
`signing-enabled=false` 时 `RsaTokenSigner` 不读私钥且拒绝全部三类签发。
生成、文件权限及启动路径见 [环境手册](../environments.md)。

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
- `/api/v1/auth/me` 为只读，两种模式都应存在。
- **D02业务选择已确认，技术实现尚未完成**：账号由云端签、边缘只验账号，
  设备可在边缘本地签发并断外网登录。当前三类共用signer不能满足该选择，
  需按03b审阅后的方案拆分能力及用途信任；不采用“关闭账号即关闭设备”的旧推荐。
- 实现类（`RsaTokenSigner`）落在 `scds-api`（本项目暂无 `scds-infra` 模块，签名与弹簧安全同属"接口适配层的基础设施"）。若日后拆出 `scds-infra`，把它迁过去即可。

### 4.4 免鉴权白名单（默认拒绝）

```
permitAll：
  POST  /api/v1/auth/login          仅 signing-enabled=true 时存在
  POST  /api/v1/auth/refresh        同上
  POST  /api/v1/auth/device/login   当前恒在；D02目标仅设备签发启用时存在
  GET   /actuator/health            探活
  GET   /error                      Boot 内部错误转发

authenticated：
  anyRequest()                       其余一切，含 /ws/v1/** 与 /actuator/info|metrics
```

补充约定：

- **`/actuator/info`、`/actuator/metrics` 需要 `ADMIN`**（指标会泄露内部结构与容量，不对外开放）。
- 必须覆盖 `/actuator/metrics/**`，当前仅匹配精确根路径，已实测 VIEWER 能读取子路径，见审查 R01。
- **OPTIONS 预检一律放行**：Flutter 桌面/安卓不发 `Origin`，CORS 实际不生效；但一旦将来接 Web 控制台，预检被拦会导致"连登录都发不出去"这种极难排查的现象。
- **白名单是"只增不改"清单**：任何新增 `permitAll` 必须回到本文档登记一行。理由——鉴权漏洞几乎从不是"写错了判断"，而是"某次调试顺手放行了一个端点，然后再也没人删"。
- **服务端授权规则同属"只增不改"**：`SecurityConfig` 里每条按域声明的角色规则都必须在 §4.5.1 的矩阵里出现，
  并与 `RolePermissions` 同源。漏写一组**不会报错**，只会静默退化成"任意账号都能写"，这类缺口没有测试之外的发现途径。
- D02拟议能力关闭时精确404出口、账号与设备独立白名单见03b第5节；
  本轮未修改SecurityConfig，不能将该目标矩阵当成运行结果。

### 4.5 角色 → 权限点

`User.permissions` 是契约要求的细粒度权限点，前端据此显隐按钮。

**裁定：角色到权限点的映射是代码里的静态常量表，不进数据库。**

- 理由：角色只有 4 个，权限点集合小且随代码演进。进库会引入**权限点漂移**——库里多出一条代码不认识的权限点、或代码新增权限点而库里没配，两者都只能在运行时炸。
- 映射（`RolePermissions`，放 `scds-core`）：

| 角色 | 权限点 |
|---|---|
| `ADMIN` | 全部 |
| `DIRECTOR` | `exercise:*`、`command:*`、`situation:read`、`replay:*`、`map:*`、`device:read` |
| `OPERATOR` | `command:*`、`situation:read`、`device:link:*`、`map:read` |
| `VIEWER` | `situation:read`、`replay:read`、`map:read`、`device:read` |

- `DIRECTOR` 是 `map:*` 而不是 `map:read`（D04 裁定：想定/编成/地图的**写**权限归 DIRECTOR），
  但不含 `device:link:*`——**能指挥，不能开关硬件链路**。这条边界在 §4.5.1 的服务端规则里同样成立。
- **`permissions` 不进 JWT**，只由 `/api/v1/auth/me` 返回。理由：令牌体积，以及权限点会随版本变化——把它冻在 30 分钟的令牌里，改权限就得等令牌过期；而角色正好相反（变化极少，且变了本来就该重登）。

#### 4.5.1 服务端写权限矩阵（已落地，D04）

**读一律 `VIEWER` 及以上**：四个角色都持 `AUD_scds-edge`，所以所有 GET 继续由末条 `anyRequest()` 放行。
下表只约束**非 GET**（`SecurityConfig` 已按此声明）：

| 域 | 端点 | 允许角色 |
|---|---|---|
| 台账 / 基站（含绑定密钥与 SID 主动失效） | `POST /api/v1/devices/vests`、`PUT·DELETE /api/v1/devices/vests/*`、`PUT /api/v1/devices/anchors/*` | `ADMIN` |
| 想定 / 编成 / 演习生命周期 / 地图 / 复盘导出 | `POST·PUT·DELETE /api/v1/plans*`、`POST·DELETE /api/v1/exercise/current`、`POST·PUT·DELETE /api/v1/maps*`、`POST /api/v1/replays/*/exports` | `ADMIN`、`DIRECTOR` |
| 指令 | `POST·PUT /api/v1/command/**` | `ADMIN`、`DIRECTOR`、`OPERATOR` |
| 链路操作（串口 / UWB 开关） | `POST·DELETE /api/v1/devices/links/**` | `ADMIN`、`OPERATOR` |

- **规则必须声明在 `anyRequest()` 兜底之前**：Spring 的请求匹配器**按声明顺序短路**，写在兜底之后永远不生效。
- 真正的缺口不是"写错判断"，而是**新增写接口时忘了补一组规则**——那不会报错，只会退化成任意账号可写。
  新增写接口时必须同时补规则并回到本表登记一行。
- 回归：`RoleWritePermissionIT`（32 条写路由 × 4 角色 + 23 条读路径 × 4 角色 + 5 条授权层形状，共 60 项）。
  口径是「**非 401/403 即放行**」——骨架期控制器抛 `NotImplementedException`，所以不能断言具体状态码，
  这样同一套用例在骨架期与实现期都能跑。
- 方法级 `@PreAuthorize` 暂不铺开（见 §6 Q8）：按域在 filter chain 里集中声明更易审计，
  也避免"哪条规则真的在起作用"被 70+ 端点淹没。

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
| `exp` 已过 | 401 | `AUTH_TOKEN_EXPIRED`（目标；当前真实过期令牌误归为 INVALID，见 R02） |
| 已认证但角色/权限不足 | 403 | `AUTH_FORBIDDEN`（已新增） |
| 已认证但 `aud` 用错（设备令牌调指挥台接口） | 403 | `AUTH_FORBIDDEN` |

两个 handler 必须**复用 `ApiProblem` 与 `TraceIdFilter` 的 attribute**，保证 `traceId` 与日志一致——否则第 4.6 节之外的那些 401 会变成"没有 traceId 的孤立错误"，现场报障时对不上日志。

当前 EntryPoint 只识别 `JwtValidationException` 的 "Jwt expired" 文案；手搭的 Nimbus processor
可在 Spring validator 前先拒绝过期 claim。必须用生产 decoder 的真实过期令牌回归，不能只 mock 异常。
还需强制 `exp/sub/aud` 等必需字段存在、类型正确；当前缺 exp 的合法签名 token 可通过认证。
MVC 错误须同时检查 Boot ProblemDetails advice 优先级，不能只验证 filter 的 401/403。

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
- **已确认定位**：对原文算 SHA-256，按唯一 `token_hash` 查行；core 不解析 JWT，
  不新增 TokenVerifier。库行与 `expires_at` 是刷新凭据的权威。
- **滚动刷新**：独立 `REQUIRES_NEW` 事务，先锁账号，再锁定读令牌最新状态，
  旧行撤销、插入新 hash、写 replaced_by 原子提交。签名在进程内先完成以计算 hash，
  但必须在返回客户端之前落库并成功提交。
- **已确认退出语义**：logout 无请求体，按 accessToken 的 accountId 撤销该账号全部 refreshToken，
  不修改请求形状，不再描述为“只撤当前一条”。
- **重放检测**：若收到的 refreshToken 已被撤销过 → 判定为"令牌被盗后重放"，**撤销该账号整条 refreshToken 链**并返回 `AUTH_TOKEN_INVALID`。这是 RFC 6819 的标准做法，代价是现场偶发"两边同时刷新"会踢掉一方——但这种情况本来就该重登。
- **存库而非内存**：现场机器可能被断电重启，把 refreshToken 放内存等于"每次断电全体重新登录"，与现场作业方式不符。
- 重放时撤销该账号全部仍有效的 refreshToken，并先提交再抛 BusinessException，
  即使调用方外层事务回滚也不能撤销此次处置。并发刷新最终会使新 refreshToken 也失效；
  已签发 accessToken 仍按无状态短 TTL 规则存在。
- 清理任务尚未实现。保留期限与历史已撤销 token 重放影响新登录的策略须确认后再加调度，
  不能因为“量小”而将无限保留视为生产规范。

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
| 账号（**库内身份 `account.id`**） | 5 次失败 / 5 分钟 | 5 分钟 |
| 客户端 IP | 30 次失败 / 5 分钟 | 5 分钟 |

- **限流键取库内身份，不取请求原文**（D01 身份归一化，已落地）：`t_account.username` 的排序规则是
  `utf8mb4_0900_ai_ci`（不区分大小写、不区分重音），`Admin` 与 `admin` 在库里就是同一行。按原文记失败会让
  拼写变体各记一份，单账号 5 次的上限被翻倍。故 `AuthService.login` 在限流判定**之前**先按用户名查一次账号，
  用 `account.id()` 作键。代价是锁定期内仍会查一次库；换来的是「上限对的是账号，不是字符串」。
  这次查询只用于定位限流键，不作鉴权依据——事务内持行锁后按 id 重读，禁用状态与口令一律以锁内为准。
- **未登记的用户名共用一个键**（`account:unknown`）：既不为每个乱填的用户名建桶（失败桶容量因此有界，
  见审查 R27），也让「未命中账号」与「口令错误」同为 5 次锁定——否则「试不存在的用户名不会被锁」
  会把用户名是否存在从 429/401 的差异里泄漏出去。
- **计数与判锁在同一把实例锁内，并发下不丢更新**（D01 并发预算，已回归）：
  `LoginThrottleTest.concurrentFailuresAtTheThresholdStillLockTheAccount`（5 线程掐同一空转起跑线 × 50 轮，
  丢一次更新就会停在 4 次而漏锁）与 `concurrentFailuresAcrossUsernamesStillExhaustTheClientIpBudget`
  （30 次 IP 预算，每个用户名只失败 1 次）。
- 桶的回收挂在**失败路径**上：只有失败会新增键，于是清理频率天然被限流封顶
  （单个 IP 每 5 分钟至多记 30 次失败，锁定期内更是一次都不记）。
- 只对**失败**计数，成功即清零（仅在未锁定状态下）。
- **锁定期内在校验口令之前直接拒绝**（返回 429）——锁定期不因"这次口令正好对了"而放行，否则攻击者靠撞对一次即可解锁。
- IP 取值：默认使用 `remoteAddr`；只有明确配置可信代理、代理覆盖外来转发头且完成信任链验证后
  才读取转发地址。禁止直接信任任意客户端的 `X-Forwarded-For`。
- 失败响应统一 `AUTH_BAD_CREDENTIALS`，**不区分账号不存在与密码错误**（契约明确要求）。锁定期的响应用 429。
- 计数器放在 `scds-core` 的 `LoginThrottle`，时钟可注入，便于单测。

### 4.11 口令散列

- core 用 `spring-security-crypto` 的 `BCryptPasswordEncoder`，不引 Web 安全过滤链。
- `t_account.password_hash` 与 `t_vest.bind_secret_hash` 都用它（V1/V6 已如此注明）。
- cost 取默认 10：设备登录在现场可能**集中发生**（一次演习开始，几十台终端同时登录），cost 太高会在登录端点排队，反而影响开演。

### 4.12 配置校验：漏配给默认，配错就失败关闭

`AuthJwtProperties` 的紧凑构造器是 `scds.auth.jwt.*` 的唯一入口，规则分两类：

- **漏配（`null`）补明确默认值**，不让整个上下文因一项漏配而起不来：`issuer` 取 `scds-edge`、
  access 30m、refresh 14d、device 12h、`signing-enabled` 取 `true`。
- **配了但配错直接拒绝**（`IllegalArgumentException`，**报错只带属性名、不回显配置值**）：
  - `jwks-uri` 非空：本地部署只认 PEM，远端 JWKS 一律拒绝，避免鉴权意外依赖外网。
  - 三类 TTL 为 0 或负数：静默回落会表现为"登录后令牌立刻过期"这类**极难定位**的故障（审查 R27）。
  - `refresh-token-ttl` 未**严格**长于 `access-token-ttl`：滚动换发依赖"access 先过期、refresh 还有效"
    这个窗口；两者相等则前端没有任何换发窗口。
- 历史兼容键 `jwks-refresh-ms` 无消费者，配了非正值仍回落默认、不拒绝——它不影响任何行为，
  为它拒绝启动反而会把无害的残留配置变成故障。
- **失败发生在装配期而不是第一次登录**：`ApplicationContextRunner` 回归见 `AuthJwtPropertiesTest`。
  这和 §4.3 的密钥缺失同属一条原则——**配置错误必须在启动期响亮失败**，宁可起不来，
  也不要带着一个"能启动但每次登录都立刻过期"的实例上现场。

---

## 5. 落地清单

**根 `pom.xml` / `scds-api/pom.xml`**

- 新增 `spring-boot-starter-oauth2-resource-server`（版本由 BOM 管）

**`scds-domain`**

- `com.dualx.core.port.TokenSigner`（含 `enabled()`）
- claim 载体：`AccessTokenClaims` / `RefreshTokenClaims` / `DeviceTokenClaims`

**`scds-core`**

- `AuthService`（login / refresh / logout / me）
- `DeviceAuthService`（deviceLogin）；deviceSelf 按已确认决定本阶段保留 501，等待态势查询
- `LoginThrottle`、`RolePermissions`、`RefreshTokenRepository`、`VestDeviceSessionRepository`（`t_vest` 两列的读写）

**`scds-api`**

- `SecurityConfig`：filter chain、白名单（§4.4）、`@EnableMethodSecurity`、两个 problem 出口（§4.6）
- `JwtDecoderConfig`：本地公钥优先、`jwtDecoder` 自建、**禁止** `jwk-set-uri` 自动装配
- `RsaTokenSigner implements TokenSigner`（§4.3.2）
- `ApiProblemAuthenticationEntryPoint` / `ApiProblemAccessDeniedHandler`
- `DeviceSidValidator`（§4.9 的校验与缓存）
- 拆 `AccountAuthController`（按 signing-enabled 条件注册 login/refresh/logout），接核心服务；
  设备登录单独接服务、me 映射 User DTO，device/me 本阶段保留 501
- `TraceIdFilter` 已标记 `Ordered.HIGHEST_PRECEDENCE`；真实 401/403 traceId 已实测

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
| **Q5** | 初始管理员账号从哪来 | **已确认并实现**：部署配置 `.env` 明文 `SCDS_ADMIN_USERNAME/PASSWORD`，应用首次启动 BCrypt cost 10 建号；V9 不种账号，已有账号不覆盖。当前本机 .env 尚无管理员项 |
| **Q6** | 权限点集合是否要整理成完整清单（§4.5 只给了通配级别） | 建议单独出一份 `docs/design/03a-permissions.md` 或在契约里补 `permissions` 枚举；**本设计不阻塞于它**（`permissions` 只影响前端显隐） |
| **Q7** | WS 是否允许设备令牌连接 | **不允许**（403）。理由见 §4.7：会与设计 02 的均匀广播冲突。若单兵终端确有实时态需求，正解是给它一条按会话裁剪的独立通道，而不是放开现状势 WS |
| **Q8** | `@PreAuthorize` 是否全面铺开 | **不铺开**。已改为按域在 `SecurityConfig` 集中声明角色规则（§4.5.1，4 组已落地），filter chain 里一眼能看完；方法级 `@PreAuthorize` 只在日后出现"同一 URL 不同方法分级、无法用域表达"时按需补 |
| **Q9** | `TraceIdFilter` 与 Spring Security 顺序 | **已实现并实测**最高优先级；异步事件 traceId 传播仍属后续需求 |
| **Q10** | `sid` 校验缓存 TTL 30s | 采纳；但需确认"登出后立即失效"对现场是否足够（缓存键主动失效已覆盖同进程登出，跨进程/断电重启无缓存） |
| **Q11** | 按 hash 刷新还是 api 先解析 JWT | **已确认甲并实现**：按 token_hash 查库 |
| **Q12** | logout 撤一条还是全部 | **已确认甲并实现**：该账号全部 refreshToken |
| **Q13** | 本地开发密钥来源 | **已确认乙并实现**：公开固定测试密钥；生产独立密钥 |
| **Q14** | device/me 本批是否实现 | **已确认甲**：留 501，等待态势服务 |
| **Q15** | 账号只验签时设备是否仍能登录 | **D02业务已确认**：边缘设备本地签发并支持断外网登录；独立密钥/issuer配置与迁移按03b待批准方案实施 |

---

## 7. 验收判据

1. **401 形状**：无令牌访问 `/api/v1/exercise/current` → 401，`Content-Type: application/problem+json`，含 `code=AUTH_TOKEN_INVALID` 与**非空** `traceId`，且该 traceId 能在日志里搜到（证明 filter chain 之前的异常也带追踪号）。
2. **过期与无效可区分**：过期令牌 → `AUTH_TOKEN_EXPIRED`；签名错误的令牌 → `AUTH_TOKEN_INVALID`。
3. **越权被拒**：用设备令牌访问 `/api/v1/exercise/current` → **403** `AUTH_FORBIDDEN`（`aud` 防线生效）。
4. **白名单正确**：遍历契约全部路径，逐个不带令牌调用——白名单内 3 个端点不返回 401，其余**一律 401**（不许有"忘了加保护"的漏网端点）。
5. **令牌重放检测**：同一个 `refreshToken` 连用两次 → 第二次失败，且该账号此前签发的全部 refreshToken 均被撤销。
6. **设备单端在线**：终端 A 登录后，终端 B 用同一 `vestNum` 登录 → A 的令牌在下一次请求即被拒（不等缓存过期）。
7. **离线可启动可验签**：本地 PEM、jwks-uri 空，物理断网后启动与验签均成功。
   未实现的受保护接口通过认证后仍应为 501，不拿 200 证明不存在的业务；业务接线后另测成功响应。
8. **WS 握手**：不带 `Authorization` → 401 且不升级；带设备令牌 → 403；带账号令牌 → 101 且 `session.getPrincipal()` 非空。
9. **限流**：同一账号连续 5 次错误口令 → 第 6 次返回 429；用正确口令在第 6 次也仍是 429（锁定期不因口令正确而放行）。
10. **账号签发能力开关**：signing=false 时 login/refresh/logout 不注册且按约定得到 404；
    必须同时调整过滤链对不存在路由的处理，否则无令牌请求仍是 401。
    device/login应由独立设备能力控制，Q15/D02业务已确认，具体拆分尚未实现；
    不把原单密钥启动通过写成新能力验收。
11. **claim 完整性**：缺 exp/sub、错误类型、未知用途令牌均 401；真实过期令牌归为 EXPIRED。
12. **MVC 错误形状**：空体、非法 JSON、DTO 校验、multipart 缺失、405/404 均按约定返回
    Problem 的 code/traceId，不能被 Boot 默认 advice 截走。
