# 同供应商模型对照（2026-11-03）

## 范围
用户限定只对照同一供应商的 `qwen/qwen3.8-flash`。基线为 `cline-pass/deepseek-v4.1-flash-1017`，使用相同供应商兼容端点、相同提示词和固定评测集；没有引入第三家供应商或其他 Qwen 型号。

## 配置
| 项目 | deepseek | qwen |
| --- | --- | --- |
| 模型 | `cline-pass/deepseek-v4.1-flash-1017` | `qwen/qwen3.8-flash` |
| 供应商 | ClinePass compatible | ClinePass compatible |
| 超时 | 60秒 | 180秒 |
| 评审提示 | `review.compat.v2` | `review.compat.v2` |
| 版本提示 | `version.compat.v1` | `version.compat.v1` |
| 输出上限 | 8192 | 8192 |

qwen 首次以60秒运行评审时出现2/3超时，因此公平性对照使用180秒；这也说明其延迟显著更高。除模型与超时外，schema、prompt、预算、单次调用和评测规则相同。

## 单次结果
| 用途 | 模型 | 通过 | 通过率 | 任务成功/缺失 | 召回 | Token | 平均span |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 需求评审 | deepseek | 20/20 | 100% | 20/20 | finding 100% / criterion 100% | 76,439 | 22.123s |
| 需求评审 | qwen | 17/20 | 85% | 19/20，1缺失 | finding 95.24% / criterion 94.74% | 105,801 | 87.501s |
| 版本对比 | deepseek | 15/16 | 93.75% | 16/16 | change 100% / criterion 100% | 31,523 | 9.527s |
| 版本对比 | qwen | 14/16 | 87.5% | 15/16，1缺失 | change 100% / criterion 100% | 50,175 | 42.777s |

deepseek 另有三次重复基线：需求评审稳定16/20、重复通过93.33%；版本对比稳定15/16、重复通过97.92%。qwen 每个用途只有一次运行，稳定性尚未测量。

## 观察
- qwen 需求评审有一个任务在180秒配置下仍超时；版本对比有一个任务 `OUTPUT_INVALID`（vd-003删除CSV）。
- qwen 把“仅段落重排”标记为 MODIFIED 变化，说明无变化对照更容易误报。
- qwen 的需求评审通过率、召回、延迟和 Token 均差于 deepseek；版本对比通过率也较低，延迟约为4.5倍、Token约为1.6倍。
- qwen 的注入样本通过；不能据此推断其他安全能力。
- 超时配置不同是本次对照的混杂因素；若强制相同60秒，qwen 在评审上会更差。

## 结论
在当前固定样本和相同供应商下，**不把 qwen/qwen3.8-flash 作为默认或替代模型**。若后续要正式比较，应先为 qwen 增加至少三次重复、按模型固定超时，并分别记录超时/无效输出与语义错误。金额未知，Token 不是账单。

数据摘要：`eval/model-comparison/2026-11-03-qwen-vs-deepseek.json`。原始结果保留在本地忽略目录，不提交正文。
