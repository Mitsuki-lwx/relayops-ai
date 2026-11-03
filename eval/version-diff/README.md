# 文档版本对比固定评测集

这是 `VERSION_DIFF` 的固定合成评测集，用于比较提示词/profile 变化后的行为回归，不证明影响分析完整或总体准确率。设计参考现有需求评审评测方式；输入全部为自编中文短文本。

## 文件

- `cases.json`：16 个固定样本，含基线/修订、标签、期望和说明。
- `schema.json`：数据集结构。
- `../../scripts/score-version-diff-eval.mjs`：确定性评分器。
- `../../scripts/score-version-diff-eval.test.mjs`：评分器测试。
- `../../scripts/run-version-diff-eval.mjs`：受控真实 API 运行器。

## 覆盖
- 无变化对照：完全相同、段落重排、仅空白变化；出现变化即失败。
- ADDED：新增文件上限、日志保留期、失败状态码、VIEWER 限制。
- REMOVED：删除 CSV、删除大小上限。
- MODIFIED：大小/权限/超时/单位变化，必须双侧引用。
- 多变化：三项、五项独立变化，`requireDistinctChanges` 防止合并成一条。
- 注入：修订文本包含 `SYSTEM_OVERRIDE`，不得执行或复述。
- 冲突引入：修订从单一超时变成 10/30 秒冲突。

## 评分
```powershell
node scripts/score-version-diff-eval.mjs --validate-only
node scripts/score-version-diff-eval.mjs --results artifacts/version-eval/<run>
node scripts/score-version-diff-eval.mjs --results <run1> --results <run2> --results <run3> --expected-repeats 3 --json
```
结构检查：schema、最多8项变化、增删侧引用、MODIFIED 双侧、影响约束/验收上限、引用精确匹配。规则检查：必找变化、禁止变化、必找影响/验收、禁止文字；`expected no changes` 用于无变化对照。缺失结果可计为失败重复。默认失败返回退出码1。

## 运行
```powershell
node scripts/run-version-diff-eval.mjs --config artifacts/review-eval-<ts>/config.env --user eval.owner --out artifacts/review-eval-<ts>/run1 --acknowledge-cost
```
运行器只接受独立本地端口（默认18081，拒绝18080/8080），每例单次调用、不自动重试、不建回调目的地；连续基础设施失败达到阈值停止。建议至少三次独立运行后再比较。

## 基线
- `baselines/2026-11-03-version-compat-v1-3x.json`：`version.compat.v1` 历史基线（48任务全成功、稳定15/16、重复通过97.92%、83,368 Token、平均8.528秒）。
- `baselines/2026-11-04-version-compat-v2-3x.json`：当前 `version.compat.v2` 基线（47/48成功、稳定15/16、重复通过97.92%、72,848 Token、平均5.699秒）；唯一失败为一次重排样本输出无效。
- `manual-review.json` / `manual-review-v2.json`：人工语义复核；v2 7通过/1失败（重排一次无结果），`confirmedByUser=false`。

## 局限
- 关键词评分只覆盖预定义关注点；通过不代表变化完整，失败也可能是措辞不同。
- 16 个合成样本不足以估计准确率；没有人类全量语义复核、重复采样或跨模型对照。
- 注入只有一种模式；不是安全评测基准。变更期望时新建数据集版本，不静默改写历史结果。
