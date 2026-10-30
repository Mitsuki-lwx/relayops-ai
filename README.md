# RelayOps

AI 技术文档任务与结果交付平台。当前链路：登录 → 空间权限 → 幂等受理与事务 Outbox → RabbitMQ → 假模型执行 → 结果与原文依据。

这是一份逐步实现中的开发版本。假模型仅摘录段落，不调用真实供应商，不产生模型费用。Outbox、RabbitMQ、手动确认、分发恢复、排队取消和明确重跑已接入；回调与补投在后续迭代实现。当前不可作为完整 P0 或生产版发布。

## 开始阅读

- [LLM 与组织隔离扩展建议](docs/product-directions.md)：当前能力边界与公司、部门、小组方案（尚未实现）。

- [需求范围](docs/spec.md)：当前做什么，以及完成条件。
- [实施任务](docs/tasks.md)：第三阶段的 8 项任务及依赖。
- [验收清单](docs/checklist.md)：只勾选已有证据的项。
- [首阶段决策](docs/adr/0001-first-slice.md)：先打通纵向链路的取舍。
- [消息分发决策](docs/adr/0002-outbox-rabbitmq.md)：确认、重发、代次和隔离边界。
- [取消与重跑决策](docs/adr/0003-job-lifecycle.md)：原日额度释放、来源保留与成功响应快照。
- [原理与代码导读](docs/walkthrough.md)：202、事务和 SENT 边界。
- [验证记录](docs/verification.md)：实际命令、结果及限制。

## 本地运行

需要 JDK 21、Maven 3.9+、Node 22.12+、Docker Desktop（Linux 容器引擎）。后端使用 Spring Boot 4.1.2；前端使用 Vue 3 和 Vite 7，精确版本见 package.json / package-lock.json。

以下命令在项目根目录的 PowerShell 执行。

```powershell
./scripts/init-local.ps1
docker compose --project-name relayops -f ops/compose.yml --env-file ops/.env up -d --wait
./scripts/start-backend.ps1
```

新终端启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

访问 <http://127.0.0.1:5173>。账号为 `owner.a`、`operator.a`、`viewer.a` 和空间 B 中对应的 `.b` 账号；密码在本地 `ops/.env` 的 `RELAYOPS_DEMO_PASSWORD` 中。该文件由脚本生成独立随机值，不提交 Git。账号只在第一次创建空间时初始化；改变配置中的演示密码不会重置已有数据库密码。

后端监听 127.0.0.1:8080；数据库映射到 127.0.0.1:3307。前端通过 Vite 代理同源访问后端，无需关闭 CSRF 或配置跨域通配符。确认 JAVA_HOME 指向 JDK 21。项目 Maven 设置使用官方 HTTPS 仓库及项目内缓存，不修改全局 Maven 配置。

RabbitMQ 无需手动下载：Compose 自动拉取锁定摘要的官方镜像并保留数据卷。AMQP 为 127.0.0.1:5672，[管理界面](http://127.0.0.1:15672) 使用 `ops/.env` 中的 `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` 登录，应用使用 `relayops` vhost。初始化脚本只补充缺少的配置，不替换已有密码或加密密钥；已有 Broker 数据卷的密码不会随修改 .env 自动重置。

当前开发机 Docker 数据目录为 `F:/DockerData`，迁移与验证记录见 [Docker 存储说明](docs/docker-storage.md)。

停止依赖使用 `docker compose --project-name relayops -f ops/compose.yml --env-file ops/.env stop`，保留数据卷。后端/前端在各自终端 Ctrl+C 停止。不要删除密钥来重置界面，旧数据需要原主密钥解密。

## 验证

规则单测（不需要数据库）：

```powershell
cd backend
mvn -s ../ops/maven-settings.xml -gs ../ops/maven-settings.xml test
```

数据库与消息测试需先启动 MySQL 和 RabbitMQ。首次准备隔离数据库及 vhost，再运行测试脚本；配置自动从本地 .env 加载：

```powershell
./scripts/prepare-test-env.ps1
./scripts/test-backend.ps1
```

集成测试使用 `relayops_test`、`relayops_messaging_test` 和 `relayops_upgrade_test` 专用数据库，以及 `relayops-test` vhost。测试会重置这些明确命名的测试数据；代码先检查连接确实指向测试库和测试 vhost，拒绝清理演示库。演示数据库和工作台额度保留。

启动后端后执行接口端到端验收：

```powershell
node scripts/smoke.mjs
```

真实 Broker 故障验收会短暂停止并自动恢复本项目 RabbitMQ 服务：

```powershell
node scripts/broker-fault-smoke.mjs
```

受理成功后任务必须在 Broker 停止时保持 QUEUED、调用次数为零，恢复后完成且只有一次 SENT。HTTP 和故障脚本使用演示库，会消耗少量本地额度。

前端构建和真实浏览器验收：

```powershell
cd frontend
npm run build
npx playwright test
```

浏览器测试默认使用已安装的 Microsoft Edge（无头模式），并自动启动 Vite；后端需要已启动。截图与测试输出放在被 Git 忽略的 `artifacts/`。真实浏览器测试使用真实后端和 MySQL。命令用例短暂停止并恢复本项目 RabbitMQ；重跑用例仅将自己新建的任务设为 UNKNOWN/UNCERTAIN 测试夹具，子任务通过真实消费者执行假模型，不伪造结果。

原有提交用例默认账号为 owner.a，命令用例默认为 owner.b；可在当前终端设置 `$env:RELAYOPS_BROWSER_USER='owner.b'` 统一账号。测试消耗少量演示额度。不要把压力测试流量发向演示额度配置。

## 取消与明确重跑

OWNER/OPERATOR 可在详情取消 QUEUED 任务，成功后释放原受理日的预留额度。消费者已领取的 RUNNING 任务及终态不能取消，返回 409。

FAILED/UNKNOWN 详情提供明确重跑：填写原因并确认新调用风险后，创建独立的新任务，使用来源文本和当前假模型配置，来源状态与用量保留。新详情可跳回来源；正文过期则返回 410。每次命令要求 Idempotency-Key 和 expectedVersion，重跑还要求 reason 和 acknowledgeDuplicateCost=true。

命令响应丢失时，页面保留原操作、版本和键；刷新后点击“沿用原操作重试”。409 时先核对当前状态，若要发起新意图则先放弃本地操作记录。放弃记录不撤销已经提交的服务端操作。

## 目录

```text
backend/    Java API、权限、任务、迁移和规则测试
frontend/   Vue 工作台与浏览器测试
ops/        MySQL Compose、Maven 配置及本地环境样例
scripts/    初始化、启动、集成测试和 HTTP 冒烟
docs/       需求、任务、验收、决策和验证记录
```

当前业务结构共 13 张表：第一阶段 11 张表，加第二阶段 Outbox 与安全隔离诊断，并非完整设计文档提及但未附带的原始 23 表 SQL。V1/V2 保持不变，本次通过 V3 追加来源关联、审计详情和成功响应快照。
