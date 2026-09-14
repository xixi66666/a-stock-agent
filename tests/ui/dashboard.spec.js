/*
 * FinRobot 投研工作台 UI 测试。
 *
 * 测试统一拦截 HTTP 请求，使用离线快照和模型响应 Fixture 验证页面行为：模型选择是否
 * 传给 FinRobot endpoint、旧请求是否会被丢弃、单一投研报告是否保留证据与缺失数据。
 * 这里不访问真实行情 Provider 或大模型 API。
 */
const { test, expect } = require("@playwright/test");
const baseSnapshot = require("./fixtures/partial-snapshot.json");
const aiModels = require("./fixtures/ai-models.json");

async function selectModel(page, modelId) {
  await page.locator("#model-picker-toggle").click();
  await page.locator(`.model-picker-option[data-model-id="${modelId}"]`).click();
  await expect(page.locator("#model-picker-label")).toContainText(modelId);
}

// 旧流程仍可通过迁移配置选择；此文件保留原有兼容性测试，新引擎另有端到端测试。
test.beforeEach(async ({ page }) => {
  await page.route('**/api/finrobot/runtime', route => route.fulfill({ json: { engine: 'legacy' } }));
});

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

function industryValuationSection() {
  return structuredClone(snapshot().industryValuation);
}

function fundFlowSummarySection() {
  return structuredClone(snapshot().fundFlowSummary);
}

function candlestickSection(timeframe = "DAILY") {
  return {
    status: "HEALTHY",
    provenance: { provider: "Tencent", sourceUrl: "https://web.ifzq.gtimg.cn", fetchedAt: "2026-08-25T07:10:00Z", cached: false },
    issues: [],
    payload: {
      timeframe, asOf: "2026-08-25", analyzedBars: 260,
      previousSession: {
        bar: { date: "2026-09-07", open: 84, high: 91, low: 83.8, close: 84.6, volumeShares: 1200000 },
        candleType: "阳线", shape: "长上影线", bodyLength: 0.6, upperShadow: 6.4, lowerShadow: 0.2,
        bodyPercent: 8.33, upperShadowPercent: 88.89, lowerShadowPercent: 2.78,
        changePercent: -4.94, volumeRatio: 1.24,
        interpretation: "收盘未能保持日内高位；此前 5 根日线收盘趋势向下。此前 20 根日线前高 112.20 元，当日最高 91.00 元、收盘 84.60 元：收盘跌破此前区间低点 88.20 元，区间支撑失守，当前结构偏弱，上方压力尚未消化。成交量为此前 20 日均量的 1.24 倍，达到放量阈值（1.20 倍），但放量本身不代表压力已消化。当日上影压力仍未获后续收盘突破确认，截至 2026-09-07 尚无后续完整日线，不能判定未来能否消化。",
        bookExcerpts: [{ bookTitle: "日本蜡烛图技术", author: "史蒂夫·尼森", chapter: "第五章 星线",
          text: "同一种形状的蜡烛线既可以是看涨的，也可以是看跌的，取决于在其出现之前的趋势方向",
          sourceLocator: "content/chapters/011-section-011.md",
          scope: "节选自倒锤子线与流星线的比较；长上影轮廓本身不等于这两种命名形态。" }],
        trendEvidence: "此前 5 根日线收盘趋势向下",
        locationEvidence: "此前 20 根日线区间 88.20—112.20 元；收盘位于区间下方",
        followUp: "截至 2026-09-07 尚无后续完整日线，跨日确认状态为未确认。价格边界判据：后续完整日线收盘严格高于 91.00 元记为向上突破；收盘严格低于 83.80 元记为向下破位；收盘处于两者之间或等于边界，记为未突破。该判据不等同于趋势反转。",
        dateNote: "取上海日期 2026-09-08 之前数据中最近一根日线；复盘截至 2026-09-07，不含之后行情。",
        signals: [],
      },
      completion: { latestPeriodComplete: true, excludedDate: null, note: "分析序列中的最后一根 K 线已完成" },
      trend: { shortTerm: "DOWN", primary: "UP", shortReturnPercent: -2.34, closeVsSma20Percent: -1.12, evidence: "短期按 5 期、主要趋势按 20 期收盘变化判定" },
      signals: [{
        id: "EVENING_STAR", name: "黄昏星形态", englishName: "Evening star", family: "星线反转", direction: "BEARISH",
        startDate: "2026-08-23", endDate: "2026-08-25", evidenceScore: 92, evidenceGrade: "VERY_STRONG",
        confirmationStatus: "CONFIRMED", idealGeometry: true,
        constructionEvidence: ["第一根为上升趋势中的长白实体", "第二根小实体向上跳空", "第三根收市价跌破第一根实体中点"],
        trendEvidence: "形态出现于短期上涨趋势之后", locationEvidence: "三根线在上涨段高位完成",
        confirmationEvidence: "第三根蜡烛线收市后形态完成，并构成方向确认", invalidationPrice: 108,
        invalidationRule: "收市价有效升破形态最高点，则看跌警告失效", sourceChapter: "第五章 星线",
      }],
      confluence: { direction: "BEARISH", score: 85, grade: "STRONG", conclusion: "共 4/5 类证据同向；分数不是成功概率", factors: [
        { kind: "CANDLESTICK", label: "黄昏星形态", direction: "BEARISH", aligned: true, weight: 40, evidence: "三根线结构完成" },
        { kind: "TREND", label: "短期趋势", direction: "BEARISH", aligned: true, weight: 15, evidence: "最近 5 期转弱" },
        { kind: "MOMENTUM", label: "RSI 14", direction: "BEARISH", aligned: true, weight: 15, evidence: "RSI=72.40" },
        { kind: "VOLUME", label: "20 期量比", direction: "BEARISH", aligned: true, weight: 15, evidence: "量比=1.42" },
        { kind: "LEVEL", label: "形态阻挡", direction: "BEARISH", aligned: false, weight: 15, evidence: "等待复测" },
      ] },
      risk: { direction: "BEARISH", entryReference: 101.5, invalidationPrice: 108, riskPerShare: 6.5, targetReference: 90, rewardRiskRatio: 1.77, quality: "MARGINAL", notes: ["目标来自最近 20 期结构支撑，不是蜡烛图价格目标"] },
      levels: [{ role: "RESISTANCE", lower: 108, upper: 108, origin: "黄昏星形态高点", status: "ACTIVE", validationRule: "以收市价是否升破判断有效性" }],
      methodology: { ruleVersion: "NISON-CANDLESTICK-1.0", analysisSequence: ["前置趋势", "形态构成", "相对位置", "后续确认", "支撑阻挡与失效", "风险报偿", "其他技术信号"], bookReferences: [{ chapter: "第五章 星线", topic: "星线确认" }], transparentThresholds: { "十字线": "实体不超过全幅 5%" }, scoreMeaning: "0—100 分表示规则证据覆盖度，不是方向发生概率或历史胜率" },
      limitations: ["反转形态表示原趋势可能变化的警告，不保证立即形成反向趋势", "本分析仅用于研究，不构成个性化投资建议"],
    },
  };
}

async function mockApis(page) {
  // 每个测试从同一份快照开始，单个测试只覆盖它关心的响应或请求路由。
  await page.route("**/api/finrobot/status", (route) => route.fulfill({ json: { enabled: false, status: "DISABLED_CONFIGURATION_MISSING" } }));
  await page.route("**/api/ai/models", (route) => route.fulfill({ json: aiModels }));
  await page.route("**/api/stocks/600519/snapshot", (route) => route.fulfill({ json: snapshot() }));
  await page.route("**/api/stocks/600519/candlestick**", (route) => {
    const timeframe = new URL(route.request().url()).searchParams.get("timeframe") || "DAILY";
    return route.fulfill({ json: candlestickSection(timeframe) });
  });
  await page.route("**/api/stocks/search**", (route) => route.fulfill({ json: [{ code: "600519", name: "贵州茅台", exchange: "SHANGHAI" }] }));
}

test("candlestick methodology shows the cited method note without a query entry", async ({ page }) => {
  await page.route("**/api/stocks/600519/candlestick**", route => {
    const section = candlestickSection();
    section.payload.methodology.knowledge = [{ id: "nison-reversal", title: "反转警告与前置趋势", chapter: "第四章 反转形态", summary: "形态提示趋势变化" }];
    return route.fulfill({ json: section });
  });
  await page.goto("/workbench.html");
  await page.getByRole("button", { name: /贵州茅台/ }).click();
  await page.getByText("方法、章节与限制", { exact: true }).click();
  await expect(page.getByText("反转警告与前置趋势", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "反转警告与前置趋势" })).toHaveCount(0);
});

test.beforeEach(async ({ page }) => {
  await mockApis(page);
});

test("FinRobot replaces the former Agent analysis entry with an equity research workspace", async ({ page }) => {
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();

  await expect(page.getByRole("tab", { name: "FinRobot 投研" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "Agent 分析" })).toHaveCount(0);

  await page.getByRole("tab", { name: "FinRobot 投研" }).click();
  await expect(page.locator("#view-content")).toContainText("FinRobot 投研");
  await expect(page.locator("#view-content")).toContainText("多角色研究流程");
  await expect(page.locator("#view-content")).toContainText("证据快照");
  await expect(page.locator("#view-content")).toContainText("估值建模");
  await expect(page.locator("#view-content")).toContainText("风险复核");
  await expect(page.getByRole("button", { name: "运行 FinRobot 投研" })).toBeVisible();
});

test("candlestick distinguishes historical pattern date from current analysis cutoff", async ({ page }) => {
  await page.route("**/api/stocks/600519/candlestick**", (route) => {
    const section = candlestickSection();
    section.payload.asOf = "2026-09-08";
    section.payload.signals[0].startDate = "2026-09-01";
    section.payload.signals[0].endDate = "2026-09-01";
    return route.fulfill({ json: section });
  });

  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();

  const detail = page.locator(".candlestick-signal-detail");
  await expect(detail).toContainText("形态发生日：2026-09-01—2026-09-01");
  await expect(detail).toContainText("分析截止：2026-09-08");
});

test("candlestick workbench renders evidence confirmation levels and methodology", async ({ page }) => {
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();

  const workbench = page.locator(".candlestick-workbench");
  await expect(workbench).toBeVisible();
  await expect(workbench).toContainText("尼森蜡烛图研判");
  await expect(workbench).toContainText("黄昏星形态");
  await expect(workbench).toContainText("已确认");
  await expect(workbench).toContainText("失效位");
  await expect(workbench).toContainText("风险报偿");
  await expect(workbench).toContainText("第五章 星线");
  await expect(workbench.getByRole("button", { name: "日线" })).toHaveAttribute("aria-pressed", "true");
  await expect(workbench.getByText("85", { exact: true })).toBeVisible();
});

test("previous session is prominent and distinguishes candle color from daily return", async ({ page }) => {
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  const review = page.getByRole("region", { name: "最近已收盘日线分析" });
  await expect(review).toBeVisible();
  await expect(review).toContainText("2026-09-07");
  await expect(review).toContainText("阳线");
  await expect(review).toContainText("-4.94%");
  await expect(review).toContainText("未识别到截至该日完成的命名形态");
  await expect(review.getByRole("img", { name: /日线结构/ })).toBeVisible();
  await expect(review).toContainText("88.89%");
  await expect(review).toContainText("区间支撑失守，当前结构偏弱，上方压力尚未消化");
  await expect(review).not.toContainText("结合所处位置观察");
  const positions = await page.evaluate(() => ({
    review: document.querySelector(".previous-session").getBoundingClientRect().top,
    history: document.querySelector(".candlestick-primary-grid").getBoundingClientRect().top,
  }));
  expect(positions.review).toBeLessThan(positions.history);
});

test("previous session shows original book text immediately after system interpretation", async ({ page }) => {
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  const review = page.locator(".previous-session");
  await expect(review.locator(".previous-session-interpretation + .previous-session-excerpts")).toBeVisible();
  await expect(review.locator("blockquote")).toContainText("取决于在其出现之前的趋势方向");
  await expect(review.locator("cite")).toContainText("史蒂夫·尼森《日本蜡烛图技术》 · 第五章 星线");
  await expect(review.locator(".previous-session-excerpts")).toContainText("长上影轮廓本身不等于");
});

test("previous session keeps missing values and zero range honest", async ({ page }) => {
  await page.route("**/api/stocks/600519/candlestick**", route => {
    const section = candlestickSection();
    const review = section.payload.previousSession;
    Object.assign(review.bar, { open: 89, high: 89, low: 89, close: 89 });
    Object.assign(review, { candleType: "开收持平", shape: "无振幅线", bodyPercent: null,
      upperShadowPercent: null, lowerShadowPercent: null, changePercent: null, volumeRatio: null });
    return route.fulfill({ json: section });
  });
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  const review = page.locator(".previous-session");
  await expect(review).toContainText("无振幅线");
  await expect(review).toContainText("不可计算");
  await expect(review).not.toContainText("NaN");
  await expect(review).not.toContainText("Infinity");
});

test("previous session preserves the 600115 upper shadow and body proportions", async ({ page }) => {
  await page.route("**/api/stocks/600519/candlestick**", route => {
    const section = candlestickSection();
    Object.assign(section.payload.previousSession, {
      bar: { date: "2026-09-10", open: 3.48, high: 3.51, low: 3.47, close: 3.47 },
      candleType: "阴线", bodyPercent: 25, upperShadowPercent: 75, lowerShadowPercent: 0,
    });
    return route.fulfill({ json: section });
  });
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  const candle = page.locator(".previous-session-candle");
  await expect(candle).toHaveAttribute("data-tone", "down");
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    await page.setViewportSize(viewport);
    const geometry = await candle.evaluate(svg => {
      const wick = svg.querySelector("line").getBoundingClientRect();
      const body = svg.querySelector("rect").getBoundingClientRect();
      return { upper: (body.top - wick.top) / wick.height,
        body: body.height / wick.height, lower: (wick.bottom - body.bottom) / wick.height };
    });
    expect(geometry.upper).toBeCloseTo(0.75, 5);
    expect(geometry.body).toBeCloseTo(0.25, 5);
    expect(geometry.lower).toBeCloseTo(0, 5);
    await candle.screenshot({ path: `target/ui-screenshots/candle-600115-${viewport.width}.png` });
  }
});

test("candle remains bounded when its stylesheet is unavailable", async ({ page }) => {
  await page.route("**/candlestick.css*", route => route.fulfill({ contentType: "text/css", body: "" }));
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  const candle = page.locator(".previous-session-candle");
  await expect(candle).toBeVisible();
  const bounds = await candle.boundingBox();
  expect(bounds.width).toBeLessThanOrEqual(120);
  expect(bounds.height).toBeLessThanOrEqual(180);
});

test("previous session review follows the selected timeframe", async ({ page }) => {
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();

  const review = page.locator(".previous-session");
  const timeframes = page.locator(".candlestick-timeframes");
  await expect(review).toContainText("最近已收盘日线分析");
  await expect(review).toContainText("成交量 / 前20日均量");
  await expect(review.getByRole("img", { name: /日线结构/ })).toBeVisible();

  await timeframes.getByRole("button", { name: "周线" }).click();
  await expect(review).toContainText("最近已收盘周线分析");
  await expect(review).toContainText("周线复盘");
  await expect(review).toContainText("成交量 / 前20期均量");
  await expect(review.getByRole("img", { name: /周线结构/ })).toBeVisible();
  await expect(review).toContainText("该复盘按所选周期（周线）聚合展示");

  await timeframes.getByRole("button", { name: "月线" }).click();
  await expect(review).toContainText("最近已收盘月线分析");
  await expect(review).toContainText("月线复盘");
  await expect(review.getByRole("img", { name: /月线结构/ })).toBeVisible();
});

function finRobotResponse(overrides = {}) {
  const base = {
    status: "MODEL_ASSISTED",
    message: "FinRobot 投研已生成",
    report: {
      ticker: "600519",
      companyName: "贵州茅台",
      tagline: "高质量白酒企业的证据化研究摘要",
      companyOverview: "公司概览内容",
      investmentOverview: "投资逻辑内容",
      valuationOverview: "估值分析内容",
      risks: "风险评估内容",
      competitorAnalysis: "竞争格局内容",
      majorTakeaways: "主要结论内容",
      newsSummary: "事件与新闻内容",
      dataQualitySummary: "数据质量摘要",
      technicalAndCapital: "技术与资金内容",
      bullishEvidence: ["盈利能力保持稳定"],
      bearishEvidence: ["短期波动仍然存在"],
      riskFactors: ["数据时效性风险"],
      scenarios: { stronger: "偏强情景", neutral: "中性情景", weaker: "偏弱情景" },
      conflictsAndMissingData: ["暂无重大冲突"],
      sourceReferences: [{ section: "quote", provider: "Tencent", fetchedAt: "2026-08-03T02:00:00Z" }],
      industryValuation: industryValuationSection(),
      fundFlowSummary: fundFlowSummarySection(),
      modelName: "deepseek-chat",
      snapshotAt: "2026-08-03T02:00:00Z",
      generatedAt: "2026-08-03T02:01:00Z",
      generationMode: "MODEL_ASSISTED",
      pipelineVersion: "finrobot-equity-v1",
      disclaimer: "仅供学习研究，不构成投资建议",
    },
  };
  return {
    ...base,
    ...overrides,
    report: { ...base.report, ...(overrides.report || {}) },
  };
}

test("FinRobot model can be selected per research request", async ({ page }) => {
  let requestBody = null;
  await page.route("**/api/finrobot/research", async (route) => {
    requestBody = route.request().postDataJSON();
    await route.fulfill({ json: finRobotResponse({ report: { modelName: "mimo-v2.5-pro" } }) });
  });

  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  await selectModel(page, "mimo");
  await page.getByRole("tab", { name: "FinRobot 投研" }).click();
  await page.getByRole("button", { name: "运行 FinRobot 投研" }).click();

  expect(requestBody).toEqual({ code: "600519", modelId: "mimo" });
  await expect(page.locator("#finrobot-output")).toContainText("mimo-v2.5-pro");
  await expect(page.locator("#finrobot-output")).toContainText("公司概览内容");
  await expect(page.locator("#finrobot-output")).not.toContainText("综合得分");
});

test("model picker defaults to the catalog model and shows connectivity", async ({ page }) => {
  await page.route("**/api/finrobot/research", (route) => route.fulfill({ json: finRobotResponse() }));

  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  await expect(page.locator("#model-picker-label")).toContainText("deepseek · deepseek-chat");
  await expect(page.locator("#model-picker-dot")).toHaveAttribute("data-state", "ok");

  await page.locator("#model-picker-toggle").click();
  await expect(page.locator('.model-picker-option[data-model-id="mimo"] .status-dot'))
    .toHaveAttribute("data-state", "failed");
  await expect(page.locator('.model-picker-option[data-model-id="mimo"] .model-picker-state'))
    .toContainText("不可用");
  await page.keyboard.press("Escape");

  await page.getByRole("tab", { name: "FinRobot 投研" }).click();
  await page.getByRole("button", { name: "运行 FinRobot 投研" }).click();
  const generationLine = page.locator("#finrobot-output .generation-line").first();
  await expect(generationLine).toContainText("生成模式：模型生成");
  await expect(generationLine).toContainText("模型：deepseek-chat");
});

test("stale FinRobot completion does not overwrite the newer request", async ({ page }) => {
  const pending = [];
  await page.route("**/api/finrobot/research", async (route) => {
    let release;
    const gate = new Promise((resolve) => { release = resolve; });
    const requestNumber = pending.length + 1;
    pending.push({ release });
    await gate;
    await route.fulfill({ json: finRobotResponse({ report: { tagline: `第 ${requestNumber} 次 FinRobot 结论` } }) });
  });

  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "FinRobot 投研" }).click();
  const button = page.getByRole("button", { name: "运行 FinRobot 投研" });
  await button.click();
  await expect.poll(() => pending.length).toBe(1);
  await page.getByRole("button", { name: "刷新当前股票" }).click();
  await button.click();
  await expect.poll(() => pending.length).toBe(2);
  await expect(button).toBeDisabled();

  pending[0].release();
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await expect(button).toBeDisabled();
  await expect(page.locator("#finrobot-output")).not.toContainText("第 1 次 FinRobot 结论");

  pending[1].release();
  await expect(page.locator("#finrobot-output")).toContainText("第 2 次 FinRobot 结论");
  await expect(button).toBeEnabled();
});

test("FinRobot result survives view remounting and surfaces a model response failure", async ({ page }) => {
  let requestCount = 0;
  await page.route("**/api/finrobot/research", async (route) => {
    requestCount += 1;
    if (requestCount === 1) {
      await route.fulfill({ json: finRobotResponse({ report: { majorTakeaways: "第一份 FinRobot 结论" } }) });
      return;
    }
    await route.fulfill({ json: { status: "MODEL_FAILED", message: "所选模型生成失败", diagnostic: { errorCode: "MODEL_TIMEOUT", message: "模型响应超时" } } });
  });

  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  const finRobotTab = page.getByRole("tab", { name: "FinRobot 投研" });
  await finRobotTab.click();
  await page.getByRole("button", { name: "运行 FinRobot 投研" }).click();
  await expect(page.locator("#finrobot-output")).toContainText("第一份 FinRobot 结论");

  await page.getByRole("tab", { name: "技术分析" }).click();
  await finRobotTab.click();
  await expect(page.locator("#finrobot-output")).toContainText("第一份 FinRobot 结论");

  await selectModel(page, "mimo");
  await page.getByRole("button", { name: "运行 FinRobot 投研" }).click();
  await expect(page.locator("#finrobot-output")).toContainText("所选模型生成失败");
});

test("FinRobot renders the official equity research sections and diagnostics", async ({ page }) => {
  await page.route("**/api/finrobot/research", (route) => route.fulfill({ json: finRobotResponse({
    message: "FinRobot 投研已生成，存在校验提示",
    report: { companyOverview: "模型公司概览", investmentOverview: "模型投资逻辑" },
    diagnostic: {
      failureStage: "VALIDATION", errorCode: "MODEL_NARRATIVE_VALIDATION_WARNING",
      exceptionType: "ReportValidationWarning", message: "模型叙述存在校验警告",
      validationIssues: ["UNSUPPORTED_NUMBER"], modelName: "deepseek-chat",
      durationMs: 120, occurredAt: "2026-08-03T02:00:00Z", traceId: "finrobot-trace-1",
    },
  }) }));

  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "FinRobot 投研" }).click();
  await page.getByRole("button", { name: "运行 FinRobot 投研" }).click();

  const output = page.locator("#finrobot-output");
  await expect(output).toContainText("模型公司概览");
  await expect(output).toContainText("模型投资逻辑");
  await expect(output).toContainText("同行估值对比");
  await expect(output).toContainText("五粮液");
  await expect(output).toContainText("资金流窗口汇总");
  await expect(output).toContainText("近 20 日");
  await expect(output).toContainText("仅供学习研究，不构成投资建议");
  await output.locator("details.model-diagnostic summary").click();
  await expect(output).toContainText("MODEL_NARRATIVE_VALIDATION_WARNING");
  await expect(output).toContainText("finrobot-trace-1");
});

test("FinRobot keeps deterministic research available without a configured model", async ({ page }) => {
  await page.route("**/api/finrobot/research", (route) => route.fulfill({ json: finRobotResponse({
    status: "MODEL_NOT_CONFIGURED",
    message: "没有配置可用的 FinRobot 模型，已返回确定性研究结果",
    report: { modelName: null, generationMode: "DETERMINISTIC_FALLBACK", majorTakeaways: "确定性研究结果" },
  }) }));
  await page.unroute("**/api/ai/models");
  await page.route("**/api/ai/models", (route) => route.fulfill({ json: { models: [] } }));

  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "FinRobot 投研" }).click();
  await expect(page.locator("#model-picker-label")).toContainText("未配置");
  await expect(page.getByRole("button", { name: "运行 FinRobot 投研" })).toBeEnabled();
  await page.getByRole("button", { name: "运行 FinRobot 投研" }).click();
  await expect(page.locator("#finrobot-output")).toContainText("确定性研究回退");
  await expect(page.locator("#finrobot-output")).toContainText("确定性研究结果");
});

test("landing opens the research layout only after choosing a stock", async ({ page }) => {
  await page.goto("/workbench.html");
  await expect(page.locator(".security-overview")).toBeHidden();
  await expect(page.getByRole("tablist", { name: "研究维度" })).toBeHidden();
  await expect(page.getByRole("heading", { name: "选择一只股票开始研究" })).toBeVisible();
  await page.locator('[data-symbol="600519"]').click();
  await expect(page.locator(".security-overview")).toBeVisible();
  await expect(page.getByRole("tablist", { name: "研究维度" })).toBeVisible();
  await expect(page.locator("#security-name")).toHaveText("贵州茅台");
  await expect(page.locator(".indicator-card")).toHaveCount(38);
});

test("technical chart follows the interface accent when switching indicators", async ({ page }) => {
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  await page.locator('[data-indicator-id="RSI_6"]').click();
  await expect(page.locator("#detail-name")).toHaveText("RSI 6");
  const colors = await page.evaluate(() => ({
    accent: getComputedStyle(document.documentElement).getPropertyValue("--accent").trim(),
    chart: window.echarts.getInstanceByDom(document.querySelector("#technical-chart")).getOption().series[0].lineStyle.color,
  }));
  expect(colors.chart).toBe(colors.accent);
});

for (const viewport of [
  { width: 1440, height: 1000, columns: 4 },
  { width: 1024, height: 768, columns: 3 },
  { width: 768, height: 1024, columns: 2 },
  { width: 390, height: 844, columns: 1 },
]) {
  test(`technical workbench is stable at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto("/workbench.html");
    await page.locator('[data-symbol="600519"]').click();
    await expect(page.locator(".indicator-card")).toHaveCount(38);
    await expect(page.locator(".previous-session")).toBeVisible();
    await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(247, 247, 244)');
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
    const reviewOverflow = await page.locator(".previous-session").evaluate(element =>
      [...element.querySelectorAll("p, dd, figcaption, time")].some(child => child.scrollWidth > child.clientWidth + 1));
    expect(reviewOverflow).toBe(false);
    // 元素截图会自动滚动；隐藏吸顶栏仅用于拍摄，避免它覆盖超长手机模块的截图。
    await page.locator(".previous-session").screenshot({ path: `target/ui-screenshots/previous-session-${viewport.width}x${viewport.height}.png`,
      style: ".app-header { visibility: hidden !important; }" });
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: `target/ui-screenshots/dashboard-${viewport.width}x${viewport.height}.png`, fullPage: true });

    await page.getByRole("tab", { name: "FinRobot 投研" }).click();
    await expect(page.locator("#model-picker-toggle")).toBeVisible();
    const agentLayout = await page.evaluate(() => {
      const button = document.querySelector("#run-finrobot");
      const picker = document.querySelector("#model-picker-toggle");
      return {
        scrollWidth: document.documentElement.scrollWidth,
        viewportWidth: document.documentElement.clientWidth,
        buttonFits: button.scrollWidth <= button.clientWidth + 1 && button.scrollHeight <= button.clientHeight + 1,
        pickerFits: picker.scrollWidth <= picker.clientWidth + 1,
      };
    });
    expect(agentLayout.scrollWidth).toBeLessThanOrEqual(agentLayout.viewportWidth);
    expect(agentLayout.buttonFits).toBe(true);
    expect(agentLayout.pickerFits).toBe(true);
    await page.screenshot({ path: `target/ui-screenshots/finrobot-${viewport.width}x${viewport.height}.png`, fullPage: true });
  });
}

test("every research tab renders an owned state without raw JSON", async ({ page }) => {
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  for (const tab of ["资金筹码", "基本面", "估值预期", "事件资讯", "数据来源", "FinRobot 投研"]) {
    await page.getByRole("tab", { name: tab }).click();
    await expect(page.locator("#view-content")).toBeVisible();
    await expect(page.locator("#view-content pre")).toHaveCount(0);
  }
});

test("source quality uses the backend scoring weights", async ({ page }) => {
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "数据来源" }).click();
  await expect(page.locator(".quality-components")).toContainText("30 / 30");
  await expect(page.locator(".quality-components")).toContainText("17 / 25");
  await expect(page.locator(".quality-components")).toContainText("15 / 15");
});

test("valuation view shows target and deterministic peer groups", async ({ page }) => {
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "估值预期" }).click();

  const table = page.getByRole("table", { name: "同行估值对比" });
  await expect(table).toContainText("目标");
  await expect(table).toContainText("市值接近");
  await expect(table).toContainText("行业龙头");
  await expect(table).toContainText("000858");
  await expect(table).toContainText("五粮液");
});

test("capital view shows latest and multi-window order-size flows", async ({ page }) => {
  await page.goto("/workbench.html");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "资金筹码" }).click();

  await expect(page.locator("#view-content")).toContainText("最新日资金流");
  await expect(page.locator("#view-content")).toContainText("中单净流入");
  await expect(page.locator("#view-content")).toContainText("小单净流入");
  const table = page.getByRole("table", { name: "资金流窗口汇总" });
  await expect(table).toContainText("近 5 日");
  await expect(table).toContainText("近 20 日");
  await expect(table).toContainText("18 / 20 日");
});
