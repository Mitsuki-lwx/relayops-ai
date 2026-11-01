# Task Plan: RelayOps 1.0 Release

## Goal
在既有任务可靠性和组织隔离之上，按OpenSpec完成单机自托管1.0发布，交付可运行包、真实账号初始化、隔离验证和备份恢复说明。

## Next Step
候选已通过120后端/79前端/实际release JAR及27表恢复。等待用户配置ops/mysql-native.cnf，随后核对本机MySQL版本/迁移/hash，加载主服务并完成CI/最终1.0发布；保留原卷和主库，先提交已验证候选。

## Current Phase
Phase 44

## Phases

### Phase 1: Requirements & Discovery
- [x] Understand user intent
- [x] Identify constraints
- [x] Document in findings.md
- **Status:** complete

### Phase 2: Planning & Structure
- [x] Define approach
- [x] Create project structure
- **Status:** complete

### Phase 3: Implementation
- [x] Execute the plan
- [x] Write to files before executing
- **Status:** complete

### Phase 4: Testing & Verification
- [x] Verify requirements met
- [x] Document test results
- **Status:** complete

### Phase 5: Delivery
- [x] Review outputs
- [x] Prepare verified delivery links and limitations
- **Status:** complete

### Phase 6: Move Docker storage to F drive
- [x] 确认当前存储在 C 盘，用户授权迁移整个 Docker 存储。
- [x] 保存清单并通过受支持方式迁移，保留容器、镜像和卷。
- [x] 重启后核对存储位置、对象清单及 MySQL/RabbitMQ 服务。
- **Status:** complete

### Phase 7: Cancellation and explicit rerun design
- [x] 更新三份文档与 ADR，确定状态、幂等、额度和锁顺序。
- **Status:** complete

### Phase 8: Cancellation and explicit rerun implementation
- [x] V3、事务命令、权限和界面，保留原 Job 与调用边界。
- **Status:** complete

### Phase 9: Lifecycle verification and delivery
- [x] 真实 MySQL/RabbitMQ 的竞争、幂等、撤权与 TTL 用例。
- [x] 浏览器操作与响应丢失恢复，自审与文档更新。
- **Status:** complete

### Phase 10: Compatible model design and implementation
- [x] 规范、协议核对、结果 DTO、模型配置/适配器、任务路由和心跳。
- **Status:** complete

### Phase 11: Compatible model verification and publication
- [x] 本机 HTTP 故障、真实数据库/消息和浏览器验证，自审与文档。
- [x] 自动提交推送并核对远程。
- **Status:** complete

### Phase 12: Organization isolation design
- [x] 明确公司边界、组织成员、显式授权、迁移和锁序，更新三份文档。
- **Status:** complete

### Phase 13: Organization isolation implementation
- [x] V4、统一鉴权、组织管理与空间创建接口、管理页面。
- **Status:** complete

### Phase 14: Organization isolation verification and publication
- [x] MySQL升级、跨公司/跨组/撤权、幂等重放和浏览器验证。
- [x] 自审、凭据检查、提交推送并核对远程。
- **Status:** complete

### Phase 15: Taste app design
- [x] 数据/主任务盘点、抽取风格与产品特征，更新三份文档和设计说明。
- **Status:** complete

### Phase 16: Frontend reconstruction
- [x] 重构应用外壳、任务列表/详情、编辑区、登录和独立组织视图。
- **Status:** complete

### Phase 17: Taste verification and publication
- [x] 构建、真实浏览器回归、1440/768/390截图及taste lint/inspect与视觉自审。
- [x] 核对差异与凭据、自动提交推送。
- **Status:** complete

### Phase 18: Activate configured model
- [x] 安全启用真实配置，定位成功信封包装并修复；第四个关联任务成功，原三次失败及用量保留。
- **Status:** complete

### Phase 19: Complete result presentation
- [x] 展示摘要依据、约束、关键要点和未知项，解释安全错误码，不改变调用边界。
- **Status:** complete

### Phase 20: Model verification and publication
- [x] 70项后端、11项浏览器、构建及真实成功任务两档只读核对通过。
- [x] 自审、凭据检查、提交推送，远程哈希一致。
- **Status:** complete

## Git follow-up
2026-10-29 用户提供 relayops-ai 远程。已初始化仓库，保留远程 8923f53 初始历史，本地 main 追踪 origin/main。

2026-10-30 用户授权以后做完自行推送。本轮核对现有 LLM/隔离边界，记录扩展建议（尚未实现），整理当前已验证实现并提交推送；提交前检查忽略规则和凭据，推送后核对远程状态。

## Migration decision
源数据盘：C:/Users/lwx/AppData/Local/Docker/wsl/disk/docker_data.vhdx，约 6.3 GiB。目标使用 F:/DockerData。用户已说明迁移 Docker 容器目录，无需只迁 MQ；目标磁盘空间充足。

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Sol Advisor solo route | 用户确认 Sol / High；限定为 AI 分发，不增加回调或真实模型；不启动辅助 agent。 |
| Compose-managed RabbitMQ | 用户无需手动安装；自动生成独立凭据，绑定 loopback，持久化数据卷。 |
| Preserve V1 migration | V1 已在真实 MySQL 执行；只通过 V2 追加代次、Outbox 和诊断表。 |

## Errors Encountered
第三阶段文档补丁初次同时 delete/add 同一路径被拒绝；改为单次 Update，不改已完成代码。
| Error | Resolution |
|-------|------------|
| 技能目录的目录表路径缺少一层包名 | 已搜索定位并读取实际安装的 Sol Advisor 和 planning-with-files SKILL.md。 |
| git status: not a git repository | 当前目录尚未初始化 Git；使用项目内只读基线快照与 no-index diff 自审，不创建提交。 |
| javap 初次指定了不存在的 Flyway 补丁路径 | 已从项目缓存定位实际 12.4.0 并核对 FluentConfiguration 签名。 |
| 文档补丁末项上下文未匹配 | 读取实际文件确认已应用范围，按完整段落单独补齐导读，未重复应用已生效变更。 |
| Docker 重启在 Inference 管理器失败：旧 dockerInference Unix socket 无法删除 | 磁盘复制校验一致且原盘保留；诊断单个运行时 socket，恢复启动后再验证迁移。 |
| 残留 socket 单独查询/重命名均返回系统错误 1920 | 改为在 Desktop 停止时保留重命名整个 run 目录，并新建正常运行时目录，避免解析坏的 socket 节点。 |
| WslDataFolder 在 4.82 被记录为 unknown，实际仍挂载 C 盘 | 清理前的文件占用校验拦截；读取 Desktop 设置 API 确认正确键为 customWslDistroDir，并改用其原生更新接口。 |
| 独占打开 F 盘 VHD 不报错，尽管原生迁移后数据和对象清单均已转移 | 文件共享锁不是可靠挂载信号；使用 DiskImage 状态和原生配置/原文件迁移/对象清单综合验证。 |

### Phase 21: Controlled delivery design
- [x] 核对原设计，限定本机受控目录、签名、稳定字节与自动重试，更新三份文档和ADR。
- **Status:** complete

### Phase 22: Delivery implementation
- [x] V5、目录授权、任务目标冻结、原子成功结算、独立Outbox/MQ及投递执行/恢复。
- [x] 独立SQLite演示接收端、页面配置与时间线。
- **Status:** complete

### Phase 23: Delivery verification and publication
- [x] 专用MySQL/Broker/接收端验证、迁移、故障恢复、权限、签名与浏览器。
- [x] 自审、凭据核查、提交推送。
- **Status:** complete

### Phase 24: Replay design
- [x] 明确仅最新DEAD、两分钟预览、权限/版本/目标/hash/TTL绑定及历史保留。
- **Status:** complete

### Phase 25: Replay implementation
- [x] V6计划表、预览/确认、幂等及审计、完整Run历史与页面恢复。
- **Status:** complete

### Phase 26: Replay verification and publication
- [x] 隔离数据库/Broker并发、撤权、过期/停用、响应丢失、浏览器与构建。
- [x] 自审、凭据检查、自动提交推送。
- **Status:** complete

### Phase 27: Payload retention design
- [x] 明确七天TTL、终态清理、活跃责任结算、在线/备份边界，更新三份文档和ADR。
- **Status:** complete

### Phase 28: Payload retention implementation
- [x] V7、分批清理与标记、执行前TTL校验及恢复、正文读取拒绝和安全错误说明。
- **Status:** complete

### Phase 29: Payload retention verification and publication
- [x] 隔离库验证到期/未到期、跨空间、SENT边界、并发/回滚/重复清理与迁移。
- [x] 适用构建/浏览器、只读对账、自审与凭据检查，自动提交推送。
- **Status:** complete

### Phase 30: Main-database cleanup activation
- [x] 用户于2026-10-31明确批准启用主库自动清理；完成清理与前后对账。
- **Status:** complete

### Phase 31: Operations visibility design
- [x] 定义全量保留数据/最新交付轮次/待发布/清理口径，OWNER审计白名单与分页，更新三份文档及ADR。
- **Status:** complete

### Phase 32: Operations visibility implementation
- [x] 空间只读概览、OWNER审计分页/筛选，工作台页面与可见性轮询。
- **Status:** complete

### Phase 33: Operations visibility verification and publication
- [x] 真实库权限/隔离/统计/分页负例、浏览器三档与切空间/撤权清除，构建及自审。
- [x] 凭据检查、自动提交推送并核对远程。
- **Status:** complete


### Phase 34: Job history design
- [x] 定义安全字段、字面搜索、状态筛选、时间/ID游标和上下文清理，更新三份文档。
- **Status:** complete

### Phase 35: Job history implementation
- [x] 只读历史端点、真实库负例、筛选分页页面和详情入口。
- **Status:** complete

### Phase 36: Job history verification and publication
- [x] 适用后端/浏览器三档、构建、只读对账及自审。
- [x] 凭据检查、提交推送并核对远程。
- **Status:** complete


### Phase 37: Analysis report export design
- [x] 明确安全文本报告、重新授权/TTL及本地副本边界，更新三份文档和ADR。
- **Status:** complete

### Phase 38: Analysis report export implementation
- [x] 格式化器、下载控件与共享正文读取/上下文清理。
- **Status:** complete

### Phase 39: Analysis report export verification and publication
- [x] 下载/格式/拒绝/竞争与三档浏览器、构建/只读对账及自审。
- [x] 暂存凭据检查、自动提交推送并核对远程。
- **Status:** complete

### Phase 40: Organization audit design
- [x] 明确当前ADMIN只读安全六列、筛选/分页和前端上下文保护，更新三份文档。
- **Status:** complete

### Phase 41: Organization audit implementation
- [x] 实现租户查询、真实库负例和审计面板/浏览器夹具。
- **Status:** complete

### Phase 42: Organization audit verification and publication
- [x] 后端、浏览器三档、构建、只读核对及自审。
- [ ] 暂存凭据扫描、自动提交推送并核对远程。
- **Status:** pending

### Phase 43: OpenSpec release scope
- [x] 初始化本地OpenSpec、能力/设计/任务与发布门禁，用户明确要求继续实现至1.0。
- **Status:** complete

### Phase 44: Runtime and provisioning
- [ ] 恢复依赖、完成组织审计；实现同源JAR、健康/版本、公司成员初始化。
- **Status:** in_progress

### Phase 45: Distribution and operations
- [ ] 打包/启动/校验、隔离测试工具、CI及备份恢复演练和文档。
- **Status:** pending

### Phase 46: Release acceptance
- [ ] 后端/全链路浏览器、打包JAR真实运行和安全/恢复门禁通过。
- **Status:** pending

### Phase 47: Release publication
- [ ] 自审/凭据检查、OpenSpec归档、提交推送、v1.0.0与发布附件核对。
- **Status:** pending
