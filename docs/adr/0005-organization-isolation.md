# 公司、组织单元与显式空间授权

2026-10-30。用户确认按空间共享，小组需显式授权。

Tenant是公司边界；Workspace始终属于一个Tenant，保留原workspace_id作为内容/预算/消息/AAD边界。Department和Team采用organization_unit记录kind及可选父部门，父关系只用于组织展示、不传播权限，不允许修改父级或形成环。一个成员可加入多个组织单元。workspace_unit_grant显式授予OPERATOR或VIEWER，不授予OWNER。

直接成员和组织授权取最高角色，所有路径同时检查用户enabled和公司成员active。ADMIN是组织治理角色，不能替代Workspace访问权限；管理授权要求ADMIN并且对目标空间有效OWNER。公司管理员可通过显式、审计化的成员变更影响已有授权，这不是不可提权的治理/数据权限分权系统。

V4为每个旧空间建立一家公司（Tenant ID沿用该空间ID，但实体不同），回填公司成员，原有效OWNER成为ADMIN。旧空间和任务ID、角色、预算、密文与Outbox不变。新空库由LocalSeed先创建公司/成员再创建空间。新组织关联使用包含tenant_id的复合外键，阻止跨公司父部门、成员和空间授权。旧workspace_member仍使用原表，统一Access额外校验公司成员，不新增绕过入口。

写命令先以共享锁当前读定位空间的不可变公司归属，再锁公司行及当前直接空间成员，后续保留应用→预算→Job顺序。初始当前读不在等待公司锁前建立旧的重复读快照，且共享锁兼容后台外键检查。组织成员/授权变更持有同一公司锁，并发撤销和受理按公司锁顺序线性化。每家公司写命令因此串行，是当前规模下的明确折中，后续性能优化必须保留撤权语义。读取逐请求查询当前角色，不缓存角色。后台消费者不继承浏览器身份，继续处理已经受理的责任；撤权只影响后续交互，不隐式取消模型。

管理界面仅使用现有公司成员，不提供注册/邀请、切换空间所属公司、删除组织、改父级、公司预算或公司密钥。创建操作不自动重试；响应丢失时应刷新列表核对，避免重复创建。成员和授权PUT是目标状态操作，可安全重试。组织审计与Job审计分表，均不存正文或密钥。

依据：[OWASP逐请求授权](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)、[MySQL复合外键](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html)。这些资料支持授权检查与关系约束，具体组织策略由本项目明确决定。
