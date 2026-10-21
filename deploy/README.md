# 本地业务与FRPC公网入口

本目录落实2026-10-21的部署架构决定：本地运行全部业务，云端仅反向代理。
样例尚未部署到真实主机；没有为SCDS分配域名、端口、FRPS凭据或证书。
最新权威边界见[架构第0节](../docs/architecture.md)，不再采用旧云签/双签发方案。

## 1. 拓扑与身份

```text
远程Flutter客户端 -- HTTPS/WSS:443 --> 云端Nginx
                                           |
                                   云端回环FRPS代理端口
                                           |
                          加密FRP隧道（本地FRPC主动连接）
                                           |
现场Flutter客户端 -- 可信LAN或本地TLS --> 本地scds-next:11001
                                           |
                               本地MySQL / 文件 / 硬件
```

账号、设备、refresh记录及身份查询都在同一个本地业务实例。
本地使用每客户独立PEM签发及验签，signing=true，jwks-uri必须为空。
云端不持业务JWT私钥，也不运行账号服务、数据库、文件服务或硬件gateway。
断外网后公网用户不可达，但LAN用户仍能本地登录/刷新；FRPC停止不应停止业务进程。
不使用公开dev-keys对外服务，本地实例未通过业务验收前不能直接开放公网。

## 2. 样例与运行方式

| 文件 | 用途 |
|---|---|
| frp/frpc.toml | 原生FRPC转发本地HTTP/WS，不转发MySQL/UWB |
| nginx/scds.conf.example | 云端TLS与WS反代，公网屏蔽actuator |
| ../.env.example | 非秘密配置键登记，不是生产凭据或FRPC自动加载文件 |

参考ZAA的Nginx/FRPS/FRPC拓扑，不复用ZAA站点配置、proxy name、代理端口或token。
本例推荐原生FRPC和Java同机运行，适合Windows串口部署，也可由Linux服务管理器托管。
不复制FRPC二进制进版本库；用部署方校验来源和版本的FRPC，与FRPS兼容。
本轮仅以本地缓存的FRPC 0.61.0镜像做配置验证，不安装或启动真实隧道。

## 3. FRPC环境与信任

配置由FRPC的Go模板读取进程环境，FRPC不会自动加载项目.env。
生产由受限服务配置注入；JWT、数据库和FRP秘密不得打印到日志或放进命令参数。
FRPC启动前逐项核验下表非空、端口合法、CA文件可读，禁止用演示值启服务。

| 键 | 要求 |
|---|---|
| SCDS_FRP_SERVER_ADDR | 已批准FRPS地址，不默认指向ZAA的ECS |
| SCDS_FRP_SERVER_PORT | 已分配控制端口；7001仅隔离样例，须与FRPS一致 |
| SCDS_FRP_SERVER_NAME | FRPS TLS证书匹配的DNS名，不能只启TLS而不验服务器身份 |
| SCDS_FRP_CA_FILE | 本机受保护CA文件，验证FRPS证书；不是云端站点TLS私钥 |
| SCDS_FRP_TOKEN | 与对应FRPS一致的受保护随机秘密，不复用ZAA_FRP_TOKEN |
| SCDS_FRP_PROXY_NAME | 每客户/实例唯一；开发机与正式机不能同时占用同一代理 |
| SCDS_FRP_LOCAL_IP | 原生同机默认127.0.0.1；若应用绑定LAN地址则明确指定该地址 |
| SCDS_FRP_REMOTE_PORT | 已分配云端回环端口；18081仅样例，勿占用ZAA的18080 |

CA/serverName缺失不视为安全配置。先使用FRPC配置校验，再在受控网络检查真实证书链、
主机名、令牌拒绝及端口边界；静态verify不证明TLS信任或公网连通。
不直接把独立token写进既有共享FRPS：FRPS通常使用实例级token，修改会影响其他客户端。
优先单独分配FRPS监听实例与代理资源；如需复用，先批准端口/名称限制及认证隔离方案，
不得为了SCDS重启、换token或重配ZAA入口。

以下只在可信FRPC已安装、环境及服务配置获批后执行，不构成本轮远端变更授权：

```powershell
frpc verify -c E:/DualX/scds-next/deploy/frp/frpc.toml
frpc -c E:/DualX/scds-next/deploy/frp/frpc.toml
```

前台退出使用该进程Ctrl+C；正式部署由独立服务管理器负责重启与日志轮转。
容器FRPC不能用127.0.0.1连接宿主机Java；改用容器网络或明确宿主机路由/绑定地址，
须重新做网卡与防火墙验证，不能照搬ZAA的localIP=web。

## 4. 云端Nginx与FRPS

由有权限的部署方在维护窗口执行，先备份、检查现有站点及代理资源。
只加载SCDS独立站点，不覆盖现有nginx.conf、ZAA站点或共享FRPS配置。

1. 分配域名、DNS、站点TLS证书、FRPS控制端口和代理端口。
2. FRPS设置proxyBindAddr=127.0.0.1或等效隔离，控制入口限制来源/凭据及允许代理端口。
   代理端口只供云端Nginx访问，安全组也不得放行到公网；共享FRPS全局变更需评估其他站点。
3. 配置FRPC可信CA、serverName及一致token，先验证云端回环代理口可达。
4. 在独立站点替换scds.example.invalid、证书文件和18081；执行nginx -t后再受控重载。
5. HTTPS/WSS公网443供客户端使用；80只跳转HTTPS。未分配/备案/TLS未通过前不切公网。

样例20MiB上传上限、75s代理等待仅候选配置，须与API上传限制、WS保活和容量要求校准。
上线前按已确认限流预算添加Nginx源IP限流；样例没有代替现有按账号限流。
覆盖外来XFF/Forwarded防伪造；应用forward-headers-strategy=none，不默认信任穿透链。
如以后需要业务审计真实客户端IP，须单独实现只信任批准代理的方案和回归。
公网actuator被屏蔽，本机health和ADMIN指标仍按应用安全链访问。
JWT签名不是传输加密；不可信现场网络也必须本地TLS，不能靠FRP解决LAN明文问题。

## 5. 本地业务配置

使用明确的生产配置位置与绝对PEM路径，避免开发local profile自动导入公开dev密钥。
业务实例signing=true、jwks-uri空；me读同库账号，refresh只改本地同库。
原生同机FRPC可连接回环11001；需要LAN直连时只开放经批准的业务网卡/现场网段。
MySQL仅回环/受保护内网；UWB12001仅可信硬件网段，不创建对应FRP代理。
FRPC是否开启独立于Spring profile/deployment-mode；不默认更改实际.env或生成账号。
应用、FRPC分开托管；单客户同库只运行一个业务进程，迁机先停旧代理/业务再启新节点。

## 6. 验收与回退

- 本地health、安全、账号login/refresh/logout/me和设备/SID业务先验收，不把501当成功。
- FRPC配置校验、CA/主机名/token错误拒绝、FRPS回环绑定和公网端口扫描逐项验证。
- 从外网执行真实登录、轮询、WS101握手、保活与重连；即使WS连接也不能停Poll。
- 物理断外网后从LAN重新登录、刷新，检查硬件采集和本地业务继续；恢复后远程重同步。
- 公开域名访问actuator为404、无令牌业务仍401、越权403、Problem及traceId保持。
- 检查ZAA旧站点/隧道未受影响；代理切换只涉及获批的SCDS名称和端口。
- 发布需制品/提交号、隔离库全量测试、备份恢复、单进程和现场容量门禁。

异常回退先停用本次SCDS公网代理/站点，保留本地业务及数据；不删除卷、重置账号或换业务密钥。
若需回滚jar或数据库，按独立已演练方案执行，不把“关闭FRPC”当业务版本回滚。
本轮未进行远端部署、DNS/TLS或真实公网验证；配置样例不等于可生产发布。

## 7. 本轮执行证据

代码起点为86f5a05，本次修改尚未提交/推送。分段白名单如下，共14个不同文件，
重访文档也计入当段文件数；没有一次扩为超过3文件的原子任务。

| 段 | 文件 |
|---|---|
| A：部署边界 | docs/architecture.md、docs/requirements.md、docs/environments.md |
| B：本地鉴权约束 | application.yml、AuthJwtProperties.java、AuthJwtPropertiesTest.java |
| C：代理样例 | deploy/frp/frpc.toml、deploy/nginx/scds.conf.example、deploy/README.md |
| D：入口说明/契约 | README.md、.env.example、docs/api/paths/auth.yaml |
| E：公网服务声明 | docs/api/openapi.yaml |
| F：旧回归适配/证据 | SecurityJwtValidationIT.java、docs/architecture.md、deploy/README.md |
| G：最新验证入口 | README.md |

- 鉴权专项17项全通过，其中新增配置测试10项，保留本地RSA签发测试7项。
- 首次专项因PowerShell拆分-D参数未进入测试；完整引号后执行成功，未改全局工具配置。
- 首轮全量568项有1项旧JWKS期望不兼容。改为拒绝配置、回环请求数0后再跑完整clean verify，
  没有跳过或删除该回归；原本地验签/过期/错签矩阵保留。
- 最终mvn clean verify在2026-10-21 15:16:42 +08:00完成，8个reactor项目SUCCESS。
  568项，失败0、错误0、跳过0；37份Surefire XML与testcase节点数一致。
  protocol19、core247、api89、app213；产物scds-app/target/scds-next.jar。
- FRPC 0.61.0缓存镜像在network=none、无宿主机端口发布下verify成功；
  缺控制端口的负向模板校验返回非零，属于预期拒绝。
- Nginx 1.27-alpine缓存镜像使用临时自签测试证书、network=none进行nginx -t，语法检查成功。
  临时证书不用于部署，未下载镜像或建立FRP连接；Windows受策略限制的frpc.exe未绕过执行。
- SnakeYAML2.4禁用重复键，应用与全部OpenAPI路径引用解析成功：2个server、68个operation，
  HTTP11001及forward-headers-strategy=none保持；没有改变请求/响应schema。
- 协议SHA256仍为7CB9B4988B0D6BE64A6CA5A6B4AE91BC8005DA11466727C46B5BF0DD5859BD92。
- 验证后11001/12001无监听，本地MySQL仍healthy，实际.env/.env.test未修改且保持忽略。

以上只证明构建、既有隔离集成回归及代理配置语法，不证明真实证书信任、公网登录、
WS穿透、物理断网或硬件业务可用；正式发布仍须第6节门禁。
