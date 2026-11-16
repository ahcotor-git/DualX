# D02：账号与设备签发能力拆分

> 日期：2026-10-21。业务选择已确认；本文件的具体配置、信任和迁移方案待审阅批准。
> 已确认场景：账号令牌由云端签发，边缘只验签；设备令牌由边缘本地签发，
> 支持无外网时设备登录。不能把业务确认扩大为所有实现细节、部署变更或提交推送授权。
> 当前代码仍是单issuer、单密钥、单signing-enabled；本设计尚未实现。
> 最新已执行自动测试为testing.md第11节的558项，不是本双密钥方案的验收证据。

## 1. 已确认与待批准范围

| 项目 | 状态 |
|---|---|
| 云端账号签发、边缘账号只验签 | 用户已确认，目标场景 |
| 边缘本地设备签发与断网设备登录 | 用户已确认，目标场景 |
| 用deployment-mode推导签发能力 | 禁止，保持既有正交约束 |
| 独立账号/设备密钥、issuer与用途绑定 | 本文推荐，具体实现待批准 |
| 新配置键、默认值、兼容迁移及双decoder | 本文推荐，待批准 |
| 云端账号信息怎样供边缘auth/me使用 | 尚未决定，见第7节 |
| 设备绑定管理、SID缓存参数、全部权限矩阵 | 仍受D03、D01/D04等独立决策约束 |
| 改已有.env、生成部署密钥、重置账号、迁移DDL | 本轮不授权、不执行 |

既有PRIVATE独立单机自签自验选择不在本轮删除；如实际需要该形态，
可显式启用账号签发能力，不因“PRIVATE”自动启用，也不把它套用到本轮云边场景。

## 2. 密钥与数据归属

推荐每客户的账号签发实例和每个边缘设备签发实例各持一对独立RSA密钥。
算法保持RS256，RSA至少2048位；不使用HMAC，不共用账号/设备私钥。
边缘只安装云端账号公钥，不安装云端账号私钥。
边缘设备私钥只用于aud=scds-device，不提供任意claims签发接口。

| 令牌 | 签发者 | 信任的issuer/公钥 | 权威记录 | 保持的有效期 |
|---|---|---|---|---|
| 账号accessToken | 云端账号实例 | 显式配置的账号issuer/账号公钥 | 云端账号；边缘身份查询策略另议 | 30min |
| 账号refreshToken | 管理该账号刷新记录的云端实例 | 账号issuer/账号公钥 | 该实例t_refresh_token | 14d |
| deviceToken | 本地边缘实例 | 本边缘设备issuer/设备公钥 | 本边缘t_vest绑定及SID | 12h |

refresh不得跨库兑换。保持原文SHA-256、token_hash查库、滚动刷新、重放整链撤销、
无请求体全账号logout等已确认规则，不在边缘新增代理刷新、复制refresh记录或离线宽限。
云端logout不会立即撤销边缘已接受的无状态accessToken，仍由短有效期兜底。
边缘设备签发不能调用AuthService账号签发流程，也不产生t_refresh_token行。

本方案未允许所有客户共用一把云端账号公钥或issuer。每客户独立实例约束不变，
不引入tenant_id；账号信任配置只接受该客户被批准的签发者。

## 3. 配置与兼容原则

**（2026-11-15，段 92 裁定）** 配置键方案已裁定为**统一重命名子键**：账号能力/信任配置迁为
`scds.auth.jwt.account.*`，设备签发与信任为 `scds.auth.jwt.device.*`，二者不再复用私钥或 issuer。
原拟议「账号保留 `scds.auth.jwt.*`、另加 `scds.auth.device-jwt.*`」**已被本裁定取代**。
重命名意味着现有账号键（`scds.auth.jwt.*`）与 `AuthJwtProperties` 前缀须一并迁移（见 §10.1）。
以下是拟议键名和非秘密示例，不是当前可运行配置，也不写入实际.env。

```yaml
scds:
  auth:
    jwt:
      account:
        signing-enabled: false
        issuer: customer-account-authority
        public-key-file: /protected/account-public.pem
        private-key-file: ""
        jwks-uri: ""
      device:
        signing-enabled: false
        issuer: customer-edge-device-authority
        public-key-file: /protected/device-public.pem
        private-key-file: /protected/device-private.pem
        token-ttl: 12h
```

推荐规则：

- 新设备签发开关缺省false，必须显式启用；不得继承账号signing-enabled。
- 启用设备签发时必须显式配置独立issuer及公私钥，校验公私钥配对、RSA强度。
- 两用途issuer与公钥必须不同，重复或不完整的目标双密钥配置在启动时失败关闭。
- 设备配置不回退账号配置，缺设备私钥不能借账号私钥签发设备令牌。
- 禁用账号签发时不读取账号私钥；禁用设备签发时不读取设备私钥。
- 验签信任与签发能力分开：禁用签发不等于撤销已配公钥的验签信任。
  如需要只验证设备令牌而不签发，仍须显式配置设备issuer/公钥。
- 设备信任未配置时不接受设备JWT；不能从令牌的iss/jku/x5u自动发现或下载密钥。
- 本轮云边离线方案只用本地PEM。账号jwks-uri必须为空，不宣称现有远端JWKS有离线兜底。
- 账号键重命名为 `scds.auth.jwt.account.*` 后，旧键名不再生效；升级须同步迁移账号配置，
  改实际凭据仍需单独授权，不自动复制私钥、不把公开dev-keys部署到现场。

当前 `TokenSigner.enabled()` 仍被 `AuthService` 用作账号能力判断。
**（段 92 裁定）** 新增 domain 端口 `DeviceTokenSigner`，独立暴露 `enabled`、设备 TTL 与 `signDeviceToken`，
设备业务仅依赖该端口；**一次迁净**——直接删除 `TokenSigner.signDeviceToken`，**不保留兼容入口**
（`DeviceAuthService` 在同段迁到 `DeviceTokenSigner`）。账号业务继续依赖 `TokenSigner`，
不重写已完成 `AuthService`。

## 4. 双用途验签与权限隔离

推荐由一个生产JwtDecoder门面分派到两套固定本地信任decoder。
未验签的iss/aud只可用来选择预配置的信任项，不代表认证成功。

1. 保留SEC-02的结构检查：单一已知aud、iss/exp/sub必需字段、类型、
   账号UUID、设备编号及sid UUID等约束。
2. aud为scds-edge或scds-refresh时，只使用账号decoder。
   aud为scds-device时，只使用设备decoder。
3. 对所选decoder强制验证精确issuer、固定RS256和对应公钥，不试遍全部公钥，
   不允许账号公钥签出的deviceToken，也不允许设备公钥签出的账号/refreshToken。
4. 通过签名、issuer和字段检查后才分类时间失效；错签且过期仍INVALID，
   真正过期才EXPIRED。保持现有60秒时钟偏差规则，不扩大离线有效期。
5. Authentication只建立经过验证的身份；用途与角色授权仍由SecurityConfig执行。
   设备令牌不得提供有效账号ROLE_*权限，即使载荷中夹带ADMIN也不能获取账号权限。
6. 有效设备令牌调用指挥/WS接口仍403；交叉签发、错误issuer、未知用途仍401。
   单兵自身可见性和SID业务检查不因验签通过而省略。

账号refresh用途可保持现有“结构/签名可验证，但不能访问账号业务接口”的403边界。
这不允许边缘兑换refresh；/auth/refresh缺席时必须按能力路由返回404。
初版每用途只信任一把公钥，仍不要求kid；不读取远端密钥地址。
双用途不等于同用途平滑轮换，旧新钥并存窗口及安全下线策略另议，
不能在换钥后继续承诺所有旧令牌可用。

## 5. HTTP能力矩阵

以下是目标行为。只控制能力，不用CLOUD/PRIVATE猜开关。
（写作时控制器尚为占位；后续工作包已陆续接线，逐项落地情况见本节末「实现进展」。）

| 接口 | 云端账号签发实例：账号开、设备关 | 目标边缘：账号关、设备开 |
|---|---|---|
| POST /api/v1/auth/login | 注册，调用已有AuthService | 不注册，404 |
| POST /api/v1/auth/refresh | 注册，兑换该实例refresh记录 | 不注册，404 |
| POST /api/v1/auth/logout | 注册，无请求体，全账号撤销 | 不注册，404 |
| GET /api/v1/auth/me | 注册，账号Bearer，本地User映射 | 注册，账号Bearer；身份来源待第7节确认 |
| POST /api/v1/auth/device/login | 不注册，404 | 注册，检查本地绑定后签设备令牌 |
| GET /api/v1/auth/device/me | 如注册仍受设备用途限制，本阶段501 | 设备Bearer，本阶段仍501 |

只验签账号场景并不关闭设备login。两签发能力都关闭时，各自签发入口均缺席。
缺席路由404还需处理Security在MVC前返回401的现有R05：
推荐仅对被关闭的精确operation路径设置固定ApiProblem ROUTE_NOT_FOUND出口，
不注册占位业务controller，也不放行整个/api/v1/auth/**。
缺席能力端点的404出口须在Bearer验证前生效，连无效Bearer也保持404；
按实际关闭能力构建精确匹配表并回归，其他受保护路径仍走正常401/403。
开启的login/refresh/device-login才进入对应免鉴权白名单，logout始终要求账号令牌。
不得把注册却因配置错误抛IllegalStateException的500当成能力关闭响应。

**实现进展（2026-11-16，段 92 工作包 5 —— 取代段 91 旧口径）**：与本仓库实际落地形态对照——

- **「账号 / 设备两个独立开关」已落地**：`scds.auth.jwt.account.signing-enabled`（默认 `true`）与
  `scds.auth.jwt.device.signing-enabled`（默认 `false`）**各自独立**；账号侧由 `AccountAuthController` /
  `RsaTokenSigner`、设备侧由 `DeviceAuthController` / `DeviceRsaTokenSigner` 分别控制
  （§10.1 / §10.2 / §10.3 落地）。段 90 曾把两者合并为**同一个**开关，本段起**取消该合并**，
  上表「云端账号签发实例：账号开、设备关」这一列因此在本仓库**可达**了。
- **「安全层固定 404 出口」改为按能力分表**：`DisabledCapabilityRouteFilter`（`scds-api`）由
  「单开关 / 单一匹配表」改为**两张表**——**账号**关闭时命中 `{login, refresh, logout}`，
  **设备**关闭时命中 `{device/login}`；仍排在 Bearer 验证**之前**，对缺席的精确 operation 写固定
  `ROUTE_NOT_FOUND`，因此**无令牌 / 合法令牌 / 无效令牌**三种请求得到同一个 404；其他受保护路径
  仍走正常 401/403。控制器仍是条件注册（不注册占位 controller），也未放行整个 `/api/v1/auth/**`。
- **免鉴权白名单已条件化**（**口径变更**）：`SecurityConfig` 按能力构建 `permitAll` 清单——
  **开启的** `login` / `refresh` / `device/login` 才进白名单，`logout` **始终**要求账号令牌（本节第 136 行）。
  段 90 的旧口径「`SecurityConfig` 不读能力开关、三条无条件放行」**就此作废**，改约同步于
  `docs/design/03-auth.md` §4.4。**这与第 134 行「连无效 Bearer 也保持 404」不冲突**：404 出口排在
  安全链**之前**，能力关闭的路径先被过滤器短路，根本到不了 `AuthorizationFilter`，故收窄白名单
  不会把缺失路由的 404 退化成 401。（若日后移除该过滤器，则须恢复无条件放行，二者耦合。）
- **`device/login` 归设备开关**：`DeviceAuthController` 的 `@ConditionalOnProperty` 前缀由账号开关改为
  `scds.auth.jwt.device`，且 `matchIfMissing = false`——「账号开 / 设备关」时设备端点**不注册（404）**，
  而不是注册后因缺设备私钥抛 `IllegalStateException` 变成 500（本节第 137 行）。
- **logout 不进免鉴权白名单**：它始终要求账号令牌；只有能力关闭、路由缺席时才得到 404，
  此时由上述 404 出口给出（`AuthorizationFilter` 已不在其路径上）。
- 本地证据：`DisabledCapabilityRouteFilterTest`（14 例，四组合交叉判定）＋ `ApplicationStartupIT`
  （四组合 `account × device`：真 Tomcat + 真安全链 + 真 MySQL）；见 testing.md §85。

## 6. 断网边界与设备会话

无外网但本地服务、数据库、设备绑定数据、公私钥、系统时钟可用时，
边缘能够启动、接受尚未过期的云端账号令牌，并进行本地设备登录与验签。
“断网”仅指无外网，不指数据库或边缘服务不可用；数据库失效时不得绕过绑定/SID。

设备登录仍需D03明确绑定创建/换绑/解绑和密钥管理流程。
登录事务生成SID、写本地会话并签发，必须提交成功后才把令牌交给客户端；
重复登录使旧SID在后续请求被拒，主动缓存失效和跨进程行为须按后续服务设计验证。
本轮不决定缓存TTL、集中登录容量或后台运行策略，也不实现DEVICE-AUTH。

外网断开后不能在边缘重登或刷新云端账号。预发accessToken到期后指挥端需要
恢复云端连接或使用另行批准的独立单机账号签发形态；不把deviceToken当账号替代。
设备12h是从签发时算有效期，不是按日历午夜切断；不在此次修改TTL。
物理断网、正确设备绑定、SID顶号、时钟偏移、重启恢复均需现场/集成验证，
当前558项测试只验证原单密钥装配，不证明这些目标已经交付。

## 7. 边缘账号身份缺口

当前AuthService.me(accountId)从本地t_account及角色表查询，User需要username、
displayName、roles、permissions和lastLoginAt。当前JWT只稳定提供sub/roles等认证字段，
本仓库没有云端账号同步、带签名的User快照协议或云端查询适配器。

因此，云端token验签通过不代表边缘已有同UUID账号或可以返回完整User。
不能按用户名碰撞匹配、临时创建管理员、复制云端口令散列、
把无法解析的字段补为默认ADMIN，或在断网时隐式调用云端查询。

实施前须选择身份策略，建议单独裁定，不默认扩展D02：

| 候选 | 影响 |
|---|---|
| 边缘预置/同步最小账号身份与角色快照 | 复用现有本地me；须定义相同UUID、初始化工具、签名/同步来源、失效及陈旧容忍策略 |
| 云端令牌携带签名身份快照，由边缘映射User | 新增JWT字段、签发端兼容和字段校验；角色/姓名更新到令牌刷新才生效，me不再复用原本地查库语义 |

**（2026-11-15，段 92 裁定）** 身份策略取**第一行**：边缘**预置/同步相同 UUID 的最小账号身份与角色快照**，
`/auth/me` **复用现有本地查库语义**。由此新增前置：相同 UUID 约定、初始化/同步工具、失效与陈旧容忍策略
（独立方案**已出**：[03c-edge-identity-sync.md](03c-edge-identity-sync.md)，2026-11-16；其决策点待裁定）。
**不**采纳「令牌携带签名身份快照」路线。

两者都不能自动声称解决云端账号禁用的即时传播：accessToken仍按既有不可撤销语义，
本地身份检查如何影响其他业务端点需和D04权限规则对齐。
客户端另配云端认证baseUrl和边缘业务baseUrl，避免向边缘的404刷新入口反复请求；
最终客户端配置与恢复流程在客户端任务中确认。
若只先做账号控制器本地映射，未知本地sub返回401是现有服务语义，
必须明确云边身份闭环仍未完成，不能拿该实现作为云边部署可用验收。

## 8. 分段实施建议

每个原子任务修改文件数最多3，故以下是顺序工作包，不是一次性修改清单。
每包开工前列具体文件白名单及测试，不能用此表绕过后续确认。

1. 契约/部署示例：回写能力矩阵和拟议配置，不改变请求/响应字段、不修改实际.env。
2. 新设备配置与domain签发端口：显式开关、独立issuer/TTL、配置负向测试。
3. 独立设备RSA适配器：公私钥配对、禁用不读私钥、设备用途限定及真实签发测试。
4. 双用途decoder：按aud分派固定信任，交叉签名/issuer/字段/过期矩阵，保持SEC-02保障。
5. 路由能力分离：拆账号和设备签发controller，处理R05精确404出口及白名单；
   HTTP业务还未接线时合法请求保持501，不误报成功。
6. AUTH-HTTP：复用AuthService实现login/refresh/logout/me，User只映射安全字段，
   用真实安全链、隔离测试库和自身UUID清理验证；me身份策略先按第7节裁定。
7. 原完整启动IT扩展双密钥与全部能力组合，更新隔离门禁，不读开发/生产秘密。
8. D03确认后再推进DEVICE-AUTH、SID校验和本地断网集成验收。

迁移过程中先增加新实现再迁移引用，不提前删旧方法；每段编译/专项/全量均通过。
不重复开发AuthService、不改已应用V1-V7/V9、不默认加V10，
不安装工具链、不重置数据库、不清库删卷；提交及推送另需授权。

## 9. 双能力验收清单

- 四个账号/设备签发开关组合均明确；关闭的精确路径无Bearer/合法/无效Bearer均404。
- 账号关闭设备开启时，应用无账号私钥仍启动，设备签发有效但账号签发拒绝。
- 开关启用但对应密钥/issuer缺失、错配或账号设备复用时启动失败，日志不泄漏秘密。
- 账号公钥不能验证设备用途JWT；设备私钥签ADMIN账号JWT不能进入账号授权。
- 真签名且错误issuer401，真过期EXPIRED，错签且过期INVALID，SEC-02字段用例保持。
- 有效设备JWT访问指挥、metrics、WS均403；有效账号JWT按原角色矩阵授权。
- 关闭能力的处理不影响启用login/refresh校验、401/403 traceId及MVC Problem。
- refresh只改权威库，重放整链撤销与无请求体logout保持，无边缘离线refresh回退。
- me按获批身份策略返回真实User，不泄漏passwordHash，不凭空创建本地账号。
- 隔离库、初始化前门禁、内存双密钥、回环随机端口、连接池/监听关闭均通过。
- 真设备重复登录顶号、换绑失效与断外网本地登录另验；不以decoder测试替代SID服务。
- 发布前明确断外网账号过期后的操作边界，并完成真实设备、时间和恢复验证。

## 10. 分段实施白名单与测试计划（拟议，2026-11-15 段 92）

依据 §8「**每包开工前列具体文件白名单及测试，不能用此表绕过后续确认**」。本节把 §8 的八个工作包
落成**逐包文件白名单 + 测试计划 + 验收判据 + 边界**，供批准后逐段开工。

> **本节是计划不是授权。** 开工每一包前仍需**逐段授权**（提交 / 推送同样逐次授权，见 §8 末）。
> 本节**不改任何代码、不改 `.env`、不改迁移、不生成密钥**。文件数为**拟议清单**，
> 实施时按「每原子任务 ≤3 文件」上限**再拆**，破限需显式声明例外。

### 10.0 现状锚点（2026-11-15 实测，供白名单映射）

| 关注点 | 现文件（仓库相对路径） | 现状 |
|---|---|---|
| 账号签发/验签端口 | `scds-domain/src/main/java/com/dualx/core/port/TokenSigner.java` | 单端口，`enabled`/三个 TTL/`signAccessToken`/`signRefreshToken`/`signDeviceToken` 全在此 |
| 签发实现 | `scds-api/src/main/java/com/dualx/api/security/RsaTokenSigner.java` | 单私钥，账号三用途共签（含设备） |
| 账号配置 | `scds-api/src/main/java/com/dualx/api/security/AuthJwtProperties.java` | `scds.auth.jwt.account.*`，单 issuer / 单公私钥 / 三 TTL |
| 验签装配 | `scds-api/src/main/java/com/dualx/api/security/JwtDecoderConfig.java` | 单 decoder（本地 PEM 或远端 JWKS 死分支），`KNOWN_AUDIENCES` 已含三用途 |
| 密钥读取 | `scds-api/src/main/java/com/dualx/api/security/PemKeys.java` | 按 `AuthJwtProperties` 读账号私钥/公钥 |
| 授权/白名单 | `scds-api/src/main/java/com/dualx/api/security/SecurityConfig.java` | 三条签发入口无条件 `permitAll`；`logout` 要求账号令牌 |
| 能力缺席 404 | `scds-api/src/main/java/com/dualx/api/security/DisabledCapabilityRouteFilter.java` | **单开关**匹配表（段 91） |
| 控制器 | `scds-api/src/main/java/com/dualx/api/auth/{AuthController,AccountAuthController,DeviceAuthController}.java` | 前二者无条件注册，后二者 `@ConditionalOnProperty(signing-enabled)` |
| 服务 | `scds-core/src/main/java/com/dualx/core/auth/{AuthService,DeviceAuthService}.java` | `DeviceAuthService` 现经 `TokenSigner.signDeviceToken` 签设备 |
| 配置样例 | `scds-app/src/main/resources/application.yml` | `scds.auth.jwt.account.*`（第 88~110 行） |
| 相关测试 | `scds-api/src/test/java/com/dualx/api/security/{AuthJwtPropertiesTest,RsaTokenSignerTest,DisabledCapabilityRouteFilterTest}.java`、`scds-app/src/test/java/com/dualx/api/security/SecurityJwtValidationIT.java`、`scds-app/src/test/java/com/dualx/{AuthHttpIT,ApplicationStartupIT}.java`、`scds-app/src/test/java/com/dualx/security/SecurityMetricsIT.java`、`scds-core/src/test/java/com/dualx/core/auth/{AuthServiceIT,DeviceAuthServiceIT}.java` | 单密钥装配 |

### 10.1 工作包 2 — 账号键重命名 + 设备配置与 domain 签发端口

**目标**：按 **§3 段 92 裁定**落地配置键——账号键迁 `scds.auth.jwt.account.*`、新增 `scds.auth.jwt.device.*`；
并新增 domain 设备签发端口。**前置**：无（可先做）。

- **拆 2a — 账号键重命名**（3 文件）：
  - 改 `scds-api/src/main/java/com/dualx/api/security/AuthJwtProperties.java` —— `@ConfigurationProperties` 前缀
    `scds.auth.jwt` → `scds.auth.jwt.account`（字段 / 校验不变）。
  - 改 `scds-app/src/main/resources/application.yml` —— 现有 `scds.auth.jwt.*` 账号段下移为 `scds.auth.jwt.account.*`
    （**仅键名变更、值语义不变**）。
  - 改 `scds-api/src/test/java/com/dualx/api/security/AuthJwtPropertiesTest.java`（如硬编码键名则同步）。
  - **验收**：账号签发 / 验签行为与现状**等价**（回归；含 `ApplicationStartupIT` / `AuthHttpIT`）。
- **拆 2b — 设备配置与端口**（3 文件）：
  - **新增** `scds-api/src/main/java/com/dualx/api/security/DeviceJwtProperties.java` ——
    `@ConfigurationProperties(prefix = "scds.auth.jwt.device")`：`issuer` / `signingEnabled`（**默认 false**）/
    `publicKey` / `publicKeyFile` / `privateKey` / `privateKeyFile` / `deviceTokenTtl`（默认 `12h`）。
    紧凑构造器负向校验：启用而 issuer / 私钥缺失 → 失败；公私钥不配对 / RSA 强度不足 → 失败；
    **issuer 或公钥与账号相同** → 失败（§3）；TTL 非正 → 失败。
  - **新增** `scds-domain/src/main/java/com/dualx/core/port/DeviceTokenSigner.java` ——
    `boolean enabled()` / `Duration deviceTokenTtl()` / `String signDeviceToken(DeviceTokenClaims)`（§3）。
  - **新增** `scds-api/src/test/java/com/dualx/api/security/DeviceJwtPropertiesTest.java` ——
    默认关闭；启用缺 issuer / 缺私钥 → 抛；公私钥错配 → 抛；与账号 issuer / 公钥相同 → 抛；TTL 非正 → 抛。
- **拆 2c — yml 接线**（1 文件，可与 2a 的 yml 编辑合并到同一笔）：改 `application.yml` 增 `scds.auth.jwt.device` 段
  （默认 `signing-enabled: false`），**不写任何默认私钥**。
- **边界**：本包**不动** `TokenSigner` 的设备方法（删除在包 3b，与 `DeviceAuthService` 迁移同段，以保证编译）；不改迁移。

### 10.2 工作包 3 — 独立设备 RSA 适配器 + 一次迁净

**目标**：设备签发用**独立私钥/issuer**，禁用不读私钥（§3/§4）；**按段 92 裁定一次迁净**——删除旧
`TokenSigner.signDeviceToken`、迁移 `DeviceAuthService`，**不保留兼容入口**。**前置**：包 2。

- **拆 3a — 设备适配器与键来源参数化**（3 文件）：
  - **新增** `scds-api/src/main/java/com/dualx/api/security/DeviceRsaTokenSigner.java`（`implements DeviceTokenSigner`）：
    仅 `signingEnabled=true` 时读**设备**私钥；签 `aud=scds-device`、带 `sid`、TTL 取设备 TTL。
  - 改 `PemKeys.java` —— 密钥读取**参数化到「键来源」**（账号 / 设备各读各的，两套不互借）。
  - **新增** `scds-api/src/test/java/com/dualx/api/security/DeviceRsaTokenSignerTest.java`：禁用时构造**不读私钥**；
    签发载荷 `aud`/`sid`/`exp` 正确；TTL 取设备 TTL。
- **拆 3b — 迁依赖并删旧方法**（**须同段**，否则编译不过；4 文件 ⇒ **声明破「≤3 文件」例外**）：
  - 改 `scds-domain/src/main/java/com/dualx/core/port/TokenSigner.java` —— **删除** `signDeviceToken`（及仅设备用的 `deviceTokenTtl`，如无其他引用）。
  - 改 `scds-api/src/main/java/com/dualx/api/security/RsaTokenSigner.java` —— 删除对应实现与 import。
  - 改 `scds-core/src/main/java/com/dualx/core/auth/DeviceAuthService.java` —— 依赖 `DeviceTokenSigner`。
  - 改 `scds-core/src/test/java/com/dualx/core/auth/DeviceAuthServiceIT.java` —— 迁到 `DeviceTokenSigner` 假件。
  - （如 `RsaTokenSignerTest` 断言了设备签发，同段一并去掉该用例 → 可能 5 文件，**破限例外**。）
- **验收**：设备令牌由设备私钥签出；账号私钥**不**参与设备签发；编译保证**无遗留旧方法引用**。
- **边界**：不动 `signDeviceToken` 的**载荷字段**（契约冻结）；不快照设备业务规则。

### 10.3 工作包 4 — 双用途 decoder（按 aud 分派固定信任）

**目标**：未验签 `iss`/`aud` 只用于**选信任项**，不试遍公钥、不跨用途（§4）。**前置**：包 2。

- **修改** `scds-api/src/main/java/com/dualx/api/security/JwtDecoderConfig.java` —— 由单 decoder 改为**门面**：
  先做 §4 的结构检查取**未验签** `aud`；`aud ∈ {scds-edge, scds-refresh}` → **账号** decoder（账号 issuer/公钥）；
  `aud = scds-device` → **设备** decoder（设备 issuer/公钥）；两者均强制精确 issuer、固定 RS256 与对应公钥。
  错签 / 错误 issuer / 未知用途仍 401 `AUTH_TOKEN_INVALID`；纯过期 `EXPIRED`；60 秒时钟偏差不变（§4.4）。
- **新增（如需）** `scds-api/src/main/java/com/dualx/api/security/JwtDecoderFacade.java`（拟），避免 `JwtDecoderConfig` 膨胀。
- **测试** `scds-app/src/test/java/com/dualx/api/security/SecurityJwtValidationIT.java`（改，**扩**）：
  账号公钥签的 deviceToken → 401；设备公钥签的账号/refreshToken → 401；错误 issuer → 401；
  纯过期 → `EXPIRED`；错签且过期 → `INVALID`；SEC-02 字段用例保持。
- **验收**：交叉签发一律拒绝；同用途验签行为与现状等价（回归）。
- **文件数**：≤3。
- **边界**：仍不读远端密钥地址（`jwks-uri` 保持强制为空）；不承诺同用途平滑轮换（§4 末）。

### 10.4 工作包 5 — 路由能力分离（**已完成**，段 90 / 91 / 92）

- **段 90 / 91 已落地**：拆 `AccountAuthController` / `DeviceAuthController` 条件注册；`DisabledCapabilityRouteFilter` 安全层精确 404 出口
  （无令牌 / 合法令牌 / 无效令牌同一 404）；见 testing.md §79 / §80。
- **段 92 剩余项已收口（2026-11-16）**：① `DisabledCapabilityRouteFilter` 的单开关匹配表改为**两张表**
  （账号关闭 → `{login, refresh, logout}`；设备关闭 → `{device/login}`，§5 第 135 行）；
  ② `SecurityConfig` 白名单**条件化**——**开启的** `login`/`refresh`/`device-login` 才 `permitAll`，
  `logout` 始终要求账号令牌（§5 第 136 行）；③ `DeviceAuthController` 的 `@ConditionalOnProperty` 前缀由
  **账号**开关改**设备**开关且 `matchIfMissing=false`，故「账号开 / 设备关」时设备端点 404 而非 500（§5 第 137 行）。
- **落点（实测 7 文件）**：`scds-api/.../security/{DisabledCapabilityRouteFilter,SecurityConfig}.java`、
  `scds-api/.../auth/DeviceAuthController.java`；**测试 / 支撑** `DisabledCapabilityRouteFilterTest.java`、
  `scds-app/src/test/java/com/dualx/api/MvcProblemIT.java`、`ApplicationStartupSupport.java`、
  `ApplicationStartupIT.java`（四组合）。按用户裁定**合并一段**执行（原拟「文件数 4 ⇒ 拆两小段」作废）。
- **口径变更**：`SecurityConfig` 自此**读**能力开关，段 90 旧裁定与 `03-auth.md` §4.4 已同步改约；
  与 §5 第 134 行「连无效 Bearer 也 404」不冲突（404 出口在安全链之前）。证据见 testing.md §85。

### 10.5 工作包 6 / 7 / 8 — 依赖项（§7 身份策略**已裁定**）

- **包 6（AUTH-HTTP）**：`scds-api/src/main/java/com/dualx/api/auth/AuthController.java`（`me` 映射）＋
  `scds-core/src/main/java/com/dualx/core/auth/AuthService.java`（如需）。**§7 已裁定取「边缘预置/同步最小身份快照、复用本地 `me`」**；
  开工前须先出「相同 UUID 约定 / 初始化同步工具 / 陈旧容忍」独立方案
  （**已出**：[03c-edge-identity-sync.md](03c-edge-identity-sync.md)；决策点与推荐项见其 §0，待逐项裁定）。
  未知本地 `sub` 返回 401 是现有语义，**不得**据其宣称云边身份闭环完成（§7 末）。
- **包 7（启动 IT 扩双密钥与全组合）**：`scds-app/src/test/java/com/dualx/ApplicationStartupIT.java` ＋
  `scds-app/src/test/java/com/dualx/security/SecurityMetricsIT.java`（＋内存双密钥夹具），
  覆盖 §9 四个开关组合；**不读开发 / 生产秘密**。
- **包 8（DEVICE-AUTH / SID / 断网）**：**D03 确认后**再推进，不在本表展开。

### 10.6 开工前设计前置（**已于 2026-11-15 段 92 裁定**）

1. **配置键方案** —— 裁定：**统一重命名子键** `scds.auth.jwt.account.*` / `scds.auth.jwt.device.*`（§3）。
2. **`TokenSigner` 设备方法去留** —— 裁定：**一次迁净**，删除 `TokenSigner.signDeviceToken` 并迁移 `DeviceAuthService`，不保留兼容入口（§3 / §10.2）。
3. **`me` 身份策略（§7）** —— 裁定：**边缘预置/同步最小身份快照，复用本地 `me`**（§7）。
4. **密钥供给方式** —— 裁定：**部署侧提供 PEM**（本表**不生成、不入库**密钥）；轮换策略另议（§4 末）。

> 四项裁定**仅解除设计阻塞**，**不构成包 2 的开工授权**；逐段授权与逐次提交 / 推送授权仍适用。

### 10.7 本表不构成授权（复述）

- 不改 `.env` / 不生成密钥 / 不重置数据库 / 不清库删卷 / 不改已应用 V1~V11 / 不新增迁移（§8 末）。
- 迁移原则：**优先先增新实现再迁引用**；**唯一例外**是包 3b 按段 92 裁定**一次迁净**删除 `TokenSigner.signDeviceToken`
  （删方法与迁移其唯一调用方 `DeviceAuthService` 必须同段，否则编译不过）。每段编译 + 专项 + 全量均须通过。
- 每包开工前**逐段授权**；提交与推送**另需逐次授权**。
