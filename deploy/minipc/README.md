# Mini PC 独立验证实例

本目录部署当前后端基础工程，不是现场生产发布。业务HTTP仍有501占位，
gateway及Flutter未实现；健康正常不等于能开演或完成账号/设备HTTP登录。
同机ZAA不停止、不更新、不共享数据库/凭据；SCDS公网入口本次不启用。

## 1. 目标布局

| 项目 | 位置/策略 |
|---|---|
| 主机 | Mini PC 192.168.110.110的WSL2，SSH2222 |
| 独立根目录 | /opt/scds，root管理 |
| Docker项目 | scds-validation |
| 容器 | scds-validation-backend、scds-validation-mysql |
| 数据卷 | scds-validation_mysql_data，从空库开始，不导入开发数据 |
| HTTP | 仅WSL回环127.0.0.1:11001，不自动配置Windows转发或LAN防火墙 |
| MySQL | 仅独立Docker网络3306，不发布13306/3306到宿主机 |
| 硬件 | 不发布12001，不映射串口；gateway当前为空 |
| 密钥/配置 | /opt/scds/secrets，目录0700；业务文件仅以只读绑定挂载提供给容器 |
| 发布证据 | /opt/scds/.deploy-state及releases/<commit>/归档 |

后端运行UID/GID10001，根文件系统只读、临时目录有界、禁提权和额外能力，
DB与后端各最多768MiB/1CPU，日志轮转10MiB×3。镜像及MySQL均禁止自动拉取。
端口只在WSL回环可达，不把它写成已开放的192.168.110.110:11001 LAN入口。
需要LAN/TLS、真实硬件映射或FRPC时另行核查宿主机与资源后部署。

## 2. 制品与首次部署

1. 从隔离库全量验证的JAR构建镜像；Docker构建上下文只放scds-next.jar，不包含.env或dev私钥。
2. 使用完整提交号作为scds-next镜像tag及revision标签；保存镜像与该提交源码归档。
3. 通过既有SSH信任传输，只创建未占用的/opt/scds；核对归档SHA256后load镜像。
4. 将本目录compose.yaml、deploy.sh、Dockerfile传入根目录，确保源码与提交归档一致。
5. 管理员执行脚本，传入完整提交号及JAR SHA256：

```bash
bash /opt/scds/deploy.sh <full-commit-sha> <jar-sha256>
```

脚本先检查版本、权限、镜像及JAR校验和，再检查容器归属、卷和端口。
凭据初始化单段3文件：.env、RSA私钥、公钥；下一段生成application.properties；
健康/建号验证后移除运行配置的管理员密码，仅保留root-only恢复凭据。
任何部分初始化或无状态的已有卷均拒绝，不删除重试、不旋转已有秘密、不重置已有账号。
业务绑定的秘密文件组权限仅供容器10001读取；恢复.env保持0600，不进入Git/日志。

## 3. 验证与后续更新

- Docker两容器health均healthy，HTTP health=200 UP；MySQL迁移V1-V7/V9成功，DB会话UTC。
- 无JWT的me/metrics等受保护入口401且Problem包含code/traceId；有效DTO触发501不能写成登录成功。
- 初始admin只建一次、角色ADMIN、BCrypt cost10；运行配置无建号明文口令。
- 从宿主机确认无MySQL/UWB公网发布，后端非root；同时检查ZAA后端UP、Web200。
- 重启/更新只操作scds-validation；持久卷保留，旧密钥不覆盖，版本和数据库兼容先核查。
- 不执行down -v、remove-orphans、数据库clean/repair或账号重置；首发失败保留证据供核查。

后续部署既有实例时脚本保留凭据和账号；没有DDL降级或自动数据库回滚，
不可把换回镜像当成已验证的完整恢复。生产前还需备份/恢复、现场硬件、客户端和容量门禁。
FRPC与云端站点按[公网入口说明](../README.md)另行分配资源，不能复用/改动ZAA配置。

## 4. 本次部署记录（2026-10-21）

已在Mini PC完成独立验证部署，状态时间为2026-10-21T07:33:59Z。
实际运行镜像对应提交`6477c0e3ae1d8950bfba3ddf289aaa7bf1e33431`；
后续验收脚本/本文档的提交不改变运行镜像版本。

| 制品 | SHA256 |
|---|---|
| scds-next.jar | 755f77388e4a355aaa555c60e664b30fb82b17f7d5f1240208696f805d4061e4 |
| scds-image.tar | ef42553aaf4c11383317860b62227d96bb9e19468a806d33a370eb005fa6b77e |
| source.tar | 04bf93bb8b37396e3275fa42de771df49f678150e86185093a7fa8a4b447465d |

归档在远端校验后离线load；镜像revision、JAR标签和容器内实际JAR均一致。
远端image ID为`sha256:6f06e893658b91a771fa3dcf40c83d2042bb26bfaa0519b650fbb468232f4aa0`。
本次只新增验收脚本和说明，不修改Java源码；JAR沿用部署前568项全量通过的制品，
本次完成的是以下真实部署验收，不声称重新执行了568项测试：

- 两容器healthy；health=200；数据库8份迁移V1-V7/V9成功、19张表、会话UTC。
- 仅1个启用admin及ADMIN角色，密码为BCrypt cost10；refresh和exercise均0。
- 初次建号后后端单独重建，运行配置管理员密码为空；恢复凭据仅在root-only .env。
- 无JWT访问me/metrics为401；无效JWT为401；ADMIN访问metrics为200、VIEWER为403。
- 带有效JWT访问me仍501；空登录DTO为400、有效形状DTO仍501，认证业务HTTP尚未接线。
- 所有错误响应均为Problem JSON并含code/traceId；只读验收前后业务计数不变。
- UID/GID10001、只读根文件系统、版本/实际JAR及秘密文件权限均通过verify.sh检查。
- 后端仅127.0.0.1:11001；SCDS MySQL无宿主映射，12001/13306无监听。
  宿主既有127.0.0.1:3306属于ZAA，不是本次新发布的端口。
- ZAA后端仍UP、Web200、其余服务保持运行；ZAA FRPC部署前已Restarting，本次未修复或改动。

验收脚本在内存中通过本地私钥签短时测试令牌，不打印令牌或私钥、不写refresh记录：

```bash
bash /opt/scds/verify.sh
```

该脚本只用于当前空业务数据验证实例，预期1账号/0刷新/0演习；
以后开展业务后应先调整验收前置条件，不能为满足断言清理数据库。
本次未启用Windows LAN转发、FRPC公网入口、TLS站点或硬件映射，
也未做备份恢复、容量和现场验收，不能作为生产发布许可。
