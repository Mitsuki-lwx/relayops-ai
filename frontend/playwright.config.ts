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
    baseURL: process.env.RELAYOPS_BROWSER_BASE ?? "http://127.0.0.1:5173",
    channel: process.env.RELAYOPS_BROWSER_CHANNEL ?? "msedge",
    headless: true,
    screenshot: "only-on-failure",
  },
  webServer: process.env.RELAYOPS_BROWSER_BASE ? undefined : {
    command: "npm run dev -- --port 5173 --strictPort",
    url: "http://127.0.0.1:5173",
    reuseExistingServer: true,
  },
});
