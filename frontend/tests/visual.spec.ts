import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { test, expect, type Page } from "@playwright/test";

const password = readFileSync(
  new URL("../../ops/.env", import.meta.url),
  "utf8",
)
  .split(/\r?\n/)
  .find((line) => line.startsWith("RELAYOPS_DEMO_PASSWORD="))!
  .split("=")
  .slice(1)
  .join("=");
async function snapshot(page: Page, name: string) {
  // Local DOM capture for the taste inspector; no session cookies or password values are exported.
  const body = await page.evaluate(() => {
    const clone = document.body.cloneNode(true) as HTMLElement;
    clone.querySelectorAll("script").forEach((node) => node.remove());
    clone
      .querySelectorAll<HTMLInputElement>('input[type="password"]')
      .forEach((node) => {
        node.value = "";
        node.removeAttribute("value");
      });
    return clone.innerHTML;
  });
  const css = readFileSync(
    new URL("../src/style.css", import.meta.url),
    "utf8",
  ).replace(/url\(["']?\/fonts\/([^"')]+)["']?\)/g, (_match, name: string) => {
    const font = readFileSync(
      new URL(`../public/fonts/${name}`, import.meta.url),
    );
    return `url("data:font/ttf;base64,${font.toString("base64")}")`;
  });
  const folder = new URL("../../artifacts/taste/snapshots/", import.meta.url);
  mkdirSync(folder, { recursive: true });
  writeFileSync(
    new URL(`${name}.html`, folder),
    `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>RelayOps ${name}</title><style>${css}</style><body>${body}</body></html>`,
  );
}

for (const width of [1440, 768, 390]) {
  test(`taste layouts and task filtering at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/");
    await page.screenshot({
      path: `../artifacts/taste/login-${width}.png`,
      fullPage: true,
    });
    await page.getByLabel("账号", { exact: true }).fill("owner.a");
    await page.getByLabel("密码", { exact: true }).fill(password);
    await page.getByRole("button", { name: "登录工作台", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "AI 任务工作台", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "新建文档", exact: true }),
    ).toBeEnabled();
    await page.evaluate(() => document.fonts.ready);
    expect(
      await page.evaluate(() => document.fonts.check('14px "IBM Plex Sans"')),
    ).toBe(true);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page
      .getByLabel("搜索任务", { exact: true })
      .fill("没有匹配的标题-visual-check");
    await expect(
      page.getByRole("heading", { name: "没有匹配的任务" }),
    ).toBeVisible();
    await page.getByLabel("搜索任务", { exact: true }).clear();
    await page.getByLabel("搜索任务", { exact: true }).focus();
    await page.keyboard.press("ArrowLeft");
    await expect(page.getByLabel("搜索任务", { exact: true })).toBeFocused();
    expect(await page.getByLabel("搜索任务", { exact: true }).evaluate(node => getComputedStyle(node).outlineStyle)).toBe("solid");
    expect(await page.getByRole("button", { name: "新建文档", exact: true }).evaluate(node => getComputedStyle(node).transitionDuration)).toBe("0s");
    await page.getByRole("button", { name: /^需关注 / }).click();
    await expect(
      page.getByRole("button", { name: /^需关注 / }),
    ).toHaveAttribute("aria-pressed", "true");
    await page.getByRole("button", { name: /^全部 / }).click();
    if (await page.locator(".job-row").count()) {
      await page.locator(".job-row").first().click();
      await expect(page.locator(".detail")).toBeVisible();
      if (width === 390) {
        await expect(page.locator(".task-panel")).toBeHidden();
        await page
          .getByRole("button", { name: "返回任务列表", exact: true })
          .click();
        await expect(page.locator(".task-panel")).toBeVisible();
      }
    }
    await page.screenshot({
      path: `../artifacts/taste/tasks-${width}.png`,
      fullPage: true,
    });
    if (width === 1440) await snapshot(page, "tasks");
    await page.getByRole("button", { name: "新建文档", exact: true }).click();
    await expect(page.getByLabel("技术文档")).toBeVisible();
    await expect(page.getByRole("button", { name: "提交任务" })).toBeDisabled();
    await page.screenshot({
      path: `../artifacts/taste/editor-${width}.png`,
      fullPage: true,
    });
    if (width === 1440) await snapshot(page, "editor");
    if (width === 390) {
      await page.getByRole("button", { name: "展开导航", exact: true }).click();
      await page.getByRole("button", { name: "组织管理", exact: true }).click();
      await expect(
        page.getByRole("button", { name: "展开导航", exact: true }),
      ).toBeVisible();
    } else
      await page.getByRole("button", { name: "组织管理", exact: true }).click();
    await expect(page.getByLabel("管理公司", { exact: true })).toBeVisible();
    await page.screenshot({
      path: `../artifacts/taste/organization-${width}.png`,
      fullPage: true,
    });
    if (width === 1440) await snapshot(page, "organization");
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  });
}
