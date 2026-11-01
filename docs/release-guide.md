# RelayOps 1.0.0 单机自托管版

工作台与后端包含在relayops.jar，支持文档分析、引用校验、公司/空间及部门小组授权、可靠异步任务、受控本机回调/补投、运行概览、历史查询和Markdown报告。适用于可信宿主机、单应用实例和受控团队；默认仅监听127.0.0.1:18080。

## 安装

运行需要Java21、MySQL8.0.21+、RabbitMQ和PowerShell7；原生初始化/备份工具需Node22，应用运行不需要Node/Maven/Vite。MySQL可直接使用本机已有服务，Docker MySQL仅为可选开发/CI依赖。保持整个解压目录结构，先按SHA256SUMS核对ZIP。

已有本机MySQL时，将ops/mysql-native.cnf.example复制为mysql-native.cnf，填写本机管理员连接（私密文件不提交）。以下初始化拒绝覆盖已存在的relayops库或relayops_local用户：

```powershell
./scripts/verify-release.ps1
./scripts/init-local.ps1
node scripts/init-native-mysql.mjs
docker compose --project-name relayops -f ops/compose.yml --env-file ops/.env up -d --wait rabbitmq
./scripts/provision-user.ps1 -Username team.owner -CompanyName '我的公司'
./scripts/start-release.ps1
```

初始化命令隐藏读取新账号密码，仅输出userId/tenantId/workspaceId。打开http://127.0.0.1:18080，用新账号登录。发布启动采用release profile，不创建owner.a等演示账号；已有数据库中的账号仍保留。原生MySQL通常为3306，初始化自动更新本地ops/.env的DB_URL/DB_USERNAME；RabbitMQ5672/15672。原主密钥不可重置。

已有本项目Docker数据库需要迁移时，使用`node scripts/init-native-mysql.mjs --migrate-docker`：源容器保留，逻辑导入至不存在的本机relayops库，逐表数据及密文hash一致后才切换配置。若目标库/用户已存在则停止，不能覆盖。核对主服务后可停止原mysql容器，保留原卷。全新环境若选择Docker MySQL，跳过原生初始化并在compose命令中同时启动mysql，默认映射13307；二者择一。

## 加入团队

```powershell
./scripts/provision-user.ps1 -Username team.member -TenantId '<初始化输出的tenantId>'
```

新成员无空间权限。公司管理员在组织管理中新建小组、加入该成员，并以目标空间OWNER显式授权OPERATOR或VIEWER。父部门不继承权限，公司管理员身份不自动获得其他空间正文。用户名3–80位ASCII字母/数字/点/横线/下划线，密码至少12字符且最多72个UTF-8字节，重名拒绝且不重置密码。初始化是宿主机管理操作，没有匿名HTTP入口；宿主机操作者须自行保留操作记录。

## 模型配置

ops/.env默认RELAYOPS_MODEL_MODE=fake，无外部模型费用。真实模式在该文件配置RELAYOPS_MODEL_MODE=compatible、RELAYOPS_MODEL_ENDPOINT、RELAYOPS_MODEL_API_KEY、RELAYOPS_MODEL_NAME，具体网关须兼容Chat Completions结构。密钥不填写到页面或提交Git。重启应用加载配置；新建任务需明确确认文档外发及新调用，UNKNOWN不会自动再次调用。

## 运行与升级

GET /healthz返回版本1.0.0及数据库UP/DOWN；不表示模型、Broker或交付端到端健康。使用页面运行概览核对队列责任和用量。启动脚本保持前台，服务化可使用操作系统管理器，工作目录为解压目录，退出后重新登录。Linux也可通过PowerShell7运行同一脚本，或自行注入所需环境后执行`java -jar relayops.jar --spring.profiles.active=release`。

升级、备份、恢复和故障处理见docs/operations.md。首次部署/升级前保存独立主密钥及数据库备份。应用版本与SHA256校验不是数字签名，校验文件应来自同一个可信发布渠道。

## 版本边界

1.0提供一个文档提取任务类型及OpenAI兼容适配器；不含公司自助注册、邮件邀请、SSO、忘记密码、公司计费、RAG、PDF/OCR、多实例高可用、任意外网回调或任务级私有范围。账号生命周期/密码更换由可信宿主机管理员按独立运维流程管理，初始化命令刻意不覆盖已有密码。回调仅固定本机接收端，运行多实例会带来会话与恢复竞争，不属于本版部署契约。

若经反向代理供远程团队使用，操作者必须配置TLS、访问控制、可信代理和安全Cookie；本版本验证范围为本机同源部署，不承诺公网生产就绪。报告下载副本不受在线TTL/撤权控制；在线清理默认关闭，显式启用仅清除到期载荷，备份需另行管理。
