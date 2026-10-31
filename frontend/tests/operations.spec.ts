import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { snapshot } from "./snapshot";

const resource = "11111111-1111-4111-8111-111111111111";
const cursor = "9007199254741002";
const overview = {
  observedAt: "2026-10-31T02:00:00Z",
  jobs: { QUEUED: 2, RUNNING: 1, SUCCEEDED: 60, FAILED: 3, UNKNOWN: 1, CANCELLED: 4 },
  deliveries: { PENDING: 0, IN_FLIGHT: 1, RETRY_WAIT: 0, SUCCEEDED: 8, DEAD: 1 },
  aiOutbox: { pending: 2, claimed: 1, retried: 1, oldestPendingAt: "2026-10-31T01:59:00Z" },
  deliveryOutbox: { pending: 0, claimed: 0, retried: 0, oldestPendingAt: null },
  pendingLimit: 1000,
  recovery: { expiredModelLeases: 0, expiredDeliveryLeases: 1 },
  retention: { enabled: true, expiredJobs: 0, expiredDeliveries: 0, clearedJobs: 7, clearedDeliveries: 0 },
};
const event = (id: string, actorName = "fixture.owner") => ({
  id, operation: "JOB_ACCEPTED", resourceId: resource,
  actorId: "actor", actorName, createdAt: "2026-10-31T02:00:00Z",
});

async function fixture(page: Page, role = "OWNER") {
  const state = {
    mutations: 0, overviewReads: 0, auditReads: 0, delayed: false, empty: false, overviewStatus: 200, auditStatus: 200,
    auditQueries: [] as URLSearchParams[], delay: "" as string, release: () => {},
  };
  await page.route(/\/(?:api\/v1|auth)\//, async route => {
    const url = new URL(route.request().url()), path = url.pathname;
    if (route.request().method() !== "GET") { state.mutations++; await route.abort(); return; }
    let value: unknown, status = 200;
    if (path === "/auth/csrf") value = { token: "fixture-token", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/v1/me") value = { username: "fixture.owner", workspaces: ["space", "other"].map(id => ({ id, name: id, role, tenantId: "tenant", tenantName: "验收公司" })) };
    else if (path === "/api/v1/organizations" || path.endsWith("/destinations") || path.endsWith("/jobs")) value = [];
    else if (path.endsWith("/applications")) value = [{ id: "app", name: "文档提取" }];
    else if (path.endsWith("/model-profile")) value = { profileId: "fixture", provider: "fake", modelName: "fixture", billable: false, creditsPerJob: 1 };
    else if (path.endsWith("/budget")) value = { credits: { dailyLimit: 20, spent: 0, reserved: 0, uncertain: 0 } };
    else if (path.endsWith("/operations")) {
      state.overviewReads++; status = state.overviewStatus;
      value = status === 200 ? (state.empty ? { ...overview, jobs: Object.fromEntries(Object.keys(overview.jobs).map(k => [k, 0])), deliveries: Object.fromEntries(Object.keys(overview.deliveries).map(k => [k, 0])), aiOutbox: overview.deliveryOutbox } : path.includes("/other/") ? { ...overview, jobs: { SUCCEEDED: 5 } } : overview) : { code: "NOT_FOUND", message: "空间运行数据读取失败" };
    } else if (path.endsWith("/audit-events")) {
      state.auditReads++; state.auditQueries.push(url.searchParams); status = state.auditStatus;
      value = status === 200 ? {
        items: state.empty ? [] : [event(url.searchParams.has("beforeId") ? "9007199254741001" : "9007199254741003", path.includes("/other/") ? "other.owner" : "fixture.owner")],
        nextBeforeId: state.empty || url.searchParams.has("beforeId") ? null : cursor,
      } : { code: "FORBIDDEN", message: "仅空间所有者可查询审计记录" };
    } else throw new Error(`Unexpected fixture path: ${path}`);
    if (state.delay && path.endsWith(state.delay) && path.includes("/space/")) {
      await new Promise<void>(resolve => { state.release = resolve; state.delayed = true; });
    }
    await route.fulfill({ status, contentType: "application/json", json: value }).catch(() => {});
  });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "AI 任务工作台", exact: true })).toBeVisible();
  if (!await page.getByRole("button", { name: "运行概览", exact: true }).isVisible()) await page.getByRole("button", { name: "展开导航", exact: true }).click();
  await page.getByRole("button", { name: "运行概览", exact: true }).click();
  await expect(page.getByRole("region", { name: "任务状态统计" })).toContainText("71 个任务");
  if (role === "OWNER") await expect(page.locator(".audit-list")).toContainText("fixture.owner");
  return state;
}

for (const width of [1440, 768, 390]) {
  test(`operations counts and audit filters at ${width}px without writes`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    const state = await fixture(page);
    await expect(page.getByRole("region", { name: "交付状态统计" })).toContainText("10 个交付");
    await expect(page.getByRole("region", { name: "待发布消息" })).toContainText("2 / 1000");
    await expect(page.getByRole("region", { name: "恢复与清理" })).toContainText("已启用");
    await page.getByLabel("事件类型", { exact: true }).fill("JOB_ACCEPTED");
    await page.getByLabel("资源ID", { exact: true }).fill(resource);
    await page.getByRole("button", { name: "查询审计", exact: true }).click();
    await expect.poll(() => state.auditReads).toBe(2);
    await page.getByLabel("事件类型", { exact: true }).fill("JOB_FAILED");
    await page.getByRole("button", { name: "更早记录", exact: true }).click();
    await expect(page.getByRole("button", { name: "更早记录", exact: true })).toBeDisabled();
    const query = state.auditQueries.at(-1)!;
    expect(query.get("beforeId")).toBe(cursor);
    expect(query.get("operation")).toBe("JOB_ACCEPTED");
    expect(query.get("resourceId")).toBe(resource);
    await page.getByRole("button", { name: "返回最新记录", exact: true }).click();
    await expect(page.getByRole("button", { name: "更早记录", exact: true })).toBeEnabled();
    await page.getByLabel("事件类型", { exact: true }).fill("JOB_ACCEPTED");
    await page.evaluate(() => document.fonts.ready);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (width === 1440) await snapshot(page, "operations");
    await page.screenshot({ path: `../artifacts/stage11-operations-fixture-${width}.png`, fullPage: true });
    expect(state.mutations).toBe(0);
  });
}

for (const role of ["OPERATOR", "VIEWER"]) {
  test(`${role} sees overview without audit query or controls`, async ({ page }) => {
    const state = await fixture(page, role);
    await expect(page.getByText("当前角色可以查看运行概览，审计查询需空间所有者权限。")).toBeVisible();
    await expect(page.getByRole("button", { name: "查询审计", exact: true })).toHaveCount(0);
    expect(state.auditReads).toBe(0); expect(state.mutations).toBe(0);
  });
}

for (const endpoint of ["/operations", "/audit-events"]) {
  test(`workspace switch discards delayed ${endpoint}`, async ({ page }) => {
    const state = await fixture(page);
    state.delay = endpoint;
    await page.getByRole("button", { name: endpoint === "/operations" ? "刷新运行概览" : "返回最新记录", exact: true }).click();
    await expect.poll(() => state.delayed).toBe(true);
    await page.getByLabel("当前空间", { exact: true }).selectOption("other");
    await expect(page.getByRole("region", { name: "任务状态统计" })).toContainText("5 个任务");
    await expect(page.locator(".audit-list")).toContainText("other.owner");
    state.release();
    await expect(page.locator(".operations-panel")).not.toContainText("fixture.owner");
    await expect(page.getByRole("region", { name: "任务状态统计" })).not.toContainText("71 个任务");
    expect(state.mutations).toBe(0);
  });
}

test("revoked overview clears all data and audit denial clears rows and controls", async ({ page }) => {
  const state = await fixture(page);
  state.auditStatus = 403;
  await page.getByRole("button", { name: "返回最新记录", exact: true }).click();
  await expect(page.locator(".audit-list")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "查询审计", exact: true })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "任务状态统计" })).toBeVisible();
  state.overviewStatus = 404;
  await page.getByRole("button", { name: "刷新运行概览", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("空间运行数据读取失败");
  await expect(page.locator(".operations-grid")).toHaveCount(0);
  await expect(page.locator(".audit-panel")).toHaveCount(0);
  expect(state.mutations).toBe(0);
});

test("empty counts, no pending time, empty audit and transient error recover honestly", async ({ page }) => {
  const state = await fixture(page);
  state.empty = true;
  await page.getByRole("button", { name: "刷新运行概览", exact: true }).click();
  await expect(page.getByRole("region", { name: "任务状态统计" })).toContainText("0 个任务");
  await expect(page.getByRole("region", { name: "交付状态统计" })).toContainText("0 个交付");
  await expect(page.getByRole("region", { name: "待发布消息" }).getByText("最早记录：—", { exact: true })).toHaveCount(2);
  await expect(page.getByText("当前筛选下没有审计记录。", { exact: true })).toBeVisible();
  state.overviewStatus = 500;
  await page.getByRole("button", { name: "刷新运行概览", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.locator(".operations-grid")).toHaveCount(0);
  state.overviewStatus = 200;
  await page.getByRole("button", { name: "刷新运行概览", exact: true }).click();
  await expect(page.getByRole("region", { name: "任务状态统计" })).toContainText("0 个任务");
  expect(state.mutations).toBe(0);
});

test("one overview poll pauses when hidden and stops after unmount", async ({ page }) => {
  await page.clock.install();
  const state = await fixture(page);
  await page.clock.runFor(15000);
  await expect.poll(() => state.overviewReads).toBe(2);
  expect(state.auditReads).toBe(1);
  await page.evaluate(() => { Object.defineProperty(document, "hidden", { configurable: true, get: () => true }); document.dispatchEvent(new Event("visibilitychange")); });
  await page.clock.runFor(60000); expect(state.overviewReads).toBe(2);
  await page.evaluate(() => { Object.defineProperty(document, "hidden", { configurable: true, get: () => false }); document.dispatchEvent(new Event("visibilitychange")); });
  await expect.poll(() => state.overviewReads).toBe(3);
  await page.clock.runFor(15000); await expect.poll(() => state.overviewReads).toBe(4);
  await page.getByRole("button", { name: "任务队列", exact: true }).click();
  await page.clock.runFor(60000); expect(state.overviewReads).toBe(4);
  expect(state.mutations).toBe(0);
});

test("main workspace overview agrees with authenticated read-only API", async ({ page }) => {
  let writes = 0;
  page.on("request", request => { if (new URL(request.url()).pathname.startsWith("/api/") && request.method() !== "GET") writes++; });
  const password = readFileSync(new URL("../../ops/.env", import.meta.url), "utf8").split(/\r?\n/).find(line => line.startsWith("RELAYOPS_DEMO_PASSWORD="))!.slice("RELAYOPS_DEMO_PASSWORD=".length);
  await page.goto("/"); await page.getByLabel("账号", { exact: true }).fill("owner.a"); await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录工作台", exact: true }).click();
  await page.getByRole("button", { name: "运行概览", exact: true }).click();
  const me = await (await page.request.get("/api/v1/me")).json();
  const prefix = `/api/v1/workspaces/${me.workspaces[0].id}`;
  const response = await page.request.get(prefix + "/operations"); expect(response.status()).toBe(200);
  const actual = await response.json();
  const total = Object.values(actual.jobs).reduce((a: number, b) => a + Number(b), 0);
  await expect(page.getByRole("region", { name: "任务状态统计" })).toContainText(`${total} 个任务`);
  await expect(page.getByRole("region", { name: "恢复与清理" })).toContainText("已启用");
  await expect(page.locator(".audit-list li").first()).toBeVisible();
  const audit = await (await page.request.get(prefix + "/audit-events?limit=2")).json();
  for (const row of audit.items) { expect(typeof row.id).toBe("string"); expect(Object.keys(row).sort()).toEqual(["actorId", "actorName", "createdAt", "id", "operation", "resourceId"].sort()); }
  await page.getByLabel("资源ID", { exact: true }).fill(audit.items[0].resourceId);
  const filteredResponse = page.waitForResponse(response => {
    const url = new URL(response.url());
    return url.pathname === prefix + "/audit-events" && url.searchParams.get("resourceId") === audit.items[0].resourceId;
  });
  await page.getByRole("button", { name: "查询审计", exact: true }).click();
  const filtered = await (await filteredResponse).json();
  await expect(page.locator(".audit-list li")).toHaveCount(filtered.items.length);
  for (const row of await page.locator(".audit-list li").all()) await expect(row).toContainText(audit.items[0].resourceId);
  await page.evaluate(() => { scrollTo(0, 0); return new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))); });
  await page.screenshot({ path: "../artifacts/stage11-operations-main-readonly.png", fullPage: true });
  expect(writes).toBe(0);
});
