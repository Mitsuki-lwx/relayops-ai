# Proposal

## Why
需求评审已能识别单篇文本的问题，但团队仍手工核对版本变化及其影响的约束、验收项。用户已选择“文档版本对比与影响分析”作为下一产品方向。

## What Changes
- 新增 `VERSION_DIFF` 任务用途，接受基线和修订两份文本；单次模型调用输出语义变化、受影响约束和受影响验收草稿。
- 每项变化必须分别引用基线/修订原文；新增只引用修订，删除只引用基线，修改两侧都要引用。
- 复用现有权限、预算、幂等、冻结配置、加密、TTL、回调和脱敏观测；不自动合并、不修改需求、不执行测试。
- 旧 EXTRACT/REQUIREMENTS_REVIEW 请求、结果、回调和重跑保持兼容。

## Capabilities
### New Capabilities
- `version-diff`: 双版本文本对比、变化分类、影响分析和可追溯验收草稿。

### Modified Capabilities
无；既有任务用途和观测/交付边界保持。

## Impact
DocumentInput、TaskType、DocumentResult、ModelGateway/Fake、JobService/Worker、前端任务表单/结果/报告/评测。使用规范化组合输入，不新增数据库列，不修改 V1–V8。
