# 开发、测试与生产环境手册

> 核查日期：2026-10-21。开发环境已实测；生产部分为部署要求与候选流程，尚未发布验证。
> 源代码入口：`e:\DualX\scds-next`。当前状态见 [snapshot.md](../snapshot.md)。

## 1. 当前环境事实

| 项 | 本次实测 |
|---|---|
| 操作系统 | Windows 11 amd64，PowerShell 5，Asia/Shanghai |
| JDK | Zulu 21.0.12.1；`D:\DevTools\zulu21.52.203-ca-jdk21.0.12.1-win_x64` |
| Maven | 3.9.9；`E:\Code\.tools\apache-maven-3.9.9` |
| Git/OpenSSL | OpenSSL 可用；GitHub 前序推送断连，见快照，不代表代码验证失败 |
| Flutter | 可执行文件在 `D:\DevTools\flutter\bin`；未执行doctor，SDK/Android/Windows工具链就绪程度未验证 |
| MySQL | Docker `scds-local-mysql`，mysql:8.4 标签当前内容为 8.4.11，healthy |
| 数据库 | scds_next，应用账号scds_app；监听127.0.0.1:13306 |
| 应用产物 | `scds-app/target/scds-next.jar`，仅构建产物，不入库 |
| HTTP | 本次两种签发配置均在127.0.0.1:11001成功启动；验证结束已停止本次进程 |
| UWB TCP | 12001 未监听；gateway 尚无源码，不是防火墙故障 |
| Flyway | 8份迁移已验证，当前schema版本9，原预留V8未创建 |

本地已存在 `.env`，本次未改动秘密。只核验键名与非空：
SCDS_MYSQL_ROOT_PASSWORD、SCDS_MYSQL_PASSWORD、JWT公私钥文件路径。
**当前没有 SCDS_ADMIN_USERNAME/PASSWORD**；建号功能已实现不等于本机部署管理员已配置。
本次测试中的it_admin账号由测试产生并回滚，不是可交付管理员。

## 2. 环境自检

以下在仓库根工作目录执行，示例不要求修改全局配置：

```powershell
java -version
mvn -version
docker version
docker ps -a --filter name=scds-local-mysql
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object { $_.LocalPort -in 11001,12001,13306 }
git status -sb
```

先确认终端可用、Docker Engine已启动、端口占用者，再执行启动。
不得因端口占用就强杀不明PID；不得重置其他项目MySQL密码或卷。
Trae远程终端依赖电脑端进程持续运行；休眠、关机或断网会使手机端不可用，
保持主机运行并检查客户端连接，不把此现象当成本项目业务错误。

## 3. 开发数据库

`compose.yaml`只部署本项目开发MySQL，端口仅回环绑定，独立命名卷保存数据。
不是生产应用Compose，也没有自动启动Spring服务。

首次克隆且 `.env` 不存在时，才从 `.env.example`建立本地配置并替换占位值；
已有 `.env` 不覆盖。随机口令可使用 `openssl rand -hex 24`，
但不要把实际输出写进日志、文档、聊天或版本库。

```powershell
docker compose up -d mysql
docker compose ps
docker compose logs --tail 30 mysql
```

若DB容器存在但Stopped，优先启动现有容器；healthcheck要求账号与数据库可读。
修改 `.env` 不能改掉已初始化数据卷中的MySQL账号密码，
密码轮换须在DB中明确修改并同步应用配置，禁止删卷冒充修复。
`docker compose down -v`会删除数据卷，本手册不授权执行。

## 4. 本地构建与运行

仓库根执行：

```powershell
mvn clean verify
```

已实测309项测试通过、8个reactor项目成功。`verify`不安装构件到本机Maven仓库；
后续若要在模块目录独立执行spring-boot:run，先按需跑 `mvn install`。
正式验收禁止通过 `-DskipTests`伪装成通过。

推荐使用已经构建的fat jar。现有 `.env` 的JWT路径以scds-app工作目录为基准，
因此：

```powershell
# 工作目录必须是 e:\DualX\scds-next\scds-app
java -jar target\scds-next.jar --spring.profiles.active=local
```

若工作目录为仓库根，须覆盖JWT路径，不能沿用 `../dev-keys`：

```powershell
java -jar scds-app\target\scds-next.jar --spring.profiles.active=local `
  --scds.auth.jwt.public-key-file=dev-keys/dev-jwt-public.pem `
  --scds.auth.jwt.private-key-file=dev-keys/dev-jwt-private.pem
```

local profile显式绑定127.0.0.1，并导入 `.env`/`../.env`。
Profile要通过命令行或进程环境 `SCDS_PROFILE=local` 激活；
仅写进待导入的 `.env` 不会提前激活local。
部署形态SCDS_DEPLOYMENT_MODE与签发能力SCDS_JWT_SIGNING不是Profile。

健康检查：

```powershell
curl.exe -i http://127.0.0.1:11001/actuator/health
curl.exe -i http://127.0.0.1:11001/api/v1/exercise/current
```

当前期望health=200 UP，无令牌业务接口=401 Problem。
通过鉴权的占位接口仍是501，不应将“能启动”写成“可以开演”。
停止本次开发实例使用它所在终端的Ctrl+C，查看资源关闭；不要杀全部java进程。
目前仅HTTP graceful与DB连接池存在，不能宣称已验证串口/WS/队列flush。

## 5. 配置与凭据登记

| 配置 | 作用 | 注意事项 |
|---|---|---|
| SCDS_PROFILE | Spring环境 | local只供本机；生产无默认Profile或外部文件自动导入 |
| SCDS_DEPLOYMENT_MODE | PRIVATE/CLOUD部署登记 | 当前没有业务消费者 |
| SCDS_MYSQL_USER/PASSWORD | 应用库身份 | 不使用root；生产单独账号、库及权限 |
| SCDS_JDBC_URL | 连接串覆盖 | 必须含rewriteBatchedStatements=true；不要将生产库提供给IT |
| SCDS_MYSQL_ROOT_PASSWORD | 开发容器初始化/运维 | 应用不使用，已建卷改env不改密码 |
| SCDS_ADMIN_USERNAME/PASSWORD | 首次建管理员 | 明文只在受保护部署配置；应用BCrypt，不写迁移seed |
| SCDS_ADMIN_DISPLAY_NAME | 初始显示名 | 可选，空时回退用户名 |
| SCDS_JWT_SIGNING | 当前全部三类令牌的签发能力 | false不读私钥；设备独立签发策略待裁定 |
| SCDS_JWT_ISSUER | JWT实例issuer | 签发/验签双方一致；当前允许非URL实例标识 |
| SCDS_JWT_PUBLIC_KEY[_FILE] | 单把本地验签PEM | X.509 PUBLIC KEY；优先内联后文件，生产推荐绝对文件路径 |
| SCDS_JWT_PRIVATE_KEY[_FILE] | 签发私钥 | 未加密PKCS#8 PRIVATE KEY；生产每客户不同、不可入库 |
| SCDS_JWKS_URI | 远端JWKS路径 | 非空会切到远端验签，无本地兜底；离线现场必须留空 |

PEM文件避免将多行内容塞进 `.env`。Spring将其按properties导入，
反斜杠/转义/注释字符与Docker env语义不完全相同；hex口令及路径正斜杠较易跨工具一致。
配置源优先级与运行目录应在启动日志/部署清单记录，不能靠“两个optional导入都在”保证正确。
ttl、串口、UWB等还可通过Spring参数设置，但硬约束端口不得改。

管理员确认建立后移除部署配置中的明文PASSWORD，重启不得覆盖已有账号。
实际账号管理/口令修改API尚未提供；不要用未经实现的接口作为运维方案。
保管凭据、变更已有部署账号都需明确授权。

## 6. 生产准入与部署形态

**当前禁止按生产成品交付**。缺应用部署脚本、业务闭环、设备接入、
安全回归、客户端与容量/恢复验证。下列内容是下一阶段部署规范，不是已存在自动化。

候选正式业务承载节点为Mini PC/现场主机，ECS作为公网入口；
当前开发仍在本机，无本次远程节点连通性、容器或云配置核验。
PRIVATE为离线/现场独立业务实例，CLOUD为客户独立云实例，不引tenant_id。

| 准入项 | 生产要求 |
|---|---|
| 系统 | 经硬件驱动验证的OS + Java21；Windows COM或Linux串口权限必须实测 |
| 进程 | 同客户同库单业务进程，受服务管理器托管；禁止两实例同时启动收尾 |
| 服务地址 | 显式指定绑定地址与防火墙，内部11001不变；不得误用local导致只监听回环 |
| 数据库 | 受支持且经过Flyway升级验证的MySQL8.4补丁/镜像digest，独立客户库 |
| DB网络 | 仅内网/容器网或回环；不得暴露13306到公网 |
| JWT | 每客户独立PEM，最小文件权限，公钥分发/issuer一致，禁用公开dev-keys |
| TLS | 公网入口HTTPS/WSS，后端11001内网；JWT签名不是传输加密 |
| UWB | 12001仅可信现场硬件网段，不能直接公网暴露未认证原始TCP |
| 代理 | 可信代理白名单，覆盖外来XFF/Forwarded；不得直接信任客户端头 |
| 文件 | 指定底图/导出持久目录或对象存储，上传大小/类型/路径/下载授权均测试 |
| 观测 | health、日志traceId、DB池、队列、WS背压、链路坏帧、心跳失败；指标仅ADMIN |
| 备份 | DB + 文件资产 + 恢复所需安全配置；离线加密保存私钥，恢复演练，不只“备份成功” |
| 发布 | 制品hash、commit、迁移清单、配置校验、回滚方案与现场验收记录 |

### 6.1 外部配置示意

生产不使用开发 `.env` 隐式导入。显式配置位置，例如Linux部署：

```bash
java -jar /opt/scds/scds-next.jar \
  --spring.config.additional-location=file:/etc/scds/application.properties
```

Windows使用实际绝对位置，例如 `file:E:/ScdsConfig/application.properties`。
该文件必须真实提供DB秘密、绝对PEM路径、server.address与形态/能力；
仓库当前没有该文件、prod profile、systemd/Windows Service安装脚本。
示意properties不含真实密码：

```properties
server.address=127.0.0.1
scds.deployment-mode=PRIVATE
scds.auth.jwt.signing-enabled=true
scds.auth.jwt.public-key-file=/etc/scds/keys/jwt-public.pem
scds.auth.jwt.private-key-file=/etc/scds/keys/jwt-private.pem
spring.datasource.url=jdbc:mysql://127.0.0.1:13306/scds_next?rewriteBatchedStatements=true
spring.datasource.username=scds_app
# spring.datasource.password 由受保护配置/部署环境提供
```

示例的回环地址只适合反向代理/同机接入；直连LAN现场要换为经批准的监听地址，
但不改端口。不能将这一示例原封不动视为任何客户的真实生产配置。

### 6.2 密钥

公开dev-keys仅本机开发。生产生成候选命令，输出路径需受保护并经批准：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
```

Linux私钥建议0600、部署目录0700；Windows按服务账号配置ACL，不给Everyone读取。
当前支持单把公钥；换钥要安排停机/重登窗口，没有自动平滑轮换或公私钥配对检查。
不许把生产私钥提交到dev-keys，也不在文档内收录私钥内容。

### 6.3 发布、备份与回滚

1. 在隔离测试库全量verify，记录制品SHA256、commit、测试矩阵与容量参数。
2. 备份客户DB和底图/导出资产，实际恢复到另一库检查完整性；RPO/RTO及保留期待确认。
3. 停止接新演习，完成在途任务与停演，停止唯一业务进程，再升级制品与配置。
4. Flyway先validate并执行递增迁移。已应用V9后不能直接新增V8，更不能删除历史checksum。
5. 在不对外开放的状态下验health、安全、DB版本、账号/密钥、硬件端口与业务冒烟。
6. 门禁满足才开放入口；异常时停止新版本，按已演练的数据库/文件恢复方案回滚。

当前没有downgrade迁移；破坏性DDL后的“只换回旧jar”不是可用回滚。
禁止生产中执行clean、删卷、Flyway repair或手改schema_history来绕过校验。

## 7. 已知告警与排障边界

| 现象 | 当前结论与处理 |
|---|---|
| 未提供 SCDS_MYSQL_PASSWORD / PEM | 配置缺失；显式环境与运行目录核对，不内置默认秘密 |
| DB Exited / unhealthy | 检查Docker Engine、现有容器和凭据，不删卷或重置其他项目 |
| Flyway建议升级告警 | 当前Flyway声明的已测MySQL上界低于8.4；本机已成功，不等于正式支持保证 |
| Mockito动态agent告警 | 当前Java21测试通过；后续升级JDK前配置测试agent，不按生产故障处理 |
| auth仍501 | 控制器尚未接核心，不能通过重启“修复” |
| signing=false下login401 | 已实测设计缺口，不是错误登录凭据 |
| 12001不监听 | gateway尚无代码；参数存在不代表能力已实现 |
| GitHub推送失败 | 保留本地提交和未提交文档，核验远端后再同步，不reset或force push |
