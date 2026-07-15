const { test, expect } = require("@playwright/test");
const baseSnapshot = require("./fixtures/partial-snapshot.json");

const indicators = [
  ["SMA_5", "趋势", "SMA 5"], ["SMA_10", "趋势", "SMA 10"],
  ["SMA_20", "趋势", "SMA 20"], ["SMA_30", "趋势", "SMA 30"],
  ["SMA_60", "趋势", "SMA 60"], ["SMA_120", "趋势", "SMA 120"],
  ["SMA_250", "趋势", "SMA 250"], ["EMA_20", "趋势", "EMA 20"],
  ["EMA_60", "趋势", "EMA 60"], ["BIAS_20", "趋势", "BIAS 20"],
  ["MACD_12_26_9", "动量", "MACD"], ["RSI_6", "动量", "RSI 6"],
  ["RSI_12", "动量", "RSI 12"], ["RSI_24", "动量", "RSI 24"],
  ["KDJ_9_3_3", "动量", "KDJ"], ["CCI_20", "动量", "CCI 20"],
  ["ROC_12", "动量", "ROC 12"], ["WILLIAMS_R_14", "动量", "Williams %R"],
  ["PSY_12", "动量", "PSY 12"], ["ATR_14", "波动", "ATR 14"],
  ["NATR_14", "波动", "NATR 14"], ["BOLL_WIDTH_20_2", "波动", "布林带宽"],
  ["HISTORICAL_VOLATILITY_20", "波动", "20 日历史波动率"], ["DONCHIAN_WIDTH_20", "波动", "唐奇安通道宽度"],
  ["VOLUME_RATIO_20", "量价", "20 期量比"], ["OBV", "量价", "OBV"],
  ["MFI_14", "量价", "MFI 14"], ["CMF_20", "量价", "Chaikin Money Flow"],
  ["RETURN_20", "相对强弱与风险", "20 期收益"], ["RETURN_60", "相对强弱与风险", "60 期收益"],
  ["RETURN_120", "相对强弱与风险", "120 期收益"], ["RETURN_250", "相对强弱与风险", "250 期收益"],
  ["MAX_DRAWDOWN", "相对强弱与风险", "最大回撤"], ["SHARPE", "相对强弱与风险", "Sharpe"],
  ["VAR_95", "相对强弱与风险", "历史 VaR 95%"], ["CVAR_95", "相对强弱与风险", "历史 CVaR 95%"],
  ["SKEWNESS", "相对强弱与风险", "收益偏度"], ["KURTOSIS", "相对强弱与风险", "超额峰度"],
];

function snapshot() {
  const result = structuredClone(baseSnapshot);
  result.technical.payload.cards = indicators.map(([id, group, name], index) => ({
    id, group, name, parameters: { period: index % 20 + 5 },
    value: Number((38 + Math.sin(index) * 22).toFixed(2)),
    unit: id.includes("RETURN") || id.includes("BIAS") ? "%" : "",
    state: index % 5 === 0 ? "STRONG" : index % 7 === 0 ? "WEAK" : "NEUTRAL",
    trigger: `${name} 当前状态由最近有效交易日数据计算`,
    series: Array.from({ length: 30 }, (_, point) => Number((30 + index + Math.sin(point / 3 + index) * 8).toFixed(2))),
    calculatedAt: "2026-07-15", sectionStatus: "HEALTHY",
  }));
  return result;
}

async function mockApis(page) {
  await page.route("**/api/agent/status", (route) => route.fulfill({ json: { enabled: false, status: "DISABLED_CONFIGURATION_MISSING" } }));
  await page.route("**/api/stocks/600519/snapshot", (route) => route.fulfill({ json: snapshot() }));
  await page.route("**/api/stocks/search**", (route) => route.fulfill({ json: [{ code: "600519", name: "贵州茅台", exchange: "SHANGHAI" }] }));
}

test.beforeEach(async ({ page }) => {
  await mockApis(page);
});

for (const viewport of [
  { width: 1440, height: 1000, columns: 4 },
  { width: 1024, height: 768, columns: 3 },
  { width: 768, height: 1024, columns: 2 },
  { width: 390, height: 844, columns: 1 },
]) {
  test(`technical workbench is stable at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto("/");
    await page.locator('[data-symbol="600519"]').click();
    await expect(page.locator(".indicator-card")).toHaveCount(38);
    const columns = await page.locator(".indicator-grid").evaluate((element) => getComputedStyle(element).gridTemplateColumns.split(" ").length);
    expect(columns).toBe(viewport.columns);
    await expect(page.locator("#technical-chart canvas")).toBeVisible();
    const paintedPixels = await page.locator("#technical-chart canvas").evaluate((canvas) => {
      const context = canvas.getContext("2d");
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
      let painted = 0;
      for (let index = 3; index < pixels.length; index += 4) if (pixels[index] > 0) painted += 1;
      return painted;
    });
    expect(paintedPixels).toBeGreaterThan(100);
    const layout = await page.evaluate(() => {
      const header = document.querySelector(".app-header").getBoundingClientRect();
      const overview = document.querySelector(".security-overview").getBoundingClientRect();
      const overflowingButtons = [...document.querySelectorAll("button")]
        .filter((button) => button.scrollWidth > button.clientWidth + 1 || button.scrollHeight > button.clientHeight + 1)
        .map((button) => ({ className: button.className, text: button.textContent.trim().slice(0, 30), client: [button.clientWidth, button.clientHeight], scroll: [button.scrollWidth, button.scrollHeight] }));
      return {
        scrollWidth: document.documentElement.scrollWidth,
        viewportWidth: document.documentElement.clientWidth,
        headerOverlap: overview.top < header.bottom - 1,
        overflowingButtons,
      };
    });
    expect(layout.scrollWidth).toBeLessThanOrEqual(layout.viewportWidth);
    expect(layout.headerOverlap).toBe(false);
    expect(layout.overflowingButtons).toEqual([]);
    await page.screenshot({ path: `target/ui-screenshots/dashboard-${viewport.width}x${viewport.height}.png`, fullPage: true });
  });
}

test("every research tab renders an owned state without raw JSON", async ({ page }) => {
  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  for (const tab of ["资金筹码", "基本面", "估值预期", "事件资讯", "数据来源", "Agent 分析"]) {
    await page.getByRole("tab", { name: tab }).click();
    await expect(page.locator("#view-content")).toBeVisible();
    await expect(page.locator("#view-content pre")).toHaveCount(0);
  }
});

test("source quality uses the backend scoring weights", async ({ page }) => {
  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "数据来源" }).click();
  await expect(page.locator(".quality-components")).toContainText("30 / 30");
  await expect(page.locator(".quality-components")).toContainText("17 / 25");
  await expect(page.locator(".quality-components")).toContainText("15 / 15");
});
