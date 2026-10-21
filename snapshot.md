# scds-next 交接快照

> 更新：2026-10-21，Asia/Shanghai。此快照用于新对话接续，不是生产验收证明。
> 当前任务：全盘核查设计与代码、补全文档并生成新对话提示词；本轮没有修业务代码。

## 1. 从这里继续

仓库：`e:\DualX\scds-next`。先读本快照，再读
[审查清单](docs/review.md)、[需求](docs/requirements.md)、
[架构与实施方案](docs/architecture.md)、[环境](docs/environments.md)、
[测试](docs/testing.md)和[下一对话提示词](docs/next-chat-prompt.md)。
HTTP/WS唯一契约入口是 [openapi.yaml](docs/api/openapi.yaml)。

建议下一原子任务为 SEC-01：修复指标子路径授权并补真实Security过滤链测试。
先核查当前代码并提出 <=3 文件方案与验收口径，等待用户确认后实施。
本次文档整理不授权自动执行全部后续工作包。

## 2. Git与工作树

| 项 | 当前状态 |
|---|---|
| 分支 | main |
| 源代码HEAD | da02622bdca893ddb60910de20efd4301da2116f |
| 本地origin/main跟踪值 | 3c4f4f4adf650f644ae6a86c67ad8411cbcb6b6f |
| 本地待推送 | af54c45、3d30e09、da02622，共3个提交 |
| 实际远端 | 前序GitHub连接失败，未成功核验实际远端HEAD；本地跟踪值不代表实时远端 |
| 本轮文档 | 未提交、未推送；开始下一任务前git status/diff确认 |
| 凭据 | 实际.env未修改，仍被gitignore排除；禁止打印或提交 |

本轮新增README、snapshot、需求/架构/环境/测试/审查/新对话提示词与协议原文；
修订设计01/02/03、auth契约说明、common响应headers层级及.env.example。
没有改Java、pom或已应用迁移。不得恢复用户已删除的临时提交信息文件。
本轮无新的提交授权，不自动commit或push文档。

## 3. 实际实现状态

已实现：协议Framer/下行字节编码、冻结算法迁移、domain端口、
JDBC仓储、账号首次建号、登录限流、RS256签发/验签、
AuthService login/refresh/logout/me及事务并发保护。

未完成：6个auth HTTP方法仍501，其他60个REST业务方法同样占位；
gateway零主源码，演习/态势/指令业务链未实现。
DeviceAuthService、SID校验及主动缓存失效未实现。
WS只有PING/PONG，首帧、补齐、背压、保活扫描尚缺。
Flutter工程与生产应用部署脚本尚无。

实际版本为Java21、Spring Boot **3.5.7**、MySQL本机8.4.11；
以pom和当前代码为准，不沿用历史记忆里的Boot3.3.5。
Flyway文件V1-V7、V9，共8份；18张业务表，另有Flyway历史表。
原预留V8自愈迁移未创建，而本地已应用V9。

## 4. 验证证据与局限

本轮已运行`mvn clean verify`：**309项，失败/错误/跳过均0**。
protocol19 + core211 + api79，全部reactor构建成功。
AuthServiceIT有17项真实MySQL测试，包含refresh/logout并发、
重放撤销持久性、签发/插入失败回滚。报告在各模块target/surefire-reports。
API骨架使用standalone且只断言非404，不能证明真实Security或业务闭环。

本地fat jar在signing=true/false下均已人工启动并验证HTTP，随后已停止。
实测缺陷：

| 审查ID | 实测 |
|---|---|
| R01 | VIEWER访问metrics根403，但指标子路径200 |
| R02 | 正确签名过期JWT返回INVALID而不是EXPIRED |
| R03 | 缺exp的合法签名JWT通过认证进入501 |
| R04 | 空device/login DTO返回默认400 Problem，缺code/traceId |
| R05 | signing=false时无token账号login返回401，目标404 |
| R07 | 不存在SID的device token进入device/me占位，校验未实现 |

以上只记录，**未修复**。R18-R28等静态风险未逐项运行时复现。
无真实硬件、solver数值对拍、物理断网、WS帧序列、Flutter或生产节点验证。
发布结论：**不可生产发布**。完整问题及测试矩阵见review/testing。

## 5. 环境接续

最后检查：scds-local-mysql healthy，127.0.0.1:13306监听；
11001/12001无监听，本轮应用进程均已停止。新对话必须重新核查，不照搬瞬时状态。
实际.env有DB秘密及JWT文件路径，没有SCDS_ADMIN_USERNAME/PASSWORD。
首次建号实现不等于已部署管理员，不擅自补凭据。

从scds-app目录运行：

```powershell
java -jar target\scds-next.jar --spring.profiles.active=local
```

从仓库根运行须覆盖dev公私钥路径，详见环境手册。
local需通过进程环境或命令行激活，仅在待导入.env里写profile不能提前激活它。
IT当前与开发共库，不允许指向生产；AuthServiceIT真实提交，清理不是全量回滚。

## 6. 已确认与待确认

已确认：refresh按令牌原文SHA-256的token_hash查库，core不解析JWT；
logout无请求体，撤账号全部refresh；access无主动撤销。
管理员部署.env明文、首次BCrypt建号、不写seed；
device/me本阶段留501；公开dev密钥仅开发。
自愈写SYSTEM证据后删该场残留指令，同事务；
协议LogType不能加SYSTEM；谁持状态谁调推送端口。

待确认：只验签实例的设备签发能力；自愈是否用下一递增V10；
链路两通道的数组/信封根类型；跨进程revision恢复；
WS超窗409/close语义；权限与单兵可见性；UWB SERIAL/启停；
容量、文件保留及RPO/RTO。不得默认同意推荐方案。

## 7. 不可越过的边界

- HTTP11001、UWBTCP12001；WS连着仍Poll，同构载荷。
- 最后心跳<=5000ms在线；阵亡冻结最后有效坐标，motion=FROZEN，判活才解除。
- 马甲/UWB逐字节及算法/参数冻结，gateway/core不互依，domain承载SPI。
- UTC/RFC3339、DB datetime(3)、t_ms INT、Problem含code/traceId。
- 每业务阶段先方案确认；原子修改<=3文件，修复最多5轮。
- 不覆盖用户改动，不改.env或历史迁移，不清库/删卷/reset/force push。
- 提交和推送须有当次授权；网络失败保留本地提交并如实报告。

## 8. 文档校验记录

冻结协议从旧迁移目录按原字节复制，SHA256：
`7CB9B4988B0D6BE64A6CA5A6B4AE91BC8005DA11466727C46B5BF0DD5859BD92`。
协议内容不编辑；未来staging/checkout时仍需防Windows换行转换。

已完成校验：

- 11份非冻结Markdown文档，82个相对链接目标全部存在。
- SnakeYAML2.4解析11份YAML，禁用重复键；441个$ref文件及JSON Pointer目标均存在。
- 从根paths解析66个REST、2个WS操作，operationId无缺失/重复。
- TooManyRequests的Retry-After位于Response Object headers层级。
- Surefire报告重计309项、失败/错误/跳过均0。
- git diff --check通过；修改范围为文档、契约说明/响应结构和.env.example。

未执行完整OpenAPI专业lint；未验证协议未来Git检出字节、物理硬件、Flutter或生产。
链接目标存在不等于所有业务说明已通过验收；后续实现必须同步文档与真实测试证据。
