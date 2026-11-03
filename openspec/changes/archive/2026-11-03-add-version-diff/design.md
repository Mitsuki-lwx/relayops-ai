# Design

## Context
现有单输入任务链路已可靠：`ai_job.input_cipher` 保存一份正文，Worker 用 `DocumentInput.paragraphs` 生成段落ID，模型结果按段落ID精确校验。新增版本对比必须保留这条责任链，不能另起一套任务/预算/回调。

## Goals / Non-Goals
Goals: 双文本输入、变化分类、双侧引用、影响约束/验收草稿、单次调用、旧用途兼容。
Non-Goals: 自动合并、自动修改需求、跨文档RAG、代码执行、差异可视化编辑器、质量评测集扩展。

## Decisions
- `TaskType.VERSION_DIFF`，结果版本`version.diff.result.v1`；`CreateJob`增加可选`inputTextB`，仅该用途必填。旧构造器和旧请求序列化不变。
- `DocumentInput`新增版本输入规范：分别用现有`normalize`校验A/B（各6000字符），再用标记`<<<VERSION_A>>>`/`<<<VERSION_B>>>`组合存储；标记冲突拒绝。段落ID分别为`a0001`/`b0001`，每份最多100段。组合文本只用于加密/重跑/读取，不把标记暴露给模型正文以外。
- 受理时`normalize`生成组合输入并写入现有`input_cipher`；重跑用相同用途拆分组合输入；读取结果按`schema_version`选择段落解析。无数据库迁移。
- `DocumentResult`新增可选`diff`：changes(kind=ADDED/REMOVED/MODIFIED, text, before[], after[])、impactedConstraints[]、impactedAcceptanceCriteria[]。结构/引用校验沿用现有精确匹配；ADDED/REMOVED/MODIFIED的双侧引用数量有硬约束。
- 独立提示词/profile：`version.compat.v1`，复用评审输出上限8192；profile指纹包含版本、提示词和评审上限。旧EXTRACT/REVIEW profile不变。
- Worker从job schema选择段落解析；回调外壳仍为`relay.webhook.v1`，以`result.schemaVersion`区分。前端双文本框、用途切换、草稿恢复、结果视图和Markdown报告；VIEWER/撤权/TTL规则不变。
- 任务表单继续只在非EXTRACT时发送`taskType`；版本请求额外发送`inputTextB`，默认请求指纹不变。

## Risks / Trade-offs
- 组合标记是内部存储格式，若原文包含标记必须拒绝，而不是转义后静默改变语义。
- 双侧引用和影响分析仍可能漏报；界面明确“变化建议，待人工确认”，不宣称完整。
- 两份各6000字符可能超过单次模型上下文/输出上限；首版保留现有6000字符/份和8192输出上限，真实样本再评估。
