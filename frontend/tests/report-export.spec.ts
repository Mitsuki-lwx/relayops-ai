import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { analysisReport, reportFilename } from "../src/analysisReport";
import type { ResultBody } from "../src/resultTypes";
import { snapshot } from "./snapshot";

const id = "11111111-1111-4111-8111-111111111111";
const claim = { text: "提交后才受理。", evidence: [{ paragraphId: "p0001", quote: "事务提交后返回已受理。" }] };
const body: ResultBody = {
  data: { title: "接口约束分析", summary: claim, keyPoints: [claim], constraints: [claim], unknowns: ["未给出重试策略。"] },
  paragraphs: [{ paragraphId: "p0001", text: "事务提交后返回已受理。DO_NOT_EXPORT_FULL_SOURCE" }],
};
const job = { jobId: id, title: "接口受理说明", status: "SUCCEEDED", applicationId: "app", modelProfile: "fixture-profile", errorCode: null, createdAt: "2026-10-31T02:00:00Z", payloadExpiresAt: "2099-01-01T00:00:00Z", modelCalls: 1, version: 3, parentJobId: null };

async function fixture(page: Page, role = "OWNER", status = "SUCCEEDED", expiresAt = job.payloadExpiresAt) {
  const state = { reads: 0, mutations: 0, denied: 0, network: false, delay: false, waiting: false, release: () => {}, body: structuredClone(body), downloads: 0 };
  page.on("download", () => state.downloads++);
  await page.addInitScript(() => {
    const events = { created: 0, revoked: 0 };
    Object.defineProperty(window, "reportUrls", { value: events });
    const create = URL.createObjectURL.bind(URL), revoke = URL.revokeObjectURL.bind(URL);
    URL.createObjectURL = value => { events.created++; return create(value); };
    URL.revokeObjectURL = value => { events.revoked++; revoke(value); };
  });
  await page.route(/\/(?:api\/v1|auth)\//, async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/auth/logout") { await route.fulfill({ status: 204 }); return; }
    if (route.request().method() !== "GET") { state.mutations++; await route.abort(); return; }
    let value: unknown, code = 200;
    const first = { ...job, status, payloadExpiresAt: expiresAt }, second = { ...job, jobId: "22222222-2222-4222-8222-222222222222", title: "另一任务" };
    if (path === "/auth/csrf") value = { token: "fixture-token", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/v1/me") value = { username: "fixture.owner", workspaces: ["space", "other"].map(space => ({ id: space, name: space, role, tenantId: "tenant", tenantName: "验收公司" })) };
    else if (path === "/api/v1/organizations" || path.endsWith("/destinations") || path.endsWith("/deliveries")) value = [];
    else if (path.endsWith("/applications")) value = [{ id: "app", name: "文档提取" }];
    else if (path.endsWith("/model-profile")) value = { profileId: "fixture", provider: "fake", modelName: "fixture", billable: false, creditsPerJob: 1 };
    else if (path.endsWith("/budget")) value = { credits: { dailyLimit: 20, reserved: 0, spent: 0, uncertain: 0 } };
    else if (path.endsWith("/jobs")) value = [first, second];
    else if (path.endsWith("/result")) {
      state.reads++; code = state.denied || 200;
      const network = state.network;
      value = state.denied ? { code: "FORBIDDEN", message: "正文读取已拒绝或过期" } : structuredClone(state.body);
      if (state.delay) await new Promise<void>(resolve => { state.release = resolve; state.waiting = true; });
      if (network) { await route.abort(); return; }
    } else if (path.endsWith(first.jobId)) value = first;
    else if (path.endsWith(second.jobId)) value = second;
    else throw new Error(`Unexpected fixture path: ${path}`);
    await route.fulfill({ status: code, contentType: "application/json", json: value }).catch(() => {});
  });
  await page.goto("/"); await expect(page.locator(".job-row")).toHaveCount(2);
  await page.locator(".job-row").first().click();
  await expect(page.locator(".detail")).toBeVisible();
  return state;
}

for (const width of [1440, 768, 390]) {
  test(`fresh audited download preserves UTF-8 report at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    const state = await fixture(page);
    await page.getByRole("button", { name: "查看结果与原文依据", exact: true }).click();
    await expect(page.locator(".result")).toBeVisible();
    state.body.data.summary.text = "导出时重新读取的内容 中文😀";
    const downloadEvent = page.waitForEvent("download");
    await page.getByRole("button", { name: "下载分析报告", exact: true }).click();
    const download = await downloadEvent;
    expect(download.suggestedFilename()).toBe(`relayops-analysis-${id}.md`);
    const path = `../artifacts/stage13-report-${width}.md`; await download.saveAs(path);
    expect(await download.failure()).toBeNull();
    const report = readFileSync(path, "utf8");
    for (const text of [id, job.title, job.modelProfile, body.data.title, "导出时重新读取的内容 中文😀", "## 关键结论", "## 约束与要求", "## 未确定信息", claim.evidence[0]!.quote, "段落：p0001", "不代表事实判断完全正确"]) expect(report).toContain(text);
    expect(report).not.toContain("DO_NOT_EXPORT_FULL_SOURCE");
    expect(state.reads).toBe(2); expect(state.downloads).toBe(1); expect(state.mutations).toBe(0);
    await expect(page.getByRole("status")).toContainText("已开始下载");
    await expect.poll(() => page.evaluate(() => (window as unknown as { reportUrls: { revoked: number } }).reportUrls.revoked)).toBe(1);
    await expect(page.locator('a[href^="blob:"]')).toHaveCount(0);
    expect(await page.evaluate(() => [localStorage, sessionStorage].some(storage => Object.values(storage).some(value => String(value).includes("导出时重新读取"))))).toBe(false);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.evaluate(() => { scrollTo(0, 0); return document.fonts.ready; });
    if (width === 1440) await snapshot(page, "report-export");
    await page.screenshot({ path: `../artifacts/stage13-export-${width}.png`, fullPage: true });
  });
}

test("hostile Markdown and HTML stay inside literal fences and empty sections are explicit", () => {
  const hostile = "中文😀\n```\n``````\n# injected\n<script>alert(1)</script>\n![image](https://example.invalid/a)\n<iframe src='https://example.invalid'></iframe>\n[link](javascript:alert(1))\n~~~\n";
  const badClaim = { text: hostile, evidence: [{ paragraphId: hostile, quote: hostile }] };
  const report = analysisReport({ ...job, title: hostile, modelProfile: hostile }, { ...body, data: { title: hostile, summary: badClaim, keyPoints: [], constraints: [], unknowns: [hostile] } }, new Date("2026-10-31T02:00:00Z"));
  let fence = 0; const outside: string[] = [];
  for (const line of report.split("\n")) {
    if (!fence && /^`{3,}text$/.test(line)) fence = line.length - 4;
    else if (fence && new RegExp(`^\x60{${fence},}$`).test(line)) fence = 0;
    else if (!fence) outside.push(line);
  }
  expect(fence).toBe(0);
  for (const text of ["# injected", "<script>", "![image]", "javascript:", "<iframe", "~~~"]) expect(outside.join("\n")).not.toContain(text);
  expect(report).toContain(hostile); expect(report).toContain("未提取到关键结论。"); expect(report).toContain("未提取到明确约束。");
  const empty = analysisReport(job, { ...body, data: { ...body.data, unknowns: [] } }, new Date());
  expect(empty).toContain("模型未列出额外待确认项。");
  expect(reportFilename("../../CON:<bad>?\\name")).toMatch(/^relayops-analysis-[A-Za-z0-9_-]+\.md$/);
  expect(reportFilename("/".repeat(100))).toBe("relayops-analysis-report.md");
});

for (const denied of [401, 403, 404, 410]) {
  test(`cached body cannot bypass HTTP ${denied} on export`, async ({ page }) => {
    const state = await fixture(page);
    await page.getByRole("button", { name: "查看结果与原文依据", exact: true }).click(); await expect(page.locator(".result")).toBeVisible();
    state.denied = denied;
    await page.getByRole("button", { name: "下载分析报告", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText("正文读取已拒绝或过期");
    await expect(page.locator(".result")).toHaveCount(0); expect(state.downloads).toBe(0); expect(state.reads).toBe(2);
  });
}

test("network failure clears old body without generating a file", async ({ page }) => {
  const state = await fixture(page);
  await page.getByRole("button", { name: "查看结果与原文依据", exact: true }).click(); await expect(page.locator(".result")).toBeVisible();
  state.network = true; await page.getByRole("button", { name: "下载分析报告", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible(); await expect(page.locator(".result")).toHaveCount(0); expect(state.downloads).toBe(0);
});

test("pending export disables duplicates and releases the only object URL", async ({ page }) => {
  const state = await fixture(page, "OPERATOR"); state.delay = true;
  await page.getByRole("button", { name: "下载分析报告", exact: true }).click(); await expect.poll(() => state.waiting).toBe(true);
  await expect(page.getByRole("button", { name: "导出中…", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "读取中…", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "导出中…", exact: true }).dispatchEvent("click"); expect(state.reads).toBe(1);
  const download = page.waitForEvent("download"); state.release(); await download;
  await expect.poll(() => page.evaluate(() => (window as unknown as { reportUrls: { revoked: number } }).reportUrls.revoked)).toBe(1);
  expect(state.downloads).toBe(1); expect(state.mutations).toBe(0);
});

for (const change of ["job", "workspace", "view", "hidden", "logout"]) {
  test(`late export after ${change} change is discarded`, async ({ page }) => {
    const state = await fixture(page); state.delay = true;
    await page.getByRole("button", { name: "下载分析报告", exact: true }).click(); await expect.poll(() => state.waiting).toBe(true);
    if (change === "job") await page.locator(".job-row").nth(1).click();
    if (change === "workspace") await page.getByLabel("当前空间", { exact: true }).selectOption("other");
    if (change === "view") await page.getByRole("button", { name: "新建文档", exact: true }).click();
    if (change === "hidden") await page.evaluate(() => { Object.defineProperty(document, "hidden", { configurable: true, get: () => true }); document.dispatchEvent(new Event("visibilitychange")); });
    if (change === "logout") {
      await page.getByRole("button", { name: "退出登录", exact: true }).click();
      await expect(page.getByRole("button", { name: "登录工作台", exact: true })).toBeVisible();
    }
    state.release();
    await expect(page.locator(".result")).toHaveCount(0);
    expect(await page.evaluate(() => (window as unknown as { reportUrls: { created: number } }).reportUrls.created)).toBe(0);
    expect(state.downloads).toBe(0); expect(state.mutations).toBe(0);
  });
}

test("payload that expires while authorization is pending cannot download", async ({ page }) => {
  await page.clock.install();
  const state = await fixture(page, "OWNER", "SUCCEEDED", new Date(Date.now() + 60000).toISOString()); state.delay = true;
  await page.getByRole("button", { name: "下载分析报告", exact: true }).click(); await expect.poll(() => state.waiting).toBe(true);
  await page.clock.fastForward(70000); state.release();
  await expect(page.getByRole("alert")).toContainText("正文已过期"); expect(state.downloads).toBe(0); await expect(page.locator(".result")).toHaveCount(0);
});

test("late refusal from a previous task cannot clear a newly authorized result", async ({ page }) => {
  const state = await fixture(page); state.denied = 403; state.delay = true;
  await page.getByRole("button", { name: "下载分析报告", exact: true }).click(); await expect.poll(() => state.waiting).toBe(true);
  await page.locator(".job-row").nth(1).click();
  state.denied = 0; state.delay = false;
  await page.getByRole("button", { name: "查看结果与原文依据", exact: true }).click();
  await expect(page.locator(".result")).toBeVisible(); state.release();
  await expect(page.locator(".result")).toContainText(claim.text);
  await expect(page.getByRole("alert")).toHaveCount(0); expect(state.downloads).toBe(0);
});

test("successful export retains online TTL clearing", async ({ page }) => {
  await page.clock.install();
  const state = await fixture(page, "OWNER", "SUCCEEDED", new Date(Date.now() + 60000).toISOString());
  const download = page.waitForEvent("download"); await page.getByRole("button", { name: "下载分析报告", exact: true }).click(); await download;
  await expect(page.locator(".result")).toBeVisible(); await page.clock.fastForward(70000);
  await expect(page.locator(".result")).toHaveCount(0); expect(state.downloads).toBe(1);
});

for (const status of ["QUEUED", "RUNNING", "FAILED", "UNKNOWN", "CANCELLED"]) {
  test(`${status} has no export control`, async ({ page }) => {
    const state = await fixture(page, "OWNER", status);
    await expect(page.getByRole("button", { name: "下载分析报告", exact: true })).toHaveCount(0); expect(state.reads).toBe(0);
  });
}

test("viewer has no export control", async ({ page }) => {
  const state = await fixture(page, "VIEWER");
  await expect(page.getByRole("button", { name: "下载分析报告", exact: true })).toHaveCount(0); expect(state.reads).toBe(0);
});
