# Proposal

## Why
真实样本证明模型能识别缺失信息和冲突，但当前界面仅提供摘要、关键点和未知项，用户仍需手工整理评审问题和验收草稿。用户已选择“需求评审与验收清单”作为下一开发方向。

## What Changes
- 新增需求评审任务用途，与原技术文档分析并列，单次模型调用输出疑似缺漏/歧义/冲突、澄清问题及Given/When/Then验收草稿。
- 每项问题与草稿保留关联原文；推断和待确认条件单独标记，不生成可直接执行的测试代码。
- 冻结用途、提示词与结果版本；重跑保持原用途，历史、结果、回调和Markdown识别新结果类型。
- 复用权限、外发确认、幂等、预算、SENT/UNKNOWN、TTL和Langfuse脱敏观测。

## Capabilities
### New Capabilities
- `requirements-review`: 需求问题识别、澄清与可追溯的验收草稿及版本化任务用途。
### Modified Capabilities
无，既有观测及交付责任保持原契约。

## Impact
JobService/Controller、ModelGateway/FakeModel、结果验证、前端任务表单/结果/报告、真实数据库和浏览器验收。现有冻结schema_version列可区分用途，无需迁移。旧默认请求幂等指纹、旧profile及旧结果格式保持。
