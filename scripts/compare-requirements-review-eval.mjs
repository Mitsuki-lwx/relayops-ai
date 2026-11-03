#!/usr/bin/env node
// Compare two score-requirements-review-eval.mjs --json reports.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const METRICS = ["passRate", "repeatPassRate", "findingRecall", "changeRecall", "impactRecall", "criterionRecall", "structuralFailures", "forbiddenFindingHits", "forbiddenTermHits", "forbiddenChangeHits"];

export function parseArgs(argv) {
  const args = { baseline: "", current: "", json: false, allowRegressions: false, maxPassDrop: 0, maxRecallDrop: 0, help: false };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    const next = () => {
      const value = argv[++i];
      if (value === undefined || value.startsWith("--")) throw new Error(`Missing value for ${token}`);
      return value;
    };
    if (token === "--baseline") args.baseline = next();
    else if (token === "--current") args.current = next();
    else if (token === "--json") args.json = true;
    else if (token === "--allow-regressions") args.allowRegressions = true;
    else if (token === "--max-pass-drop") args.maxPassDrop = Number(next());
    else if (token === "--max-recall-drop") args.maxRecallDrop = Number(next());
    else if (token === "--help" || token === "-h") args.help = true;
    else throw new Error(`Unknown argument: ${token}`);
  }
  if (args.help) return args;
  if (!args.baseline || !args.current) throw new Error("--baseline and --current are required");
  if (!Number.isFinite(args.maxPassDrop) || args.maxPassDrop < 0) throw new Error("Invalid --max-pass-drop");
  if (!Number.isFinite(args.maxRecallDrop) || args.maxRecallDrop < 0) throw new Error("Invalid --max-recall-drop");
  return args;
}

export function compareReports(baseline, current) {
  const baseCases = new Map((baseline.cases ?? []).map(item => [item.id, item]));
  const currentCases = new Map((current.cases ?? []).map(item => [item.id, item]));
  const ids = new Set([...baseCases.keys(), ...currentCases.keys()]);
  const regressions = [];
  const fixes = [];
  const newCases = [];
  const removedCases = [];
  for (const id of ids) {
    const before = baseCases.get(id);
    const after = currentCases.get(id);
    if (!before) { newCases.push({ id, title: after.title, pass: after.pass }); continue; }
    if (!after) { removedCases.push({ id, title: before.title, pass: before.pass }); continue; }
    if (before.pass && !after.pass) regressions.push({ id, title: after.title, passRate: after.passRate ?? 0, failureReasons: after.failureReasons ?? [] });
    if (!before.pass && after.pass) fixes.push({ id, title: after.title });
  }
  const baseSummary = baseline.summary ?? {};
  const currentSummary = current.summary ?? {};
  const deltas = {};
  for (const metric of METRICS) {
    const before = baseSummary[metric] ?? 0;
    const after = currentSummary[metric] ?? 0;
    deltas[metric] = Number((after - before).toFixed(4));
  }
  const passDrop = (baseSummary.passRate ?? 0) - (currentSummary.passRate ?? 0);
  const recall = (summary, key) => summary.findingRecall ?? summary.changeRecall ?? summary[key] ?? null;
  const recallDrop = Math.max(
    (recall(baseSummary, "impactRecall") ?? 1) - (recall(currentSummary, "impactRecall") ?? 1),
    (baseSummary.criterionRecall ?? 1) - (currentSummary.criterionRecall ?? 1),
  );
  return {
    baseline: { dataset: baseline.dataset ?? null, summary: baseSummary },
    current: { dataset: current.dataset ?? null, summary: currentSummary },
    deltas,
    regressions,
    fixes,
    newCases,
    removedCases,
    passDrop: Number(passDrop.toFixed(4)),
    recallDrop: Number(recallDrop.toFixed(4)),
  };
}

function usage() {
  return `Usage: node scripts/compare-requirements-review-eval.mjs --baseline <report.json> --current <report.json> [options]\n\nOptions:\n  --json                 Print machine-readable JSON only\n  --allow-regressions    Exit 0 despite case regressions or metric drops\n  --max-pass-drop <n>    Allowed passRate drop (default 0)\n  --max-recall-drop <n>  Allowed finding/criterion recall drop (default 0)\n  --help\n\nInputs are the --json outputs of scripts/score-requirements-review-eval.mjs.\n`;
}

function main(argv) {
  const args = parseArgs(argv);
  if (args.help) { console.log(usage()); return 0; }
  const baseline = JSON.parse(readFileSync(resolve(args.baseline), "utf8"));
  const current = JSON.parse(readFileSync(resolve(args.current), "utf8"));
  const result = compareReports(baseline, current);
  const failed = result.regressions.length > 0 || result.passDrop > args.maxPassDrop || result.recallDrop > args.maxRecallDrop;
  if (args.json) console.log(JSON.stringify(result, null, 2));
  else {
    console.log(`passRate ${result.baseline.summary.passRate} -> ${result.current.summary.passRate} (${result.deltas.passRate >= 0 ? "+" : ""}${result.deltas.passRate})`);
    const baselineRecall = result.baseline.summary.findingRecall ?? result.baseline.summary.changeRecall;
    const currentRecall = result.current.summary.findingRecall ?? result.current.summary.changeRecall;
    if (baselineRecall !== undefined || currentRecall !== undefined) {
      const delta = (currentRecall ?? 0) - (baselineRecall ?? 0);
      console.log(`recall ${baselineRecall} -> ${currentRecall} (${delta >= 0 ? "+" : ""}${Number(delta.toFixed(4))})`);
    }
    console.log(`criterionRecall ${result.baseline.summary.criterionRecall} -> ${result.current.summary.criterionRecall} (${result.deltas.criterionRecall >= 0 ? "+" : ""}${result.deltas.criterionRecall})`);
    console.log(`regressions ${result.regressions.length}, fixes ${result.fixes.length}, new ${result.newCases.length}, removed ${result.removedCases.length}`);
    for (const item of result.regressions) console.log(`REGRESSION ${item.id} ${item.title} [${item.passRate} repeats] ${item.failureReasons.slice(0, 2).join("; ")}`);
  }
  return !args.allowRegressions && failed ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 2;
  }
}
