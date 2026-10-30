import { test, expect } from "@playwright/test";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { spawn, execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { once } from "node:events";
import { requireFakeModel } from "./require-fake";
import { snapshot } from "./snapshot";

const root = fileURLToPath(new URL("../../", import.meta.url));
const password = readFileSync(
  new URL("../../ops/.env", import.meta.url),
  "utf8",
)
  .split(/\r?\n/)
  .find((l) => l.startsWith("RELAYOPS_DEMO_PASSWORD="))!
  .split("=")
  .slice(1)
  .join("=");
for (const width of [1440, 390]) {
  test(`controlled callback and independent receiver at ${width}px`, async ({
    page,
  }) => {
    test.setTimeout(90000);
    page.setDefaultTimeout(15000);
    await page.setViewportSize({ width, height: 1000 });
    await page.goto("/");
    await page.getByLabel("账号", { exact: true }).fill("owner.a");
    await page.getByLabel("密码", { exact: true }).fill(password);
    await page.getByRole("button", { name: "登录工作台", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "AI 任务工作台", exact: true }),
    ).toBeVisible();
    await requireFakeModel(page);
    const me = await (await page.request.get("/api/v1/me")).json();
    const workspace = me.workspaces[0].id;
    if (width === 390)
      await page.getByRole("button", { name: "展开导航", exact: true }).click();
    await page.getByRole("button", { name: "回调目的地", exact: true }).click();
    const name = `独立接收端 ${width} ${Date.now()}`;
    await page.getByLabel("目的地名称", { exact: true }).fill(name);
    const accepted = page.waitForResponse(
      (r) =>
        r.request().method() === "POST" && r.url().endsWith("/destinations"),
    );
    await page.getByRole("button", { name: "创建目的地", exact: true }).click();
    const destination = await (await accepted).json();
    const read = page.getByRole("button", {
      name: `读取 ${name} 签名密钥`,
      exact: true,
    });
    await expect(read).toBeDisabled();
    await page
      .getByRole("checkbox", { name: /我确认需要读取签名密钥/ })
      .check();
    const revealed = page.waitForResponse((r) =>
      r.url().endsWith(`/${destination.id}/reveal-key`),
    );
    await read.click();
    const key = await (await revealed).json();
    await page.getByRole("button", { name: "隐藏密钥", exact: true }).click();
    await expect(page.locator("[data-secret]")).toHaveCount(0);
    expect(
      await page.evaluate(
        (value) =>
          JSON.stringify(localStorage).includes(value) ||
          JSON.stringify(sessionStorage).includes(value),
        key.secret,
      ),
    ).toBe(false);
    const folder = new URL("../../artifacts/receiver/", import.meta.url);
    mkdirSync(folder, { recursive: true });
    const file = new URL(`browser-${width}-${Date.now()}.json`, folder);
    const inbox = fileURLToPath(new URL(`inbox-${destination.id}.sqlite`, folder));
    writeFileSync(
      file,
      JSON.stringify({
        [destination.id]: { secret: key.secret, workspaceId: workspace },
      }),
      { mode: 0o600 },
    );
    const receiver = spawn(
      "python",
      [
        "scripts/demo-receiver.py",
        "--keys",
        fileURLToPath(file),
        "--db",
        inbox,
        ...(width === 1440 ? ["--fail-first", "2"] : ["--drop-first-response"]),
      ],
      { cwd: root, windowsHide: true, stdio: ["ignore", "pipe", "pipe"] },
    );
    try {
      await expect
        .poll(async () => {
          if (receiver.exitCode !== null)
            throw Error(
              "Local receiver did not start; check that port 8091 is available",
            );
          return page.request
            .get("http://127.0.0.1:8091/ready", { timeout: 2000 })
            .then((r) => r.status())
            .catch(() => 0);
        })
        .toBe(200);
      await page.evaluate(() => document.fonts.ready);
      await page.screenshot({
        path: `../artifacts/stage8-destinations-${width}.png`,
        fullPage: true,
      });
      if (width === 1440) await snapshot(page, "destinations");
      if (width === 390)
        await page
          .getByRole("button", { name: "展开导航", exact: true })
          .click();
      await page.getByRole("button", { name: "新建任务", exact: true }).click();
      await page.getByLabel("任务标题", { exact: true }).fill(name);
      await page
        .getByLabel(/^技术文档/)
        .fill(
          "回调失败只重试交付，不应重新执行模型。\n\n接收端必须按交付ID原子去重。",
        );
      await page.getByRole("checkbox", { name, exact: true }).check();
      const created = page.waitForResponse(
        (r) => r.request().method() === "POST" && /\/jobs$/.test(r.url()),
      );
      await page.getByRole("button", { name: /^提交任务/ }).click();
      const job = await (await created).json();
      const panel = page.getByRole("region", { name: "结果交付", exact: true });
      await expect(panel.getByText("已确认接收", { exact: true })).toBeVisible({
        timeout: 45000,
      });
      await expect(panel.locator(".attempt-list li")).toHaveCount(
        width === 1440 ? 3 : 2,
      );
      if (width === 390) await expect(panel).toContainText("结果未知");
      const current = await (
        await page.request.get(
          `/api/v1/workspaces/${workspace}/jobs/${job.jobId}`,
        )
      ).json();
      expect(current.modelCalls).toBe(1);
      expect(current.status).toBe("SUCCEEDED");
      expect(execFileSync("python", ["-c", "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); print(c.execute('SELECT COUNT(*) FROM inbox').fetchone()[0]); c.close()", inbox], { encoding: "utf8", windowsHide: true }).trim()).toBe("1");
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      await page.screenshot({
        path: `../artifacts/stage8-delivery-${width}.png`,
        fullPage: true,
      });
      if (width === 1440) await snapshot(page, "delivery");
    } finally {
      if (receiver.exitCode === null) {
        const exited = once(receiver, "exit");
        receiver.kill();
        await exited;
      }
    }
  });
}
