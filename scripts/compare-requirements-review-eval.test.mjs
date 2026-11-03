import { test } from "node:test";
import assert from "node:assert/strict";
import { compareReports, parseArgs } from "./compare-requirements-review-eval.mjs";

const report = (passRate, cases, extra = {}) => ({
  dataset: { schemaVersion: "relayops.requirements-review.eval.v1", cases: cases.length },
  summary: { passRate, repeatPassRate: passRate, findingRecall: 1, criterionRecall: 1, structuralFailures: 0, forbiddenFindingHits: 0, forbiddenTermHits: 0, ...extra },
  cases,
});

test("detects case regressions, fixes and metric drops", () => {
  const baseline = report(1, [{ id: "rr-001", title: "a", pass: true }, { id: "rr-002", title: "b", pass: false }]);
  const current = report(0.5, [{ id: "rr-001", title: "a", pass: false, passRate: 0.67, failureReasons: ["repeat 3: missing GAP"] }, { id: "rr-002", title: "b", pass: true }], { findingRecall: 0.8 });
  const result = compareReports(baseline, current);
  assert.equal(result.regressions.length, 1);
  assert.equal(result.regressions[0].id, "rr-001");
  assert.equal(result.fixes.length, 1);
  assert.equal(result.fixes[0].id, "rr-002");
  assert.equal(result.passDrop, 0.5);
  assert.equal(result.recallDrop, 0.2);
  assert.equal(result.deltas.structuralFailures, 0);
});

test("detects new and removed cases without counting them as regressions", () => {
  const baseline = report(1, [{ id: "rr-001", title: "a", pass: true }]);
  const current = report(1, [{ id: "rr-002", title: "b", pass: true }]);
  const result = compareReports(baseline, current);
  assert.equal(result.regressions.length, 0);
  assert.deepEqual(result.newCases.map(item => item.id), ["rr-002"]);
  assert.deepEqual(result.removedCases.map(item => item.id), ["rr-001"]);
});

test("parses thresholds and rejects negative values", () => {
  const args = parseArgs(["--baseline", "a.json", "--current", "b.json", "--max-pass-drop", "0.1", "--max-recall-drop", "0.05"]);
  assert.equal(args.maxPassDrop, 0.1);
  assert.equal(args.maxRecallDrop, 0.05);
  assert.throws(() => parseArgs(["--baseline", "a.json", "--current", "b.json", "--max-pass-drop", "-1"]));
});
