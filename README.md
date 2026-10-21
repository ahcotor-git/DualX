# scds-next

旧DualX/SCDS-UWB的全量重写工程：Java21 + Spring Boot3.5.7后端，
Flutter Android/Windows客户端目标，每客户独立实例与数据库。

**部署架构：全部业务本地运行，云端仅Nginx/FRPS公网入口，本地FRPC主动穿透。**
账号、设备令牌和refresh由本地统一签发，auth/me读取本地账号库，无云端账号同步。
现场客户端可走LAN，远程客户端走HTTPS/WSS；断外网不影响本地登录和刷新。
最新[架构决定](docs/architecture.md#0-本地部署拓扑已确认)取代旧云签及双签发候选设计。

**当前是可构建的后端基础工程，不可生产发布。**
协议、仓储及账号核心已有测试，HTTP业务仍占位，gateway和Flutter尚未实现。
最新完整构建568项测试全部通过，含隔离数据库、安全链及完整启动回归；
FRPC/Nginx仅离线配置校验通过，不代表公网、硬件或演习闭环验收通过。
本轮分段修改与证据见[部署验证记录](deploy/README.md#7-本轮执行证据)。

## 文档入口

| 环节 | 文档 |
|---|---|
| 当前状态与新会话交接 | [snapshot.md](snapshot.md) |
| 新对话提示词 | [next-chat-prompt.md](docs/next-chat-prompt.md) |
| 产品需求、已定边界、待确认点 | [requirements.md](docs/requirements.md) |
| 模块架构与分阶段方案 | [architecture.md](docs/architecture.md) |
| 开发/测试/生产环境及部署门禁 | [environments.md](docs/environments.md) |
| 本地FRPC、云端Nginx与资源隔离样例 | [deploy/README.md](deploy/README.md) |
| 测试证据、矩阵与发布门禁 | [testing.md](docs/testing.md) |
| 审查问题与后续工作包 | [review.md](docs/review.md) |
| 演习自愈设计 | [设计01](docs/design/01-exercise-liveness.md) |
| 状态推送设计 | [设计02](docs/design/02-push-ports.md) |
| 鉴权设计 | [设计03](docs/design/03-auth.md) |
| HTTP/WS唯一契约入口 | [openapi.yaml](docs/api/openapi.yaml) |
| 冻结硬件协议原文 | [PROTOCOL.md](docs/PROTOCOL.md) |

专题设计标注“已确认”“候选”“未实现”，不能直接视为生产能力；
旧鉴权专题的云签/双issuer方案已经由上述最新本地部署决定替代。
代码/契约发生变化后同步需求、设计、测试与snapshot，不重复维护第二份接口定义。

## 本地启动

先按环境手册确认JDK21、Maven、Docker、已有.env和本项目MySQL。
不要覆盖已有凭据或删除数据卷。仓库根执行：

```powershell
docker compose up -d mysql
mvn clean verify
```

然后在`e:\DualX\scds-next\scds-app`目录启动：

```powershell
java -jar target\scds-next.jar --spring.profiles.active=local
```

该运行目录对应当前.env的开发PEM相对路径；仓库根启动的覆盖参数见环境手册。
健康检查`http://127.0.0.1:11001/actuator/health`；认证后业务仍可能501。
UWB TCP固定12001但当前gateway为空，不会监听。
公开dev密钥仅开发，生产本地每客户独立密钥；实际.env绝不提交。
业务部署显式开启SCDS_JWT_SIGNING，远端JWKS非空配置拒绝，不读取云端密钥。
FRPC/Nginx目前提供样例，尚未部署、分配公网资源或验证真实隧道；MySQL/UWB不穿透。

## 协作约束

每业务阶段先方案与影响范围、确认后实施，原子修改<=3文件。
保持协议/算法冻结、gateway/core通过domain SPI解耦、
WS+Poll同构与持续兜底、5000ms心跳在线和阵亡坐标冻结。
不擅改历史迁移、不清库、不覆盖用户改动；提交/推送须明确授权。
