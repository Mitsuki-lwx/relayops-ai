# Task Plan: RelayOps Outbox and RabbitMQ

## Goal
完成 Outbox/RabbitMQ、Docker 存储迁移到 F 盘，并继续实现排队取消与明确重跑；以真实数据库、Broker 和浏览器验证责任与用量边界。

## Next Step
本批公司、部门/小组与显式空间授权链路已实现、验证并推送。公司注册/邀请、公司配置与预算、回调/补投留给后续批次。

## Current Phase
Phase 14 complete

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
