import type { Claim, ResultBody } from "./resultTypes";

type ReportJob = { jobId: string; title: string; modelProfile: string; createdAt: string };

// Dynamic content stays literal, including HTML, Markdown links and nested fences.
function literal(text: string) {
  let length = 3;
  for (const match of text.matchAll(/`+/g)) length = Math.max(length, match[0].length + 1);
  const fence = "`".repeat(length);
  return `${fence}text\n${text}\n${fence}\n`;
}

function claimReport(claim: Claim) {
  const evidence = claim.evidence.map(item => literal(`段落：${item.paragraphId}\n引用：${item.quote}`));
  return [literal(claim.text), "原文依据：\n", ...evidence].join("\n");
}

export function analysisReport(job: ReportJob, result: ResultBody, exportedAt: Date) {
  if (result.data.review) {
    const review = result.data.review;
    return [
      "# RelayOps 需求评审与验收草稿\n",
      "生成建议，待人工确认。关联原文用于定位背景，不证明某项要求缺失；模型未列出问题不代表需求完整。\n",
      "报告含原文摘录；下载副本不受平台后续撤权或自动清理控制。\n",
      "## 任务信息\n", literal(`任务ID：${job.jobId}\n任务标题：${job.title}\n冻结配置：${job.modelProfile}\n受理时间：${job.createdAt}\n导出时间：${exportedAt.toISOString()}`),
      "## 模型报告标题\n", literal(result.data.title),
      "## 摘要\n", claimReport(result.data.summary),
      "## 需求评审\n", ...review.findings.map((f, i) => [literal(`${i + 1}. ${f.kind}\n${f.text}\n待澄清：${f.question}`), ...f.evidence.map(e => literal(`段落：${e.paragraphId}\n引用：${e.quote}`))].join("\n")),
      ...(review.findings.length ? [] : ["模型未列出问题，不代表需求完整。\n"]),
      "## 验收草稿\n", ...review.acceptanceCriteria.map((c, i) => [literal(`${i + 1}. ${c.title}\n前置条件（Given）：${c.given}\n执行操作（When）：${c.when}\n预期行为（Then）：${c.then}`), "待确认假设：\n", ...(c.assumptions.length ? c.assumptions.map(literal) : ["模型未列出额外假设，仍需人工审核。\n"]), ...c.evidence.map(e => literal(`段落：${e.paragraphId}\n引用：${e.quote}`))].join("\n")),
      ...(review.acceptanceCriteria.length ? [] : ["未生成可核对的验收草稿。\n"]),
      "引用匹配仅说明原文可定位，不代表事实判断完全正确。\n",
    ].join("\n");
  }
  const claims = (items: Claim[], empty: string) => items.length ? items.map(claimReport).join("\n") : empty + "\n";
  return [
    "# RelayOps 分析报告\n",
    "报告含原文摘录；下载副本不受平台后续撤权或自动清理控制。\n",
    "## 任务信息\n",
    literal(`任务ID：${job.jobId}\n任务标题：${job.title}\n冻结配置：${job.modelProfile}\n受理时间：${job.createdAt}\n导出时间：${exportedAt.toISOString()}`),
    "## 模型报告标题\n", literal(result.data.title),
    "## 摘要\n", claimReport(result.data.summary),
    "## 关键结论\n", claims(result.data.keyPoints, "未提取到关键结论。"),
    "## 约束与要求\n", claims(result.data.constraints, "未提取到明确约束。"),
    "## 未确定信息\n", result.data.unknowns.length ? result.data.unknowns.map(literal).join("\n") : "模型未列出额外待确认项。\n",
    "引用匹配仅说明原文可定位，不代表事实判断完全正确。\n",
  ].join("\n");
}

export function reportFilename(jobId: string) {
  return `relayops-analysis-${jobId.replace(/[^A-Za-z0-9_-]/g, "").slice(0, 64) || "report"}.md`;
}
