# 需求评审固定评测集

这是 `REQUIREMENTS_REVIEW` 的固定、可重复评测集，用于比较提示词/profile 变化后的行为回归，不是“证明评审完整”或“给出总体准确率”的基准。数据集全部为中文合成文本，未复制公开文档；设计参考了需求质量属性、公开需求语料和 Gherkin 表达方式。

## 文件

- `cases.json`：20 个固定样本，包含输入、标签、期望和说明。
- `schema.json`：数据集 JSON Schema（编辑器和人工校验用）。
- `../../scripts/score-requirements-review-eval.mjs`：无依赖的确定性评分器。
- `../../scripts/score-requirements-review-eval.test.mjs`：评分器单元测试。

## 覆盖范围

| 类别 | 样本 | 检查点 |
| --- | --- | --- |
| 干净对照 | `rr-001`、`rr-002` | 完整需求不产生 GAP/CONFLICT 误报 |
| 缺失 | `rr-003`、`rr-004`、`rr-016`、`rr-020` | 指出未定义的限制/失败契约/审计字段，不编造数值 |
| 歧义 | `rr-005`、`rr-012`、`rr-014` | 不可验证措辞与统计口径，不把建议写成事实 |
| 冲突 | `rr-006`—`rr-009`、`rr-017`、`rr-019`、`rr-020` | 至少两条不同引用，不替用户裁决 |
| 混合 | `rr-009`、`rr-017`、`rr-019`、`rr-020` | 同一文本中多类问题都被覆盖 |
| 注入 | `rr-010` | 文档内指令不执行、不复述 |
| 假设陷阱 | `rr-011` | 不把 401/403 等建议写成既定要求 |
| 误报控制 | `rr-013`、`rr-018` | 语义重复或单位等价不算冲突 |
| 权限 | `rr-015` | 角色权限基本完整时只禁止冲突输出 |
| 长文本 | `rr-019`、`rr-020` | 多个冲突/缺失去重、引用和输出上限 |

## 样本格式

```json
{
  "id": "rr-006-conflict-timeout",
  "title": "超时数值冲突",
  "input": "同一版本的接口规范甲写明……",
  "tags": ["conflict", "api", "numbers"],
  "expect": {
    "expectNoFindings": false,
    "requiredFindings": [
      { "kind": "CONFLICT", "anyOf": ["超时", "10秒", "30秒"], "minEvidence": 2 }
    ],
    "forbiddenFindings": [],
    "requiredCriteria": [{ "anyOf": ["超时", "规范"] }],
    "forbiddenTerms": ["以10秒为准", "以30秒为准"]
  },
  "notes": "为什么这样判定"
}
```

- `requiredFindings`：至少一个 findings 同时满足 kind、evidence 数量和关键词（沿用 `anyOf` 的 OR 语义）。关键词匹配 finding 文本、澄清问题和引用原文。
- `forbiddenFindings`：不得出现匹配 kind/关键词的问题；只检查模型正文，不检查引用原文。
- `requiredCriteria`：至少一个验收草稿的 title/given/when/then/assumptions 命中关键词。
- `forbiddenTerms`：只检查摘要、finding 的问题正文、验收草稿和假设，不检查原文引用或澄清问句；避免把“是否返回401？”这类提问误判为裁决。
- `expectNoFindings`：干净对照要求 findings 为空。
- `requireDistinctFindings`：多个 required 项必须由不同 finding 覆盖，防止合并成一条“全说到了”。

## 评分

```powershell
node scripts/score-requirements-review-eval.mjs --validate-only
node scripts/score-requirements-review-eval.mjs --results artifacts/review-eval/<run>
node scripts/score-requirements-review-eval.mjs --results artifacts/review-eval/<run> --json
```

结果目录中每个样本一个 `<case-id>.json`，内容可以是结果接口的完整响应 `{data, paragraphs}`，也可以是 `{jobId,status,result:{...}}`。评分器同时检查：

- 结果版本、空数组边界、字段长度、最多6项；
- 每条引用都能按段落 ID 和原文子串精确匹配；
- `CONFLICT` 至少两条不同引用；
- 必找问题、禁止问题、必找验收草稿和禁止文字；
- 汇总 `passRate`、`findingRecall`、`criterionRecall`、结构失败数、禁止命中和缺失结果。

默认任一案例失败返回退出码1；`--allow-failures` 只出报告。评分器不调用任何模型，也不把模型输出当可信输入。

## 收集结果

当前仓库不包含自动调用供应商的评测运行器，避免误用主库或产生未确认费用。建议在独立 schema/vhost 和专用端口上评测：

1. 使用固定 profile，记录模型名、provider、`profileId`、提示词版本和两个输出上限。
2. 每个 case 只提交一次评审任务，`title` 使用样本 `title`，`inputText` 使用样本 `input`；不自动重跑。
3. 成功结果保存为 `<id>.json`；失败/UNKNOWN 记录状态与错误码，不要用另一次调用覆盖原失败。
4. 同一 profile 至少保留一次完整运行；比较提示词版本时，两版各自独立运行，相同 case 不共享幂等键。
5. 评分后在报告中保留 `profileId`、模型、日期、数据集版本和原始结果目录，避免只留聚合分数。

## 参考

- [ISO/IEC/IEEE 29148:2018](https://www.iso.org/standard/72089.html)：需求质量属性，如明确、可验证、无歧义。
- [PURE: a Dataset of Public Requirements Documents](https://zenodo.org/records/7118517)：公开需求文档语料；本数据集未直接复制其文本。
- [Langfuse offline evaluation](https://langfuse.com/docs/evaluation/get-started/offline)：固定数据集和实验比较的工作方式。
- [Gherkin Given/When/Then](https://cucumber.io/docs/gherkin/reference/)：验收草稿表达方式。

## 局限

- 关键词评分只能覆盖可预期的关注点，不能证明模型语义正确；通过不代表评审完整，失败也可能只是措辞不同。
- 20 个样本不足以估计总体准确率、稳定性或供应商差异；同一 case 重复采样、模型对照和人工复核仍需单独执行。
- 注入样本只覆盖一种指令模式；不是安全评测基准。
- 评测集本身需要随产品契约升级版本；变更 case 或期望时同步修改 `schemaVersion` 或增加新数据集版本，不静默改写历史结果。
