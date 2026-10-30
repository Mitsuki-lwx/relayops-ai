import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  workers: 1,
  timeout: 45000,
  outputDir: "../artifacts/playwright",
  reporter: [
    ["list"],
    ["json", { outputFile: "../artifacts/playwright-results.json" }],
  ],
  use: {
    baseURL: "http://127.0.0.1:5173",
    channel: "msedge",
    headless: true,
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "npm run dev -- --port 5173 --strictPort",
    url: "http://127.0.0.1:5173",
    reuseExistingServer: true,
  },
});
