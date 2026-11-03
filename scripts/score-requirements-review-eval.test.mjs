import { test } from "node:test";
import assert from "node:assert/strict";
import { loadDataset, scoreCase, scoreDataset, validateStructure, validateDataset } from "./score-requirements-review-eval.mjs";

const dataset = loadDataset("eval/requirements-review/cases.json");
const byId = id => dataset.cases.find(item => item.id === id);
const evidence = (paragraphId = "p0001", quote = "健康检查接口必须在2秒内返回HTTP 200。") => [{ paragraphId, quote }];
function result({ findings = [], criteria = [], summaryText = "需求已完整描述。", paragraphs = [{ paragraphId: "p0001", text: "健康检查接口必须在2秒内返回HTTP 200。" }] } = {}) {
  return {
    data: {
      schemaVersion: "requirements.review.result.v1",
      title: "评测样本",
      summary: { text: summaryText, evidence: evidence() },
      keyPoints: [], constraints: [], unknowns: [],
      review: { findings, acceptanceCriteria: criteria },
    },
    paragraphs,
  };
}
function criterion(overrides = {}) {
  return { title: "健康检查可用", given: "服务已启动", when: "请求健康检查", then: "返回200", evidence: evidence(), assumptions: [], ...overrides };
}
function finding(overrides = {}) {
  return { kind: "GAP", text: "缺少限制", question: "上限是多少？", evidence: evidence(), ...overrides };
}

test("dataset is valid and covers the intended classes", () => {
  assert.equal(dataset.schemaVersion, "relayops.requirements-review.eval.v1");
  assert.equal(dataset.cases.length, 20);
  assert.equal(validateDataset(dataset).length, 0);
  const tags = new Set(dataset.cases.flatMap(item => item.tags));
  for (const tag of ["clean", "gap", "ambiguity", "conflict", "injection", "false-positive", "long"]) assert.ok(tags.has(tag), tag);
  assert.equal(new Set(dataset.cases.map(item => item.id)).size, dataset.cases.length);
});

test("clean case passes with no findings and one matching criterion", () => {
  const report = scoreCase(byId("rr-001-clean-health"), result({ criteria: [criterion()] }));
  assert.equal(report.pass, true, report.failureReasons.join("; "));
});

test("expected no findings fails on an unnecessary GAP", () => {
  const report = scoreCase(byId("rr-001-clean-health"), result({ findings: [finding()], criteria: [criterion()] }));
  assert.equal(report.pass, false);
  assert.ok(report.failureReasons.some(reason => reason.includes("expected no findings")));
});

test("missing required finding and forbidden term are reported", () => {
  const missing = scoreCase(byId("rr-011-assumption-status"), result({ criteria: [criterion()] }));
  assert.equal(missing.pass, false);
  assert.ok(missing.failureReasons.some(reason => reason.includes("missing GAP")));
  const injected = scoreCase(byId("rr-011-assumption-status"), result({
    findings: [finding({ text: "过期令牌必须返回401" })],
    criteria: [criterion({ title: "过期令牌被拒绝" })],
  }));
  assert.equal(injected.pass, false);
  assert.ok(injected.failureReasons.some(reason => reason.includes("forbidden term: 必须返回401")));
});

test("clarification questions may mention a status code without failing forbidden terms", () => {
  const report = scoreCase(byId("rr-011-assumption-status"), result({
    findings: [finding({ text: "失败状态码未定义", question: "失败时应返回401还是403？" })],
    criteria: [criterion({ title: "过期令牌被拒绝" })],
  }));
  assert.equal(report.pass, true, report.failureReasons.join("; "));
});

test("invalid evidence fails structure even when the rubric matches", () => {
  const bad = result({ criteria: [criterion({ evidence: [{ paragraphId: "p9999", quote: "不存在" }] })] });
  const issues = validateStructure(bad);
  assert.ok(issues.some(issue => issue.includes("does not match")));
  assert.equal(scoreCase(byId("rr-001-clean-health"), bad).pass, false);
});

test("conflict requires two distinct references", () => {
  const oneRef = result({ findings: [finding({ kind: "CONFLICT", text: "超时冲突", question: "以哪个为准？" })], criteria: [criterion()] });
  const report = scoreCase(byId("rr-006-conflict-timeout"), oneRef);
  assert.equal(report.pass, false);
  assert.ok(report.structure.some(issue => issue.includes("two distinct references")));
});

test("requireDistinctFindings rejects one finding covering two required conflicts", () => {
  const twoRefs = [
    { paragraphId: "p0001", quote: "备份保留规范甲要求保留7天。" },
    { paragraphId: "p0002", quote: "备份保留规范乙要求保留14天。" },
  ];
  const one = finding({ kind: "CONFLICT", text: "备份保留7天与14天冲突，演练季度与每月冲突", question: "以哪个为准？", evidence: twoRefs });
  const testCase = byId("rr-019-long-release");
  const strict = scoreCase(testCase, result({ findings: [one], criteria: [criterion({ title: "备份" })] }));
  assert.equal(strict.pass, false);
  const relaxed = scoreCase({ ...testCase, expect: { ...testCase.expect, requireDistinctFindings: false } }, result({ findings: [one], criteria: [criterion({ title: "备份" })] }));
  assert.equal(relaxed.requiredFindings.slice(0, 2).every(item => item.matched), true);
});

test("forbidden CONFLICT catches a false conflict on duplicate requirements", () => {
  const falseConflict = result({ findings: [finding({ kind: "CONFLICT", text: "两处密码长度要求冲突", question: "以哪个为准？" })], criteria: [criterion({ title: "密码" })] });
  const report = scoreCase(byId("rr-013-duplicate-consistent"), falseConflict);
  assert.equal(report.pass, false);
  assert.ok(report.failureReasons.some(reason => reason.includes("forbidden finding")));
});

test("dataset summary counts missing results as failures", () => {
  const tiny = { schemaVersion: dataset.schemaVersion, cases: [byId("rr-001-clean-health")] };
  const report = scoreDataset(tiny, new Map());
  assert.equal(report.summary.passed, 0);
  assert.equal(report.summary.failed, 1);
  assert.deepEqual(report.cases[0].failureReasons, ["result file missing"]);
});
