/*
 * 财报分析 Tab 的 UI 测试。
 *
 * 全部 HTTP 请求离线拦截:验证评分卡、9 信号清单、趋势图渲染、确定性回退徽标、
 * 数据源不可用状态,以及四种视口下无横向溢出。不访问真实行情 Provider 或大模型 API。
 */
const { test, expect } = require("@playwright/test");
const path = require("path");
const baseSnapshot = require("./fixtures/partial-snapshot.json");
const financialReport = require("./fixtures/financial-report.json");

const viewports = [
  { width: 1440, height: 1000 },
  { width: 1024, height: 768 },
  { width: 768, height: 1024 },
  { width: 390, height: 844 },
];

async function mockApis(page) {
  await page.route("**/api/agent/status", (route) => route.fulfill({ json: { enabled: false, status: "DISABLED_CONFIGURATION_MISSING" } }));
  await page.route("**/api/stocks/600519/snapshot", (route) => route.fulfill({ json: baseSnapshot }));
  await page.route("**/api/stocks/600519/candlestick**", (route) => route.fulfill({ json: {
    status: "UNAVAILABLE", payload: null, provenance: null, issues: ["该测试不加载蜡烛图数据"],
  } }));
  await page.route("**/api/stocks/search**", (route) => route.fulfill({ json: [{ code: "600519", name: "贵州茅台", exchange: "SHANGHAI" }] }));
  await page.route("**/api/agent/financial-report", (route) => route.fulfill({ json: financialReport }));
}

async function openFinancialTab(page) {
  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "财报分析" }).click();
}

test.beforeEach(async ({ page }) => {
  await mockApis(page);
});

for (const viewport of viewports) {
  test(`renders score, signals and chart at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await openFinancialTab(page);
    await page.getByRole("button", { name: "生成财报分析" }).click();

    await expect(page.locator(".financial-score")).toHaveText("6");
    await expect(page.locator(".financial-latest-period")).toContainText("2026-03-31");
    await expect(page.locator(".financial-latest-period")).toContainText("营业总收入");
    await expect(page.locator(".financial-latest-period")).toContainText("571.3 亿");
    await expect(page.locator(".financial-latest-period .source-status")).toContainText("fixture");
    await expect(page.locator(".financial-analysis-overview")).toContainText("6 项通过");
    await expect(page.locator(".financial-analysis-overview")).toContainText("3 项需关注");
    await expect(page.locator(".financial-trend-snapshot .trend-snapshot-item")).toHaveCount(4);
    await expect(page.locator(".financial-trend-snapshot")).toContainText("营业总收入");
    await expect(page.locator(".financial-trend-snapshot")).toContainText("同比 +11.1%");
    await expect(page.locator(".financial-signals li")).toHaveCount(9);
    await expect(page.locator("#financial-trend-chart canvas")).toBeVisible();
    await expect(page.locator(".financial-narrative")).toContainText("总体结论");
    await expect(page.locator(".financial-narrative")).toContainText("为什么得出这个结论");
    await expect(page.locator(".financial-narrative")).toContainText("需要留意什么");
    await expect(page.locator(".report-disclaimer")).toContainText("仅供学习研究，不构成投资建议");
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);
    if (process.env.UI_CAPTURE_DIR) {
      await page.screenshot({
        path: path.resolve(process.env.UI_CAPTURE_DIR, `financial-${viewport.width}x${viewport.height}.png`),
        fullPage: true,
      });
    }
  });
}

test("shows deterministic fallback badge", async ({ page }) => {
  await openFinancialTab(page);
  await page.getByRole("button", { name: "生成财报分析" }).click();

  await expect(page.locator(".financial-report-header .source-status")).toContainText("确定性规则回退");
});

test("shows model-assisted badge when live narrative passes validation", async ({ page }) => {
  await page.route("**/api/agent/financial-report", (route) => route.fulfill({
    json: { ...financialReport, generationMode: "MODEL_ASSISTED" },
  }));
  await openFinancialTab(page);
  await page.getByRole("button", { name: "生成财报分析" }).click();

  await expect(page.locator(".financial-report-header .source-status")).toContainText("DeepSeek 叙事已校验");
});

test("shows unavailable state when data source fails", async ({ page }) => {
  await page.route("**/api/agent/financial-report", (route) => route.fulfill({
    status: 503,
    contentType: "application/problem+json",
    body: JSON.stringify({
      type: "about:blank",
      title: "财报数据不可用",
      status: 503,
      detail: "Sina statements failed",
      code: "FINANCIAL_DATA_UNAVAILABLE",
    }),
  }));
  await openFinancialTab(page);
  await page.getByRole("button", { name: "生成财报分析" }).click();

  await expect(page.locator("#financial-output")).toContainText("财报分析不可用");
});

test("insufficient data renders honest placeholder instead of zero", async ({ page }) => {
  await page.route("**/api/agent/financial-report", (route) => route.fulfill({
    json: {
      ...financialReport,
      periodCount: 3,
      qualityScore: { total: 0, tier: "数据不足", evaluatedSignals: 0, signals: [], sufficientData: false },
      narrative: {
        tierInterpretation: "报告期不足 4 期，无法计算财务质量评分，当前状态为数据不足。",
        signalCommentary: "当前没有可评估的财务质量信号。",
        trendCommentary: "当前没有可展示的趋势序列。",
        riskNotes: "趋势基于报告期累计口径，同比为当期与上年同期比较。",
      },
    },
  }));
  await openFinancialTab(page);
  await page.getByRole("button", { name: "生成财报分析" }).click();

  await expect(page.locator(".financial-score")).toHaveText("--");
  await expect(page.locator(".financial-score-card")).toContainText("数据不足");
  await expect(page.locator(".financial-signals")).toContainText("数据不足");
});
