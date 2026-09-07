/*
 * 财报分析视图:评分卡、信号清单、趋势图与 DeepSeek 叙事。
 * 所有状态如实渲染:数据不足 / 确定性回退 / 模型诊断不渲染为 0 或空白。
 */

function escapeText(value) {
  const raw = String(value ?? "");
  return raw.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#039;");
}

const SIGNAL_STATUS = {
  PASS: { label: "通过", tone: "ok" },
  FAIL: { label: "未通过", tone: "bad" },
  UNVERIFIED: { label: "无法评估", tone: "muted" },
};

const MODE_META = {
  MODEL_ASSISTED: { status: "HEALTHY", label: "DeepSeek 叙事已校验" },
  DETERMINISTIC_FALLBACK: { status: "DEGRADED", label: "确定性规则回退" },
};

export function renderFinancialViewShell() {
  return `<section class="financial-view" aria-label="财报分析">
    <div class="financial-toolbar">
      <button id="run-financial-report" class="primary-command" type="button"
              aria-label="生成财报分析" title="生成财报分析">
        <span>生成分析</span><i data-lucide="sparkles" aria-hidden="true"></i>
      </button>
      <div id="financial-request-status" aria-live="polite"></div>
    </div>
    <div id="financial-output"><p class="muted">基于新浪财报三表历史计算财务质量评分(F-Score)与多期趋势,由 DeepSeek 生成解读。</p></div>
  </section>`;
}

function renderScoreCard(report) {
  const score = report.qualityScore || {};
  if (report.insufficientData === true || score.sufficientData === false) {
    return `<div class="financial-score-card" data-tone="muted">
      <strong class="financial-score">--</strong>
      <div><b>数据不足</b><span>报告期少于 4 期,无法计算 F-Score</span></div>
    </div>`;
  }
  return `<div class="financial-score-card">
    <strong class="financial-score">${escapeText(score.total)}</strong>
    <div><b>F-Score · ${escapeText(score.tier)}</b><span>0-2 弱 / 3-5 中 / 6-7 良 / 8-9 优 · 评估 ${escapeText(score.evaluatedSignals)} 个信号</span></div>
  </div>`;
}

function renderSignals(report) {
  const signals = Array.isArray(report.qualityScore?.signals) ? report.qualityScore.signals : [];
  if (!signals.length) return '<section class="financial-signals"><h4>财务质量信号</h4><p class="muted">数据不足,暂无信号明细。</p></section>';
  const rows = signals.map((signal) => {
    const meta = SIGNAL_STATUS[signal.status] || SIGNAL_STATUS.UNVERIFIED;
    return `<li data-status="${escapeText(signal.status)}">
      <span class="signal-dot" data-tone="${escapeText(meta.tone)}"></span>
      <strong>${escapeText(signal.number)} ${escapeText(signal.name)}</strong>
      <span class="signal-label" data-tone="${escapeText(meta.tone)}">${escapeText(meta.label)}</span>
      <small>${escapeText(signal.evidence || "")}</small>
    </li>`;
  }).join("");
  return `<section class="financial-signals"><h4>财务质量信号</h4><ul>${rows}</ul></section>`;
}

function renderNarrative(report) {
  const narrative = report.narrative || {};
  const items = [
    ["总体结论", narrative.tierInterpretation || "UNAVAILABLE：暂无总体结论。"],
    ["为什么得出这个结论", narrative.signalCommentary || "UNAVAILABLE：暂无归因。"],
    ["经营变化怎么看", narrative.trendCommentary || "UNAVAILABLE：暂无趋势解读。"],
    ["需要留意什么", narrative.riskNotes || "UNAVAILABLE：暂无风险说明。"],
  ];
  return `<section class="financial-narrative"><h4>分析结论</h4>
    <div class="financial-narrative-list">${items.map(([label, text]) => `<div class="financial-narrative-item">
      <strong>${escapeText(label)}</strong><p>${escapeText(text)}</p>
    </div>`).join("")}</div>
  </section>`;
}

function renderAnalysisOverview(report) {
  const score = report.qualityScore || {};
  const signals = Array.isArray(score.signals) ? score.signals : [];
  const pass = signals.filter((signal) => signal.status === "PASS");
  const fail = signals.filter((signal) => signal.status === "FAIL");
  const unverified = signals.filter((signal) => signal.status === "UNVERIFIED");
  const focus = fail.length ? `重点关注：${fail.map((signal) => signal.name).join("、")}`
    : "当前没有未通过的财务质量信号";
  return `<section class="financial-analysis-overview" aria-label="财务分析摘要">
    <div class="financial-overview-conclusion">
      <span>总体判断</span><strong>财务质量${escapeText(score.tier || "数据不足")}</strong>
      <p>${escapeText(focus)}</p>
    </div>
    <div class="financial-overview-counts" aria-label="信号统计">
      <span><strong>${pass.length}</strong> 项通过</span>
      <span><strong>${fail.length}</strong> 项需关注</span>
      ${unverified.length ? `<span><strong>${unverified.length}</strong> 项暂无法判断</span>` : ""}
    </div>
  </section>`;
}

function latestComparableValue(series) {
  const values = Array.isArray(series?.yoyGrowthPercent) ? series.yoyGrowthPercent : [];
  for (let index = values.length - 1; index >= 0; index -= 1) {
    if (values[index] != null && Number.isFinite(Number(values[index]))) return Number(values[index]);
  }
  return null;
}

function renderTrendSnapshot(report) {
  const seriesList = Array.isArray(report.trends?.series) ? report.trends.series : [];
  const preferred = ["营业总收入", "归母净利润", "经营现金流", "资产负债率"];
  const directionLabel = { RISING: "上升", FALLING: "下降", MIXED: "波动", INSUFFICIENT: "样本不足" };
  const items = preferred.map((name) => seriesList.find((series) => series.name === name)).filter(Boolean);
  if (!items.length) return "";
  return `<section class="financial-trend-snapshot" aria-label="关键趋势摘要">
    <div class="financial-section-heading"><h4>关键变化</h4><span>最新可比报告期</span></div>
    <div class="financial-trend-snapshot-grid">${items.map((series) => {
      const value = latestComparableValue(series);
      const change = value == null ? "暂无同比" : series.unit === "%"
        ? `同比变动 ${value >= 0 ? "+" : ""}${value.toFixed(1)} 个百分点`
        : `同比 ${value >= 0 ? "+" : ""}${value.toFixed(1)}%`;
      return `<div class="trend-snapshot-item"><span>${escapeText(series.name)}</span>
        <strong>${escapeText(change)}</strong><small>${escapeText(directionLabel[series.direction] || "样本不足")}</small></div>`;
    }).join("")}</div>
  </section>`;
}

function formatFinancialAmount(value) {
  if (value == null || value === "") return "--";
  const amount = Number(value);
  if (!Number.isFinite(amount)) return "--";
  const absolute = Math.abs(amount);
  if (absolute >= 1e8) return `${(amount / 1e8).toFixed(1)} 亿`;
  if (absolute >= 1e4) return `${(amount / 1e4).toFixed(1)} 万`;
  return amount.toLocaleString("zh-CN");
}

function renderLatestPeriod(report) {
  const section = report.latestPeriod || {};
  const latest = section.payload;
  if (!latest) {
    return `<section class="financial-latest-period"><h4>最新一期财报</h4>
      <p class="muted">UNAVAILABLE：最新一期财报明细不可用。</p></section>`;
  }
  const provenance = section.provenance || {};
  const fields = [
    ["营业总收入", latest.operatingRevenue, "累计"],
    ["营业总成本", latest.operatingCost, "累计"],
    ["净利润", latest.netProfit, "累计"],
    ["归母净利润", latest.netProfitAttributable, "累计"],
    ["经营现金流", latest.operatingCashFlow, "累计"],
    ["总资产", latest.totalAssets, "期末"],
    ["总负债", latest.totalLiabilities, "期末"],
    ["流动资产", latest.currentAssets, "期末"],
    ["流动负债", latest.currentLiabilities, "期末"],
    ["归母权益", latest.equityAttributable, "期末"],
    ["总股本", latest.shareCapital, "期末"],
  ];
  const metrics = fields.map(([label, value, caliber]) => `<div class="financial-period-metric">
    <span>${escapeText(label)} <small>${escapeText(caliber)}</small></span>
    <strong>${escapeText(formatFinancialAmount(value))}</strong>
  </div>`).join("");
  const sourceLabel = provenance.provider || "来源未知";
  return `<section class="financial-latest-period">
    <div class="financial-period-heading">
      <div><h4>最新一期财报</h4><strong>${escapeText(latest.reportPeriod || "--")}</strong></div>
      <span class="source-status" data-status="${escapeText(section.status || "UNVERIFIED")}"><span></span>${escapeText(sourceLabel)}</span>
    </div>
    <div class="financial-period-grid">${metrics}</div>
    <p class="muted financial-note">利润表与现金流量表为年初至今累计口径；资产负债表为报告期末时点值。缺失字段显示为“--”。</p>
  </section>`;
}

function renderDiagnostic(diagnostic) {
  if (!diagnostic || !Object.keys(diagnostic).length) return "";
  const issues = Array.isArray(diagnostic.validationIssues) && diagnostic.validationIssues.length
    ? `<dt>校验问题</dt><dd>${escapeText(diagnostic.validationIssues.join(", "))}</dd>` : "";
  return `<details class="model-diagnostic"><summary>模型诊断 · ${escapeText(diagnostic.errorCode || "MODEL_FAILURE")}</summary>
    <dl><dt>阶段</dt><dd>${escapeText(diagnostic.failureStage || "--")}</dd>
    <dt>原因</dt><dd>${escapeText(diagnostic.message || "--")}</dd>
    ${issues}
    <dt>模型</dt><dd>${escapeText(diagnostic.modelName || "--")}</dd>
    <dt>追踪 ID</dt><dd>${escapeText(diagnostic.traceId || "--")}</dd></dl></details>`;
}

export function renderFinancialReport(report) {
  if (!report) return '<p class="muted">暂无财报分析结果。</p>';
  const modeMeta = MODE_META[report.generationMode] || { status: "DEGRADED", label: "报告状态未知" };
  const industryNote = report.financialIndustry
    ? '<p class="muted financial-note">金融行业:毛利率与资产周转率信号不适用传统口径。</p>' : "";
  return `<article class="financial-report">
    <header class="financial-report-header">
      <div>
        <span class="source-status" data-status="${escapeText(modeMeta.status)}"><span></span>${escapeText(modeMeta.label)}</span>
        <h3>${escapeText(report.securityCode || "")} 财报分析</h3>
      </div>
      <dl>
        <dt>报告期</dt><dd>${escapeText(report.reportPeriodRange || "--")}</dd>
        <dt>期数</dt><dd>${escapeText(report.periodCount == null ? "--" : `${report.periodCount} 期`)}</dd>
        <dt>生成时间</dt><dd>${escapeText(report.generatedAt || "--")}</dd>
        <dt>规则版本</dt><dd>${escapeText(report.ruleVersion || "--")}</dd>
      </dl>
    </header>
    ${renderLatestPeriod(report)}
    ${renderAnalysisOverview(report)}
    ${renderTrendSnapshot(report)}
    <div class="financial-grid">
      ${renderScoreCard(report)}
      <div id="financial-trend-chart" class="financial-chart" aria-label="多期财务趋势图"></div>
    </div>
    ${renderSignals(report)}
    ${industryNote}
    ${renderNarrative(report)}
    ${renderDiagnostic(report.diagnostic)}
    <small class="report-disclaimer">${escapeText(report.disclaimer || "仅供学习研究，不构成投资建议")}</small>
  </article>`;
}

export function activateFinancialChart(report, container) {
  if (!container || !report?.trends?.series?.length || !window.echarts) return null;
  const seriesList = report.trends.series;
  const periodKeys = [...new Set(seriesList.flatMap((series) => (series.points || []).map((point) => point.period)))].sort();
  const valueOf = (series, period) => {
    const point = (series.points || []).find((item) => item.period === period);
    return point && point.value != null ? Number(point.value) : null;
  };
  const chart = window.echarts.init(container);
  const bars = seriesList.filter((series) => series.unit === "元").slice(0, 2);
  const lines = seriesList.filter((series) => series.unit === "%").slice(0, 3);
  const formatYuan = (value) => (value == null ? "--" : value >= 1e8 ? `${(value / 1e8).toFixed(1)} 亿` : `${(value / 1e4).toFixed(1)} 万`);
  chart.setOption({
    tooltip: { trigger: "axis" },
    legend: { top: 0 },
    grid: { left: 60, right: 60, top: 32, bottom: 28 },
    xAxis: { type: "category", data: periodKeys.map((period) => period.slice(0, 7)), axisLabel: { color: "#807d72" } },
    yAxis: [
      { type: "value", name: "金额(累计)", axisLabel: { color: "#807d72", formatter: formatYuan }, splitLine: { lineStyle: { color: "#e6e5e0" } } },
      { type: "value", name: "比率 %", axisLabel: { color: "#807d72" }, splitLine: { show: false } },
    ],
    series: [
      ...bars.map((series, index) => ({
        name: series.name, type: "bar", yAxisIndex: 0,
        itemStyle: { color: index === 0 ? "#7158d9" : "#cfcdc4" },
        data: periodKeys.map((period) => valueOf(series, period)),
      })),
      ...lines.map((series, index) => ({
        name: series.name, type: "line", yAxisIndex: 1, smooth: true,
        lineStyle: { color: index === 0 ? "#d83b53" : index === 1 ? "#1f8a65" : "#807d72" },
        itemStyle: { color: index === 0 ? "#d83b53" : index === 1 ? "#1f8a65" : "#807d72" },
        data: periodKeys.map((period) => valueOf(series, period)),
      })),
    ],
  });
  return { dispose: () => chart.dispose() };
}
