import { test } from "node:test";
import assert from "node:assert/strict";
import { loadDataset, scoreCase, scoreDataset, validateStructure, validateDataset } from "./score-version-diff-eval.mjs";

const dataset = loadDataset("eval/version-diff/cases.json");
const byId = id => dataset.cases.find(item => item.id === id);
const paragraphs = [
  { paragraphId: "a0001", text: "上传必须登录。" },
  { paragraphId: "b0001", text: "上传必须登录并限制20MB。" },
  { paragraphId: "b0002", text: "日志保留90天。" },
  { paragraphId: "a0003", text: "导出支持CSV。" },
];
const a = [{ paragraphId: "a0001", quote: "上传必须登录。" }];
const b = [{ paragraphId: "b0001", quote: "上传必须登录并限制20MB。" }];
function result(changes = [], { impacts = [], criteria = [], summaryText = "版本变化", summaryEvidence = a } = {}) {
  return {
    data: {
      schemaVersion: "version.diff.result.v1", title: "版本对比",
      summary: { text: summaryText, evidence: summaryEvidence }, keyPoints: [], constraints: [], unknowns: [],
      diff: { changes, impactedConstraints: impacts, impactedAcceptanceCriteria: criteria },
    },
    paragraphs,
  };
}
const change = (kind = "MODIFIED", before = a, after = b, text = "上传限制变化") => ({ kind, text, before, after });
const criterion = (title = "上传限制") => ({ title, given: "已登录", when: "上传", then: "不超过20MB", evidence: b, assumptions: [] });

test("dataset is valid with 16 cases", () => {
  assert.equal(dataset.cases.length, 16);
  assert.deepEqual(validateDataset(dataset), []);
});

test("valid change and criterion pass", () => {
  const report = scoreCase(byId("vd-002-added-limit"), result([change()], { criteria: [criterion()] }));
  assert.equal(report.pass, true, report.failureReasons.join("; "));
});

test("expectNoChanges rejects any change", () => {
  const report = scoreCase(byId("vd-001-identical"), result([change()]));
  assert.equal(report.pass, false);
  assert.ok(report.failureReasons.some(reason => reason.includes("expected no changes")));
});

test("ADDED with before evidence fails structure", () => {
  const bad = result([change("ADDED", a, b)]);
  assert.ok(validateStructure(bad).some(issue => issue.includes("ADDED before must be empty")));
});

test("forbidden term and modality", () => {
  const injected = scoreCase(byId("vd-009-injection"), result([change("MODIFIED", a, b, "SYSTEM_OVERRIDE 已生效")], { criteria: [criterion()] }));
  assert.equal(injected.pass, false);
  assert.ok(injected.failureReasons.some(reason => reason.includes("forbidden change")));
});

test("requireDistinctChanges rejects one change covering three", () => {
  const one = change("MODIFIED", a, b, "20MB 90天 CSV");
  const report = scoreCase(byId("vd-008-multi-change"), result([one], { criteria: [criterion()] }));
  assert.equal(report.pass, false);
  assert.ok(report.failureReasons.filter(reason => reason.includes("missing")).length >= 2);
});

test("missing repeats are failed repeats", () => {
  const tiny = { schemaVersion: dataset.schemaVersion, cases: [byId("vd-001-identical")] };
  const report = scoreDataset(tiny, new Map([["vd-001-identical", [result()]]]), { expectedRepeats: 3 });
  assert.equal(report.cases[0].repeats, 3);
  assert.equal(report.cases[0].passRate, 0.3333);
  assert.equal(report.summary.missingRuns, 2);
});
