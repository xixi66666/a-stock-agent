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
  await page.route("**/api/agent/models?capability=overall-report", (route) => route.fulfill({ json: {
    models: [
      { id: "deepseek", modelName: "deepseek-chat", defaultModel: true },
      { id: "mimo", modelName: "mimo-v2.5-pro", defaultModel: false },
      { id: "primary", modelName: "gpt-5", defaultModel: false },
    ],
  } }));
  await page.route("**/api/stocks/600519/snapshot", (route) => route.fulfill({ json: snapshot() }));
  await page.route("**/api/stocks/search**", (route) => route.fulfill({ json: [{ code: "600519", name: "贵州茅台", exchange: "SHANGHAI" }] }));
}

test.beforeEach(async ({ page }) => {
  await mockApis(page);
});

test("overall report model can be selected per request", async ({ page }) => {
  let requestBody = null;
  await page.route("**/api/agent/overall-report", async (route) => {
    requestBody = route.request().postDataJSON();
    await route.fulfill({ json: {
      status: "MODEL_ASSISTED",
      report: {
        overallConclusion: "总体判断内容",
        dataQualitySummary: "数据质量摘要",
        companyAndFundamentals: "基本面",
        technicalAndCapital: "技术与资金",
        valuationAndIndustry: "估值与行业",
        eventsAndSentiment: "事件与情绪",
        bullishEvidence: [], bearishEvidence: [], riskFactors: [], scenarios: {},
        conflictsAndMissingData: [], sourceReferences: [],
        modelName: "mimo-v2.5-pro", disclaimer: "仅供学习研究，不构成投资建议",
      },
    } });
  });

  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
  const selector = page.getByLabel("总体报告模型");
  await expect(selector).toHaveValue("deepseek");
  await selector.selectOption("mimo");
  await page.getByRole("button", { name: /生成总体报告/ }).click();

  expect(requestBody).toEqual({ code: "600519", modelId: "mimo" });
  await expect(page.locator("#overall-report-output")).toContainText("mimo-v2.5-pro");
  await expect(page.locator("#overall-report-output")).not.toContainText("DeepSeek 总体报告");
});

test("overall report generation is disabled when no model is available", async ({ page }) => {
  await page.unroute("**/api/agent/models?capability=overall-report");
  await page.route("**/api/agent/models?capability=overall-report", (route) =>
    route.fulfill({ json: { models: [] } }));

  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();

  await expect(page.getByLabel("总体报告模型")).toBeDisabled();
  await expect(page.getByRole("button", { name: /生成总体报告/ })).toBeDisabled();
  await expect(page.locator("#overall-model-help")).toContainText("没有可用模型");
  await expect(page.getByRole("button", { name: "生成研究报告" })).toBeEnabled();
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

    await page.getByRole("tab", { name: "Agent 分析" }).click();
    await expect(page.getByLabel("总体报告模型")).toBeVisible();
    const agentLayout = await page.evaluate(() => {
      const button = document.querySelector("#run-overall-report");
      const selector = document.querySelector("#overall-model-select");
      return {
        scrollWidth: document.documentElement.scrollWidth,
        viewportWidth: document.documentElement.clientWidth,
        buttonFits: button.scrollWidth <= button.clientWidth + 1 && button.scrollHeight <= button.clientHeight + 1,
        selectorFits: selector.scrollWidth <= selector.clientWidth + 1,
      };
    });
    expect(agentLayout.scrollWidth).toBeLessThanOrEqual(agentLayout.viewportWidth);
    expect(agentLayout.buttonFits).toBe(true);
    expect(agentLayout.selectorFits).toBe(true);
    await page.screenshot({ path: `target/ui-screenshots/agent-${viewport.width}x${viewport.height}.png`, fullPage: true });
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

test("agent renders the structured report even when direction metadata is absent", async ({ page }) => {
  await page.route("**/api/agent/analyze", (route) => route.fulfill({
    json: {
      direction: null,
      generationMode: null,
      executiveSummary: "结构化判断内容",
      technicalAndFlow: { narrative: "技术与资金分析内容", facts: [{ label: "最新价", value: "100" }], evidence: [] },
      fundamentals: { narrative: "基本面分析内容", facts: [{ label: "收入同比", value: "12%" }], evidence: [] },
      valuationAndIndustry: { narrative: "估值与行业分析内容", facts: [{ label: "个股PE", value: "20" }], evidence: [] },
      coreDrivers: [], catalysts: [], risks: [], conflicts: [], missingData: [], invalidationConditions: [], sources: [],
      disclaimer: "仅供学习研究，不构成投资建议",
    },
  }));
  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
  await page.getByRole("button", { name: "生成研究报告" }).click();
  await expect(page.locator("#agent-output")).toContainText("结构化判断内容");
  await expect(page.locator("#agent-output")).toContainText("技术与资金分析内容");
  await expect(page.locator("#agent-output")).toContainText("最新价");
});

test("agent renders evidence-backed report diagnostics", async ({ page }) => {
  await page.route("**/api/agent/analyze", (route) => route.fulfill({ json: {
    direction: "STRONGER", generationMode: "DETERMINISTIC_FALLBACK", evidenceStatus: "SUFFICIENT",
    executiveSummary: "确定性报告完整保留",
    coreDrivers: [{ conclusion: "SMA20高于SMA60", rationale: "趋势与动量互相确认", invalidation: "均线反向交叉" }],
    technicalAndFlow: {
      narrative: "技术结论", facts: [{ label: "SMA20", value: "100" }],
      signals: [{ conclusion: "20日收益为正" }], methodology: ["趋势跟随与动量确认"],
      counterEvidence: ["量能未同步放大"], limitations: ["历史指标不代表未来收益"],
    },
    fundamentals: { narrative: "基本面结论", facts: [], signals: [], methodology: [], counterEvidence: [], limitations: [] },
    valuationAndIndustry: { narrative: "估值结论", facts: [], signals: [], methodology: [], counterEvidence: [], limitations: [] },
    catalysts: [], risks: [], conflicts: [], missingData: [], invalidationConditions: [], sources: [],
    modelDiagnostic: {
      failureStage: "VALIDATION", errorCode: "MODEL_NARRATIVE_VALIDATION_FAILED",
      exceptionType: "ReportValidationException", message: "包含证据包未支持的数字",
      validationIssues: ["UNSUPPORTED_NUMBER"], modelName: "gpt-test", durationMs: 80,
      occurredAt: "2026-08-03T02:00:00Z", traceId: "trace-test-1",
    }, disclaimer: "仅供学习研究，不构成投资建议",
  } }));

  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
  await page.getByRole("button", { name: "生成研究报告" }).click();

  await expect(page.locator("#agent-output")).toContainText("SMA20高于SMA60");
  await expect(page.locator("#agent-output")).toContainText("趋势跟随与动量确认");
  await expect(page.locator("#agent-output")).toContainText("反证与限制");
  await expect(page.locator("#agent-output")).toContainText("MODEL_NARRATIVE_VALIDATION_FAILED");
  await page.locator(".model-diagnostic summary").click();
  await expect(page.locator("#agent-output")).toContainText("trace-test-1");
});

test("agent keeps valid model narrative when only one field falls back", async ({ page }) => {
  await page.route("**/api/agent/analyze", (route) => route.fulfill({ json: {
    direction: "NEUTRAL", generationMode: "MODEL_ASSISTED_PARTIAL", evidenceStatus: "PARTIAL",
    executiveSummary: "模型主摘要", coreDrivers: [],
    technicalAndFlow: { narrative: "确定性技术参考", facts: [], signals: [], methodology: [], counterEvidence: [], limitations: [] },
    fundamentals: { narrative: "模型基本面叙述", facts: [], signals: [], methodology: [], counterEvidence: [], limitations: [] },
    valuationAndIndustry: { narrative: "模型估值叙述", facts: [], signals: [], methodology: [], counterEvidence: [], limitations: [] },
    catalysts: [], risks: [], conflicts: [], missingData: [], invalidationConditions: [], sources: [],
    modelDiagnostic: {
      failureStage: "VALIDATION", errorCode: "MODEL_NARRATIVE_VALIDATION_FAILED",
      exceptionType: "ReportValidationException", message: "技术模块存在阻断问题",
      validationIssues: ["TRADE_INSTRUCTION"], modelName: "mimo-v2.5-pro", durationMs: 120,
      occurredAt: "2026-08-03T06:00:00Z", traceId: "trace-partial-1",
    }, disclaimer: "仅供学习研究，不构成投资建议",
  } }));

  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
  await page.getByRole("button", { name: "生成研究报告" }).click();

  await expect(page.locator("#agent-output")).toContainText("模型叙述已生成（部分字段回退）");
  await expect(page.locator("#agent-output")).toContainText("模型基本面叙述");
  await expect(page.locator("#agent-output")).toContainText("确定性技术参考");
  await expect(page.locator("#agent-output")).toContainText("trace-partial-1");
});

test("overall report is independent and appears before the existing research report", async ({ page }) => {
  await page.route("**/api/agent/overall-report", (route) => route.fulfill({ json: {
    status: "MODEL_ASSISTED",
    report: {
      overallConclusion: "总体判断内容",
      dataQualitySummary: "数据质量良好，仍需关注缺失项",
      companyAndFundamentals: "公司与基本面分析",
      technicalAndCapital: "技术面与资金面分析",
      valuationAndIndustry: "估值与行业分析",
      eventsAndSentiment: "事件与情绪分析",
      bullishEvidence: ["盈利能力保持稳定"], bearishEvidence: ["短期波动仍然存在"],
      riskFactors: ["数据时效性风险"], scenarios: { base: "基准情景" },
      conflictsAndMissingData: ["暂无重大冲突"],
      sourceReferences: [{ section: "quote", provider: "Tencent", fetchedAt: "2026-08-03T02:00:00Z" }],
      modelName: "deepseek-chat", generatedAt: "2026-08-03T02:01:00Z",
      disclaimer: "仅供学习研究，不构成投资建议",
    },
  } }));
  await page.route("**/api/agent/analyze", (route) => route.fulfill({ json: {
    direction: "NEUTRAL", generationMode: "DETERMINISTIC_FALLBACK", executiveSummary: "现有研究报告内容",
    technicalAndFlow: { narrative: "技术与资金" }, fundamentals: { narrative: "基本面" }, valuationAndIndustry: { narrative: "估值" },
    coreDrivers: [], catalysts: [], risks: [], conflicts: [], missingData: [], invalidationConditions: [], sources: [],
    disclaimer: "仅供学习研究，不构成投资建议",
  } }));

  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
  await expect(page.getByRole("button", { name: /生成总体报告/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "生成研究报告" })).toBeVisible();
  const layout = await page.evaluate(() => {
    const controls = document.querySelector(".agent-controls").getBoundingClientRect();
    const overall = document.querySelector("#overall-report-output").getBoundingClientRect();
    const existing = document.querySelector("#agent-output").getBoundingClientRect();
    return { overallBelowControls: overall.top >= controls.bottom, overallLeftOfExisting: overall.right <= existing.left + 1 };
  });
  expect(layout.overallBelowControls).toBe(true);
  expect(layout.overallLeftOfExisting).toBe(true);

  await page.getByRole("button", { name: /生成总体报告/ }).click();
  await expect(page.locator("#overall-report-output")).toContainText("总体判断内容");
  await expect(page.locator("#agent-output")).toContainText("等待生成");
  await page.getByRole("button", { name: "生成研究报告" }).click();
  await expect(page.locator("#agent-output")).toContainText("现有研究报告内容");

  // 重新加载当前股票后，两份报告都回到独立的等待状态。
  await page.getByRole("button", { name: "刷新当前股票" }).click();
  await expect(page.locator("#overall-report-output")).toContainText("等待生成");
  await expect(page.locator("#agent-output")).toContainText("等待生成");
});

test("selected overall report model shows a local configuration error without affecting the existing report", async ({ page }) => {
  await page.route("**/api/agent/overall-report", (route) => route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "所选模型未配置" }) }));
  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
  await page.getByRole("button", { name: /生成总体报告/ }).click();
  await expect(page.locator("#overall-report-output")).toContainText("所选模型未配置");
  await expect(page.locator("#agent-output")).toContainText("等待生成");
});

test("overall report remains visible with validation warnings", async ({ page }) => {
  await page.route("**/api/agent/overall-report", (route) => route.fulfill({ json: {
    status: "MODEL_ASSISTED",
    message: "总体报告已生成，存在校验警告",
    report: {
      overallConclusion: "模型原始总体结论",
      dataQualitySummary: "数据质量说明",
      companyAndFundamentals: "公司与基本面",
      technicalAndCapital: "技术与资金",
      valuationAndIndustry: "估值与行业",
      eventsAndSentiment: "事件与情绪",
      bullishEvidence: [], bearishEvidence: [], riskFactors: [], scenarios: {},
      conflictsAndMissingData: [], sourceReferences: [], modelName: "deepseek-chat",
      snapshotAt: "2026-08-03T02:00:00Z", disclaimer: "模型自定义说明",
    },
    diagnostic: {
      failureStage: "VALIDATION", errorCode: "MODEL_NARRATIVE_VALIDATION_WARNING",
      exceptionType: "ReportValidationWarning", message: "模型叙述存在校验警告",
      validationIssues: ["UNSUPPORTED_NUMBER", "UNKNOWN_SOURCE_REFERENCE"], modelName: "deepseek-chat",
      durationMs: 120, occurredAt: "2026-08-03T02:00:00Z", traceId: "overall-trace-test-1",
    },
  } }));

  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
  await page.getByRole("button", { name: /生成总体报告/ }).click();

  await expect(page.locator("#overall-report-output")).toContainText("模型原始总体结论");
  await page.locator("#overall-report-output details.model-diagnostic").click();
  await expect(page.locator("#overall-report-output")).toContainText("MODEL_NARRATIVE_VALIDATION_WARNING");
  await expect(page.locator("#overall-report-output")).toContainText("UNSUPPORTED_NUMBER");
  await expect(page.locator("#overall-report-output")).toContainText("模型自定义说明");
});
