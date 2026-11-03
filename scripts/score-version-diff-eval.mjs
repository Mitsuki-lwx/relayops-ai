#!/usr/bin/env node
// Deterministic scorer for eval/version-diff/cases.json.
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const DATASET = "relayops.version-diff.eval.v1";
const SCHEMA = "version.diff.result.v1";
const KINDS = new Set(["ADDED", "REMOVED", "MODIFIED"]);
const norm = value => String(value ?? "").normalize("NFKC").toLowerCase();
const text = value => typeof value === "string" ? value : "";
const includesAny = (haystack, anyOf) => anyOf.some(term => norm(haystack).includes(norm(term)));

export function loadDataset(path) {
  const data = JSON.parse(readFileSync(path, "utf8"));
  const issues = validateDataset(data);
  if (issues.length) throw new Error("Invalid version-diff dataset:\\n" + issues.join("\\n"));
  return data;
}

export function validateDataset(data) {
  const issues = [];
  if (data?.schemaVersion !== DATASET) issues.push(`schemaVersion must be ${DATASET}`);
  if (!Array.isArray(data?.cases) || !data.cases.length) issues.push("cases must be non-empty");
  const ids = new Set();
  (data?.cases ?? []).forEach((testCase, index) => {
    const where = `cases[${index}]`;
    if (!/^vd-[0-9]{3}-[a-z0-9-]+$/.test(testCase.id ?? "")) issues.push(`${where}.id is invalid`);
    if (ids.has(testCase.id)) issues.push(`${where}.id is duplicated`);
    ids.add(testCase.id);
    for (const field of ["title", "base", "revised", "notes"]) if (!text(testCase[field]).trim()) issues.push(`${where}.${field} is required`);
    for (const field of ["base", "revised"]) if (text(testCase[field]).length > 6000) issues.push(`${where}.${field} exceeds 6000 characters`);
    if (!Array.isArray(testCase.tags) || !testCase.tags.length) issues.push(`${where}.tags must be non-empty`);
    const expect = testCase.expect;
    if (!expect || typeof expect.expectNoChanges !== "boolean") { issues.push(`${where}.expect is invalid`); return; }
    for (const key of ["requiredChanges", "forbiddenChanges", "requiredImpacts", "requiredCriteria", "forbiddenTerms"])
      if (!Array.isArray(expect[key])) issues.push(`${where}.expect.${key} must be an array`);
    for (const [i, rule] of (expect.requiredChanges ?? []).entries()) {
      if (!Array.isArray(rule.kinds) || !rule.kinds.length || rule.kinds.some(kind => !KINDS.has(kind))) issues.push(`${where}.requiredChanges[${i}].kinds is invalid`);
      if (!Array.isArray(rule.anyOf) || !rule.anyOf.length || rule.anyOf.some(value => !text(value).trim())) issues.push(`${where}.requiredChanges[${i}].anyOf is invalid`);
    }
    for (const [i, rule] of (expect.forbiddenChanges ?? []).entries()) {
      if (!rule.kinds && !rule.anyOf) issues.push(`${where}.forbiddenChanges[${i}] needs kinds or anyOf`);
      if (rule.kinds && rule.kinds.some(kind => !KINDS.has(kind))) issues.push(`${where}.forbiddenChanges[${i}].kinds is invalid`);
    }
    for (const key of ["requiredImpacts", "requiredCriteria"]) for (const [i, rule] of (expect[key] ?? []).entries()) {
      if (!Array.isArray(rule.anyOf) || !rule.anyOf.length) issues.push(`${where}.${key}[${i}].anyOf is invalid`);
    }
    if ((expect.forbiddenTerms ?? []).some(term => !text(term).trim())) issues.push(`${where}.forbiddenTerms has an empty term`);
    if (expect.expectNoChanges && ((expect.requiredChanges ?? []).length || (expect.forbiddenChanges ?? []).length)) issues.push(`${where} cannot combine expectNoChanges with change rules`);
  });
  return issues;
}

function evidenceIssues(evidence, paragraphs, label) {
  const issues = [];
  if (!Array.isArray(evidence) || evidence.length < 1 || evidence.length > 10) return [`${label}: 1-10 evidence items required`];
  for (const item of evidence) {
    if (!item || typeof item.paragraphId !== "string" || typeof item.quote !== "string" || !item.quote.trim() || item.quote.length > 1000) { issues.push(`${label}: invalid evidence item`); continue; }
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
  const result = raw?.result?.data ? raw.result : raw;
  const data = result?.data ?? {};
  const paragraphs = Array.isArray(result?.paragraphs) ? result.paragraphs : [];
  const issues = [];
  if (data.schemaVersion !== SCHEMA) issues.push(`schemaVersion must be ${SCHEMA}`);
  if (!paragraphs.length) issues.push("paragraphs are required for evidence checks");
  if (!Array.isArray(data.keyPoints) || data.keyPoints.length || !Array.isArray(data.constraints) || data.constraints.length || !Array.isArray(data.unknowns) || data.unknowns.length) issues.push("keyPoints/constraints/unknowns must be empty");
  if (data.review !== undefined && data.review !== null) issues.push("review must be absent");
  const summary = data.summary ?? {};
  if (!text(summary.text).trim() || summary.text.length > 2000) issues.push("summary.text is invalid");
  issues.push(...evidenceIssues(summary.evidence, paragraphs, "summary"));
  const diff = data.diff;
  if (!diff || !Array.isArray(diff.changes) || !Array.isArray(diff.impactedConstraints) || !Array.isArray(diff.impactedAcceptanceCriteria)) return [...issues, "diff.changes/impactedConstraints/impactedAcceptanceCriteria are required"];
  if (diff.changes.length > 8 || diff.impactedConstraints.length > 6 || diff.impactedAcceptanceCriteria.length > 6) issues.push("diff fan-out exceeds limits");
  diff.changes.forEach((change, index) => {
    const label = `changes[${index}]`;
    if (!KINDS.has(change?.kind)) issues.push(`${label}: kind is invalid`);
    if (!text(change?.text).trim() || change.text.length > 1000) issues.push(`${label}: text is invalid`);
    if (!Array.isArray(change?.before) || !Array.isArray(change?.after)) { issues.push(`${label}: before/after must be arrays`); return; }
    if (change.kind === "ADDED") {
      if (change.before.length) issues.push(`${label}: ADDED before must be empty`);
      issues.push(...evidenceIssues(change.after, paragraphs, `${label}.after`));
    } else if (change.kind === "REMOVED") {
      if (change.after.length) issues.push(`${label}: REMOVED after must be empty`);
      issues.push(...evidenceIssues(change.before, paragraphs, `${label}.before`));
    } else if (change.kind === "MODIFIED") {
      issues.push(...evidenceIssues(change.before, paragraphs, `${label}.before`));
      issues.push(...evidenceIssues(change.after, paragraphs, `${label}.after`));
    }
  });
  for (const [index, impact] of diff.impactedConstraints.entries()) {
    if (!text(impact?.text).trim() || impact.text.length > 2000) issues.push(`impactedConstraints[${index}].text is invalid`);
    issues.push(...evidenceIssues(impact?.evidence, paragraphs, `impactedConstraints[${index}]`));
  }
  for (const [index, criterion] of diff.impactedAcceptanceCriteria.entries()) {
    for (const field of ["title", "given", "when", "then"]) if (!text(criterion?.[field]).trim()) issues.push(`impactedAcceptanceCriteria[${index}].${field} is required`);
    if (!Array.isArray(criterion?.assumptions) || criterion.assumptions.length > 5) issues.push(`impactedAcceptanceCriteria[${index}].assumptions is invalid`);
    issues.push(...evidenceIssues(criterion?.evidence, paragraphs, `impactedAcceptanceCriteria[${index}]`));
  }
  return issues;
}

function changeHaystack(change) {
  return [text(change?.text), ...(change?.before ?? []).map(item => text(item?.quote)), ...(change?.after ?? []).map(item => text(item?.quote))].join("\n");
}

function proseText(result) {
  const data = (result?.result?.data ? result.result : result)?.data ?? {};
  const diff = data.diff ?? {};
  return [text(data.summary?.text), ...(diff.changes ?? []).map(change => text(change?.text)), ...(diff.impactedConstraints ?? []).map(impact => text(impact?.text)), ...(diff.impactedAcceptanceCriteria ?? []).flatMap(criteriaText)].join("\n");
}

function matchRequired(rules, changes, distinct) {
  const candidates = rules.map(rule => changes.map((change, index) => {
    const before = change?.before?.length ?? 0, after = change?.after?.length ?? 0;
    const ok = (!rule.kinds || rule.kinds.includes(change?.kind)) && before >= (rule.minBefore ?? 0) && after >= (rule.minAfter ?? 0) && includesAny(changeHaystack(change), rule.anyOf);
    return ok ? index : -1;
  }).filter(index => index >= 0));
  const assigned = new Array(rules.length).fill(null);
  if (!distinct) { candidates.forEach((list, index) => { assigned[index] = list[0] ?? null; }); return assigned; }
  const used = new Set();
  for (const { index } of candidates.map((list, index) => ({ index, size: list.length })).sort((a, b) => a.size - b.size)) {
    const match = candidates[index].find(value => !used.has(value));
    if (match !== undefined) { assigned[index] = match; used.add(match); }
  }
  return assigned;
}

export function scoreCase(testCase, raw) {
  const result = raw?.result?.data ? raw.result : raw;
  const data = result?.data ?? {};
  const diff = data.diff ?? {};
  const changes = Array.isArray(diff.changes) ? diff.changes : [];
  const impacts = Array.isArray(diff.impactedConstraints) ? diff.impactedConstraints : [];
  const criteria = Array.isArray(diff.impactedAcceptanceCriteria) ? diff.impactedAcceptanceCriteria : [];
  const expect = testCase.expect;
  const structure = validateStructure(result);
  const assigned = matchRequired(expect.requiredChanges ?? [], changes, !!expect.requireDistinctChanges);
  const requiredChanges = (expect.requiredChanges ?? []).map((rule, index) => ({ rule, matched: assigned[index] !== null, changeIndex: assigned[index] }));
  const forbiddenChanges = (expect.forbiddenChanges ?? []).map(rule => {
    const index = changes.findIndex(change => (!rule.kinds || rule.kinds.includes(change?.kind)) && (!rule.anyOf || includesAny(changeHaystack(change), rule.anyOf)));
    return { rule, matched: index >= 0, changeIndex: index >= 0 ? index : null };
  });
  const requiredImpacts = (expect.requiredImpacts ?? []).map(rule => { const index = impacts.findIndex(impact => includesAny(text(impact?.text), rule.anyOf)); return { rule, matched: index >= 0, impactIndex: index >= 0 ? index : null }; });
  const requiredCriteria = (expect.requiredCriteria ?? []).map(rule => { const index = criteria.findIndex(criterion => includesAny(criteriaText(criterion), rule.anyOf)); return { rule, matched: index >= 0, criterionIndex: index >= 0 ? index : null }; });
  const forbiddenTerms = (expect.forbiddenTerms ?? []).map(term => ({ term, matched: includesAny(proseText(result), [term]) }));
  const noChanges = !expect.expectNoChanges || changes.length === 0;
  const missingChanges = requiredChanges.filter(item => !item.matched), extraChanges = forbiddenChanges.filter(item => item.matched);
  const missingImpacts = requiredImpacts.filter(item => !item.matched), missingCriteria = requiredCriteria.filter(item => !item.matched), badTerms = forbiddenTerms.filter(item => item.matched);
  const pass = !structure.length && noChanges && !missingChanges.length && !extraChanges.length && !missingImpacts.length && !missingCriteria.length && !badTerms.length;
  return {
    id: testCase.id, title: testCase.title, tags: testCase.tags, present: true, pass, structure,
    requiredChanges, forbiddenChanges, requiredImpacts, requiredCriteria, forbiddenTerms,
    expectNoChanges: { expected: !!expect.expectNoChanges, actual: changes.length },
    failureReasons: [...structure, ...(noChanges ? [] : [`expected no changes, got ${changes.length}`]), ...missingChanges.map(item => `missing ${item.rule.kinds.join("|")} match: ${item.rule.anyOf.join(" | ")}`), ...extraChanges.map(item => `forbidden change matched: ${(item.rule.kinds ?? []).join("|")} ${(item.rule.anyOf ?? []).join(" | ")}`), ...missingImpacts.map(item => `missing impact match: ${item.rule.anyOf.join(" | ")}`), ...missingCriteria.map(item => `missing criterion match: ${item.rule.anyOf.join(" | ")}`), ...badTerms.map(item => `forbidden term: ${item.term}`)],
  };
}

export function scoreCaseRuns(testCase, raws, expectedRepeats = 0) {
  const runs = (raws ?? []).map(raw => scoreCase(testCase, raw));
  const total = Math.max(expectedRepeats, runs.length);
  if (!total) return { id: testCase.id, title: testCase.title, tags: testCase.tags, present: false, repeats: 0, presentRepeats: 0, missingRepeats: 0, pass: false, passRate: 0, runs: [], failureReasons: ["result file missing"] };
  const passed = runs.filter(run => run.pass).length, missing = Math.max(0, total - runs.length);
  return { id: testCase.id, title: testCase.title, tags: testCase.tags, present: runs.length > 0, repeats: total, presentRepeats: runs.length, missingRepeats: missing, repeatPasses: passed, pass: passed === total, passRate: Number((passed / total).toFixed(4)), runs, failureReasons: [...(missing ? [`${missing} repeat(s) missing`] : []), ...runs.flatMap((run, index) => run.failureReasons.map(reason => `repeat ${index + 1}: ${reason}`))] };
}

export function scoreDataset(dataset, results, options = {}) {
  const expectedRepeats = Number.isInteger(options.expectedRepeats) ? options.expectedRepeats : 0;
  const cases = dataset.cases.map(testCase => scoreCaseRuns(testCase, results.get(testCase.id) ?? [], expectedRepeats));
  const runs = cases.flatMap(item => item.runs);
  const count = (list, predicate = Boolean) => list.reduce((n, item) => n + (predicate(item) ? 1 : 0), 0);
  const requiredChanges = runs.reduce((n, run) => n + run.requiredChanges.length, 0), matchedChanges = runs.reduce((n, run) => n + count(run.requiredChanges, item => item.matched), 0);
  const requiredImpacts = runs.reduce((n, run) => n + run.requiredImpacts.length, 0), matchedImpacts = runs.reduce((n, run) => n + count(run.requiredImpacts, item => item.matched), 0);
  const requiredCriteria = runs.reduce((n, run) => n + run.requiredCriteria.length, 0), matchedCriteria = runs.reduce((n, run) => n + count(run.requiredCriteria, item => item.matched), 0);
  const failed = cases.filter(item => !item.pass);
  const expectedRuns = cases.reduce((n, item) => n + item.repeats, 0);
  return {
    dataset: { schemaVersion: dataset.schemaVersion, cases: dataset.cases.length, expectedRepeats: expectedRepeats || null },
    summary: {
      passed: cases.length - failed.length, failed: failed.length,
      passRate: cases.length ? Number(((cases.length - failed.length) / cases.length).toFixed(4)) : 0,
      repeatsPerCase: cases.length ? { min: Math.min(...cases.map(item => item.repeats)), max: Math.max(...cases.map(item => item.repeats)), mean: Number((cases.reduce((n, item) => n + item.repeats, 0) / cases.length).toFixed(2)) } : null,
      expectedRuns, repeatRuns: runs.length, missingRuns: cases.reduce((n, item) => n + item.missingRepeats, 0), repeatPasses: count(runs, run => run.pass),
      repeatPassRate: expectedRuns ? Number((count(runs, run => run.pass) / expectedRuns).toFixed(4)) : 0,
      requiredChanges, matchedChanges, changeRecall: requiredChanges ? Number((matchedChanges / requiredChanges).toFixed(4)) : null,
      requiredImpacts, matchedImpacts, impactRecall: requiredImpacts ? Number((matchedImpacts / requiredImpacts).toFixed(4)) : null,
      requiredCriteria, matchedCriteria, criterionRecall: requiredCriteria ? Number((matchedCriteria / requiredCriteria).toFixed(4)) : null,
      structuralFailures: count(runs, run => run.present && run.structure.length),
      forbiddenChangeHits: runs.reduce((n, run) => n + count(run.forbiddenChanges, item => item.matched), 0),
      forbiddenTermHits: runs.reduce((n, run) => n + count(run.forbiddenTerms, item => item.matched), 0),
    },
    cases,
  };
}

function loadResults(directories) {
  const map = new Map();
  for (const directory of directories) {
    if (!existsSync(directory)) continue;
    for (const name of readdirSync(directory)) {
      if (!name.endsWith(".json")) continue;
      const id = name.slice(0, -5);
      if (!map.has(id)) map.set(id, []);
      map.get(id).push(JSON.parse(readFileSync(join(directory, name), "utf8")));
    }
  }
  return map;
}

function usage() {
  return `Usage: node scripts/score-version-diff-eval.mjs --results <dir> [--results <dir> ...] [options]\\n\\nOptions:\\n  --cases <file>          Dataset (default: eval/version-diff/cases.json)\\n  --results <dir>         Results directory; repeat for multiple runs\\n  --expected-repeats <n>  Count missing per-case results as failed repeats\\n  --json                  Machine-readable JSON\\n  --allow-failures        Exit 0 despite failures\\n  --validate-only         Validate dataset only\\n  --help\\n`;
}

function main(argv) {
  const args = { cases: "eval/version-diff/cases.json", results: [], expectedRepeats: 0, json: false, allowFailures: false, validateOnly: false };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--cases") args.cases = argv[++i];
    else if (argv[i] === "--results") args.results.push(argv[++i]);
    else if (argv[i] === "--expected-repeats") args.expectedRepeats = Number(argv[++i]);
    else if (argv[i] === "--json") args.json = true;
    else if (argv[i] === "--allow-failures") args.allowFailures = true;
    else if (argv[i] === "--validate-only") args.validateOnly = true;
    else if (argv[i] === "--help" || argv[i] === "-h") { console.log(usage()); return 0; }
    else { console.error(`Unknown argument: ${argv[i]}\\n${usage()}`); return 2; }
  }
  const dataset = loadDataset(resolve(args.cases));
  if (args.validateOnly) { console.log(JSON.stringify({ valid: true, cases: dataset.cases.length, schemaVersion: dataset.schemaVersion })); return 0; }
  if (!args.results.length) { console.error("--results is required unless --validate-only is used"); return 2; }
  if (!Number.isInteger(args.expectedRepeats) || args.expectedRepeats < 0) { console.error("--expected-repeats must be a non-negative integer"); return 2; }
  const report = scoreDataset(dataset, loadResults(args.results.map(value => resolve(value))), { expectedRepeats: args.expectedRepeats });
  if (args.json) console.log(JSON.stringify(report, null, 2));
  else {
    for (const item of report.cases) {
      console.log(`${item.pass ? "PASS" : "FAIL"} ${item.id}  ${item.title}  [${item.repeatPasses}/${item.repeats} repeats]`);
      if (!item.pass) for (const reason of item.failureReasons) console.log(`  - ${reason}`);
    }
    console.log(JSON.stringify(report.summary));
  }
  return !args.allowFailures && report.summary.failed ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) process.exitCode = main(process.argv.slice(2));
