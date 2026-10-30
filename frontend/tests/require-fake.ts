import { expect, type Page } from "@playwright/test";

// Mutation suites must explicitly run a backend started with -FakeModel.
export async function requireFakeModel(page: Page) {
  const me = await page.request.get("/api/v1/me");
  expect(me.ok()).toBe(true);
  const { workspaces } = await me.json();
  for (const workspace of workspaces) {
    const response = await page.request.get(
      `/api/v1/workspaces/${workspace.id}/model-profile`,
    );
    expect(response.ok()).toBe(true);
    expect(
      (await response.json()).billable,
      "This suite creates jobs. Restart the backend with -FakeModel first.",
    ).toBe(false);
  }
}
