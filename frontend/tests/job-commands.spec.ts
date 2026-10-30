import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { test, expect, type Page } from "@playwright/test";
import { requireFakeModel } from "./require-fake";

const root = fileURLToPath(new URL("../../", import.meta.url));
const password = readFileSync(new URL("../../ops/.env", import.meta.url), "utf8")
  .split(/\r?\n/).find(line => line.startsWith("RELAYOPS_DEMO_PASSWORD="))!.split("=").slice(1).join("=");
function broker(action: "stop" | "start") {
  execFileSync("docker.exe", ["compose", "--project-name", "relayops", "-f", "ops/compose.yml", "--env-file", "ops/.env", action,
    ...(action === "start" ? ["--wait"] : []), "rabbitmq"], { cwd: root, timeout: 60000, stdio: "pipe" });
}
function fixtureSql(sql: string) {
  execFileSync("docker.exe", ["exec", "-i", "relayops-mysql-1", "sh", "-c",
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" relayops'], { cwd: root, input: sql, timeout: 15000, stdio: ["pipe", "pipe", "pipe"] });
}
async function login(page: Page) {
  await page.goto("/");
  await page.getByLabel("账号", { exact: true }).fill(process.env.RELAYOPS_BROWSER_USER ?? "owner.b");
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录工作台" }).click();
  await expect(page.getByRole("heading", { name: "AI 任务工作台" })).toBeVisible();
  await requireFakeModel(page);
  await page.getByRole("button", { name: "新建文档", exact: true }).click();
}
async function queued(page: Page, title: string) {
  await page.getByLabel("任务标题").fill(title);
  await page.getByLabel("技术文档").fill("明确重跑创建独立任务，保留来源任务的用量记录。");
  const accepted = page.waitForResponse(r => r.request().method() === "POST" && /\/jobs$/.test(r.url()));
  await page.getByRole("button", { name: "提交任务" }).click();
  const response = await accepted;
  expect(response.status()).toBe(202);
  const { jobId } = await response.json();
  await expect(page.getByRole("button", { name: "取消排队", exact: true })).toBeVisible();
  return jobId as string;
}
async function loseFirstResponse(page: Page, operation: string) {
  const requests: { key: string; body: string | null; jobId: string }[] = [];
  await page.route(`**/jobs/*/${operation}`, async route => {
    const response = await route.fetch();
    expect(response.status()).toBe(operation === "cancel" ? 200 : 202);
    const body = await response.json();
    requests.push({ key: route.request().headers()["idempotency-key"], body: route.request().postData(), jobId: body.jobId });
    if (requests.length === 1) await route.abort("failed");
    else await route.fulfill({ response });
  });
  return requests;
}

test("mobile cancellation survives a lost response and reload", async ({ page }) => {
  test.setTimeout(100000);
  await page.setViewportSize({ width: 390, height: 1000 });
  await login(page);
  broker("stop");
  try {
    await queued(page, `取消验收 ${Date.now()}`);
    const requests = await loseFirstResponse(page, "cancel");
    await expect(page.getByRole("button", { name: "取消排队", exact: true })).toBeEnabled();
    await page.getByRole("button", { name: "取消排队", exact: true }).focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("alert")).toBeVisible();
    await page.reload();
    await expect(page.locator(".detail .status")).toHaveText("已取消");
    await page.getByRole("button", { name: "沿用原操作重试" }).click();
    await expect(page.getByRole("button", { name: "沿用原操作重试" })).toHaveCount(0);
    await expect(page.locator(".detail-meta")).toContainText("调用边界记录：0 次");
    expect(requests).toHaveLength(2);
    expect(requests[1]).toEqual(requests[0]);
    await expect(page.getByLabel("当前空间", { exact: true })).toBeEnabled();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: "../artifacts/cancel-mobile.png", fullPage: true });
  } finally { broker("start"); }
});

test("explicit rerun retains source and replays the same child after reload", async ({ page }) => {
  test.setTimeout(100000);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await login(page);
  broker("stop");
  let sourceId: string;
  const title = `重跑验收 ${Date.now()}`;
  try {
    sourceId = await queued(page, title);
    if (!/^[a-f0-9-]{36}$/.test(sourceId)) throw new Error("Invalid fixture ID");
    // Scope the simulated uncertain provider outcome to this test's own new job.
    // No provider is called; the new child then uses the real Rabbit consumer/FakeModel.
    const sql = `START TRANSACTION;
      UPDATE budget_account b JOIN ai_job j ON b.workspace_id=j.workspace_id AND b.accepted_date=j.accepted_date
        SET b.reserved=b.reserved-1,b.uncertain=b.uncertain+1 WHERE j.id='${sourceId}' AND j.status='QUEUED';
      INSERT INTO ai_attempt(id,workspace_id,job_id,lease_token,status,sent_at,completed_at)
        VALUES(UUID(),(SELECT workspace_id FROM ai_job WHERE id='${sourceId}' AND status='QUEUED'),
          '${sourceId}',UUID(),'UNKNOWN',NOW(3),NOW(3));
      UPDATE budget_entry SET state='UNCERTAIN' WHERE job_id='${sourceId}' AND state='RESERVED';
      UPDATE ai_job SET status='UNKNOWN',error_code='MODEL_RESULT_UNKNOWN',version=version+1,completed_at=NOW(3)
        WHERE id='${sourceId}' AND status='QUEUED'; COMMIT;`;
    fixtureSql(sql);
  } finally { broker("start"); }
  await page.getByRole("button", { name: new RegExp(title) }).click();
  await expect(page.locator(".detail .status")).toHaveText("结果未知");
  const confirm = page.getByRole("button", { name: "确认创建新任务" });
  await expect(confirm).toBeDisabled();
  await page.getByLabel("重跑原因", { exact: true }).fill("人工确认后再次生成，保留来源的不确定用量。");
  await page.getByLabel("我确认这是一次新的模型调用", { exact: false }).check();
  await page.screenshot({ path: "../artifacts/rerun-desktop.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 1000 });
  await page.evaluate(async () => { document.querySelector(".job-list")!.scrollTop = 0; await new Promise(requestAnimationFrame); await new Promise(requestAnimationFrame); });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: "../artifacts/rerun-mobile.png", fullPage: true });
  const requests = await loseFirstResponse(page, "retry");
  await confirm.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("alert")).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "沿用原操作重试" }).click();
  await expect(page.getByRole("button", { name: "查看结果与原文依据" })).toBeVisible({ timeout: 25000 });
  expect(requests).toHaveLength(2);
  expect(requests[1]).toEqual(requests[0]);
  expect(requests[0].jobId).not.toBe(sourceId!);
  await expect(page.locator(".job-id")).toHaveText(requests[0].jobId);
  await page.getByRole("button", { name: `来源任务：${sourceId!}` }).click();
  await expect(page.locator(".detail .status")).toHaveText("结果未知");
  await expect(page.locator(".detail-meta")).toContainText("调用边界记录：1 次");
  await page.unroute("**/jobs/*/retry");
  // Controlled changes affect only this test's source, exercising real 409/410 responses.
  fixtureSql(`UPDATE ai_job SET version=version+1 WHERE id='${sourceId!}' AND status='UNKNOWN';`);
  await page.getByLabel("重跑原因", { exact: true }).fill("检查版本冲突提示");
  await page.getByLabel("我确认这是一次新的模型调用", { exact: false }).check();
  await confirm.click();
  await expect(page.getByRole("alert")).toContainText("任务版本已变化");
  await page.getByRole("button", { name: "放弃本地操作记录" }).click();
  fixtureSql(`UPDATE ai_job SET payload_expires_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id='${sourceId!}' AND status='UNKNOWN';`);
  await page.getByLabel("重跑原因", { exact: true }).fill("检查过期提示");
  await page.getByLabel("我确认这是一次新的模型调用", { exact: false }).check();
  await confirm.click();
  await expect(page.getByRole("alert")).toContainText("来源正文已过期");
});
