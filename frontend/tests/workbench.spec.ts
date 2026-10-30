import { readFileSync } from "node:fs";
import { test, expect, type Page } from "@playwright/test";
import { requireFakeModel } from "./require-fake";

const config = Object.fromEntries(
  readFileSync(new URL("../../ops/.env", import.meta.url), "utf8")
    .split(/\r?\n/)
    .filter((line) => line.includes("="))
    .map((line) => [
      line.slice(0, line.indexOf("=")),
      line.slice(line.indexOf("=") + 1),
    ]),
);

async function login(page: Page) {
  await page.goto("/");
  await page.getByLabel("账号", { exact: true }).fill(process.env.RELAYOPS_BROWSER_USER ?? "owner.a");
  await page
    .getByLabel("密码", { exact: true })
    .fill(config.RELAYOPS_DEMO_PASSWORD);
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(
    page.getByRole("heading", { name: "AI 任务工作台" }),
  ).toBeVisible();
  await requireFakeModel(page);
  await page.getByRole("button", { name: "新建文档", exact: true }).click();
}

for (const width of [1440, 390]) {
  test(`login, submit and evidence at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await login(page);
    const title = `浏览器验收 ${width} ${Date.now()}`;
    await page.getByLabel("任务标题").fill(title);
    await page
      .getByLabel("技术文档")
      .fill(
        "任务只有在数据库事务提交后才返回已受理。\n\n回调失败不会重新调用模型。",
      );
    // Submit via keyboard to exercise the real form, including mobile layout.
    await page.getByRole("button", { name: "提交任务" }).focus();
    await page.keyboard.press("Enter");
    await expect(
      page.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
    const read = page.getByRole("button", { name: "查看结果与原文依据" });
    await expect(read).toBeVisible({ timeout: 20000 });
    await read.click();
    await expect(
      page.getByRole("heading", { name: "摘要", exact: true }),
    ).toBeVisible();
    await page.locator(".result summary").first().click();
    await expect(page.locator("blockquote").first()).toContainText(
      "数据库事务提交",
    );
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: `../artifacts/workbench-${width}.png`,
      fullPage: true,
    });
    await page.reload();
    await expect(
      page.getByRole("button", { name: new RegExp(title) }),
    ).toBeVisible();
  });
}

test("lost acceptance response retries the same intent after reload", async ({
  page,
}) => {
  await login(page);
  const keys: string[] = [];
  let responseLost = false;
  await page.route("**/api/v1/workspaces/*/jobs", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    keys.push(route.request().headers()["idempotency-key"]);
    const response = await route.fetch();
    expect(response.status()).toBe(202);
    if (!responseLost) {
      responseLost = true;
      await route.abort("failed");
    } else await route.fulfill({ response });
  });
  await page.getByLabel("任务标题").fill(`响应丢失 ${Date.now()}`);
  await page.getByLabel("技术文档").fill("客户端响应丢失时使用原幂等键重试。");
  await page.getByRole("button", { name: "提交任务" }).click();
  await expect(
    page.getByRole("button", { name: "使用原幂等键重试" }),
  ).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "使用原幂等键重试" }).click();
  await expect(
    page.getByRole("button", { name: "查看结果与原文依据" }),
  ).toBeVisible({ timeout: 20000 });
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
});

test("external consent is explicit and retained only with the pending intent", async ({ page }) => {
  // Metadata fixture exercises browser gating; execution still uses the real backend's FakeModel.
  // Compatible HTTP execution and billing fences are covered separately by ModelExecutionTest.
  const modelName = "compatible-model-" + "x".repeat(100);
  await page.route("**/model-profile", async route => {
    const response = await route.fetch();
    await route.fulfill({ response, json: { ...await response.json(), billable: true, provider: "openai-compatible", modelName } });
  });
  await page.setViewportSize({ width: 390, height: 1000 });
  await login(page);
  await expect(page.locator(".metrics .model")).toContainText(modelName);
  await page.getByLabel("任务标题").fill(`外发确认验收 ${Date.now()}`);
  await page.getByLabel("技术文档").fill("请求确认与原幂等意图一同保存，响应丢失后不得创建额外任务。");
  const submit = page.getByRole("button", { name: "提交任务", exact: false });
  const consent = page.getByLabel("我同意将本文档发送给已配置的模型供应商", { exact: false });
  await expect(submit).toBeDisabled();
  await expect(consent).not.toBeChecked();
  await consent.focus();
  await page.keyboard.press("Space");
  await expect(submit).toBeEnabled();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: "../artifacts/compatible-consent-mobile.png", fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: "../artifacts/compatible-consent-desktop.png", fullPage: true });
  const requests: { key: string; body: string | null; jobId: string }[] = [];
  await page.route("**/api/v1/workspaces/*/jobs", async route => {
    if (route.request().method() !== "POST") return route.continue();
    const response = await route.fetch();
    expect(response.status()).toBe(202);
    requests.push({ key: route.request().headers()["idempotency-key"], body: route.request().postData(), jobId: (await response.json()).jobId });
    if (requests.length === 1) await route.abort("failed");
    else await route.fulfill({ response });
  });
  await submit.click();
  await expect(page.getByRole("button", { name: "使用原幂等键重试" })).toBeVisible();
  await page.reload();
  await expect(consent).not.toBeChecked();
  await page.getByRole("button", { name: "使用原幂等键重试" }).click();
  await expect(page.getByRole("button", { name: "查看结果与原文依据" })).toBeVisible({ timeout: 20000 });
  expect(requests).toHaveLength(2);
  expect(requests[1]).toEqual(requests[0]);
  expect(JSON.parse(requests[0].body!).acknowledgeExternalProcessing).toBe(true);
  await page.getByRole("button", { name: "新建文档", exact: true }).click();
  await expect(consent).not.toBeChecked();
  await expect(submit).toBeDisabled();
});
