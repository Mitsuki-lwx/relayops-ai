# Progress Log

## Product discussion and publication: 2026-10-30

- 用户询问 LLM 扩展和多租户/部门/小组隔离，明确授权以后验证完成后自行提交推送。
- 核对当前实现：Fake 摘录；共享数据库的 Workspace 逻辑隔离和三角色；尚无企业 Tenant、部门或 Team 及按创建人可见范围。
- 扩展建议记录于 docs/product-directions.md，明确尚未实现；本轮不修改应用代码、权限和数据库结构。已有39后端测试、5浏览器用例、构建记录保持适用。
- 本轮整理并发布现有实现；提交前检查候选文件、真实本地凭据和忽略规则，提交后核验远程提交号。

## Git connection: 2026-10-29

- 用户提供远程 https://github.com/Mitsuki-lwx/relayops-ai 。此前未初始化 Git，只用阶段快照自审，没有项目提交或推送。
- 已初始化本地仓库、添加 origin、获取现有历史；远程 main 基线为 8923f53 Initial commit，仅有 README.md。
- 本地 main 基于远程基线并追踪 origin/main，现有工作文件全部保留为未提交改动；没有新提交或推送。
- git check-ignore 确认 ops/.env、artifacts、backend/target、frontend/node_modules 和 dist 已忽略。仅调整版本管理元数据和记录，未改应用代码，无需重复功能测试。

## Stage 3 lifecycle implementation

- 本批完成：39 后端测试、5 浏览器用例、HTTP 冒烟、前端类型检查/构建通过。夹具强化后的重跑用例通过；取消截图等待条件改为等待工作区恢复（空输入的提交本应禁用），取消单项复验通过。
- 最终只读对账：预算计数不一致 0、成功缺结果 0、重复 SENT 0、缺响应快照 0；MySQL/RabbitMQ healthy。工作台、CSRF 和管理端口均 200；F 数据盘存在、C 旧盘不存在。
- V1/V2 SHA-256 不变，V3 升级验证和演示库迁移完成；已比较 stage3-before 源码差异，三份工程文档、ADR、导读和 README 已同步。仍无真实供应商、心跳或回调。

- 最终一轮 5 项浏览器用例全部通过；新增手机命令、409/410 提示和子任务关联；原有 HTTP 冒烟通过。
- 对账定位初轮 Compose 项目名遗漏时的一条测试任务：实际成功但夹具把分录改为 UNCERTAIN，账户计数仍正确。按该任务成功事实修复该分录，并强化夹具：来源非 QUEUED 时强制 SQL 失败，整笔事务回滚，防止跳过 Attempt 插入却修改后续分录。

- V3、取消/重跑事务、响应快照、来源链接和前端风险确认已实现；39 项后端测试全通过（0 跳过）。
- 前端类型检查/构建通过。新增真实浏览器命令测试进行中。
- 初轮浏览器暴露首次登录可与初始化 CSRF 并发；初始化期间禁用提交，避免两次会话初始化竞争。
- 新测试的 Compose 调用补齐明确项目名 relayops 与 env-file，避免默认 ops 项目无法找到既有服务。实际依赖未丢失。
- prettier 未安装且本机 npm 默认镜像不可解析；不修改全局设置或为格式化新增依赖。

## Session: 2026-10-29

### Current Status
- **Phase:** 8 - Lifecycle implementation
- **Started:** 2026-10-29

### Actions Taken
- Docker 迁移最终完成：CustomWslDistroDir=F:/DockerData，原 C 盘数据盘不存在；MySQL/RabbitMQ 健康，后端和管理页面 HTTP 200，16 条原任务和加密结果可读。
- 已移除本次创建的已校验 F 盘临时副本与无效 WslDataFolder 字段，最终数据盘保留。
- 用户要求迁移后直接继续项目，并再次指定 Sol Advisor；沿用已确认的 Sol / High，声明 solo 路由，进入 QUEUED 取消与 FAILED/UNKNOWN 明确重跑阶段。
- 第三阶段 spec/tasks/checklist 与 ADR 0003 已更新；按原设计采用新 Job、来源关联、风险确认和命令响应快照。
- 已追加 V3、取消与重跑事务入口、当前模型配置接口；正在编译并接入界面与真实数据库/消息测试。
- 新增磁盘迁移请求：用户确认迁移整个 Docker 存储至 F，而非单独绑定 MQ 数据目录。
- 只读确认数据盘当前在 C:/Users/lwx/AppData/Local/Docker/wsl/disk/docker_data.vhdx；尚未移动或删除磁盘文件。
- 正在核对本机 Desktop 配置并保存对象清单和设置备份，随后停机复制、校验和恢复。
- 已保存 14 容器、9 镜像、43 卷的原始清单和 Docker 设置；运行中的 MySQL/RabbitMQ 已正常停止，Desktop stop 成功。
- 本机安装代码确认 WSL 存储字段是 WslDataFolder；选择 F:/DockerData/disk/docker_data.vhdx，离线复制并验证 SHA-256 后切换，源盘在运行验证前保留。
- 离线复制完成且 SHA-256 完全一致，WslDataFolder 已设置 F:/DockerData。首次启动失败日志指向 C:/Users/lwx/AppData/Local/Docker/run/dockerInference 的残留运行时 socket，发生在数据盘加载之前；正单独排查该启动问题，未删除原数据盘。
- run 目录重建后，启动推进到 Secrets Engine，发现另一独立目录中的同类 engine.sock。已确认 docker-secrets-engine 目录只有这个零字节 socket，没有密钥/数据库文件；同时保留重命名两个 socket-only 目录后重启。
- Docker 原生 POST /app/settings 已成功接受 customWslDistroDir；已自动将 C 盘数据文件移至 F:/DockerData/disk/docker_data.vhdx。14 容器、10 镜像标签记录（9 个镜像）、43 卷与原清单完全一致。
- 不再以独占打开 VHD 是否失败作为唯一挂载依据：Docker 原生迁移后文件可独占读取，该信号不能区分虚拟磁盘实际挂载；改为核对 API 配置、原路径不存在、清单一致及 Windows DiskImage 接口。
- 读取用户指定 Sol Advisor 技能；用户确认其 Sol / High 前置条件；声明 solo 路由。
- 读取 planning-with-files、原 andrej-karpathy-mode、现有任务代码和 Compose。
- 初始化并选用项目根 task_plan.md、findings.md、progress.md；未读取本地会话记录。
- 查阅 RabbitMQ 和 Spring AMQP 官方协议/API 文档。
- 第二阶段三份文档和 ADR 已更新；追加 V2，不修改 V1。
- Docker 自动拉取 RabbitMQ 4.3.6 并通过健康检查，镜像摘要已锁定。
- 已实现 Outbox 批量发布、确认和 return 联合判定、手动 ACK、代次领取与恢复；首轮编译成功。
- 自审发现原始 DLX 会复制不可信正文，改为仅传安全诊断通知并在 ADR 记录偏差。
- 首轮 25 项测试全部通过（8 规则、7 数据库、10 消息），新增 MQ UNKNOWN 重投用例并准备最终复验。
- 最终 27 项测试全部通过：8 规则、7 DB、11 消息、1 带 V1 排队责任的升级测试；V1 SHA-256 未变。
- 新版 HTTP 冒烟和真实 stop/start RabbitMQ 演练通过；3 项真实浏览器回归通过。
- 演示库保留；三份专用测试库和一个测试 vhost 隔离故障测试；凭据未打印。
- 最终源码 no-index diff 与 V1 哈希自审完成；PowerShell 语法检查通过。
- 最终演示库只读核对：预算不一致 0、成功任务缺结果 0、重复 SENT 0；三处 HTTP 地址均为 200，两个依赖健康。
- 本轮所有阶段完成；交付消息链路与本地服务地址。真实模型、取消/重跑与回调继续留在后续范围。

### Test Results
| Test | Expected | Actual | Status |
|------|----------|--------|--------|
| Backend including real MySQL/Broker and upgrade | 27 pass, zero skipped | 27 pass, zero skipped | PASS |
| HTTP session/idempotency/authorization | unchanged contract, one call | passed on RabbitListener | PASS |
| Real Broker stop/start | persist QUEUED while down, one SENT after recovery | passed | PASS |
| Browser desktop/mobile/lost response | 3 pass | 3 pass | PASS |
| V1 checksum | unchanged | identical SHA-256 | PASS |
| Demo reconciliation | zero mismatches/missing results/duplicate SENT | 0 / 0 / 0 | PASS |
| Service URLs and dependencies | HTTP 200 and healthy | frontend/backend/management 200; DB/Broker healthy | PASS |

### Errors
| Error | Resolution |
|-------|------------|
