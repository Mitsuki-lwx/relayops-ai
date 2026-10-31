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
