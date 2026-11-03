# Spec Delta

## Purpose
将技术需求文本转换为可追溯的评审问题、澄清问题和验收草稿，帮助授权团队识别疑似缺漏、歧义及冲突，并在人工确认后用于实现和测试规划，保留原有任务和数据隔离责任。

## ADDED Requirements

### Requirement: Explicit and frozen task purpose
系统SHALL支持普通文档分析与需求评审两种任务用途，受理时冻结用途、配置及结果版本；默认调用MUST保留既有分析及幂等兼容性。

#### Scenario: Purpose changes with an existing idempotency key
- **WHEN** 使用相同幂等键提交不同用途
- **THEN** 服务端拒绝复用；相同用途和请求仍返回原任务且不重复扣额度

#### Scenario: Explicit rerun
- **WHEN** 已授权用户明确重跑失败或UNKNOWN的评审任务
- **THEN** 新任务保持评审用途并冻结当前评审配置，原责任不被退款或覆盖

### Requirement: Grounded review findings and acceptance drafts
评审SHALL输出摘要、最多6项疑似GAP/AMBIGUITY/CONFLICT及最多6项Given/When/Then验收草稿，每项带有效关联原文；生成条件MUST与源事实区分，不自动执行。

#### Scenario: Missing requirement
- **WHEN** 模型认为某项条件缺失
- **THEN** 输出相关原文定位和澄清问题，界面说明关联引用不证明缺失；新增假设作为待确认建议展示

#### Scenario: Contradictory requirements
- **WHEN** 模型输出冲突问题
- **THEN** 至少提供两条不同且匹配原文的引用，不自动替用户确定优先级

#### Scenario: Invalid evidence
- **WHEN** 任一引用无法匹配段落或结构不合法
- **THEN** 任务按现有失败责任结算，不将结果标为成功，不自动重试模型

### Requirement: Safe review workflow and delivery
需求评审SHALL复用当前空间授权、外发确认、一次调用、预算、加密、TTL及脱敏观测；查看和导出MUST重新鉴权，界面与报告明确草稿待人工确认。

#### Scenario: Read or export after access loss
- **WHEN** 权限撤销或正文过期
- **THEN** 不读取或下载旧评审缓存，迟到响应不能回填或生成下载

#### Scenario: Callback result
- **WHEN** 用户选择受控回调且评审保存成功
- **THEN** 回调携带独立结果schema及草稿，不添加模型调用，仍遵循原签名与交付幂等规则
