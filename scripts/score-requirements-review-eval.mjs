#!/usr/bin/env node
// Deterministic scorer for eval/requirements-review/cases.json.
// It never calls a model; generated prose is never treated as trusted input.
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const DATASET_SCHEMA = "relayops.requirements-review.eval.v1";
const RESULT_SCHEMA = "requirements.review.result.v1";
const KINDS = new Set(["GAP", "AMBIGUITY", "CONFLICT"]);

const norm = value => String(value ?? "").normalize("NFKC").toLowerCase();
const text = value => typeof value === "string" ? value : "";
const includesAny = (haystack, anyOf) => anyOf.some(term => norm(haystack).includes(norm(term)));

export function loadDataset(path) {
  const data = JSON.parse(readFileSync(path, "utf8"));
  const issues = validateDataset(data);
  if (issues.length) throw new Error("Invalid evaluation dataset:\n" + issues.join("\n"));
  return data;
}

export function validateDataset(data) {
  const issues = [];
  if (data?.schemaVersion !== DATASET_SCHEMA) issues.push(`schemaVersion must be ${DATASET_SCHEMA}`);
  if (!Array.isArray(data?.cases) || !data.cases.length) issues.push("cases must be a non-empty array");
  const ids = new Set();
  (data?.cases ?? []).forEach((testCase, index) => {
    const where = `cases[${index}]`;
    if (!/^rr-[0-9]{3}-[a-z0-9-]+$/.test(testCase.id ?? "")) issues.push(`${where}.id must match rr-NNN-slug`);
    if (ids.has(testCase.id)) issues.push(`${where}.id is duplicated`);
    ids.add(testCase.id);
    if (!text(testCase.title).trim() || text(testCase.title).length > 100) issues.push(`${where}.title is invalid`);
    if (!text(testCase.input).trim() || text(testCase.input).length > 6000) issues.push(`${where}.input is invalid`);
    if (!Array.isArray(testCase.tags) || !testCase.tags.length) issues.push(`${where}.tags must be non-empty`);
    if (!text(testCase.notes).trim()) issues.push(`${where}.notes is required`);
    const expect = testCase.expect;
    if (!expect || typeof expect !== "object") { issues.push(`${where}.expect is required`); return; }
    if (typeof expect.expectNoFindings !== "boolean") issues.push(`${where}.expect.expectNoFindings must be boolean`);
    const required = expect.requiredFindings ?? [];
    const forbidden = expect.forbiddenFindings ?? [];
    const criteria = expect.requiredCriteria ?? [];
    const terms = expect.forbiddenTerms ?? [];
    for (const key of ["requiredFindings", "forbiddenFindings", "requiredCriteria", "forbiddenTerms"])
      if (!Array.isArray(expect[key])) issues.push(`${where}.expect.${key} must be an array`);
    required.forEach((rule, i) => {
      if (!KINDS.has(rule.kind)) issues.push(`${where}.requiredFindings[${i}].kind is invalid`);
      if (!Array.isArray(rule.anyOf) || !rule.anyOf.length || rule.anyOf.some(x => !text(x).trim())) issues.push(`${where}.requiredFindings[${i}].anyOf is invalid`);
      if (rule.minEvidence !== undefined && (!Number.isInteger(rule.minEvidence) || rule.minEvidence < 1 || rule.minEvidence > 10)) issues.push(`${where}.requiredFindings[${i}].minEvidence is invalid`);
    });
    forbidden.forEach((rule, i) => {
      if (!rule.kind && !rule.anyOf) issues.push(`${where}.forbiddenFindings[${i}] needs kind or anyOf`);
      if (rule.kind && !KINDS.has(rule.kind)) issues.push(`${where}.forbiddenFindings[${i}].kind is invalid`);
      if (rule.anyOf && (!Array.isArray(rule.anyOf) || !rule.anyOf.length)) issues.push(`${where}.forbiddenFindings[${i}].anyOf is invalid`);
    });
    criteria.forEach((rule, i) => {
      if (!Array.isArray(rule.anyOf) || !rule.anyOf.length || rule.anyOf.some(x => !text(x).trim())) issues.push(`${where}.requiredCriteria[${i}].anyOf is invalid`);
    });
    if (terms.some(term => !text(term).trim())) issues.push(`${where}.forbiddenTerms has an empty term`);
    if (expect.expectNoFindings && (required.length || forbidden.length)) issues.push(`${where} cannot combine expectNoFindings with finding rules`);
  });
  return issues;
}

export function validateManualReview(data, dataset) {
  const issues = [];
  if (data?.schemaVersion !== "relayops.requirements-review.manual-review.v1") issues.push("manual review schemaVersion is invalid");
  if (typeof data?.confirmedByUser !== "boolean") issues.push("manual review confirmedByUser must be boolean");
  if (!Array.isArray(data?.cases) || !data.cases.length) return [...issues, "manual review cases must be non-empty"];
  const known = new Set(dataset.cases.map(item => item.id));
  const seen = new Set();
  data.cases.forEach((item, index) => {
    if (!known.has(item.id)) issues.push(`manual review cases[${index}] has unknown id`);
    if (seen.has(item.id)) issues.push(`manual review cases[${index}] duplicates ${item.id}`);
    seen.add(item.id);
    if (!["pass", "fail"].includes(item.verdict)) issues.push(`manual review cases[${index}] has invalid verdict`);
    if (!Array.isArray(item.checks) || !item.checks.length) issues.push(`manual review cases[${index}] needs checks`);
  });
  return issues;
}

export function normalizeResult(raw) {
  if (raw?.result?.data) return raw.result;
  if (raw?.data?.review) return raw;
  return raw;
}

function evidenceIssues(evidence, paragraphs, label) {
  const issues = [];
  if (!Array.isArray(evidence) || evidence.length < 1 || evidence.length > 10) return [`${label}: 1-10 evidence items required`];
  for (const item of evidence) {
    if (!item || typeof item.paragraphId !== "string" || typeof item.quote !== "string" || !item.quote.trim() || item.quote.length > 1000) {
      issues.push(`${label}: invalid evidence item`);
      continue;
    }
    const paragraph = paragraphs.find(p => p.paragraphId === item.paragraphId);
    if (!paragraph || !paragraph.text.includes(item.quote)) issues.push(`${label}: quote does not match ${item.paragraphId}`);
  }
  return issues;
}

function criteriaText(criterion) {
  const c = criterion ?? {};
  return [c.title, c.given, c.when, c.then, ...(Array.isArray(c.assumptions) ? c.assumptions : [])].join("\n");
}

export function validateStructure(raw) {
  const result = normalizeResult(raw);
  const issues = [];
  if (result?.data?.schemaVersion !== RESULT_SCHEMA) issues.push(`schemaVersion must be ${RESULT_SCHEMA}`);
  const data = result?.data ?? {};
  const paragraphs = Array.isArray(result?.paragraphs) ? result.paragraphs : [];
  if (!paragraphs.length) issues.push("paragraphs are required for evidence checks");
  for (const field of ["keyPoints", "constraints", "unknowns"])
    if (!Array.isArray(data[field]) || data[field].length) issues.push(`${field} must be an empty array for review results`);
  const summary = data.summary ?? {};
  if (!text(summary.text).trim() || summary.text.length > 2000) issues.push("summary.text is invalid");
  issues.push(...evidenceIssues(summary.evidence, paragraphs, "summary"));
  const review = data.review;
  if (!review || !Array.isArray(review.findings) || !Array.isArray(review.acceptanceCriteria)) return [...issues, "review.findings and review.acceptanceCriteria are required"];
  if (review.findings.length > 6) issues.push("findings exceed 6");
  if (review.acceptanceCriteria.length > 6) issues.push("acceptanceCriteria exceed 6");
  review.findings.forEach((finding, index) => {
    const label = `findings[${index}]`;
    if (!KINDS.has(finding?.kind)) issues.push(`${label}: kind is invalid`);
    if (!text(finding?.text).trim() || finding.text.length > 1000) issues.push(`${label}: text is invalid`);
    if (!text(finding?.question).trim() || finding.question.length > 500) issues.push(`${label}: question is invalid`);
    issues.push(...evidenceIssues(finding?.evidence, paragraphs, label));
    if (finding?.kind === "CONFLICT") {
      const distinct = new Set((finding.evidence ?? []).map(e => `${e?.paragraphId}\\u0000${e?.quote}`));
      if (distinct.size < 2) issues.push(`${label}: CONFLICT needs two distinct references`);
    }
  });
  review.acceptanceCriteria.forEach((criterion, index) => {
    const label = `acceptanceCriteria[${index}]`;
    for (const field of ["title", "given", "when", "then"])
      if (!text(criterion?.[field]).trim()) issues.push(`${label}.${field} is required`);
    if (!Array.isArray(criterion?.assumptions) || criterion.assumptions.length > 5) issues.push(`${label}.assumptions is invalid`);
    issues.push(...evidenceIssues(criterion?.evidence, paragraphs, label));
  });
  return issues;
}

function findingHaystack(finding) {
  return [text(finding?.text), text(finding?.question), ...(finding?.evidence ?? []).map(e => text(e?.quote))].join("\n");
}

function proseText(result) {
  const data = normalizeResult(result)?.data ?? {};
  const review = data.review ?? {};
  return [
    text(data.summary?.text),
    ...(review.findings ?? []).map(f => text(f?.text)),
    ...(review.acceptanceCriteria ?? []).flatMap(criteriaText),
  ].join("\n");
}

function matchRequiredFindings(rules, findings, distinct) {
  const candidates = rules.map(rule => findings.map((finding, index) => {
    const evidenceCount = finding?.evidence?.length ?? 0;
    const ok = finding?.kind === rule.kind && evidenceCount >= (rule.minEvidence ?? 1) && includesAny(findingHaystack(finding), rule.anyOf);
    return ok ? index : -1;
  }).filter(index => index >= 0));
  const assigned = new Array(rules.length).fill(null);
  if (!distinct) {
    candidates.forEach((list, index) => { assigned[index] = list[0] ?? null; });
    return assigned;
  }
  const used = new Set();
  const order = candidates.map((list, index) => ({ index, size: list.length })).sort((a, b) => a.size - b.size);
  for (const { index } of order) {
    const indexMatch = candidates[index].find(value => !used.has(value));
    if (indexMatch !== undefined) { assigned[index] = indexMatch; used.add(indexMatch); }
  }
  return assigned;
}

export function scoreCase(testCase, rawResult) {
  const result = normalizeResult(rawResult);
  const structure = validateStructure(result);
  const data = result?.data ?? {};
  const review = data.review ?? {};
  const findings = Array.isArray(review.findings) ? review.findings : [];
  const criteria = Array.isArray(review.acceptanceCriteria) ? review.acceptanceCriteria : [];
  const expect = testCase.expect;
  const requiredMatches = matchRequiredFindings(expect.requiredFindings ?? [], findings, !!expect.requireDistinctFindings);
  const requiredFindings = (expect.requiredFindings ?? []).map((rule, index) => ({ rule, matched: requiredMatches[index] !== null, findingIndex: requiredMatches[index] }));
  const forbiddenFindings = (expect.forbiddenFindings ?? []).map(rule => {
    const matchedIndex = findings.findIndex(finding =>
      (!rule.kind || finding?.kind === rule.kind) &&
      (!rule.anyOf || includesAny([text(finding?.text), text(finding?.question)].join("\n"), rule.anyOf)));
    return { rule, matched: matchedIndex >= 0, findingIndex: matchedIndex >= 0 ? matchedIndex : null };
  });
  const requiredCriteria = (expect.requiredCriteria ?? []).map(rule => {
    const matchedIndex = criteria.findIndex(criterion => includesAny(criteriaText(criterion), rule.anyOf));
    return { rule, matched: matchedIndex >= 0, criterionIndex: matchedIndex >= 0 ? matchedIndex : null };
  });
  const prose = proseText(result);
  const forbiddenTerms = (expect.forbiddenTerms ?? []).map(term => ({ term, matched: includesAny(prose, [term]) }));
  const noFindings = !expect.expectNoFindings || findings.length === 0;
  const missingFindings = requiredFindings.filter(item => !item.matched);
  const extraFindings = forbiddenFindings.filter(item => item.matched);
  const missingCriteria = requiredCriteria.filter(item => !item.matched);
  const badTerms = forbiddenTerms.filter(item => item.matched);
  const pass = structure.length === 0 && noFindings && !missingFindings.length && !extraFindings.length && !missingCriteria.length && !badTerms.length;
  return {
    id: testCase.id,
    title: testCase.title,
    tags: testCase.tags,
    present: true,
    pass,
    structure,
    requiredFindings,
    forbiddenFindings,
    requiredCriteria,
    forbiddenTerms,
    expectNoFindings: { expected: !!expect.expectNoFindings, actual: findings.length },
    failureReasons: [
      ...structure,
      ...(noFindings ? [] : [`expected no findings, got ${findings.length}`]),
      ...missingFindings.map(item => `missing ${item.rule.kind} match: ${item.rule.anyOf.join(" | ")}`),
      ...extraFindings.map(item => `forbidden finding matched: ${item.rule.kind ?? "any"} ${(item.rule.anyOf ?? []).join(" | ")}`),
      ...missingCriteria.map(item => `missing criterion match: ${item.rule.anyOf.join(" | ")}`),
      ...badTerms.map(item => `forbidden term: ${item.term}`),
    ],
  };
}

export function scoreCaseRuns(testCase, rawResults, expectedRepeats = 0) {
  const runs = (rawResults ?? []).map(raw => scoreCase(testCase, raw));
  const total = Math.max(expectedRepeats, runs.length);
  if (!total) return { id: testCase.id, title: testCase.title, tags: testCase.tags, present: false, repeats: 0, presentRepeats: 0, missingRepeats: 0, pass: false, passRate: 0, structure: ["result file missing"], runs: [], failureReasons: ["result file missing"] };
  const passed = runs.filter(run => run.pass).length;
  const missing = Math.max(0, total - runs.length);
  return {
    id: testCase.id,
    title: testCase.title,
    tags: testCase.tags,
    present: runs.length > 0,
    repeats: total,
    presentRepeats: runs.length,
    missingRepeats: missing,
    pass: passed === total,
    passRate: Number((passed / total).toFixed(4)),
    structure: runs.flatMap(run => run.structure),
    runs,
    failureReasons: [
      ...(missing ? [`${missing} repeat(s) missing`] : []),
      ...runs.flatMap((run, index) => run.failureReasons.map(reason => `repeat ${index + 1}: ${reason}`)),
    ],
  };
}

export function scoreDataset(dataset, results, options = {}) {
  const expectedRepeats = Number.isInteger(options.expectedRepeats) ? options.expectedRepeats : 0;
  const scored = dataset.cases.map(testCase => {
    const value = results.get(testCase.id);
    const rawResults = Array.isArray(value) ? value : value ? [value] : [];
    return scoreCaseRuns(testCase, rawResults, expectedRepeats);
  });
  const count = (list, predicate = Boolean) => list.reduce((total, item) => total + (predicate(item) ? 1 : 0), 0);
  const allRuns = scored.flatMap(item => item.runs);
  const requiredFindings = allRuns.reduce((n, item) => n + item.requiredFindings.length, 0);
  const matchedFindings = allRuns.reduce((n, item) => n + count(item.requiredFindings, entry => entry.matched), 0);
  const requiredCriteria = allRuns.reduce((n, item) => n + item.requiredCriteria.length, 0);
  const matchedCriteria = allRuns.reduce((n, item) => n + count(item.requiredCriteria, entry => entry.matched), 0);
  const failed = scored.filter(item => !item.pass);
  const repeatCounts = scored.map(item => item.repeats);
  const expectedRuns = scored.reduce((n, item) => n + item.repeats, 0);
  const missingRuns = scored.reduce((n, item) => n + item.missingRepeats, 0);
  return {
    dataset: { schemaVersion: dataset.schemaVersion, cases: dataset.cases.length, expectedRepeats: expectedRepeats || null },
    summary: {
      passed: scored.length - failed.length,
      failed: failed.length,
      passRate: scored.length ? Number(((scored.length - failed.length) / scored.length).toFixed(4)) : 0,
      repeatsPerCase: repeatCounts.length ? { min: Math.min(...repeatCounts), max: Math.max(...repeatCounts), mean: Number((repeatCounts.reduce((a, b) => a + b, 0) / repeatCounts.length).toFixed(2)) } : null,
      expectedRuns,
      repeatRuns: allRuns.length,
      missingRuns,
      repeatPasses: count(allRuns, item => item.pass),
      repeatPassRate: expectedRuns ? Number((count(allRuns, item => item.pass) / expectedRuns).toFixed(4)) : 0,
      requiredFindings,
      matchedFindings,
      findingRecall: requiredFindings ? Number((matchedFindings / requiredFindings).toFixed(4)) : null,
      requiredCriteria,
      matchedCriteria,
      criterionRecall: requiredCriteria ? Number((matchedCriteria / requiredCriteria).toFixed(4)) : null,
      structuralFailures: count(allRuns, item => item.present && item.structure.length),
      forbiddenFindingHits: allRuns.reduce((n, item) => n + count(item.forbiddenFindings, entry => entry.matched), 0),
      forbiddenTermHits: allRuns.reduce((n, item) => n + count(item.forbiddenTerms, entry => entry.matched), 0),
    },
    cases: scored,
  };
}

function loadResults(directories) {
  const map = new Map();
  for (const directory of directories) {
    if (!existsSync(directory)) continue;
    for (const name of readdirSync(directory)) {
      if (!name.endsWith(".json")) continue;
      const id = name.slice(0, -5);
      const raw = JSON.parse(readFileSync(join(directory, name), "utf8"));
      if (!map.has(id)) map.set(id, []);
      map.get(id).push(raw);
    }
  }
  return map;
}

function usage() {
  return `Usage: node scripts/score-requirements-review-eval.mjs --results <dir> [--results <dir> ...] [options]\n\nOptions:\n  --cases <file>       Dataset (default: eval/requirements-review/cases.json)\n  --results <dir>      Directory of <case-id>.json result bodies; repeat for multiple runs\n  --json               Print machine-readable JSON only\n  --allow-failures     Exit 0 even when some cases fail\n  --validate-only      Validate the dataset and exit without results\n  --help               Show this help\n`;
}

function main(argv) {
  const args = { cases: "eval/requirements-review/cases.json", results: [], manualReview: "", expectedRepeats: 0, json: false, allowFailures: false, validateOnly: false };
  for (let i = 0; i <argv.length; i++) {
    if (argv[i] === "--cases") args.cases = argv[++i];
    else if (argv[i] === "--results") args.results.push(argv[++i]);
    else if (argv[i] === "--manual-review") args.manualReview = argv[++i];
    else if (argv[i] === "--expected-repeats") args.expectedRepeats = Number(argv[++i]);
    else if (argv[i] === "--json") args.json = true;
    else if (argv[i] === "--allow-failures") args.allowFailures = true;
    else if (argv[i] === "--validate-only") args.validateOnly = true;
    else if (argv[i] === "--help" || argv[i] === "-h") { console.log(usage()); return 0; }
    else { console.error(`Unknown argument: ${argv[i]}\n\n${usage()}`); return 2; }
  }
  const dataset = loadDataset(resolve(args.cases));
  if (args.validateOnly) {
    console.log(JSON.stringify({ valid: true, cases: dataset.cases.length, schemaVersion: dataset.schemaVersion }));
    return 0;
  }
  if (!Number.isInteger(args.expectedRepeats) || args.expectedRepeats < 0) { console.error("--expected-repeats must be a non-negative integer\n"); return 2; }
  if (!args.results.length) { console.error("--results is required unless --validate-only is used\n"); console.error(usage()); return 2; }
  const report = scoreDataset(dataset, loadResults(args.results.map(result => resolve(result))), { expectedRepeats: args.expectedRepeats });
  if (args.manualReview) {
    const manual = JSON.parse(readFileSync(resolve(args.manualReview), "utf8"));
    const issues = validateManualReview(manual, dataset);
    if (issues.length) throw new Error("Invalid manual review:\n" + issues.join("\n"));
    const failed = manual.cases.filter(item => item.verdict === "fail");
    report.manualReview = { file: args.manualReview, confirmedByUser: manual.confirmedByUser, cases: manual.cases.length, passed: manual.cases.length - failed.length, failed: failed.length, failures: failed.map(item => item.id) };
  }
  if (args.json) console.log(JSON.stringify(report, null, 2));
  else {
    for (const item of report.cases) {
      console.log(`${item.pass ? "PASS" : "FAIL"} ${item.id}  ${item.title}  [${item.repeatPasses ?? item.runs?.filter(run => run.pass).length ?? 0}/${item.repeats} repeats]`);
      if (!item.pass) for (const reason of item.failureReasons) console.log(`  - ${reason}`);
    }
    console.log(JSON.stringify(report.manualReview ? { ...report.summary, manualReview: report.manualReview } : report.summary));
  }
  return !args.allowFailures && (report.summary.failed || (report.manualReview?.failed ?? 0)) ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = main(process.argv.slice(2));
}
