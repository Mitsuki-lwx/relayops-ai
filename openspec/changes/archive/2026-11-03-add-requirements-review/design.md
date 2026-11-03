# Design

## Context
现有单一DocumentResult记录、模型profile、schema_version列和端到端责任已验证。当前真实输出能识别缺失/冲突，但关键点与约束重复，缺少可行动草稿。用户选择需求评审；源码与1.0附件分别交付。

## Goals / Non-Goals
Goals: 明确任务用途，结构化疑似问题、澄清问题与验收草稿，精确引用及安全导出，单次调用。
Non-Goals: 自动执行测试/改代码/分派工单、跨文档RAG、审批工作流、判定完整性或总体准确率。

## Decisions
- 用途为EXTRACT（默认）与REQUIREMENTS_REVIEW；以已冻结schema_version映射持久化用途，新结果版本requirements.review.result.v1；不增加数据库列，不修改V1–V7。
- 保留原profile、原提示词及默认请求指纹；新review profile独立，仍受当前配置匹配检查。CreateJob新taskType字段仅review时参与序列化/幂等，默认与显式EXTRACT均规范化为旧请求形状。重跑保持来源用途，采用该用途当前配置。
- 扩展DocumentResult可选review结构，旧结果不输出该字段。review根仍有摘要；普通keyPoints/constraints/unknowns为空。review含findings(kind/text/question/evidence)与acceptanceCriteria(title/given/when/then/evidence/assumptions)，每类最多6项；GAP/AMBIGUITY/CONFLICT三类问题，冲突至少两条不同的有效引用。
- 缺失只能用相关原文定位背景，引用不能证明全文没有要求；不确定/新增条件单列assumptions，界面统一标为待人工确认。所有引用须段落ID与原文子串匹配，结构或引用错误不保存成功结果。
- profile查询增加可选taskType，先鉴权后解析；旧调用默认EXTRACT。前端任务用途切换取消旧配置生效、清除外发确认、禁止配置未就绪提交；待确认幂等草稿冻结用途并可恢复。
- 重跑预览根据来源用途读取当前profile，避免显示另一用途的冻结配置。取消、预算及SENT/UNKNOWN路径保持；不同schema的结果原样进入现有relay.webhook.v1外壳，接收方根据result.schemaVersion区分。
- 前端独立评审结果组件、复用EvidenceList与安全Markdown文字围栏，不使用HTML渲染。原分析报告字节行为保持；VIEWER与过期/撤权拒绝规则不变。

## Risks / Trade-offs
原文引用准确不等于语义正确，模型可能漏报、误报或引入假设。最多6项会限制大文本覆盖，不宣称完整审查。不同任务用途改变提示/预算归因，因此冻结与重跑必须真实测试；后续RAG、差异分析和运营诊断只列建议。
