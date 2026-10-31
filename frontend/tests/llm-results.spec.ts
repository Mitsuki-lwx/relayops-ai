import { expect, test, type Page } from "@playwright/test";
import { snapshot } from "./snapshot";
import type { ResultBody } from "../src/resultTypes";

const source =
  "事务提交后返回已受理。原文示例：<script>window.sourceExecuted=true</script>。";
const claim = {
  text: "提交后才受理。",
  evidence: [{ paragraphId: "p0001", quote: "事务提交后返回已受理。" }],
};
const result: ResultBody = {
  data: {
    title: "模型结果验收",
    summary: claim,
    keyPoints: [claim],
    constraints: [{ ...claim, text: "必须等待事务提交。" }],
    unknowns: ["未给出回调地址。"],
  },
  paragraphs: [{ paragraphId: "p0001", text: source }],
};
const succeeded = {
  jobId: "fixture-success",
  title: "模型结果验收",
  status: "SUCCEEDED",
  applicationId: "app",
  modelProfile: "fixture-compatible",
  errorCode: null,
  createdAt: "2026-10-30T00:00:00Z",
  payloadExpiresAt: "2099-01-01T00:00:00Z",
  modelCalls: 1,
  version: 3,
  parentJobId: null,
};

async function fixture(page: Page, body = result, errorCode?: string) {
  const state = { denied: false, mutations: 0 };
  const job = errorCode
    ? { ...succeeded, status: "FAILED", errorCode }
    : succeeded;
  // Every auth/API request is fulfilled locally. These tests cannot create paid jobs.
  await page.route(/\/(?:api\/v1|auth)\//, async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() !== "GET") {
      state.mutations++;
      await route.abort();
      return;
    }
    let value: unknown;
    let status = 200;
    if (path === "/auth/csrf")
      value = { token: "fixture-token", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/v1/me")
      value = {
        username: "fixture.owner",
        workspaces: [
          {
            id: "space",
            name: "验收空间",
            role: "OWNER",
            tenantId: "tenant",
            tenantName: "验收公司",
          },
        ],
      };
    else if (path === "/api/v1/organizations") value = [];
    else if (path.endsWith("/destinations") || path.endsWith("/deliveries"))
      value = [];
    else if (path.endsWith("/applications"))
      value = [{ id: "app", name: "文档提取" }];
    else if (path.endsWith("/model-profile"))
      value = {
        profileId: "fixture-compatible",
        provider: "openai-compatible",
        modelName: "fixture-model",
        billable: true,
        creditsPerJob: 1,
      };
    else if (path.endsWith("/budget"))
      value = {
        credits: { dailyLimit: 20, spent: 1, reserved: 0, uncertain: 0 },
      };
    else if (path.endsWith("/jobs")) value = [job];
    else if (path.endsWith("/result")) {
      status = state.denied ? 403 : 200;
      value = state.denied
        ? { code: "FORBIDDEN", message: "正文读取权限已撤销" }
        : body;
    } else if (path.endsWith("/jobs/fixture-success")) value = job;
    else throw new Error(`Unexpected fixture path: ${path}`);
    await route.fulfill({
      status,
      contentType: "application/json",
      json: value,
    });
  });
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "AI 任务工作台", exact: true }),
  ).toBeVisible();
  await page.locator(".job-row").click();
  return state;
}

for (const width of [1440, 768, 390]) {
  test(`full model result and exact source at ${width}px without API mutations`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 1000 });
    const state = await fixture(page);
    await page.getByRole("button", { name: "查看结果与原文依据" }).click();
    await expect(page.locator('[data-result-section="summary"]')).toContainText(
      claim.text,
    );
    await expect(
      page.locator('[data-result-section="constraints"]'),
    ).toContainText("必须等待事务提交。");
    await expect(
      page.locator('[data-result-section="unknowns"]'),
    ).toContainText("未给出回调地址。");
    for (const section of ["summary", "keyPoints", "constraints"]) {
      const area = page.locator(`[data-result-section="${section}"]`);
      await area.locator("summary").click();
      await expect(area.locator("mark")).toHaveText(claim.evidence[0]!.quote);
      await expect(area.locator(".source-paragraph")).toHaveText(source);
    }
    expect(await page.evaluate(() => "sourceExecuted" in window)).toBe(false);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.evaluate(() => document.fonts.ready);
    if (width === 1440) await snapshot(page, "result");
    await page.screenshot({
      path: `../artifacts/stage7-result-${width}.png`,
      fullPage: true,
    });
    expect(state.mutations).toBe(0);
  });
}

test("empty collections have explicit descriptions", async ({ page }) => {
  const state = await fixture(page, {
    ...result,
    data: { ...result.data, keyPoints: [], constraints: [], unknowns: [] },
  });
  await page.getByRole("button", { name: "查看结果与原文依据" }).click();
  await expect(
    page.getByText("未提取到关键结论。", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("未提取到明确约束。", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("模型未列出额外待确认项。", { exact: true }),
  ).toBeVisible();
  expect(state.mutations).toBe(0);
});

test("denied re-read clears every previous result and source", async ({
  page,
}) => {
  const state = await fixture(page);
  await page.getByRole("button", { name: "查看结果与原文依据" }).click();
  await expect(page.locator(".result")).toBeVisible();
  state.denied = true;
  await page.getByRole("button", { name: "重新授权读取结果" }).click();
  await expect(page.getByRole("alert")).toContainText("正文读取权限已撤销");
  await expect(page.locator(".result")).toHaveCount(0);
  await expect(page.locator(".source-paragraph")).toHaveCount(0);
  expect(state.mutations).toBe(0);
});

test("expired payload explains retention and offers no impossible rerun", async ({ page }) => {
  const state = await fixture(page, result, "PAYLOAD_EXPIRED");
  await expect(page.getByRole("region", { name: "任务错误说明" })).toContainText("正文保留期已到");
  await expect(page.getByRole("button", { name: "确认创建新任务", exact: true })).toHaveCount(0);
  await expect(page.locator(".result")).toHaveCount(0);
  expect(state.mutations).toBe(0);
});

for (const code of [
  "OUTPUT_INVALID",
  "MODEL_RESULT_UNKNOWN",
  "MODEL_REJECTED",
]) {
  test(`${code} explains the failure and requires a fresh explicit intent`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 1000 });
    const state = await fixture(page, result, code);
    const explanation = page.getByRole("region", { name: "任务错误说明" });
    await expect(explanation).toContainText(code);
    await expect(explanation.locator("h3")).not.toHaveText("任务未完成");
    const retry = page.getByRole("button", {
      name: "确认创建新任务",
      exact: true,
    });
    await expect(retry).toBeDisabled();
    await page.getByLabel("重跑原因", { exact: true }).fill("修正配置后复验");
    await expect(retry).toBeDisabled();
    await page.locator("#retry-ack").check();
    await expect(retry).toBeEnabled();
    await expect(page.locator(".result")).toHaveCount(0);
    expect(state.mutations).toBe(0);
  });
}
