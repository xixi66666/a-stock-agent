const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
  testDir: "./tests/ui",
  timeout: 30_000,
  fullyParallel: false,
  use: {
    baseURL: process.env.UI_BASE_URL || "http://127.0.0.1:10001",
    channel: process.env.PLAYWRIGHT_CHANNEL || "chrome",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  reporter: [["line"]],
});
