# RelayOps

AI 技术文档任务与结果交付平台。当前链路：登录 → 公司与空间权限 → 幂等受理与事务 Outbox → RabbitMQ → 模型执行 → 结果与原文依据 → 可选本机回调。

这是一份逐步实现中的开发版本。默认假模型仅摘录段落，不调用真实供应商，不产生模型费用。可显式配置 OpenAI 兼容接口进行文档分析；工作台和服务端要求外发确认。Outbox、RabbitMQ、手动确认、分发恢复、排队取消、明确重跑和受控本机回调已接入；外网目录与人工补投留待后续迭代。当前不可作为完整 P0 或生产版发布。

## 开始阅读

- [LLM 与组织隔离扩展建议](docs/product-directions.md)：已实现边界与后续扩展。

- [需求范围](docs/spec.md)：当前做什么，以及完成条件。
- [实施任务](docs/tasks.md)：第八阶段的 8 项任务及依赖。
- [前端设计](docs/frontend-design.md)：taste app风格、信息层级和验证方式。
- [验收清单](docs/checklist.md)：只勾选已有证据的项。
- [首阶段决策](docs/adr/0001-first-slice.md)：先打通纵向链路的取舍。
- [消息分发决策](docs/adr/0002-outbox-rabbitmq.md)：确认、重发、代次和隔离边界。
- [取消与重跑决策](docs/adr/0003-job-lifecycle.md)：原日额度释放、来源保留与成功响应快照。
- [兼容模型决策](docs/adr/0004-compatible-model.md)：配置绑定、外发确认、总时限与心跳。
- [组织隔离决策](docs/adr/0005-organization-isolation.md)：公司边界、显式授权、迁移与撤权锁序。
- [受控回调决策](docs/adr/0006-controlled-delivery.md)：冻结目标、签名、独立重试与接收端去重。
- [原理与代码导读](docs/walkthrough.md)：202、事务和 SENT 边界。
- [验证记录](docs/verification.md)：实际命令、结果及限制。

## 本地运行

前端采用taste的framed-sheet工作台布局：任务队列、文档新建、组织管理各有独立视图；任务可按已载入范围搜索或筛选，手机在列表与详情间返回。字体本地托管，包含OFL许可证。业务API、权限和调用责任边界保持原有契约。

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

后端监听 127.0.0.1:18080；数据库映射到 127.0.0.1:3307。前端通过 Vite 代理同源访问后端，无需关闭 CSRF 或配置跨域通配符。确认 JAVA_HOME 指向 JDK 21。项目 Maven 设置使用官方 HTTPS 仓库及项目内缓存，不修改全局 Maven 配置。

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

浏览器回归应先用 `./scripts/start-backend.ps1 -FakeModel` 启动后端，显式覆盖本地模型配置，避免测试产生供应商费用。会创建任务的浏览器用例在登录后检查所有可访问空间均为Fake，否则停止。外发确认用例只替换配置元数据以验证页面确认和刷新恢复；真实HTTP协议、超时与心跳在后端测试中由本机模拟供应商验证。组织用例通过真实管理接口创建带时间标记的部门、小组和空间，保留审计与任务，测试结束会移出测试组员。

真实模型运行期间，可单独执行 `npx playwright test llm-results.spec.ts visual.spec.ts`：前者完全使用本地API响应夹具，后者仅登录并读取真实元数据，不创建模型任务。

原有提交用例默认账号为 owner.a，命令用例默认为 owner.b；可在当前终端设置 `$env:RELAYOPS_BROWSER_USER='owner.b'` 统一账号。测试消耗少量演示额度。不要把压力测试流量发向演示额度配置。

## 配置真实文档分析

本地默认 `RELAYOPS_MODEL_MODE=fake`。要启用兼容接口，在忽略的 `ops/.env` 中填写下列变量，再重启后端：

```dotenv
RELAYOPS_MODEL_MODE=compatible
RELAYOPS_MODEL_ENDPOINT=https://your-provider.example/v1/chat/completions
RELAYOPS_MODEL_NAME=your-model-name
RELAYOPS_MODEL_API_KEY=fill_your_key_locally
RELAYOPS_MODEL_TIMEOUT_SECONDS=60
RELAYOPS_MODEL_MAX_TOKENS=4096
RELAYOPS_MODEL_TOKEN_PARAMETER=max_tokens
RELAYOPS_MODEL_JSON_MODE=true
```

地址必须是完整 HTTPS Chat Completions 端点，路径可能因供应商而不同。此处域名和模型只是占位符。某些模型要求 `max_completion_tokens`；不支持 JSON 模式的兼容端点可将 JSON_MODE 设为 false，但仍须返回约定 JSON，结果验证不放宽。当前实现非流式响应，不做工具调用或自动切换/重试。

工作台展示服务端模型名，并要求提交者确认文档外发和潜在费用；API 新建兼容任务要求 `acknowledgeExternalProcessing=true`。密钥不返回前端、不写任务或审计。配置为所有空间共享的本地管理员配置，尚无按租户管理供应商密钥的界面。

超时允许 1–300 秒，输出 token 上限允许 256–16384；完整 HTTP 响应体上限 128 KiB。默认租约 120 秒，执行中每 40 秒按 token/代次续租，续租最多持续调用时限加 30 秒；响应超时、断连或不明服务端故障最终进入 UNKNOWN，不再次调用供应商。平台调用额度不是实际供应商账单。

配置标识绑定地址、模型、提示、超时和输出参数；更改后旧兼容排队任务以 MODEL_CONFIG_CHANGED 在发送前失败并释放预留。旧 Fake 任务仍可处理，明确重跑使用当前配置。已受理请求的幂等重放仍返回原任务。本机模拟供应商覆盖协议与故障；2026-10-30另以用户配置的ClinePass型号完成一篇自拟短文的真实链路验收，不能代表其他供应商或实际账单已验证。

兼容标准Chat Completions信封及 `success: true` 包裹的 `data` 对象；失败/错误信封仍拒绝，内部格式、截断和引用检查保持严格。空工具元数据允许，实际工具请求不执行。输出解析仅记录固定安全原因和结构布尔值，不记录供应商正文。

结果页完整展示摘要、关键结论、约束与要求、未确定信息；每条依据可展开对应原文并高亮精确引用。内容按纯文本呈现。失败详情解释安全错误码与明确重跑的费用边界；重新读取被拒绝时清除旧结果。

## 本机结果回调

空间 OWNER 在“回调目的地”创建并启用目标；当前受控目录固定为 `http://127.0.0.1:8091/relayops/events`，不接受任意 URL。OWNER/OPERATOR 新建任务时最多选择三个目标，也可以不选择。VIEWER 只读交付元数据，不能读取签名密钥。

准备独立演示接收端，需要 Python 3（仅标准库）。创建目的地后，在项目根目录执行：

```powershell
node scripts/configure-demo-receiver.mjs
python scripts/demo-receiver.py
```

注册脚本默认以 owner.a 登录，显式读取其所有可访问 OWNER 空间的启用目的地密钥，每次读取记录审计；不会创建任务或调用模型。密钥写入忽略的 `artifacts/receiver/keys.json`，不会打印。新建目的地后重新运行注册脚本；该文件会被当前账号的目标集合覆盖，切换账号需设置 `RELAYOPS_BROWSER_USER`。接收端从文件加载密钥，以独立 SQLite 保存结果与去重事实；这些本地产物也不提交 Git。

任务受理冻结目标，保存成功结果时在同一数据库事务中建立交付及独立 Outbox。回调使用 HMAC-SHA256 签名与五分钟接收窗口，正文和交付 ID 在重试间保持稳定，接收端按 ID 和正文 hash 原子去重。详细协议见 ADR0006。任务详情提供交付状态、每次尝试和下一次最早重试时间。

2xx 表示传输确认；408、429、5xx、网络未知自动退避重试，每轮最多六次且不超过一小时或原任务七天保留期。重定向不跟随，其他非2xx停止。停用目的地阻止后续领取，已经发出的请求仍可能到达。回调重试不重跑模型、不增减 AI 额度。任务明确重跑则创建新任务，并沿用来源目标、重新检查是否启用。

验签与去重测试：`python scripts/test-demo-receiver.py`。`delivery.spec.ts` 为独立接收端浏览器用例，需 Fake 后端和未占用的8091；默认浏览器命令还包含其他会写任务的用例，不应在真实模型模式运行。本批验收使用隔离测试数据库及专用 vhost，未动演示空间额度。

本批不包含外网 HTTPS 目录、密钥轮换、人工补投、接收端外部业务动作或物理 TTL 清理；两个系统无共享事务，不承诺端到端恰好一次。

## 取消与明确重跑

OWNER/OPERATOR 可在详情取消 QUEUED 任务，成功后释放原受理日的预留额度。消费者已领取的 RUNNING 任务及终态不能取消，返回 409。

FAILED/UNKNOWN 详情提供明确重跑：填写原因并确认新调用风险后，创建独立的新任务，使用来源文本和当前模型配置，来源状态与用量保留。新详情可跳回来源；正文过期则返回 410。每次命令要求 Idempotency-Key 和 expectedVersion，重跑还要求 reason 和 acknowledgeDuplicateCost=true。

命令响应丢失时，页面保留原操作、版本和键；刷新后点击“沿用原操作重试”。409 时先核对当前状态，若要发起新意图则先放弃本地操作记录。放弃记录不撤销已经提交的服务端操作。

## 公司、部门与小组

登录后，管理员从侧栏或手机菜单进入“组织管理”：创建部门或小组（可选择父部门）、将已有公司成员加入组织、创建同公司的新空间，再显式授予组织 OPERATOR 或 VIEWER。新空间的创建者成为 OWNER；其他公司成员不会自动获得新空间权限。默认演示账号 owner.a / owner.b 分属两家公司并各自具有 ADMIN，operator/viewer 没有组织管理权限。

已有数据库升级到 V4 时，每个旧空间归入独立公司，保留原空间/成员/任务ID、角色、额度、密文及消息；原有效 OWNER 成为对应公司 ADMIN。新安装同样预置两个隔离公司。空间选择器显示公司及空间名称。

权限按当前有效公司成员、直接空间成员和显式组织授权计算，多来源取最高角色。父部门仅表示组织关系，不自动继承权限。撤销组织成员或授权后，后续列表、正文、预算、命令及原键重放立即重查；已有直接成员或其他组织来源仍有效。已受理后台任务继续处理，不因浏览器撤权丢弃责任。

组织治理 ADMIN 不自动取得任务正文；设置组织到空间授权还需目标空间 OWNER。管理员可以通过显式成员变更影响授权，操作留审计，因此不是不可提权的治理/数据权限分权。当前是共享库的逻辑隔离，不是独立数据库或密钥隔离。

管理API以 `/api/v1/organizations` 为入口：GET列出当前公司；GET `/{tenantId}` 返回管理员可见的组织/成员/空间/授权；POST `/{tenantId}/units` 和 `/{tenantId}/workspaces` 创建资源；PUT `/{tenantId}/units/{unitId}/members/{userId}` 设置active；PUT `/{tenantId}/workspaces/{workspaceId}/grants/{unitId}` 设置OPERATOR/VIEWER/NONE。所有写入沿用会话CSRF；成员及授权PUT是目标状态更新。创建操作不自动重试，响应丢失时先刷新核对。

当前不包含公司注册、新用户邀请、公司总额度、公司密钥配置、部门权限继承或仅本人任务可见范围；额度仍按空间，模型配置仍由服务端统一管理。

## 目录

```text
backend/    Java API、权限、任务、迁移和规则测试
frontend/   Vue 工作台与浏览器测试
ops/        MySQL Compose、Maven 配置及本地环境样例
scripts/    初始化、启动、集成测试和 HTTP 冒烟
docs/       需求、任务、验收、决策和验证记录
```

当前业务结构共 25 张表：原19张表加目的地、任务目标快照、交付、交付轮次、尝试和独立交付Outbox六表。V1–V4保持不变，V5追加交付结构，旧任务不追补外发；已执行的迁移不可修改。
