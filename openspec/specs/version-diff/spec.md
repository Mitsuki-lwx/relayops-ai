# version-diff Specification

## Purpose
将基线与修订技术文档的变化转换为可追溯的增删改、受影响约束和验收草稿，帮助团队人工确认影响范围；不自动合并或执行。

## Requirements

### Requirement: Explicit dual-version purpose and frozen inputs
系统SHALL支持 `VERSION_DIFF` 用途，受理时冻结基线/修订文本、用途、配置及结果版本；默认分析与评审请求必须保持原行为。

#### Scenario: Missing revised input
- **WHEN** 用户选择版本对比但未提供修订文本
- **THEN** 服务端拒绝受理，不预留额度、不创建任务

#### Scenario: Rerun after failure
- **WHEN** 已授权用户明确重跑失败或UNKNOWN的版本对比任务
- **THEN** 新任务保持版本对比用途并从组合输入恢复两份文本，原责任不被退款或覆盖

### Requirement: Grounded changes and impact drafts
输出SHALL包含摘要、最多8项增删改、最多6项受影响约束和最多6项受影响验收草稿；每项变化MUST带双侧或对应侧精确引用。

#### Scenario: Added requirement
- **WHEN** 模型输出ADDED变化
- **THEN** 只引用修订文本的有效段落，基线引用为空

#### Scenario: Removed requirement
- **WHEN** 模型输出REMOVED变化
- **THEN** 只引用基线文本的有效段落，修订引用为空

#### Scenario: Modified requirement
- **WHEN** 模型输出MODIFIED变化
- **THEN** 基线和修订两侧都至少有一条不同且精确匹配的引用

#### Scenario: Invalid dual evidence
- **WHEN** 任一侧引用无法匹配段落或结构不合法
- **THEN** 任务按现有失败责任结算，不保存成功结果，不自动重试模型

### Requirement: Safe workflow and delivery
版本对比SHALL复用空间授权、外发确认、一次调用、预算、加密、TTL、签名回调和脱敏观测；查看/导出重新鉴权，界面与报告标注建议待人工确认。

#### Scenario: Access loss
- **WHEN** 权限撤销或正文过期
- **THEN** 不读取或下载旧版本对比缓存，迟到响应不能回填

#### Scenario: Callback
- **WHEN** 受控回调且版本对比保存成功
- **THEN** 回调携带`version.diff.result.v1`及变化/影响草稿，不增加模型调用
