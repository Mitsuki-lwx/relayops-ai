# Findings & Decisions

## Requirements
- 2026-10-30 用户确认真实LLM已配置并要求继续，授权启用配置及一次自拟文档真实链路验收。后续自动回归仍用Fake/本机响应夹具，避免把回归批量流量送入真实供应商。
- 2026-10-30 用户指定taste skill重构前端。本机技能在F:/CodexData/.tmp/marketplaces/taste-skill/skills/taste-skill，已读取SKILL及app-screens规则，使用app流程而非营销页流程；不引入业务新能力或假数据。
- 2026-10-30 用户要求说明本地URL/Key位置后继续开发，继续Sol Advisor；确认“按空间共享，小组需显式授权”。本批做公司边界、部门/小组、组织成员到空间的授权，不做仅本人任务可见范围。
- 迁移时每个旧空间对应独立公司，保留原用户/空间/任务ID、直接成员与预算；新建空间可归入同公司。部门树仅组织展示，不隐式继承正文权限。额度仍按空间，不引入公司额度。
- 新鉴权合并直接成员与显式组织单元授权的最高角色，始终要求当前公司成员有效；治理ADMIN不自动授予任务访问。组织管理员可显式变更小组成员并影响访问，所有变更需审计。
- 2026-10-30 用户选择 OpenAI 兼容接口。新增服务端环境配置，默认 Fake；本次无需真实密钥或收费调用，必须用本机 HTTP 服务验证适配器，不宣称已验证所有供应商。
- 当前 Job 已冻结 model_profile/prompt_version/schema_version，可用配置派生 profileId 避免新增迁移。旧 Fake Job 始终可执行；不匹配当前兼容配置的旧任务在 SENT 前失败并释放预留。
- 用户新授权：迁移完成后直接继续项目。下一阶段实现取消与明确重跑及界面/验证，不接真实付费模型，不请求重复权限。
- 2026-10-29 用户明确要求 Docker 容器/镜像存储改为 F 盘；若原本在 F 则不动。本机实际在 C，授权迁移整个 Docker 数据存储。
- 用户要求继续 RelayOps，并询问是否需要手动下载 RabbitMQ；授权继续实现和配置本地依赖。
- 用户指定 Sol Advisor，已确认主会话 Sol / High；已声明 solo 路由，不使用辅助 agent。
- 保留原 CLAUDE.md 的逐步构建、三份文档、官方文档查 API 规范。

## Research Findings
- 当前 DockerRootDir 为 Linux VM 内 /var/lib/docker，Windows 数据盘在 C:/Users/lwx/AppData/Local/Docker/wsl/disk/docker_data.vhdx，另有 main/ext4.vhdx 引导盘。
- Docker 数据盘约 6.3 GiB，F 盘约 697 GiB 可用。当前 9 镜像、14 容器、43 卷，运行容器为本项目 MySQL 与 RabbitMQ。
- Docker 官方 WSL 文档支持在 Settings → Resources → Advanced 改存储位置；CLI 仅公开 start/stop/restart 等操作，正在核对本机迁移接口。
- 安装的 Desktop 前端使用 vm.resources.wslDataFolder，后端存在 WslDataFolder 设置字段；不能将非 WSL 的 DataFolder 字段当成本机 WSL 存储设置。
- 运行验证纠正上述字段推断：4.82 settings-store 明确将 WslDataFolder 识别为 unknown；实际 GET /app/settings/flat 返回 customWslDistroDir=C:/Users/lwx/AppData/Local/Docker/wsl。已找到 Desktop 自身 POST /app/settings 接口，改用实际返回的 customWslDistroDir 字段。C 盘旧盘尚未删除。
- 现有阶段：Spring Boot 4.1.2 / JDK 21 / MySQL 8.4.7，数据库扫描 JobWorker，15 项后端测试和 3 项浏览器测试曾通过。
- 现有代码尚无 Outbox、dispatchGeneration 或 MQ；幂等、预算、AES-GCM 和 SENT 唯一索引已存在。
- Spring AMQP CorrelationData 提供 CompletableFuture；returns 已填充后才完成 confirm future，可联合判断 ACK 与 mandatory return。
- Publisher confirm 和 consumer ACK 覆盖不同链路；允许重复分发，模型调用必须由数据库状态、代次、租约与 SENT 唯一约束保护。
- RabbitMQ 官方发布表显示 4.3.6 仍在社区支持窗口，选择该补丁并记录实际镜像摘要；已启动且健康。
- 编译成功。当前 Spring AMQP Confirm 的 isAck() 已弃用，改用 record accessor ack()。
- 首轮 25 项测试全部通过，10 项消息集成使用真实 MySQL 与 Broker。Spring Boot 官方配置提供 channel-rpc-timeout，显式设为 2s，避免默认 RPC 等待超过发布租约预算。
- 最终增量 26 项后端测试通过；HTTP 主链路与真实 Broker stop/start 演练通过。补充真实 V1 带排队任务到 V2 的迁移测试，以验证 SQL backfill 而非仅检查空库迁移。

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 就绪信封不带正文 | 只包含版本、消息/空间/任务 ID、代次、时间和 traceId；消费者从数据库重新核对事实。 |
| 隔离队列仅发安全诊断通知 | DLX 会复制原始字节，可能把恶意消息中的 secret 再保存；先持久化 DB hash 诊断，再尽力发送无正文的通知。 |
| 生产代码不保留扫描执行旁路 | 扫描器只恢复责任和重建 Outbox；实际领取必须来自 MQ 信封。 |
| 数据库集成测试与消息集成测试独立 | 防止后台消费者与故障注入竞争；消息测试使用隔离测试数据库与专用 vhost。 |
| 取消与重跑共用已有命令事务风格 | 外部 public 事务入口调用 private 持久化步骤，避免同对象事务代理误用。 |
| 重跑锁顺序 | 成员→当前应用→新任务受理日预算→来源 Job；来源额度不变，避免来源 Job 锁后再等待预算与迟到执行器死锁。 |
| 取消锁顺序 | 成员→原受理日预算→来源 Job；校验 QUEUED 与 expectedVersion，再原子释放 RESERVED 并写终态。 |
| 幂等成功响应快照 | V3 为已有 CREATE_JOB 补齐响应，新取消/重跑保存完整元数据响应；重放仍重新鉴权，重跑还检查来源 TTL。 |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| 无 Git 元数据 | 在 artifacts/stage2-before 保存本阶段修改文件的初始副本用于自审。 |

## Resources
- 最终对账已修复初轮夹具对指定已成功任务的错误分录，四项一致性检查均 0，未修改账户余额。模型仍为 Fake；演示空间 B 验收后额度有限，继续自动回归优先使用专用测试库，不调高演示额度掩盖测试消耗。
- MySQL 8.4 locking reads：https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html；Spring 事务代理边界：https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html 。本阶段已核对公开事务入口和预算→Job 锁序。
- 第三阶段 39 项后端测试通过；V3 在实际演示库也迁移成功。真实浏览器初轮发现登录 bootstrap 竞争，初始化期间禁用登录提交后复验。
- https://docs.spring.io/spring-amqp/reference/4.0/amqp/template.html
- https://www.rabbitmq.com/docs/confirms
- https://www.rabbitmq.com/release-information
- 本地基线 docs/spec.md、tasks.md、checklist.md 与现有 Java 源码。

- 第七阶段：两次真实任务各一次SENT，结果均OUTPUT_INVALID。明确复验的诊断为INVALID_CHOICES；不声称空工具修复已解决供应商问题。官方ClinePass支持同API的完整订阅slug，不能依据通用/models缺失就改为按量型号。

- 实际诊断任务2005c009证明Cline成功结果是success=true/data.choices包裹。仅兼容显式布尔成功，保持内部所有输出/引用/截断校验。

- 最终成功任务3614e95d已通过真实MQ/模型/引用/加密链路，3要点/3约束/5未知；共4次真实SENT（3失败+1成功），空间A今日20/20不调额。70后端、11浏览器及两档真实结果只读通过。

- 第八阶段从原设计BR03/BR10–12和签名协议推进受控交付；本批目录仅固定127.0.0.1本机演示目标，独立Python/SQLite接收端。地址不由用户输入，目录变更拒绝旧快照。人工补投/外网目录/轮换另批，不改变模型调用边界。
- 用户要求不占用8080；后端改为18080，同步Vite代理/启动及冒烟，历史验证记录不改写。

- 回调两项真实浏览器现已通过：500/500/200与SQLite提交后响应丢失，重试均保持一次模型和一次接收端保存。目的地/交付DOM快照在1440/768/390下均无横向溢出，字体实际加载、无密钥节点；已目视审阅。taste原生inspect三次均浏览器早退code0，改用Playwright复核，不声称原生检查通过。
- 构建发现Vite配置环境变量process缺少全局Node类型，改用Vite官方loadEnv读取专用前缀；最终构建已通过。

- 第九阶段：原设计BR13明确DEAD限定、两分钟预览后确认、操作者/版本/目标/hash绑定，已按此实现。新轮次及Outbox不增加AI任务/用量，旧轮次保留。89后端与14浏览器（含确认响应丢失刷新原键）已通过；V6在主库18080升级完成。
- 三档截图发现原因框继承文档正文的较大最小高度，限定补投原因框80px；预览测试等待父任务元数据刷新，避免截图捕获短暂旧QUEUED缓存。继续局部复验与自审。

- 主库预检：73条Job，7条已过期Fake任务（5 UNKNOWN、2 SUCCEEDED），2条到期结果，0交付；已有不可读取正文的清理被自动审批拒绝，不能以开发继续授权代替此次删除授权。主库清理默认/本地配置均关闭，后续只能在明确批准后启用。
- MySQL8.4官方ALTER TABLE文档核对了ADD/DROP CHECK语法：https://dev.mysql.com/doc/refman/8.4/en/alter-table.html 。回滚测试使用专用库临时CHECK，未修改二进制日志信任或提升数据库权限。

- 2026-10-31用户明确批准清理后，本机自动调度成功清除7条过期Fake输入及2条结果正文；密文hash对账确认66/52未到期载荷、73/54元数据及预算/尝试/密钥保留。先前自动审批阻断条件已由明确授权解除；源码默认false保留，仅本地配置true。
