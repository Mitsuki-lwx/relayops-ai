import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { analysisReport } from "../src/analysisReport";
import type { ResultBody } from "../src/resultTypes";

const base = { paragraphId: "a0001", quote: "上传必须登录。" };
const revised = { paragraphId: "b0001", quote: "上传必须登录，并限制文件大小为20MB。" };
const body: ResultBody = {
  data: {
    schemaVersion: "version.diff.result.v1",
    title: "上传规范版本对比",
    summary: { text: "修订版本增加了文件大小限制。", evidence: [base, revised] },
    keyPoints: [], constraints: [], unknowns: [],
    diff: {
      changes: [{ kind: "MODIFIED", text: "上传限制从仅登录变为登录加20MB上限", before: [base], after: [revised] }],
      impactedConstraints: [{ text: "上传大小约束", evidence: [revised] }],
      impactedAcceptanceCriteria: [{ title: "拒绝超限上传", given: "用户已登录", when: "上传超过20MB", then: "拒绝上传", evidence: [revised], assumptions: ["拒绝状态码待确认。"] }],
    },
  },
  paragraphs: [
    { paragraphId: "a0001", text: "上传必须登录。DO_NOT_EXPORT_FULL_SOURCE" },
    { paragraphId: "b0001", text: "上传必须登录，并限制文件大小为20MB。" },
  ],
};
const job = { jobId: "44444444-4444-4444-8444-444444444444", title: "上传规范版本对比", status: "SUCCEEDED", applicationId: "app", modelProfile: "fake-version-v1", errorCode: null, createdAt: "2026-11-03T00:00:00Z", payloadExpiresAt: "2099-01-01T00:00:00Z", modelCalls: 1, version: 3, parentJobId: null, taskType: "VERSION_DIFF" };

async function fixture(page: Page, role = "OWNER") {
  const state = { reads: 0, denied: false, drop: false, posts: [] as { key: string | undefined; body: Record<string, unknown> }[] };
  await page.route(/\/(?:api\/v1|auth)\//, async route => {
    const url = new URL(route.request().url()), path = url.pathname;
    let value: unknown, status = 200;
    if (path === "/auth/csrf") value = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/v1/me") value = { username: "fixture.owner", workspaces: [{ id: "space", name: "版本空间", role, tenantId: "tenant", tenantName: "验收公司" }] };
    else if (path === "/api/v1/organizations") value = [];
    else if (path.endsWith("/applications")) value = [{ id: "app", name: "验收应用" }];
    else if (path.endsWith("/model-profile")) value = { profileId: url.searchParams.get("taskType") === "VERSION_DIFF" ? "fake-version-v1" : "fake-extract-v1", promptVersion: "fixture", schemaVersion: "fixture", billable: false, creditsPerJob: 1, provider: "fake", modelName: "假模型" };
    else if (path.endsWith("/budget")) value = { credits: { dailyLimit: 20, reserved: 0, spent: 1, uncertain: 0 } };
    else if (path.endsWith("/destinations")) value = [];
    else if (path.endsWith("/jobs") && route.request().method() === "POST") {
      state.posts.push({ key: route.request().headers()["idempotency-key"], body: route.request().postDataJSON() });
      if (state.drop) { await route.abort(); return; }
      status = 202; value = { jobId: job.jobId, status: "QUEUED" };
    } else if (path.endsWith("/jobs")) value = [job];
    else if (path.endsWith("/deliveries")) value = [];
    else if (path.endsWith("/result")) { state.reads++; status = state.denied ? 403 : 200; value = state.denied ? { code: "FORBIDDEN", message: "版本对比权限已撤销" } : body; }
    else if (path.endsWith(job.jobId)) value = job;
    else throw new Error("Unexpected fixture path " + path);
    await route.fulfill({ status, json: value }).catch(() => {});
  });
  await page.goto("/");
  await expect(page.locator(".job-row")).toHaveCount(1);
  return state;
}

for (const width of [1440, 768, 390]) test(`version diff renders and downloads at ${width}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 });
  const state = await fixture(page);
  await page.locator(".job-row").click();
  await page.getByRole("button", { name: "查看结果与原文依据", exact: true }).click();
  await expect(page.locator('[data-result-section="versionChanges"]')).toContainText("版本变化");
  await expect(page.locator('[data-result-section="versionChanges"]')).toContainText("基线原文");
  await expect(page.locator('[data-result-section="versionChanges"]')).toContainText("修订原文");
  await expect(page.locator('[data-result-section="versionCriteria"]')).toContainText("拒绝超限上传");
  const event = page.waitForEvent("download");
  await page.getByRole("button", { name: "下载分析报告", exact: true }).click();
  const file = await event;
  await file.saveAs(`../artifacts/version-diff-fixture-${width}.md`);
  const text = readFileSync(`../artifacts/version-diff-fixture-${width}.md`, "utf8");
  expect(text).toContain("RelayOps 文档版本对比与影响分析");
  expect(text).toContain("基线原文");
  expect(text).toContain("修订原文");
  expect(text).not.toContain("DO_NOT_EXPORT_FULL_SOURCE");
  expect(state.reads).toBe(2);
  expect(state.posts).toHaveLength(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: `../artifacts/version-diff-fixture-${width}.png`, fullPage: true });
});

test("version purpose sends both inputs and survives a pending reload", async ({ page }) => {
  const state = await fixture(page);
  await page.getByRole("button", { name: "新建文档", exact: true }).click();
  await page.getByLabel("任务用途", { exact: true }).selectOption("VERSION_DIFF");
  await page.getByLabel("基线文档", { exact: false }).fill("上传必须登录。");
  await page.getByLabel("修订文档", { exact: false }).fill("上传必须登录，并限制文件大小为20MB。");
  state.drop = true;
  await page.getByRole("button", { name: /^提交任务/ }).click();
  await expect(page.getByRole("button", { name: /使用原幂等键重试/ })).toBeVisible();
  expect(state.posts[0]!.body.taskType).toBe("VERSION_DIFF");
  expect(state.posts[0]!.body.inputTextB).toBe("上传必须登录，并限制文件大小为20MB。");
  await page.reload();
  await expect(page.getByLabel("任务用途", { exact: true })).toHaveValue("VERSION_DIFF");
  await expect(page.getByLabel("任务用途", { exact: true })).toBeDisabled();
  state.drop = false;
  await page.getByRole("button", { name: /使用原幂等键重试/ }).click();
  await expect.poll(() => state.posts.length).toBe(2);
  expect(state.posts[1]).toEqual(state.posts[0]);
});

test("version export reauthorizes and viewer has no entry", async ({ page }) => {
  const state = await fixture(page);
  await page.locator(".job-row").click();
  await page.getByRole("button", { name: "查看结果与原文依据", exact: true }).click();
  await expect(page.locator(".result")).toBeVisible();
  state.denied = true;
  let downloads = 0;
  page.on("download", () => downloads++);
  await page.getByRole("button", { name: "下载分析报告", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("版本对比权限已撤销");
  await expect(page.locator(".result")).toHaveCount(0);
  expect(downloads).toBe(0);
});

test("version viewer has no result or export control", async ({ page }) => {
  const state = await fixture(page, "VIEWER");
  await page.locator(".job-row").click();
  await expect(page.getByRole("button", { name: "查看结果与原文依据", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "下载分析报告", exact: true })).toHaveCount(0);
  expect(state.reads).toBe(0);
});

test("version Markdown keeps generated fields literal", () => {
  const result = structuredClone(body), payload = "```\n<img src=x onerror=alert(1)>\n````";
  result.data.diff!.changes[0]!.text = payload;
  result.data.diff!.impactedAcceptanceCriteria[0]!.then = payload;
  const report = analysisReport(job, result, new Date(0));
  expect(report).toContain(payload);
  expect(report).toContain("`````text");
  expect(report).not.toContain("DO_NOT_EXPORT_FULL_SOURCE");
  let fence = 0;
  for (const line of report.split("\n")) {
    const start = /^(`+)text$/.exec(line);
    if (!fence && start) { fence = start[1]!.length; continue; }
    if (fence && line === "`".repeat(fence)) { fence = 0; continue; }
    if (!fence) expect(line).not.toContain("<img");
  }
  expect(fence).toBe(0);
});
