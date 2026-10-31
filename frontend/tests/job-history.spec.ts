import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { snapshot } from "./snapshot";

const rows = Array.from({ length: 62 }, (_, n) => ({
  jobId: `${n.toString(16).padStart(8, "0")}-1111-4111-8111-111111111111`,
  title: n < 5 ? `早期接口约束 ${n + 1}` : `技术档案 ${n + 1}`,
  status: n % 3 === 0 ? "FAILED" : "SUCCEEDED", applicationId: "app", modelProfile: "fixture",
  errorCode: n % 3 === 0 ? "OUTPUT_INVALID" : null, version: 3,
  createdAt: "2026-10-30T01:00:00.123Z", payloadExpiresAt: "2099-01-01T00:00:00Z", modelCalls: 1, parentJobId: null,
})).reverse();

async function fixture(page: Page, role = "OWNER") {
  const state = { mutations: 0, reads: 0, detailReads: 0, status: 200, detailStatus: 200, delay: false, waiting: false, release: () => {}, queries: [] as URLSearchParams[] };
  await page.route(/\/(?:api\/v1|auth)\//, async route => {
    const url = new URL(route.request().url()), path = url.pathname;
    if (route.request().method() !== "GET") { state.mutations++; await route.abort(); return; }
    let value: unknown, status = 200;
    if (path === "/auth/csrf") value = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/v1/me") value = { username: "fixture.reader", workspaces: ["space", "other"].map(id => ({ id, name: id, role, tenantId: "tenant", tenantName: "验收公司" })) };
    else if (path === "/api/v1/organizations" || path.endsWith("/destinations") || path.endsWith("/deliveries")) value = [];
    else if (path.endsWith("/applications")) value = [{ id: "app", name: "文档提取" }];
    else if (path.endsWith("/model-profile")) value = { profileId: "fixture", provider: "fake", modelName: "fixture", billable: false, creditsPerJob: 1 };
    else if (path.endsWith("/budget")) value = { credits: { dailyLimit: 20, reserved: 0, spent: 0, uncertain: 0 } };
    else if (path.endsWith("/jobs")) value = rows.slice(0, 50);
    else if (path.endsWith("/job-history")) {
      state.reads++; state.queries.push(url.searchParams); status = state.status;
      let list = path.includes("/other/") ? [{ ...rows[0]!, title: "其他空间记录" }] : rows;
      const q = url.searchParams.get("q"), filter = url.searchParams.get("status"), before = url.searchParams.get("beforeId");
      if (q) list = list.filter(row => `${row.title} ${row.jobId}`.includes(q));
      if (filter) list = list.filter(row => filter === "ATTENTION" ? ["FAILED", "UNKNOWN"].includes(row.status) : row.status === filter);
      if (before) list = list.filter(row => row.jobId < before);
      const items = list.slice(0, 25), last = items.at(-1);
      value = status === 200 ? { items, nextCursor: list.length > 25 ? { createdAt: last!.createdAt, id: last!.jobId } : null } : { code: "NOT_FOUND", message: "历史访问已拒绝" };
      if (state.delay && path.includes("/space/")) await new Promise<void>(resolve => { state.release = resolve; state.waiting = true; });
    } else if (/\/jobs\/[^/]+$/.test(path)) {
      state.detailReads++; status = state.detailStatus;
      value = status === 200 ? rows.find(row => path.endsWith(row.jobId)) : { code: "NOT_FOUND", message: "任务访问已撤销" };
    } else throw new Error(`Unexpected fixture path: ${path}`);
    await route.fulfill({ status, contentType: "application/json", json: value }).catch(() => {});
  });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "AI 任务工作台", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "查询完整任务历史", exact: true }).click();
  await expect(page.locator(".history-list li")).toHaveCount(25);
  return state;
}

for (const width of [1440, 768, 390]) {
  test(`complete history filters, stable cursor and original detail at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    const state = await fixture(page);
    await page.getByRole("button", { name: "更早任务", exact: true }).click();
    await expect(page.locator(".history-pagination")).toContainText("第 2 页");
    await page.getByLabel("历史搜索", { exact: true }).fill("草稿尚未提交");
    await page.getByRole("button", { name: "更早任务", exact: true }).click();
    await expect(page.locator(".history-list li")).toHaveCount(12);
    expect(state.queries.at(-1)!.get("q")).toBeNull();
    expect(state.queries[1]!.get("beforeId")).toBe(rows[24]!.jobId);
    expect(state.queries[1]!.get("beforeCreatedAt")).toBe(rows[24]!.createdAt);
    await page.getByLabel("历史搜索", { exact: true }).fill("早期接口约束");
    await page.getByRole("button", { name: "查询历史", exact: true }).click();
    await expect(page.locator(".history-list li")).toHaveCount(5);
    await expect(page.locator(".history-scope")).toContainText("关键词“早期接口约束”");
    await expect(page.getByRole("button", { name: "更早任务", exact: true })).toBeDisabled();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.evaluate(() => { scrollTo(0, 0); return document.fonts.ready; });
    if (width === 1440) await snapshot(page, "history");
    await page.screenshot({ path: `../artifacts/stage12-history-fixture-${width}.png`, fullPage: true });
    await page.getByRole("button", { name: "查看任务：早期接口约束 5", exact: true }).click();
    await expect(page.locator(".detail")).toContainText("早期接口约束 5");
    expect(state.detailReads).toBeGreaterThan(0); expect(state.mutations).toBe(0);
  });
}

test("state filter and clear controls query server and show empty results", async ({ page }) => {
  const state = await fixture(page);
  await page.getByRole("combobox", { name: "历史状态", exact: true }).selectOption("ATTENTION");
  await page.getByRole("button", { name: "查询历史", exact: true }).click();
  await expect(page.locator(".history-list li")).toHaveCount(21);
  expect(state.queries.at(-1)!.get("status")).toBe("ATTENTION");
  await page.getByLabel("历史搜索", { exact: true }).fill("不存在的任务");
  await page.getByRole("button", { name: "查询历史", exact: true }).click();
  await expect(page.getByRole("heading", { name: "没有匹配的历史任务", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "清空条件", exact: true }).click();
  await expect(page.locator(".history-list li")).toHaveCount(25);
  expect(state.queries.at(-1)!.has("status")).toBe(false); expect(state.queries.at(-1)!.has("q")).toBe(false);
  expect(state.mutations).toBe(0);
});

test("switching space discards delayed history and resets applied filters", async ({ page }) => {
  const state = await fixture(page); state.delay = true;
  await page.getByLabel("历史搜索", { exact: true }).fill("早期");
  await page.getByRole("button", { name: "查询历史", exact: true }).click();
  await expect.poll(() => state.waiting).toBe(true);
  await expect(page.locator(".history-list li")).toHaveCount(0);
  await page.getByLabel("当前空间", { exact: true }).selectOption("other");
  await expect(page.locator(".history-list")).toContainText("其他空间记录");
  state.release();
  await expect(page.locator(".history-list")).not.toContainText("早期");
  await expect(page.getByLabel("历史搜索", { exact: true })).toHaveValue("");
  expect(state.mutations).toBe(0);
});

test("denied re-read clears history and failed detail cannot display old metadata", async ({ page }) => {
  const state = await fixture(page); state.status = 404;
  await page.getByRole("button", { name: "刷新首屏", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("历史访问已拒绝");
  await expect(page.locator(".history-list")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "更早任务", exact: true })).toBeDisabled();
  state.status = 200; await page.getByRole("button", { name: "刷新首屏", exact: true }).click();
  await expect(page.locator(".history-list li")).toHaveCount(25);
  state.detailStatus = 404;
  await page.locator(".history-list button").first().click();
  await expect(page.getByRole("alert")).toContainText("任务访问已撤销");
  await expect(page.locator(".detail")).toHaveCount(0); expect(state.mutations).toBe(0);
});

test("viewer can browse old metadata and cannot read result body", async ({ page }) => {
  const state = await fixture(page, "VIEWER");
  await page.locator(".history-list button").first().click();
  await expect(page.locator(".detail")).toBeVisible();
  await expect(page.getByRole("button", { name: "查看结果与原文依据", exact: true })).toHaveCount(0);
  expect(state.mutations).toBe(0);
});

test("unmount aborts pending history response", async ({ page }) => {
  const state = await fixture(page); state.delay = true;
  await page.getByRole("button", { name: "刷新首屏", exact: true }).click();
  await expect.poll(() => state.waiting).toBe(true);
  await page.getByRole("button", { name: "返回任务队列", exact: true }).click();
  state.release(); await expect(page.locator(".history-panel")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "AI 任务工作台", exact: true })).toBeVisible();
  expect(state.mutations).toBe(0);
});

test("main history pages reconcile with overview and reject invalid HTTP filters", async ({ page }) => {
  let writes = 0;
  page.on("request", request => { if (new URL(request.url()).pathname.startsWith("/api/") && request.method() !== "GET") writes++; });
  const password = readFileSync(new URL("../../ops/.env", import.meta.url), "utf8").split(/\r?\n/).find(s => s.startsWith("RELAYOPS_DEMO_PASSWORD="))!.slice("RELAYOPS_DEMO_PASSWORD=".length);
  await page.goto("/"); await page.getByLabel("账号", { exact: true }).fill("owner.a"); await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录工作台", exact: true }).click();
  await page.getByRole("button", { name: "查询完整任务历史", exact: true }).click();
  await expect(page.locator(".history-list li").first()).toBeVisible();
  const me = await (await page.request.get("/api/v1/me")).json(), prefix = `/api/v1/workspaces/${me.workspaces[0].id}`;
  const overview = await (await page.request.get(prefix + "/operations")).json();
  const ids: string[] = []; let cursor: { createdAt: string; id: string } | null = null;
  do {
    const params = new URLSearchParams({ limit: "7" });
    if (cursor) { params.set("beforeId", cursor.id); params.set("beforeCreatedAt", cursor.createdAt); }
    const response = await page.request.get(prefix + "/job-history?" + params); expect(response.status()).toBe(200);
    const batch = await response.json(); ids.push(...batch.items.map((row: { jobId: string }) => row.jobId)); cursor = batch.nextCursor;
  } while (cursor);
  expect(new Set(ids).size).toBe(ids.length);
  expect(ids.length).toBe(Object.values(overview.jobs).reduce((sum: number, n) => sum + Number(n), 0));
  for (const query of ["limit=101", "status=INVALID", "beforeId=bad"]) expect((await page.request.get(prefix + "/job-history?" + query)).status()).toBe(422);
  await page.getByLabel("历史搜索", { exact: true }).fill(ids.at(-1)!);
  await page.getByRole("button", { name: "查询历史", exact: true }).click();
  await expect(page.locator(".history-list li")).toHaveCount(1);
  await expect(page.locator(".history-list")).toContainText(ids.at(-1)!);
  await page.screenshot({ path: "../artifacts/stage12-history-main-readonly.png", fullPage: true });
  expect(writes).toBe(0);
});
