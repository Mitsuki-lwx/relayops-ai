import { readFileSync } from "node:fs";
import { test, expect, type Page } from "@playwright/test";

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
    await page.locator("summary").first().click();
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
