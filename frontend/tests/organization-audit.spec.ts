import { expect, test, type Page } from "@playwright/test";
import { snapshot } from "./snapshot";

const resource = "11111111-1111-4111-8111-111111111111", cursor = "9007199254751003";
async function fixture(page: Page, role = "ADMIN") {
  const state = { writes: 0, reads: 0, status: 200, empty: false, network: false, delay: false, waiting: false, release: () => {}, queries: [] as URLSearchParams[] };
  await page.route(/\/(?:api\/v1|auth)\//, async route => {
    const url = new URL(route.request().url()), path = url.pathname;
    if (route.request().method() !== "GET") { state.writes++; await route.abort(); return; }
    let json: unknown, status = 200;
    if (path === "/auth/csrf") json = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/v1/me") json = { username: "audit.admin", workspaces: ["space", "other"].map((id, i) => ({ id, name: id, role: "OWNER", tenantId: i ? "company-b" : "company-a", tenantName: i ? "运营公司" : "研发公司" })) };
    else if (path === "/api/v1/organizations") json = ["company-a", "company-b"].map((id, i) => ({ id, name: i ? "运营公司" : "研发公司", role }));
    else if (path.endsWith("/audit-events")) {
      state.reads++; state.queries.push(url.searchParams); status = state.status;
      if (state.network) { await route.abort(); return; }
      json = status !== 200 ? { code: "FORBIDDEN", message: "组织审计不可访问" } : {
        items: state.empty ? [] : [{ id: url.searchParams.has("beforeId") ? "9007199254751002" : "9007199254751004", operation: "WORKSPACE_GRANT_SET", resourceId: resource, actorId: resource, actorName: path.includes("company-a") ? "研发管理员<script>" : "运营管理员", createdAt: "2026-10-31T02:00:00Z" }],
        nextBeforeId: state.empty || url.searchParams.has("beforeId") ? null : cursor,
      };
      if (state.delay && path.includes("company-a")) await new Promise<void>(resolve => { state.release = resolve; state.waiting = true; });
    } else if (path.startsWith("/api/v1/organizations/")) json = { units: [], members: [], memberships: [], workspaces: [], grants: [], audit: [] };
    else if (path.endsWith("/jobs") || path.endsWith("/destinations")) json = [];
    else if (path.endsWith("/applications")) json = [{ id: "app", name: "文档提取" }];
    else if (path.endsWith("/model-profile")) json = { profileId: "fixture", provider: "fake", modelName: "fixture", billable: false, creditsPerJob: 1 };
    else if (path.endsWith("/budget")) json = { credits: { dailyLimit: 20, spent: 0, reserved: 0, uncertain: 0 } };
    else throw new Error(`Unexpected fixture ${path}`);
    await route.fulfill({ status, contentType: "application/json", json }).catch(() => {});
  });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "AI 任务工作台", exact: true })).toBeVisible();
  if (role === "ADMIN") {
    if (!await page.getByRole("button", { name: "组织管理", exact: true }).isVisible()) await page.getByRole("button", { name: "展开导航", exact: true }).click();
    await page.getByRole("button", { name: "组织管理", exact: true }).click();
    await expect(page.getByRole("button", { name: "查看组织审计", exact: true })).toBeVisible();
  }
  return state;
}
const panel = (page: Page) => page.getByRole("region", { name: "公司组织审计", exact: true });
async function open(page: Page) { await page.getByRole("button", { name: "查看组织审计", exact: true }).click(); }

for (const width of [1440, 768, 390]) test(`organization audit filters and string pagination at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 });
  const state = await fixture(page); expect(state.reads).toBe(0);
  await open(page); await expect(panel(page).locator(".audit-list")).toContainText("研发管理员<script>");
  await page.getByLabel("组织事件类型", { exact: true }).fill("WORKSPACE_GRANT_SET");
  await page.getByLabel("组织资源ID", { exact: true }).fill(resource);
  await page.getByRole("button", { name: "查询组织审计", exact: true }).click();
  await expect.poll(() => state.reads).toBe(2);
  await page.getByLabel("组织事件类型", { exact: true }).fill("UNIT_CREATED");
  await page.getByRole("button", { name: "更早组织记录", exact: true }).click();
  await expect(panel(page)).toContainText("第 2 页");
  expect(state.queries.at(-1)?.get("beforeId")).toBe(cursor);
  expect(state.queries.at(-1)?.get("operation")).toBe("WORKSPACE_GRANT_SET");
  expect(state.queries.at(-1)?.get("resourceId")).toBe(resource);
  await page.getByRole("button", { name: "返回最新组织记录", exact: true }).click();
  await expect(panel(page)).toContainText("第 1 页");
  await page.getByRole("button", { name: "清空组织筛选", exact: true }).click();
  await expect(page.getByLabel("组织事件类型", { exact: true })).toHaveValue("");
  await expect(panel(page).locator(".audit-list li")).toHaveCount(1);
  expect(state.queries.at(-1)?.has("operation")).toBe(false);
  await page.evaluate(() => document.fonts.ready);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await panel(page).scrollIntoViewIfNeeded();
  await page.screenshot({ path: `../artifacts/stage14-audit-${width}.png`, fullPage: true });
  if (width === 1440) await snapshot(page, "organization-audit");
  expect(await panel(page).locator("script").count()).toBe(0); expect(state.writes).toBe(0);
});

for (const status of [401, 403, 404, 500]) test(`organization audit clears cached rows on ${status}`, async ({ page }) => {
  const state = await fixture(page); await open(page); await expect(panel(page).locator("li")).toHaveCount(1);
  state.status = status; await page.getByRole("button", { name: "返回最新组织记录", exact: true }).click();
  await expect(panel(page).getByRole("alert")).toContainText("组织审计不可访问");
  await expect(panel(page).locator("li")).toHaveCount(0);
  if (status !== 500) await expect(page.getByLabel("组织事件类型", { exact: true })).toHaveCount(0);
  expect(state.writes).toBe(0);
});

test("organization audit empty result and network recovery", async ({ page }) => {
  const state = await fixture(page); state.empty = true; await open(page);
  await expect(panel(page)).toContainText("当前筛选下没有组织审计记录");
  state.empty = false; state.network = true;
  await page.getByRole("button", { name: "返回最新组织记录", exact: true }).click();
  await expect(panel(page).getByRole("alert")).toBeVisible(); await expect(panel(page).locator("li")).toHaveCount(0);
  state.network = false; await page.getByRole("button", { name: "返回最新组织记录", exact: true }).click();
  await expect(panel(page).locator("li")).toHaveCount(1); expect(state.writes).toBe(0);
});

for (const action of ["company", "view", "hidden", "collapse"]) test(`late organization audit cannot refill after ${action}`, async ({ page }) => {
  const state = await fixture(page); await open(page); await expect(panel(page).locator("li")).toHaveCount(1);
  state.delay = true; await page.getByRole("button", { name: "返回最新组织记录", exact: true }).click();
  await expect.poll(() => state.waiting).toBe(true);
  await expect(panel(page).locator("li")).toHaveCount(0);
  if (action === "company") { await page.getByLabel("管理公司", { exact: true }).selectOption("company-b"); await open(page); await expect(panel(page)).toContainText("运营管理员"); }
  if (action === "view") await page.getByRole("button", { name: "任务队列", exact: true }).click();
  if (action === "hidden") await page.evaluate(() => { Object.defineProperty(document, "hidden", { configurable: true, get: () => true }); document.dispatchEvent(new Event("visibilitychange")); });
  if (action === "collapse") await page.getByRole("button", { name: "收起组织审计", exact: true }).click();
  state.release(); await page.waitForTimeout(100);
  await expect(page.locator(".organization-audit li").filter({ hasText: "研发管理员" })).toHaveCount(0);
  if (action === "hidden") {
    state.delay = false;
    await page.evaluate(() => { Object.defineProperty(document, "hidden", { configurable: true, get: () => false }); document.dispatchEvent(new Event("visibilitychange")); });
    await expect(panel(page)).toContainText("研发管理员");
  }
  expect(state.writes).toBe(0);
});

test("member has no company audit interface", async ({ page }) => {
  const state = await fixture(page, "MEMBER");
  await expect(page.getByRole("button", { name: "组织管理", exact: true })).toHaveCount(0);
  expect(state.reads).toBe(0); expect(state.writes).toBe(0);
});
