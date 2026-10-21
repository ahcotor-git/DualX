# 新对话启动提示词

以下文本可直接作为新对话第一条消息。快照是截至2026-10-21的状态，
新对话必须先核对工作树和环境；不要把本文件当成实施所有工作包的授权。

```text
继续 e:\DualX\scds-next 项目，使用简体中文。先阅读以下文件：
1. snapshot.md
2. docs/review.md、docs/requirements.md、docs/architecture.md
3. docs/environments.md、docs/testing.md
4. docs/design/01-exercise-liveness.md、02-push-ports.md、03-auth.md
5. docs/api/openapi.yaml及下一任务涉及的paths/schemas；协议以docs/PROTOCOL.md为准。

先检查终端、git status/diff/log、JDK/Maven、Docker/MySQL和11001/12001/13306。
检查.env只输出键名/是否存在，不显示秘密。不能覆盖用户改动、重置库或杀不明进程。
以实际pom/代码为准：Java21、Boot3.5.7、Flutter Android/Windows目标，
每客户独立实例和数据库，无tenant_id、ORM/JPA、Redis、Kafka。

交接时HEAD为fae966dafeaf6d994b42d33f5a0b0bbce85f068f
（`docs(auth): 回写 D01/D04 与 R27 的落地口径`），本地跟踪的origin/main为44fa450，
即HEAD领先4个提交且本轮未联网核验远端；LEDGER全部改动仍在工作树、尚未提交。
先git status/diff/log核对，不要把工作树成果当成远端已有，不默认后续commit/push。

已完成AuthService登录/刷新/全端退出/me、DeviceAuthService设备登录与SID单端在线、
服务端写权限矩阵、TTL与限流配置校验、SEC-01/SEC-02/ERR-01、TEST-01（隔离库+完整应用启动），
以及M2 LEDGER（马甲台账、UWB基站、D03-4绑定闭环、绑定路径ADMIN写权限）。
最近mvn clean verify为681项全通过（19+281+100+281），35份XML计数一致，
证据见testing第11-12节；契约计数已修订为69 REST + 2 WS。
不要重复实现AuthService、DeviceAuthService、metrics保护、JWT字段/时间分类、MVC Problem或LEDGER。
device域10条已接线，但链路开关、串口枚举、device/me及其余exercise/command/situation/map/replay
业务方法仍占位；gateway零源码；演习自愈、态势/推送和Flutter尚未实现，当前不可生产发布。

review/架构/专题设计中R01-R04等是历史基线；最新修复证据以testing第7-12节为准。
R05 signing=false账号login返回401而非目标404、R07设备SID仍未实现（device/me仍501）。
其他静态风险见review，不要冒充已复现或自动标为完成。

下一任务为M2 MAP-BASE（地图CRUD、图片、楼层点位、引用与文件安全），
前置D09、D15仍待确认，不得默认采用推荐方案；
提出最多3文件方案并等待确认后实施。LEDGER工作树成果的提交/推送须先取得当次授权。
本条只授权检查与提出下一原子任务方案，不授权一次性修所有问题。
每阶段按检查、分析、决定、执行；每原子任务<=3文件，修复最多5轮。
获授权后实现、测试、独立提交；推送也需授权，失败保留本地成果如实报告。

硬约束：
HTTP11001、UWBTCP12001；WS已连仍低频Poll，二者同构。
最后心跳<=5000ms在线；阵亡冻结最后有效坐标，motion=FROZEN，判活才解除。
马甲串口/UWB41B逐字节和UwbSolver/UwbFilter算法及参数冻结。
gateway唯一硬件事件源，与core不得直接互依，domain定义SPI。
时间RFC3339 UTC、DB datetime(3)、t_ms INT；错误RFC7807含code/traceId。

已确认：refresh按原文SHA-256的token_hash查库，core不解析JWT；
logout无请求体、撤该账号全部refresh；accessToken无主动撤销。
管理员由部署.env明文首次BCrypt建号、不写seed；不擅自改实际.env。
device/me本阶段留501；自愈先SYSTEM存证再删残留且同事务，
SYSTEM为非协议日志类型，不能改冻结协议LogType。
D14已确认同容器独立scds_next_test/scds_test，精确转义库名授权，无开发库权限。
核心IT只导入忽略的.env.test，不导入开发.env；独立测试口令owner-only ACL，
禁止打印秘密、覆盖凭据或重置账号。Initialize-TestDatabase.ps1仅首次建库，
已有库/账号/文件一律拒绝覆盖；默认保留库，测试仅清理自身UUID。
新测试库已从空schema应用V1-V7/V9；Flyway clean禁用、out-of-order=false；
开发/测试account、refresh、exercise运行后0，迁移校验和8项一致。
核心隔离回归36项（初始化前29、真实DB7）与TEST-01完整应用启动（37项）均已完成；
M0不再有未完成的测试子任务。

待确认：只验签实例是否独立签发设备token；V9已应用后自愈是否改V10；
链路数组/信封同构；跨重启revision/epoch；WS超窗409/close；
完整权限/单兵可见性；UWB SERIAL与开停演绑定；容量、文件、RPO/RTO；
t_anchor与t_map.uwb_meta双源如何统一。不能替我默认确认，
也不能补V8、改已应用SQL、开启out-of-order或清库绕过问题。

先简短核对新旧状态差异，再给M2 MAP-BASE的<=3文件方案并等待确认。
```
