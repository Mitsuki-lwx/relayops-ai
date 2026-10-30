import { readFileSync } from "node:fs";
import { test, expect, type Page } from "@playwright/test";
import { requireFakeModel } from "./require-fake";

const password = readFileSync(new URL("../../ops/.env", import.meta.url), "utf8")
  .split(/\r?\n/).find(line => line.startsWith("RELAYOPS_DEMO_PASSWORD="))!.split("=").slice(1).join("=");
async function login(page: Page, username: string) {
  await page.goto("/");
  await page.getByLabel("账号", { exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page.getByRole("heading", { name: "AI 任务工作台" })).toBeVisible();
  await requireFakeModel(page);
}
async function create(page: Page, suffix: string, button: string) {
  const accepted = page.waitForResponse(r => r.request().method() === "POST" && r.url().endsWith(suffix));
  await page.getByRole("button", { name: button, exact: true }).click();
  const response = await accepted; expect(response.status()).toBe(201);
  return (await response.json()).id as string;
}
async function update(page: Page, suffix: string, button: string) {
  const saved = page.waitForResponse(r => r.request().method() === "PUT" && r.url().includes(suffix));
  await page.getByRole("button", { name: button, exact: true }).click();
  expect((await saved).status()).toBe(204);
}

test("organization grant admits a teammate and revocation blocks the next body read", async ({ page, browser }) => {
  test.setTimeout(100000);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await login(page, "owner.a");
  await page.getByRole("button", { name: "组织管理", exact: true }).click();
  const tenant = await page.getByLabel("管理公司", { exact: true }).inputValue();
  const tag = Date.now();
  const departmentName = `研发部门 ${tag}`, teamName = `后端小组 ${tag}`, spaceName = `组织协作 ${tag}`;
  await page.getByLabel("组织名称", { exact: true }).fill(departmentName);
  const department = await create(page, "/units", "创建组织");
  await expect(page.locator(".organization-list strong").filter({ hasText: departmentName })).toBeVisible();
  await page.getByLabel("组织名称", { exact: true }).fill(teamName);
  await page.getByLabel("组织类型", { exact: true }).selectOption("TEAM");
  await page.getByLabel("所属部门", { exact: true }).selectOption(department);
  const team = await create(page, "/units", "创建组织");
  await expect(page.locator(".organization-list strong").filter({ hasText: teamName })).toBeVisible();
  await page.getByLabel("成员所在组织", { exact: true }).selectOption(team);
  await page.getByLabel("公司成员", { exact: true }).selectOption({ label: "operator.a" });
  await update(page, `/units/${team}/members/`, "加入组织");
  await page.getByLabel("新空间名称", { exact: true }).fill(spaceName);
  const space = await create(page, "/workspaces", "创建空间");
  await page.getByLabel("授权空间", { exact: true }).selectOption(space);
  await page.getByLabel("授权组织", { exact: true }).selectOption(team);

  const memberContext = await browser.newContext();
  const member = await memberContext.newPage();
  const foreignContext = await browser.newContext();
  try {
    await login(member, "operator.a");
    await expect(member.getByLabel("当前空间", { exact: true }).locator(`option[value="${space}"]`)).toHaveCount(0);
    await expect(member.locator(".organization")).toHaveCount(0);
    await page.getByLabel("授权角色", { exact: true }).selectOption("OPERATOR");
    await update(page, `/workspaces/${space}/grants/${team}`, "保存授权");
    await expect(page.locator(".organization-list li").filter({ hasText: teamName })).toContainText(spaceName);
    await expect(page.getByLabel("授权空间", { exact: true })).toBeEnabled();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.locator(".organization").screenshot({ path: "../artifacts/organization-desktop.png" });
    await page.setViewportSize({ width: 390, height: 1000 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: "../artifacts/organization-mobile.png", fullPage: true });
    await page.locator(".organization-forms form").last().screenshot({ path: "../artifacts/organization-mobile-grant.png" });

    await member.reload();
    await member.getByLabel("当前空间", { exact: true }).selectOption(space);
    await member.getByRole("button", { name: "新建文档", exact: true }).click();
    await member.getByLabel("任务标题").fill(`小组任务 ${tag}`);
    await member.getByLabel("技术文档").fill("小组成员获得空间授权后可创建任务，撤销后后续正文读取必须拒绝。");
    const accepted = member.waitForResponse(r => r.request().method() === "POST" && r.url().endsWith(`/workspaces/${space}/jobs`));
    await member.getByRole("button", { name: "提交任务" }).click();
    const acceptedResponse = await accepted; expect(acceptedResponse.status()).toBe(202);
    const { jobId } = await acceptedResponse.json();
    await member.getByRole("button", { name: "查看结果与原文依据" }).click({ timeout: 25000 });
    await expect(member.getByRole("heading", { name: "摘要", exact: true })).toBeVisible();

    const foreign = await foreignContext.newPage(); await login(foreign, "owner.b");
    expect((await foreign.request.get(`/api/v1/organizations/${tenant}`)).status()).toBe(404);
    expect((await foreign.request.get(`/api/v1/workspaces/${space}/jobs/${jobId}/result`)).status()).toBe(404);

    await update(page, `/units/${team}/members/`, "移出组织");
    await member.getByRole("button", { name: "重新授权读取结果" }).click();
    await expect(member.getByRole("alert")).toContainText("空间不存在或不可访问");
    await expect(member.getByRole("heading", { name: "摘要", exact: true })).toHaveCount(0);
    expect((await member.request.get(`/api/v1/workspaces/${space}/jobs`)).status()).toBe(404);
    await member.reload();
    await expect(member.getByLabel("当前空间", { exact: true }).locator(`option[value="${space}"]`)).toHaveCount(0);
    // Management mutations also use the existing session CSRF contract.
    const selectedUser = await page.getByLabel("公司成员", { exact: true }).inputValue();
    expect((await page.request.put(`/api/v1/organizations/${tenant}/units/${team}/members/${selectedUser}`, { data: { active: true } })).status()).toBe(403);
  } finally { await memberContext.close(); await foreignContext.close(); }
});
