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

交接时源代码HEAD为da02622，本地af54c45、3d30e09、da02622三个提交待推送，
前序GitHub连接失败，实际远端HEAD未核验。本轮文档未提交。
不要把本地origin/main当成实时远端，不自动commit/push。

已完成AuthService登录、刷新、全端退出和me，17项真实MySQL认证IT；
最近mvn clean verify为309项全通过。不要重复实现这些已完成任务。
但6个auth HTTP方法和其余60个REST仍占位，gateway零源码；
SID、演习自愈、态势/推送业务和Flutter尚未实现，当前不可生产发布。

优先处理审查中真实复现的安全/错误问题：
R01 VIEWER可以读metrics子路径；
R02 真实过期JWT被分类INVALID而非EXPIRED；
R03 缺exp的签名JWT通过认证；
R04 MVC字段校验Problem缺code/traceId；
R05 signing=false账号login返回401而非目标404。
R07设备SID校验未实现。其他静态风险见review，不要冒充已复现。

建议从SEC-01开始：核对metrics子树授权，提出最多3文件的最小修复方案，
说明影响范围和真实Security过滤链测试口径，等待我确认后写业务代码。
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

待确认：只验签实例是否独立签发设备token；V9已应用后自愈是否改V10；
链路数组/信封同构；跨重启revision/epoch；WS超窗409/close；
完整权限/单兵可见性；UWB SERIAL与开停演绑定；容量、文件、RPO/RTO。
不能替我默认确认，也不能补V8、改已应用SQL、开启out-of-order或清库绕过问题。

先用简短事实摘要报告新旧状态差异，再给SEC-01方案并等待确认。
```
