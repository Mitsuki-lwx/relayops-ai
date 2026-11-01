# RelayOps 1.0 运维

## 备份

数据库持久化事实、原RELAYOPS_MASTER_KEY与回调接收端的独立去重库都需要保留。主密钥须通过受控离线方式单独备份，不和SQL一起公开分发。SQL包含账号密码hash及业务密文，仍是敏感资料，文件权限和保存期限由宿主机管理员管理。不要上传至Git或Release附件。

为得到可恢复的明确责任点：暂停新的受理，等待RUNNING/IN_FLIGHT等活跃任务终态；UNKNOWN保留，不能为了清空队列重跑。停止应用，Broker/数据库保留运行。在项目或解压目录运行：

```powershell
node scripts/backup-database.mjs relayops artifacts/backups/manual.sql
```

脚本优先使用ops/mysql-native.cnf及本机mysqldump；可用RELAYOPS_DATABASE_BACKEND=docker显式备份旧容器。单事务、十六进制二进制及主键排序，输出SQL和.sha256，不打印密码或导出正文。拒绝覆盖已有备份，失败保留.partial供排查。随后保留RabbitMQ命名卷、配置和接收端去重库。不要运行compose down -v。

## 恢复

先核对.sql.sha256与文件，使用原密钥和相同/兼容版本。应用保持停止。新建独立空数据库后，向该库导入SQL；不要在未经核对的主库运行导入或DROP。可通过docker exec -i传入备份，由容器MYSQL_ROOT_PASSWORD环境认证，避免命令参数带明文密码。导入后检查Flyway版本、表行数、密文hash及预算/尝试/交付关系，再将DB_URL明确指向恢复库。

先禁用worker/consumer/relay/recovery/retention，以只读方式核对。确认配置及目标后再启用执行器：未SENT责任可恢复；SENT且无确定结果必须保留UNKNOWN/UNCERTAIN，禁止人工把它改回QUEUED。回调确认丢失只能按既有稳定deliveryId去重，恢复接收端去重库后再启用交付。恢复数据库不等于消除已发生的供应商或接收端副作用。

源码仓库可运行`node scripts/test-backup-restore.mjs`，仅从relayops_test备份到本次新建的relayops_restore_时间戳库，对比重新导出的完整有序SQL/hash后删除该新库；不接触主库。此演练证明逻辑数据及密文完整，不代表跨机灾难恢复、备份泄漏防护或所有外部副作用都已恢复。

## 停止、升级与回退

停止新受理并等待活跃责任终态，Ctrl+C停止应用，备份后替换完整运行包；保留.env、主密钥和命名卷。1.0不新增迁移，Flyway V1–V7保持原文件。启动后检查healthz、登录、空间运行概览和安全审计。回退替换旧应用文件，不清空数据库；若未来版本新增迁移，先评估向后兼容，不能盲目回退schema。

## 故障处理

- healthz DOWN/503：检查MySQL容器、13307映射、凭据及磁盘；不要打印.env。Windows端口冲突先检查保留区间，勿删除系统网络保留项。
- Broker不可用：数据库继续有界受理至积压上限，恢复RabbitMQ后由Outbox重发；禁止直接调用模型绕过责任记录。
- UNKNOWN：查看安全错误码和尝试元数据，保留不确定用量；确需新调用时使用明确重跑并确认费用。
- 回调DEAD：检查本机接收端、目的地启用及TTL，按两分钟预览/确认补投；不重跑模型。
- 权限错误：核对当前公司成员、显式小组授权及空间角色；公司ADMIN不自动拥有正文访问。
- 登录：发布包无演示种子；重名初始化不会更改密码。丢失主密钥无法解密旧载荷，不要生成新密钥覆盖旧值。

备份/日志/已下载报告及接收端数据各有独立生命周期，在线七天TTL不能将这些副本撤回。公共网络部署还需独立TLS、边界访问控制、主机补丁和备份保密管理。
