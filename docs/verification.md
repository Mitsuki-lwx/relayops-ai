# 验证记录

## 第五阶段公司与组织隔离

2026-10-30，按用户确认“按空间共享，小组需显式授权”，继续Sol Advisor solo实现、自审，无辅助agent。

- 后端 **66项通过，0失败、0跳过**：前58项加8项组织集成测试。组织测试使用专用测试库中的独立公司夹具，验证部门/小组无隐式权限、父关系不继承、多组最高角色、直接OWNER不被降级、VIEWER只读元数据、授权/成员撤销、公司成员停用，以及管理员治理与空间权限分别校验。
- 跨公司组织父级、成员与空间ID均拒绝；数据库复合外键拒绝跨公司组织成员和授权。相同目标状态PUT不重复写审计。撤权后任务列表、详情、正文、预算、模型信息、创建/取消/重跑和原键重放均拒绝；已受理任务仍能成功且只有一次SENT。
- 并发用例在公司锁内移出成员，并在提交前启动原键重放；请求等待撤权提交后返回404，没有新增Job。受理前的空间归属查询使用共享锁当前读，防止等待公司锁之前固定旧重复读快照；原并发幂等和预算竞争用例继续通过。
- 升级用例实际执行V1→V2→V3→V4，保留原ID、QUEUED、预算预留、输入密文字节、Outbox和幂等快照，原OWNER成为公司ADMIN。补充全新V4模式上的LocalSeed两次运行和隔离空间查询，单项复验通过。演示库也成功迁移到V4，V1/V2/V3内容哈希与基线一致。
- 浏览器 **7项全部通过**，包括真实管理接口建部门/小组、加入operator.a、同公司建新空间、无授权时不可见、显式OPERATOR授权后提交并读取结果、移出后下一次正文读取404且清除页面结果、刷新后空间消失、owner.b跨公司读取404，以及管理写入缺CSRF返回403。原取消、重跑、响应丢失和外发确认用例均通过。
- 管理截图等待刷新完成后单项复验通过，1440px/390px无横向溢出；已目视检查桌面面板和手机授权表单。Vue类型检查与生产构建、HTTP会话/CSRF/幂等/权限/结果冒烟均通过。启动脚本新增-FakeModel并实际使用，验收不受本地真实供应商配置影响，没有付费调用。
- 最终演示库对账五项为0：预算分录差异、成功缺结果、重复SENT、缺幂等快照、直接空间成员缺公司归属。V1–V4均成功，MySQL/Broker健康，工作台/CSRF/MQ管理页均200。自审实际源码差异，候选文件未命中本地真实凭据；ops/.env保持忽略且未覆盖用户现有值。

证据：`artifacts/stage5-tests.log`、`stage5-upgrade-final.log`、`stage5-browser.log`、`stage5-organization-browser-final.log`、`stage5-frontend-build.log`、`stage5-http-smoke.log`、`stage5-reconciliation.log`、`stage5-services.json`；截图 `organization-desktop.png`、`organization-mobile.png`、`organization-mobile-grant.png`。这些原始证据位于本地忽略目录。

限制：共享库逻辑隔离，已有公司成员的组织管理和显式授权；不含公司注册/新用户邀请、公司计费/密钥、部门权限继承或仅本人可见。公司写命令目前串行。ADMIN可通过显式成员变更影响已有授权，不是不可提权的治理/数据权限分权。当前验收后端显式Fake，真实供应商效果仍需后续验收；下方历史记录描述当时状态。

实现提交 `f1e46e4` 已推送 `origin/main`，远程哈希核对一致；本节与计划完成状态另以文档提交归档。

## 第四阶段兼容模型与有界心跳

2026-10-30，用户选择 OpenAI 兼容接口，继续 Sol Advisor solo 路由，自审且无辅助 agent。

- 后端 **58 项通过，0 失败、0 跳过**：原39项，加10项本机HTTP协议测试和9项兼容模型数据库/消息执行测试。使用独立测试库、测试 vhost、127.0.0.1 模拟供应商与假密钥；没有访问真实供应商或产生模型费用。
- 验证 Bearer、模型名、段落ID、JSON模式和两种token参数；元数据由服务端赋值。拒绝无效配置、超128KiB响应、非法输出、伪造引用、截断和明确拒绝；重定向不跟随。断连、500和完整响应体超时不自动重发。
- 兼容结果加密保存，重复消息仍只有一次HTTP请求；配置不匹配在SENT前释放额度，旧Fake任务可执行，明确重跑使用当前配置且不修改来源。缺外发确认返回422且无新Job。
- 真实Broker投递的调用延迟4.5秒，测试租约3秒；心跳续租跨过原到期点，期间恢复不夺取执行权，最终仅一条SENT和结果。错token、错代次、已到期租约均不续期。慢响应体超过6秒后恢复UNKNOWN/UNCERTAIN，重复消息不再次调用。
- 原5项浏览器回归通过。新增外发确认用例首轮因按钮可访问名称带箭头而定位失败，修正定位器后单项通过，合计6项均通过。新增用例仅替换模型配置元数据以验证页面门槛，任务仍由真实后端的FakeModel执行；兼容HTTP执行由上述隔离集成测试验证。确认随原始请求与幂等键保留，丢响应后刷新重试仍返回相同Job；成功后新文档恢复未确认状态。
- 1440px、390px长模型名称可见，无横向溢出，确认可用键盘操作；已目视检查截图。Vue类型检查与生产构建、HTTP会话/CSRF/幂等/权限/结果冒烟通过。
- V1/V2/V3的Git内容哈希与已发布基线一致，未新增迁移。演示库只读对账：预算分录差异、成功缺结果、重复SENT、缺幂等响应快照均为0。前端、CSRF、MQ管理页面均200，MySQL与RabbitMQ健康。
- 首轮依赖未启动导致连接失败；启动已有Compose并保留卷、准备测试环境后58项复验通过。没有通过更改演示额度或清空演示库规避失败。

证据：`artifacts/stage4-tests.log`、`stage4-browser.log`（原5项成功和新增定位失败）、`stage4-consent-browser.log`（修正后单项成功）、`stage4-frontend-build.log`、`stage4-http-smoke.log`、`stage4-reconciliation.log`、`stage4-services.json`；截图 `compatible-consent-mobile.png` 和 `compatible-consent-desktop.png`。原始证据均在本地忽略的artifacts目录。提交前逐项自审源码差异，扫描候选文件无本地真实凭据命中。

本地默认仍为Fake；启用真实调用需管理员在忽略的ops/.env中配置地址、模型和密钥并重启。尚未验证真实供应商的效果、延迟或实际费用；组织层级隔离、回调与补投仍在后续范围。下方各阶段记录描述当时状态。

实现提交 `9e893ab` 已推送 `origin/main`，远程提交核对一致；验收与计划完成状态另以文档提交归档。

## 第三阶段取消与明确重跑

2026-10-29，继续 Sol Advisor solo 路由，无辅助 agent。Docker 原生迁移完成，数据盘为 `F:/DockerData/disk/docker_data.vhdx`，C 盘旧数据盘不存在；对象清单和旧结果读取验证见 docker-storage.md。

- 后端 **39 项通过，0 失败、0 跳过**：8 项规则、7 项原数据库、9 项生命周期、14 项真实 Broker 消息测试、1 项逐级升级测试。命令和竞争测试使用真实 MySQL 8.4，消息测试使用真实 RabbitMQ，测试库/vhost 与演示环境隔离。
- V1→V2→V3 实际升级保留旧 Job、预算和 Outbox，旧 CREATE_JOB 补齐成功响应；V1/V2 与阶段前快照 SHA-256 一致。演示库也已成功迁移到 V3。
- QUEUED 取消和原受理日额度释放只发生一次；RUNNING/终态返回 409；与领取竞争只允许取消成功且零 SENT，或取消冲突且一次 SENT；取消后旧信封不创建 Attempt。
- FAILED/UNKNOWN 明确重跑创建新 ID、来源关联、当前配置、独立密文和 Outbox，来源状态与旧用量不变。8 次并发同键只产生一个子任务和一份额度。预算 429、积压 503、权限 403/404、缺确认或原因 422、版本冲突 409、TTL 410 均无孤立业务记录。
- 浏览器 **5 项全部通过**：原有桌面/手机提交与原文依据、创建响应丢失；新增手机取消和明确重跑，命令成功响应丢失后刷新沿用原键和请求，新任务 ID 不变；来源跳转及真实 409/410 提示通过。命令以键盘 Enter 提交，390px 无页面横向溢出。首次加载等待 CSRF 初始化完成后才能登录，修复初轮暴露的初始化竞争。
- 浏览器 UNKNOWN 来源由测试对自己新建的指定 UUID 注入 UNKNOWN/UNCERTAIN，不是实际供应商超时。子任务经过真实 AMQP 消费和 FakeModel 产生结果。故障测试短暂停止后自动恢复本项目 Broker。
- 初轮测试遗漏 Compose 项目名，在 Broker 仍运行时注入过一条分录。最终对账发现，已按该指定测试任务的实际 SUCCEEDED/SENT 修复为 SPENT，并记录 TEST_FIXTURE_REPAIRED 审计；账户原计数未修改。夹具增加非 QUEUED 必须使事务失败的保护，并复验命令用例。
- V3 启动后的原 HTTP 冒烟通过（CSRF、会话、202、幂等、权限、持久化结果和单 SENT）；Vue 类型检查与生产构建通过。

证据：`artifacts/stage3-tests.log`、`artifacts/stage3-backend.log`、`artifacts/stage3-browser-final.log`、`artifacts/stage3-command-final.log`（强化夹具后重跑通过）、`artifacts/stage3-cancel-final.log`（修正空输入时截图等待条件后取消单项通过）、`artifacts/stage3-frontend-build.log`、`artifacts/stage3-http-smoke.log`、`artifacts/stage3-fixture-repair.log`。截图为 `cancel-mobile.png`、`rerun-desktop.png`、`rerun-mobile.png` 和原工作台截图，均在 artifacts 内。自审比较 `artifacts/stage3-before` 基线与当前 Java/Vue/迁移/测试源码；当前没有 Git 元数据，未创建提交或 PR。

最终只读对账 `artifacts/stage3-reconciliation.log`：预算账户与分录差异 0、成功却缺结果 0、重复 SENT 0、缺幂等响应快照 0；两个依赖 healthy。工作台、CSRF 和管理界面均 HTTP 200，记录于 `artifacts/stage3-services.json`。

当前仍为免费确定性假模型；真实供应商超时、长请求心跳、付费风险、回调与补投尚未实现。本阶段不代表完整 P0 或生产发布验收。

事务与锁依据：[MySQL locking reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)、[Spring 声明式事务与代理边界](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)。

## 第二阶段 Outbox 与 RabbitMQ

2026-10-29，按用户确认的 Sol / High 和 Sol Advisor solo 路由实现、自审与验证，无辅助 agent。官方 Spring AMQP API 核对后接入，仍使用确定性假模型。

- RabbitMQ 4.3.6-management 已由 Compose 下载启动，健康检查通过；AMQP/管理端口仅绑定 127.0.0.1。镜像锁定 `rabbitmq@sha256:54cb013d76cd1fd6c0a0708f34062ad61c452bb6f016b434c6c33f1a765d2b58`。
- 最终后端 **27 项通过，0 失败、0 跳过**：8 项规则、7 项数据库、11 项真实 Broker 消息故障测试、1 项 V1→V2 升级测试。
- 升级用例在专用库先迁移 V1 并写入一条旧排队任务，再迁移 V2，确认任务和预留额度保留、首代 Outbox 自动生成；重复迁移无重复事件。V1 文件与改动前 SHA-256 一致。
- 事务回滚没有 Job/额度分录/Outbox 残留；同键并发仅创建一条首代 Outbox。积压容量测试使用上限 2，第三个新意图返回 503，旧意图仍可重放。
- 实际 Broker 验证 mandatory return 后不标 SENT；确认已到达但刻意省略数据库回写时，发布租约恢复后重发同 messageId；过期发布者不能覆盖新 claim。
- 同信封发送十次只调用一次 Fake 模型；消费结果提交后关闭真实 AMQP channel、不发 ACK，重投不会再次调用。
- PREPARED 失效生成新代次，旧消息不执行，迟到 token 不覆盖结果；SENT 失效进入 UNKNOWN/UNCERTAIN，旧消息重投不调用。已发布超时未领取的任务重建一条新代次 Outbox。
- 非法 JSON、未知版本、错空间和不存在关联进入 DB 安全诊断，隔离队列仅收到 hash/错误码；不保留原始不可信字节。
- 数据库异常用一次 spy 注入模拟；实际 Broker 上未确认消息随 channel 关闭重新入队，之后恢复处理成功。该项不是停掉 MySQL 的演练。消费容器配置五秒恢复间隔，Broker 停止期间日志显示间隔恢复，没有无延迟 requeue。
- 新版 HTTP 冒烟通过，真实 Spring RabbitListener 消费并保存结果；已有权限、CSRF 和幂等行为保留。
- **实际停止并恢复 RabbitMQ 容器的演练通过**：停止时 202、QUEUED、零次 SENT，原键重放不变；恢复后 SUCCEEDED，只有一次 SENT。
- **3 项浏览器回归全部通过**：1440px、390px 的登录、键盘提交及依据阅读；已受理响应被丢弃后，重载页面沿用原幂等键恢复。此次使用 owner.b 避免耗尽空间 A 的演示额度。页面代码未改，第一阶段前端构建结果仍适用。

原始证据：`artifacts/stage2-tests.log`、`backend/target/surefire-reports/`、`artifacts/stage2-http-smoke.log`、`artifacts/stage2-broker-fault.log`、`artifacts/stage2-backend-run.log`、`artifacts/playwright-results.json`。Broker 暂停、不可路由和数据库异常用例会产生预期的告警日志，不能据此误判测试失败。

最终只读核对演示库：预算账户与分录不一致为 0、SUCCEEDED 却缺少结果为 0、同 Job 多条 SENT 为 0，记录于 `artifacts/stage2-reconciliation.log`。前端、CSRF 端点和 RabbitMQ 管理界面均返回 HTTP 200；MySQL/Broker 健康。

自审使用 `artifacts/stage2-before` 与当前源码的 no-index diff，当前目录仍无 Git 仓库元数据；未创建提交或 PR。消息实现改动范围为 Java、V2、应用配置、Compose、脚本、测试和文档；前端只增加测试账号的环境变量选择。当前依旧不支持真实模型、长请求心跳、取消/明确重跑、回调与补投，不代表完整 P0 完成。

消息发布整批等待确认预算五秒、发布租约三十秒、连接和 channel RPC 配置两秒。尚未进行所有网络黑洞、磁盘满和长连接极端故障的时限测试，不把本次功能用例推导为完整恢复 SLO。

API 与协议依据：[Spring AMQP 发布确认与 returns](https://docs.spring.io/spring-amqp/reference/4.0/amqp/template.html)、[RabbitMQ confirms](https://www.rabbitmq.com/docs/confirms)、[Spring Boot AMQP](https://docs.spring.io/spring-boot/reference/messaging/amqp.html)、[配置项](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)、[RabbitMQ 发布表](https://www.rabbitmq.com/release-information)。

## 第一阶段历史记录

日期：2026-10-29，时间使用 Asia/Shanghai。验证对象为本仓库首阶段代码。无真实模型、回调、RabbitMQ 或生产性能验证。

## 实际环境

| 工具 | 实际使用 |
| --- | --- |
| JDK | 21.0.7，D:/jdk |
| Maven | 3.9.11，项目设置使用官方 HTTPS 仓库 |
| Spring Boot | 4.1.2 |
| MySQL | 8.4.7，Docker 容器 relayops-mysql-1，健康检查通过 |
| MySQL 镜像 | mysql@sha256:0426ec38c7a10aa45ba383887df7878f74ee70e2fd589c7b69207f3577901903 |
| Node / npm | 24.12.0 / 11.6.2 |
| Vue / Vite | 3.5.44 / 7.3.7 |
| Playwright | 1.64.1，使用已安装的无头 Microsoft Edge |

## 已执行结果

- 后端编译成功，首个 Flyway 迁移在空 MySQL 8.4 数据库实际执行成功。
- 再次启动验证迁移校验通过，无重复迁移或演示账号初始化。
- 后端规则测试验证 Unicode/NFC、输入边界、精确引用及 AES-GCM 上下文和防篡改。
- 后端最终共 15 项测试通过、0 跳过：8 项规则测试、7 项真实数据库集成测试。后者覆盖同键并发受理、意图冲突、跨空间、VIEWER、TTL、SENT 后 UNKNOWN、排队到期释放、成员撤权和并发额度上限。
- HTTP 冒烟通过：匿名 401、缺 CSRF 的登录 403、登录后提交 202、受理响应重放一致、意图冲突 409、持久化结果及一次 SENT、VIEWER 403、跨空间 404、退出旧会话失效。
- 浏览器 3 项全部通过：1440px 和 390px 下真实登录、键盘提交、读取结果与展开原文；第三项拦截一次已受理的响应并断开，刷新页面后沿用同一幂等键重试成功。
- 两张实际截图已目视检查；390px 无横向页面溢出。
- 按 lockfile 执行 npm ci，npm 审计为 0 项漏洞；前端类型检查及生产构建成功。

原始证据在本机忽略目录：`artifacts/backend-tests.log`、`backend/target/surefire-reports/`、`artifacts/http-smoke.log`、`artifacts/playwright-results.json`、`artifacts/workbench-1440.png` 和 `artifacts/workbench-390.png`。这些是实际执行记录，不是设计目标；重新运行测试会更新记录。

## 当前阶段的限制

- 数据库扫描承担首阶段任务分发；尚未实现 Outbox、RabbitMQ 与代次协议。
- 假模型执行很短；正式模型的网络超时、心跳、供应商错误分类及故障闩锁尚未实现。当前恢复覆盖数据库租约失效，不代表已完成真实供应商故障测试。
- UNKNOWN 不自动调用；取消和明确重跑端点尚未实现。
- 每日额度与幂等已实现；独立 API 服务凭据、目的地、交付、补投与审计管理页面尚未实现。
- 正文读取执行七天 TTL，已返回结果也设置浏览器到期清除；后台物理清理、三十天元数据清理、备份和恢复尚未实现。
- 本地单主密钥配置，密文中存储 nonce；尚未实现 keyRef 轮换。不能丢失 ops/.env 中的密钥，否则现有密文无法解密。
- 仅供本地开发；完整生产账号治理、登录限频、性能、监控与安全发布验收未完成。
- 未提供完整设计所引用的独立工程包；当前 11 张业务表和测试为本次实现，不冒充原始 23 表、80 用例和 30 评测样本。

## API 资料核对

当前会话没有 Context7 MCP，按 CLAUDE.md 的“等文档工具”要求，使用官方资料核对 API：

- [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot SQL 与 JDBC 支持](https://docs.spring.io/spring-boot/reference/data/sql.html)
- [Spring Security CSRF 与显式令牌端点](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Vue 快速开始](https://vuejs.org/guide/quick-start.html)
- [Vite 快速开始及 Node 要求](https://vite.dev/guide/)
- [Playwright 配置](https://playwright.dev/docs/test-configuration)
- [Playwright 浏览器 channel](https://playwright.dev/docs/api/class-testoptions#test-options-channel)

版本以实际构建和锁文件为准。安装时发现旧 Vue/Vite 补丁的已知漏洞，升级为 npm 官方审计给出的修复版本；升级后审计返回 0。
