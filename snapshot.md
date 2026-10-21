# scds-next 交接快照

> 更新：2026-10-21，Asia/Shanghai。此快照用于新对话接续，不是生产验收证明。
> 当前任务：SEC-01/SEC-02/ERR-01已完成并推送；TEST-01数据库隔离子任务完成，待补完整应用启动验收。

## 1. 从这里继续

仓库：`e:\DualX\scds-next`。先读本快照，再读
[审查清单](docs/review.md)、[需求](docs/requirements.md)、
[架构与实施方案](docs/architecture.md)、[环境](docs/environments.md)、
[测试](docs/testing.md)和[下一对话提示词](docs/next-chat-prompt.md)。
HTTP/WS唯一契约入口是 [openapi.yaml](docs/api/openapi.yaml)。

下一原子任务：TEST-01完整ScdsNextApplication启动测试，真实安全链和测试DB共同装配；
先给出 <=3 文件方案，隔离凭据/初始化门禁/临时密钥/管理员建号均须明确。
之后AUTH-HTTP仍须先确认D02设备签发能力，不能默认批准其推荐。
先前review/架构/设计的基线缺口为历史；最新修复证据见testing第7-10节。
本次“继续”及D14授权不扩展为全部业务实施或后续自动提交推送。

## 2. Git与工作树

| 项 | 当前状态 |
|---|---|
| 分支 | main |
| 源代码HEAD | 64b73e1a8c4cdac1a397c55a00e942dd5ff9f473 |
| 实际远端main | 本轮提交/正常推送后用git ls-remote核验，同上完整SHA |
| 已推送 | 前序af54c45、3d30e09、da02622及新64b73e1；无历史待推送 |
| 本轮继续成果 | TEST-01脚本/配置/回归及交接文档尚未提交、暂存区为空 |
| 凭据 | 开发.env未修改；新.env.test为独立owner-only测试凭据，两者均忽略，禁止打印或提交 |

64b73e1纳入此前文档基线、SEC-01/SEC-02/ERR-01源码和回归，提交前已有485项通过。
随后用户明确批准D14同容器独立测试库，分段实施和验证见testing第10节；
未提交的9文件范围含被忽略的.env.test，不把本地继续成果宣称为远端已有。
未改生产配置、pom、协议、算法或历史迁移。不得恢复用户已删除的临时提交信息文件。

## 3. 实际实现状态

已实现：协议Framer/下行字节编码、冻结算法迁移、domain端口、
JDBC仓储、账号首次建号、登录限流、RS256签发/验签、
AuthService login/refresh/logout/me及事务并发保护。
新增已完成：metrics子树ADMIN保护、JWT必需字段/原始类型/纯过期机器码分类、
MVC统一Problem及真实Boot共存回归；核心IT独立库、初始化前门禁和最小权限回归。

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

最新`mvn clean verify`：2026-10-21 14:42:30 +08:00，
**521项，失败/错误/跳过均0**：protocol19 + core247 + api79 + app176。
35份XML与testcase计数一致，8个reactor成功。历史309/361/458/485不改写。
AuthServiceIT有17项真实MySQL测试，包含refresh/logout并发、
重放撤销持久性、签发/插入失败回滚。报告在各模块target/surefire-reports。
API骨架使用standalone且只断言非404，不能证明真实Security或业务闭环。

历史fat jar在signing=true/false下均人工启动验证HTTP，已停止；本次未重复完整fat jar验收。
原实测缺陷的当前状态：

| 审查ID | 实测 |
|---|---|
| R01 | SEC-01已修；真实过滤链指标根/子树授权回归52项 |
| R02/R03 | SEC-02已修；JWT字段/时间/机器码回归97项 |
| R04 | ERR-01已修；Boot MVC回归27项，含3项真实Tomcat上传检查 |
| R05 | signing=false时无token账号login返回401，目标404 |
| R07 | 不存在SID的device token进入device/me占位，校验未实现 |

R05/R07仍未修复。R23数据库隔离子任务已完成；R18-R28其他风险仍待对应工作包验证。
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
核心IT现在仅连接同一容器内的scds_next_test，scds_test只拥有精确测试库权限，
不共用开发凭据；新.env.test仅含SCDS_TEST_JDBC_URL、
SCDS_TEST_MYSQL_USER、SCDS_TEST_MYSQL_PASSWORD。
初始化前门禁拒绝误连、连接覆盖及危险Flyway模式；不允许指向生产。
AuthServiceIT仍真实提交、按自身UUID清理；多数仓储IT回滚。
测试库默认保留，Initialize-TestDatabase.ps1仅首次运行，已有库/账号/文件均拒绝覆盖。
空库已迁移V1-V7/V9，两库8份迁移校验和一致；开发与测试account/refresh/exercise均0。

## 6. 已确认与待确认

已确认：refresh按令牌原文SHA-256的token_hash查库，core不解析JWT；
logout无请求体，撤账号全部refresh；access无主动撤销。
管理员部署.env明文、首次BCrypt建号、不写seed；
device/me本阶段留501；公开dev密钥仅开发。
自愈写SYSTEM证据后删该场残留指令，同事务；
协议LogType不能加SYSTEM；谁持状态谁调推送端口。
2026-10-21新增已确认D14：同容器独立库、独立测试账号和凭据、保留库、
最小权限与初始化前门禁；不因此批准新容器、清库删卷、V10或其他决策。

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

## 8. 历史文档校验

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
最新TEST-01的35份报告、权限/清理/迁移及限制详见testing第10节；
本快照和新对话提示已同步521项及下一任务，不要求重做已完成SEC/ERR。
