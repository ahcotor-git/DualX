# scds-next 需求基线

> 核查日期：2026-10-21，Asia/Shanghai。代码基线：`da02622`。
> 本文整理已确认需求与待确认边界，不把历史资产盘点中的候选功能自动带进产品。
> 当前状态从 [交接快照](../snapshot.md) 读取；发现的问题见 [审查清单](review.md)。
> 最新部署裁定见[架构第0节](architecture.md)：本地全业务，云端仅反向代理/FRPS。
> 此裁定替代旧云签及双签发方案；历史交接中的云端身份同步待办不再是实施前置。

## 1. 产品范围

scds-next 对旧 DualX/SCDS-UWB 系统进行全量重写，不在旧工程上增量修补。
服务于实兵演习现场的指挥台与单兵终端，保留马甲、室内 UWB/点位及室外 GPS 业务。

| 项目 | 已确认边界 |
|---|---|
| 后端 | Java 21、Spring Boot、多模块 Maven；当前 Boot 版本实际为 3.5.7 |
| 前端 | Flutter，Android 先行、Windows 桌面；仓库尚无 Flutter 工程 |
| 部署 | 每客户独立本地实例、数据库和密钥；开发机验证后迁入Mini PC/现场主机 |
| 公网入口 | 本地FRPC主动连接云端FRPS；云端Nginx仅HTTPS/WSS反向代理，不承载业务/数据库/账号签发 |
| 硬件 | 马甲串口与 UWB TCP 12001；契约还提供 UWB SERIAL 模式，详见待确认项 |
| 数据 | 空库 + Flyway；不迁移历史业务数据，不复用旧库默认账号与密码 |
| 排除 | 刺杀、MQTT、IM/融云、奥维、ORM/JPA、Redis、Kafka、tenant_id 多租户 |
| 不在本批范围 | 云管 License/OTA、账号注册/找回、第三方 SSO、iOS/macOS/Linux 发布 |

旧迁移手册中的 JDK 11、Boot 2.7、MyBatis、Electron、默认 root 口令和恢复旧 dump，
均不是新项目的环境要求。不得照抄旧文档的删 Flyway 历史、强杀端口或硬编码凭据操作。

## 2. 角色与安全边界

| 身份 | 目标能力 | 当前实现程度 |
|---|---|---|
| ADMIN | 管理员全权限、受保护运维指标 | 静态权限表和安全链存在；指标子路径保护有缺口 |
| DIRECTOR | 演习生命周期、指挥、态势与复盘 | 权限点映射存在，业务服务和写接口授权待实现 |
| OPERATOR | 指令与链路操作、态势读取 | 同上，不能以 UI 显隐替代服务端授权 |
| VIEWER | 态势、复盘、地图、设备只读 | 同上 |
| 单兵设备 | 按绑定身份及授权范围读取状态，重复登录顶掉旧令牌 | SID 列/仓储存在，登录、校验与字段裁剪尚缺 |

账号和设备同用 Bearer，按 aud 区分；设备不得访问指挥台接口或统一态势 WS。
角色映射暂保留通配字符串；具体写接口的权限矩阵须确认后实现。
旧系统“自己、己方全队、敌方仅阵亡”的可见性是业务参考基线；
当前契约只有单马甲查询入口，队伍级单兵视图与裁剪细节尚须确认，不得自行扩大可见范围。

## 3. 功能需求与追踪

| ID | 需求与主要验收点 | 契约 | 当前状态 |
|---|---|---|---|
| FR-AUTH | 账号登录、刷新、全端退出、me；失败限流、刷新撤销持久化 | `api/paths/auth.yaml` | 核心服务已实现，6 个 auth HTTP 方法仍占位 |
| FR-DEVICE-AUTH | 马甲编号 + 绑定凭据登录，SID 单端在线、换绑失效 | 同上 | 会话仓储已实现，服务与校验未实现 |
| FR-PLAN | 想定 CRUD、编成整体替换、XLSX 导入/模板；整文件校验后落库 | `api/paths/exercise.yaml` | 仓储有实现，业务与接口占位 |
| FR-EXERCISE | 同时仅一场；开演快照、停演统计冻结、复盘同 ID | 同上 | DB 唯一索引与仓储存在，生命周期服务未实现 |
| FR-RECOVERY | 重启时收尾全部进行中场次；SYSTEM 存证后清残留指令 | `design/01-exercise-liveness.md` | 仅设计；字段、非协议日志类型、恢复门禁未实现 |
| FR-COMMAND | 判死/活/伤、越界、充弹、参数轮询与联调置位 | `api/paths/command.yaml` | 下行字节编码与队列仓储存在，发送编排未实现 |
| FR-SITUATION | 状态、队伍汇总、命中流水、增量 revision、阵亡冻结 | `api/paths/situation.yaml` | DTO/契约存在，状态所有者与查询未实现 |
| FR-PUSH | 态势及链路 WS + Poll，同构载荷、首帧、补齐、背压、保活 | 同上与 `device.yaml` | WS 只有 PING/PONG；链路 JSON 根类型存在设计冲突 |
| FR-LEDGER | 马甲台账、UWB 基站、链路开关及状态、串口枚举 | `api/paths/device.yaml` | 台账/基站/绑定闭环已实现；链路开关与串口枚举未实现，gateway 无源码 |
| FR-MAP | 室内底图/室外 GPS、图片、楼层点位、UWB 标定与校准 | `api/paths/map.yaml` | 仓储/DTO存在，文件与校准流程未实现 |
| FR-REPLAY | 列表、时间轴分片、战损统计、CSV/XLSX 异步导出 | `api/paths/replay.yaml` | 轨迹/任务仓储存在，统计及导出业务未实现 |
| FR-CLIENT | Android/Windows 登录、配置向导、实时地图、断线恢复、复盘 | 全部边缘契约 | 尚无前端工程 |

接口计数：71 个 operationId = 69 REST + 2 WS 握手。
`ApiSkeletonSmokeIT` 的 70 项用例 = 69 路由 + 1 统一 501 响应测试，不能写成 70 REST 接口。

## 4. 不可变约束

| ID | 规则 | 判据 |
|---|---|---|
| INV-PORT | HTTP 固定 11001；UWB TCP Server 固定 12001 | 配置、监听、启动/停止集成测试 |
| INV-PROTOCOL | 马甲与 UWB 逐字节冻结，两类 Framer 不共用 | [PROTOCOL.md](PROTOCOL.md)、黄金样本 |
| INV-UWB | 41B；ranges[i] 对应 bs(i+1)，偏移 7+4*i，小端 int32 | 原文与 3000 帧独立重算 |
| INV-ALGORITHM | UwbSolver/UwbFilter 算法及配置默认值保持原基线 | 迁移差异核查 + 待补数值对拍 |
| INV-FREEZE | 阵亡停最后有效坐标，motion=FROZEN，后续坐标丢弃；判活才解除 | 服务状态转换测试，尚缺 |
| INV-ONLINE | 最后心跳距今 <=5000ms 在线，否则离线；不由链路 OPEN 推导 | 可控时钟边界测试，尚缺 |
| INV-DUAL | WS 连接后仍维持 Poll；常规约 2s、WS 兜底不超过 10s | 客户端断网/半开/恢复测试，尚缺 |
| INV-TIME | HTTP RFC3339 UTC；数据库 datetime(3) 约定存 UTC，t_ms 为 INT | 序列化与跨时区 DB 回归 |
| INV-ERROR | RFC7807 Problem + code/traceId，字段错误含 errors | 安全链、MVC、404/405/上传错误全路径 |
| INV-ARCH | gateway/core 互不依赖，SPI 定义在 domain，gateway 唯一硬件事件源 | 模块依赖与集成测试 |
| INV-DB | 真批量写入要求 JDBC rewriteBatchedStatements=true | URL 检查及真实批量性能测试 |
| INV-STOP | graceful shutdown；HTTP、WS、串口、TCP、队列各自释放 | 停机/立即重启/异常中断专项 |

马甲上行是 ASCII HEX，下行是二进制；“逐字节冻结”不等于禁止迁移 package/import，
但禁止改变校验、偏移、枚举编码、字节序、算法运算或调优默认值。
t_ms 的有符号 INT 上限约 24.85 天，不擅改 BIGINT。

## 5. 已确认的工程决策

1. scds-domain 承载共享领域模型、端口与错误码；BusinessException 从核心抛出，api 明确映射状态。
2. refresh 通过原文 SHA-256 的 token_hash 定位库行，不让 core 引入 JWT decoder。
3. logout 不加请求体，撤销该账号全部 refreshToken；accessToken 不做主动撤销。
4. 初始管理员由部署配置明文 `.env`，应用首次启动 BCrypt cost 10 建号，不写 Flyway seed。
5. 开发使用公开固定测试 JWT 密钥；生产必须每客户独立密钥。
6. 当前阶段 device/me 保留 501，等态势服务再接线。
7. 僵尸演习在 t_exercise_log 写 SYSTEM 证据，再删除该场残留指令；协议 LogType 不新增值。
8. 谁持有状态谁调推送端口：链路归 gateway，态势归 core。
9. 每阶段先方案与影响范围、用户确认后改业务代码；每原子任务 <=3 文件，最多 5 轮修复尝试。
10. 已获阶段提交授权的任务执行“实现、测试、独立提交、推送”；不能把推送失败写成已同步。
11. 本地统一签发账号及设备令牌，auth/me读取本地账号库；不新增云端账号同步、双issuer或云端刷新。
12. 断外网只影响公网入口，不关闭本地业务或阻断LAN账号登录/刷新；数据库与硬件仍须本地可用。
13. 仅HTTP/WS入口可穿透；MySQL、UWB原始TCP、串口与运维指标不得公开。

## 6. 待确认需求

| ID | 问题 | 决策后影响 |
|---|---|---|
| Q-DEVICE-SIGN | 部署方向已裁定：本地统一签发账号/设备；仍需完成设备绑定与SID业务 | 不再因云边身份分离新增设备签发开关；复用本地signer，绑定管理仍另行确认 |
| Q-MIGRATION | 已应用 V9 后，原拟 V8 自愈迁移是否改用下一版 V10？ | 不得默默开启 out-of-order、改已应用 SQL 或重建库 |
| Q-LINK-PAYLOAD | 链路两通道统一数组快照还是共同信封？ | WS schema、push port、首帧及客户端 |
| Q-REVISION | 跨重启 revision 用 epoch 还是每次重连强制全量？ | 契约与客户端恢复 |
| Q-WS-CLOSE | 超窗在握手前 409，还是升级后 close 4000？ | 不得以候选设计覆盖冻结契约 |
| Q-PERMISSION | 完整角色/写接口权限表与单兵可见性 | 服务端授权与客户端显隐 |
| Q-CAPACITY | 最大马甲/基站/指挥台/单兵数、帧速率、延迟、保留期、备份 RPO/RTO | 队列、线程、DB容量、压测及生产发布 |
| Q-LINK-LIFECYCLE | UWB SERIAL 交付范围、TCP 与开停演的绑定方式、连接数上限 | gateway 缺 UWB SERIAL 端口，契约却允许 |
| Q-LEGACY-RULE | 手动判活保护、迫击炮 20m、断联300s停演、BD09地图等旧业务规则是否完整保留 | 必须从旧业务实现和产品决定核对，不只照搬旧资产表 |
| Q-FILE | 底图与导出文件本地目录还是对象存储、保留期及授权下载 | 文件端口、部署卷、备份与安全 |

以上仅是待确认清单，不是本次自动添加的新需求。

## 7. 验收与交付边界

每项功能须有“需求 ID、契约、实现入口、测试证据、环境、状态”记录。
上线要求不止 BUILD SUCCESS，还要通过安全、硬件、双通道恢复、异常停演、备份恢复、
客户端及生产模式专项。详见 [测试计划](testing.md) 与 [环境手册](environments.md)。
当前只能认定为可构建、部分基础能力已验证的后端工程，不能认定为可投入现场的产品。
