/*
 * 研究工作台的页面状态机。
 *
 * state.snapshot 是当前股票的规范化快照，state.institutionalReport 和
 * state.overallReportResponse 是两条独立的报告结果。所有异步回调都要检查 code 和
 * loadGeneration，防止用户切换股票后旧请求覆盖新页面。
 */
import { isPartialSnapshot, sectionPayload, stockApi } from "./api.js";
import { renderGenericView, renderLoading, renderUnavailable } from "./views.js";
import { activateTechnicalView, renderTechnicalView } from "./technical-view.js";
import { renderFundFlowSummary, renderPeerValuationTable } from "./derived-market-view.js";
import { activateFinancialChart, renderFinancialReport, renderFinancialViewShell } from "./financial-view.js";

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

const state = {
  phase: "idle",
  currentCode: null,
  currentView: "technical",
  snapshot: null,
  searchTimer: null,
  technicalController: null,
  overallReportPhase: "idle",
  overallModelsPhase: "idle",
  overallModels: [],
  selectedOverallModelId: null,
  institutionalReportPhase: "idle",
  institutionalReport: null,
  overallReportResponse: null,
  overallReportFeedback: null,
  overallReportRequestId: 0,
  financialReportPhase: "idle",
  financialReportResult: null,
  financialReportRequestId: 0,
  financialController: null,
  loadGeneration: 0,
};

function refreshIcons() {
  if (window.lucide?.createIcons) window.lucide.createIcons({ attrs: { "stroke-width": 1.8 } });
}

function formatNumber(value, digits = 2) {
  const number = Number(value);
  return Number.isFinite(number) ? number.toLocaleString("zh-CN", { minimumFractionDigits: digits, maximumFractionDigits: digits }) : "--";
}

function formatCompact(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "--";
  if (Math.abs(number) >= 1e8) return `${formatNumber(number / 1e8)} 亿`;
  if (Math.abs(number) >= 1e4) return `${formatNumber(number / 1e4)} 万`;
  return formatNumber(number, 0);
}

function marketName(exchange) {
  return ({ SHANGHAI: "沪市", SHENZHEN: "深市", BEIJING: "北交所" })[exchange] || "A 股";
}

function setPhase(phase, message) {
  // 全局加载状态只控制股票快照；报告按钮有自己的 phase，避免相互禁用。
  state.phase = phase;
  document.body.dataset.phase = phase;
  $("#global-status").textContent = message;
  $("#search-submit").disabled = phase === "loading";
  $("#refresh-data").disabled = !state.currentCode || phase === "loading";
  $("#refresh-data").classList.toggle("is-spinning", phase === "loading");
}

function clearTone(...elements) {
  elements.forEach((element) => element?.classList.remove("is-up", "is-down"));
}

function renderOverview(snapshot) {
  const quote = sectionPayload(snapshot.quote);
  const security = snapshot.security || quote?.security || {};
  $("#security-name").textContent = quote?.name || security.code || "未知股票";
  $("#security-code").textContent = security.code || state.currentCode || "------";
  $("#market-badge").textContent = marketName(security.exchange);
  $("#quote-time").textContent = `数据时间 ${quote?.quotedAt ? new Date(quote.quotedAt).toLocaleString("zh-CN", { hour12: false }) : "--"}`;
  $("#latest-price").textContent = formatNumber(quote?.price);
  const change = Number(quote?.changeAmount);
  const percent = Number(quote?.changePercent);
  $("#price-change").textContent = Number.isFinite(change) && Number.isFinite(percent) ? `${change >= 0 ? "+" : ""}${formatNumber(change)} / ${percent >= 0 ? "+" : ""}${formatNumber(percent)}%` : "-- / --";
  clearTone($("#latest-price"), $("#price-change"));
  if (percent > 0) { $("#latest-price").classList.add("is-up"); $("#price-change").classList.add("is-up"); }
  if (percent < 0) { $("#latest-price").classList.add("is-down"); $("#price-change").classList.add("is-down"); }
  $("#quote-open").textContent = formatNumber(quote?.open);
  $("#quote-high").textContent = formatNumber(quote?.high);
  $("#quote-low").textContent = formatNumber(quote?.low);
  $("#quote-previous").textContent = formatNumber(quote?.previousClose);
  $("#quote-turnover").textContent = quote?.turnoverPercent == null ? "--" : `${formatNumber(quote.turnoverPercent)}%`;
  $("#quote-amount").textContent = formatCompact(quote?.amountYuan);
  $("#quote-pe").textContent = formatNumber(quote?.peTtm);
  $("#quote-pb").textContent = formatNumber(quote?.pb);
  const score = Number(snapshot.quality?.total);
  $("#quality-score").textContent = Number.isFinite(score) ? String(score) : "--";
  $("#quality-label").textContent = !Number.isFinite(score) ? "尚未评分" : score >= 85 ? "可靠" : score >= 65 ? "可用，需复核" : "数据不完整";
}

function renderCurrentView() {
  // 每次切换标签都重新绑定局部交互，并销毁旧图表控制器，避免重复监听和内存泄漏。
  if (!state.snapshot) return;
  const content = $("#view-content");
  content.hidden = false;
  $("#workspace-state").hidden = true;
  state.technicalController?.dispose();
  state.technicalController = null;
  state.financialController?.dispose();
  state.financialController = null;
  if (state.currentView === "financial") {
    content.innerHTML = renderFinancialViewShell();
    bindFinancialAction();
    if (state.financialReportResult) {
      $("#financial-output").innerHTML = renderFinancialReport(state.financialReportResult);
      state.financialController = activateFinancialChart(state.financialReportResult, $("#financial-trend-chart"));
    }
    refreshIcons();
    return;
  }
  if (state.currentView === "technical") {
    content.innerHTML = renderTechnicalView(state.snapshot.technical);
  } else {
    content.innerHTML = renderGenericView(state.currentView, state.snapshot);
  }
  refreshIcons();
  if (state.currentView === "technical") state.technicalController = activateTechnicalView(state.snapshot.technical, content);
  if (state.currentView === "agent") {
    bindOverallModelControls();
    bindOverallReportAction();
    bindAgentAction();
    if (state.overallReportResponse) $("#overall-report-output").innerHTML = renderOverallReportResponse(state.overallReportResponse);
    renderOverallRequestFeedback();
    if (state.institutionalReport) $("#agent-output").innerHTML = isQuantResearchReport(state.institutionalReport)
      ? renderQuantResearchReport(state.institutionalReport)
      : isInstitutionalReport(state.institutionalReport)
        ? renderInstitutionalReport(state.institutionalReport) : renderLegacyReport(state.institutionalReport);
    refreshIcons();
  }
}

function escapeText(value) {
  const raw = String(value ?? "");
  const scheme = raw.match(/^\s*([a-z][a-z0-9+.-]*):/i)?.[1]?.toLowerCase();
  if (scheme && scheme !== "http" && scheme !== "https") return "";
  return raw.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#039;");
}

function renderAgentList(title, items) {
  if (!Array.isArray(items) || !items.length) return `<section class="report-block"><h4>${escapeText(title)}</h4><p class="muted">暂无可用证据</p></section>`;
  return `<section class="report-block"><h4>${escapeText(title)}</h4><ul>${items.map((item) => {
    const text = typeof item === "string" ? item : `${item.title || ""}：${item.interpretation || ""}`;
    return `<li>${escapeText(text)}</li>`;
  }).join("")}</ul></section>`;
}

function renderReportSources(sources) {
  if (!Array.isArray(sources) || !sources.length) return renderAgentList("来源", []);
  return `<section class="report-block report-sources"><h4>来源</h4><ul>${sources.map((source) => `<li><strong>${escapeText(source.section || "数据")}</strong> · ${escapeText(source.provider || "未知来源")} · ${escapeText(source.status || "")}${source.url ? ` <a href="${escapeText(source.url)}" target="_blank" rel="noopener noreferrer">查看</a>` : ""}<small>${escapeText(source.fetchedAt || "")}</small></li>`).join("")}</ul></section>`;
}

function renderReportFacts(facts) {
  if (!Array.isArray(facts) || !facts.length) return '<p class="muted report-facts-empty">暂无可用结构化指标</p>';
  return `<ul class="report-facts">${facts.map((fact) => `<li><span>${escapeText(fact.label || "指标")}</span><strong>${escapeText(fact.value || "--")}</strong></li>`).join("")}</ul>`;
}

const QUANT_METRIC_LABELS = {
  "return-5": "5 日收益", "return-20": "20 日收益", "return-60": "60 日收益",
  "return-120": "120 日收益", "return-250": "250 日收益",
  "annualized-volatility": "年化波动率", "downside-volatility": "下行波动率",
  "max-drawdown": "最大回撤", sharpe: "Sharpe", sortino: "Sortino", calmar: "Calmar",
  "var-95": "历史 VaR 95%", "cvar-95": "历史 CVaR 95%", skewness: "收益偏度", kurtosis: "超额峰度",
};

const QUANT_STATUS_LABELS = {
  AVAILABLE: "可用", INSUFFICIENT_SAMPLE: "样本不足", INVALID_INPUT: "输入无效", UNAVAILABLE: "不可用",
};

function formatQuantValue(value, unit) {
  if (value == null || value === "") return "--";
  const number = Number(value);
  const text = Number.isFinite(number)
    ? number.toLocaleString("zh-CN", { maximumFractionDigits: 4 })
    : escapeText(value);
  return `${text}${unit || ""}`;
}

function renderQuantMetrics(metrics) {
  const rows = Array.isArray(metrics) ? metrics : [];
  return `<section class="quant-section quant-metrics"><h4>个股收益与价格行为</h4>${rows.length
    ? `<div class="quant-metric-grid">${rows.map((metric) => `<article class="quant-metric" data-status="${escapeText(metric.availability || "UNAVAILABLE")}">
        <div><strong>${escapeText(QUANT_METRIC_LABELS[metric.name] || metric.name || "指标")}</strong><span>${escapeText(QUANT_STATUS_LABELS[metric.availability] || metric.availability || "不可用")}</span></div>
        <b>${formatQuantValue(metric.value, metric.unit)}</b>
        <dl><dt>窗口</dt><dd>${escapeText(metric.window || "--")}</dd><dt>截至</dt><dd>${escapeText(metric.asOf || "--")}</dd><dt>方法</dt><dd>${escapeText(metric.method || "--")}</dd></dl>
        ${Array.isArray(metric.limitations) && metric.limitations.length ? `<p>${metric.limitations.map(escapeText).join("；")}</p>` : ""}
      </article>`).join("")}</div>`
    : '<p class="muted">UNAVAILABLE：当前没有可展示的量化指标。</p>'}</section>`;
}

function renderBenchmarkComparisons(comparisons) {
  const rows = Array.isArray(comparisons) ? comparisons : [];
  return `<section class="quant-section"><h4>市场环境与基准表现</h4>${rows.length
    ? `<div class="quant-benchmark-table" role="table" aria-label="基准比较">
        <div class="quant-table-head" role="row"><span>基准</span><span>超额收益</span><span>Beta</span><span>信息比率</span><span>状态</span></div>
        ${rows.map((item) => `<div class="quant-table-row" role="row"><strong>${escapeText(item.benchmarkId || "--")}</strong><span>${formatQuantValue(item.excessReturnPercent, "%")}</span><span>${formatQuantValue(item.beta, "")}</span><span>${formatQuantValue(item.informationRatio, "")}</span><span class="source-status" data-status="${escapeText(item.availability || "UNAVAILABLE")}"><i></i>${escapeText(QUANT_STATUS_LABELS[item.availability] || item.availability || "不可用")}</span></div>`).join("")}
      </div>`
    : '<p class="muted">UNAVAILABLE：基准日线不可用。</p>'}</section>`;
}

function renderQuantTextSection(title, content) {
  return `<section class="quant-section"><h4>${escapeText(title)}</h4><p>${escapeText(content || "UNAVAILABLE：当前分区没有可用证据。")}</p></section>`;
}

function renderQuantStringList(title, values, emptyText) {
  const items = Array.isArray(values) ? values : [];
  return `<section class="quant-section"><h4>${escapeText(title)}</h4>${items.length
    ? `<ul>${items.map((value) => `<li>${escapeText(value)}</li>`).join("")}</ul>`
    : `<p class="muted">${escapeText(emptyText)}</p>`}</section>`;
}

function isQuantResearchReport(report) {
  return Boolean(report?.reportMeta?.reportType === "QUANT_SINGLE_SECURITY" || report?.portfolioScope?.scope === "SINGLE_SECURITY");
}

function renderQuantResearchReport(report) {
  const meta = report.reportMeta || {};
  const portfolio = report.portfolioScope || {};
  const unavailableReasons = Array.isArray(portfolio.unavailableReasons) ? portfolio.unavailableReasons : [];
  return `<article class="quant-report">
    <header class="quant-report-header">
      <div><span class="source-status" data-status="HEALTHY"><span></span>单股量化研究 · ${escapeText(meta.version || "v2")}</span><h3>${escapeText(meta.securityCode || state.currentCode || "--")} 量化研究报告</h3></div>
      <dl><dt>报告类型</dt><dd>${escapeText(meta.reportType || "QUANT_SINGLE_SECURITY")}</dd><dt>生成时间</dt><dd>${escapeText(meta.generatedAt || "--")}</dd></dl>
    </header>
    <p class="quant-executive-summary">${escapeText(report.executiveSummary || "UNAVAILABLE：当前没有可用摘要。")}</p>
    ${renderBenchmarkComparisons(report.benchmarkComparisons)}
    ${renderQuantMetrics(report.metrics)}
    <div class="quant-prose-grid">
      ${renderQuantTextSection("因子文字观察", report.factorObservations)}
      ${renderQuantTextSection("估值与基本面", report.valuationAndFundamentals)}
      ${renderQuantTextSection("资金与事件", report.capitalAndEvents)}
      ${renderQuantTextSection("风险与失效条件", report.riskAndInvalidation)}
      ${renderQuantTextSection("前瞻展望", report.outlook)}
      ${renderQuantTextSection("个股表现说明", report.securityPerformance)}
    </div>
    <section class="quant-unavailable" aria-label="组合级不可用说明"><h4>组合级数据不可用</h4><p>${escapeText(report.portfolioUnavailable || "UNAVAILABLE")}</p>${unavailableReasons.length ? `<ul>${unavailableReasons.map((value) => `<li>${escapeText(value)}</li>`).join("")}</ul>` : ""}</section>
    <div class="quant-prose-grid quant-evidence-grid">
      ${renderQuantStringList("来源", report.sources, "UNAVAILABLE：没有可展示来源。")}
      ${renderQuantStringList("方法", report.methods, "UNAVAILABLE：没有可展示方法。")}
    </div>
    <small class="report-disclaimer">仅供研究，不构成投资建议；历史表现不代表未来。</small>
  </article>`;
}

function renderCoreDrivers(drivers) {
  if (!Array.isArray(drivers) || !drivers.length) return renderAgentList("核心驱动", []);
  return `<section class="report-block report-drivers"><h4>核心驱动</h4><ol>${drivers.map((driver) => `<li><strong>${escapeText(driver.conclusion || "未命名驱动")}</strong><p>${escapeText(driver.rationale || "")}</p>${driver.invalidation ? `<small>失效条件：${escapeText(driver.invalidation)}</small>` : ""}</li>`).join("")}</ol></section>`;
}

function renderAnalysisList(title, values) {
  if (!Array.isArray(values) || !values.length) return "";
  return `<div class="report-subsection"><strong>${escapeText(title)}</strong><ul>${values.map((value) => `<li>${escapeText(typeof value === "string" ? value : value.conclusion || value.rationale || "")}</li>`).join("")}</ul></div>`;
}

function renderModuleAnalysis(title, section = {}, fallback) {
  const constraints = [...(section.counterEvidence || []), ...(section.limitations || [])];
  return `<section class="report-block report-analysis"><h4>${escapeText(title)}</h4>${renderReportFacts(section.facts)}<p>${escapeText(section.narrative || fallback)}</p>${renderAnalysisList("关键判断", section.signals)}${renderAnalysisList("方法依据", section.methodology)}${renderAnalysisList("反证与限制", constraints)}</section>`;
}

function renderModelDiagnostic(diagnostic, titleOverride = null) {
  if (!diagnostic) return "";
  const issueLabels = { MISSING_EVIDENCE_REFERENCE: "缺少证据引用", UNKNOWN_EVIDENCE: "引用未知证据", UNSUPPORTED_NUMBER: "包含证据包未支持的数字", TRADE_INSTRUCTION: "包含禁止的交易指令", MISSING_CONFLICT: "缺少冲突说明", EMPTY_NARRATIVE: "叙述字段为空", NARRATIVE_TOO_LONG: "叙述过长", EMPTY_REQUIRED_SECTION: "必填章节为空", INVALID_DISCLAIMER: "免责声明不正确", UNKNOWN_SOURCE_REFERENCE: "引用未知来源", MISSING_CORE_DATA_LIMITATION: "缺少核心数据限制说明" };
  const issues = Array.isArray(diagnostic.validationIssues) && diagnostic.validationIssues.length
    ? `<dt>校验问题</dt><dd>${diagnostic.validationIssues.map((value) => escapeText(issueLabels[value] ? `${value}（${issueLabels[value]}）` : value)).join("；")}</dd>` : "";
  const title = titleOverride || (diagnostic.errorCode === "MODEL_NARRATIVE_VALIDATION_WARNING" ? "模型叙述校验提示"
    : diagnostic.errorCode === "MODEL_NARRATIVE_VALIDATION_FAILED" ? "模型叙述部分回退" : "模型叙述回退");
  return `<details class="model-diagnostic"><summary>${title} · ${escapeText(diagnostic.errorCode || "MODEL_FAILURE")}</summary><dl><dt>阶段</dt><dd>${escapeText(diagnostic.failureStage || "--")}</dd><dt>原因</dt><dd>${escapeText(diagnostic.message || "--")}</dd><dt>异常</dt><dd>${escapeText(diagnostic.exceptionType || "--")}</dd><dt>模型</dt><dd>${escapeText(diagnostic.modelName || "--")}</dd><dt>耗时</dt><dd>${escapeText(diagnostic.durationMs == null ? "--" : `${diagnostic.durationMs} ms`)}</dd><dt>时间</dt><dd>${escapeText(diagnostic.occurredAt || "--")}</dd>${issues}<dt>追踪 ID</dt><dd>${escapeText(diagnostic.traceId || "--")}</dd></dl></details>`;
}

function renderInstitutionalReport(report) {
  // generationMode 是后端真实生成路径的提示，不能仅根据文本外观猜测是否调用了模型。
  const direction = ({ STRONGER: "偏强", NEUTRAL: "中性", WEAKER: "偏弱", INSUFFICIENT: "证据不足" })[report.direction] || report.direction?.label || report.direction || "证据不足";
  const mode = report.generationMode || "DETERMINISTIC_FALLBACK";
  const technical = report.technicalAndFlow?.narrative || "技术与资金证据不可用";
  const fundamental = report.fundamentals?.narrative || "基本面与机构预期证据不可用";
  const valuation = report.valuationAndIndustry?.narrative || "估值与行业证据不可用";
  const modeMeta = ({
    MODEL_ASSISTED: { status: "HEALTHY", label: "模型叙述已校验" },
    MODEL_ASSISTED_WITH_WARNINGS: { status: "DEGRADED", label: "模型叙述已生成（有校验警告）" },
    MODEL_ASSISTED_PARTIAL: { status: "DEGRADED", label: "模型叙述已生成（部分字段回退）" },
    DETERMINISTIC_FALLBACK: { status: "DEGRADED", label: "确定性规则回退" },
    REPORT_UNAVAILABLE: { status: "UNAVAILABLE", label: "报告不可用" },
  })[mode] || { status: "DEGRADED", label: "报告状态未知" };
  return `<span class="source-status" data-status="${escapeText(modeMeta.status)}"><span></span>${escapeText(modeMeta.label)}</span>
    <div class="report-header"><div><span class="section-kicker">${escapeText(report.horizon || "1-3个月")} · ${escapeText(report.evidenceStatus || "INSUFFICIENT")}</span><h3>${escapeText(direction)}</h3></div><small>${escapeText(report.ruleVersion || "")}</small></div>
    <p class="report-summary">${escapeText(report.executiveSummary || "现有证据无法生成摘要")}</p>
    <div class="report-grid">
      ${renderCoreDrivers(report.coreDrivers)}
      ${renderModuleAnalysis("技术与资金", report.technicalAndFlow, technical)}
      ${renderFundFlowSummary(report.technicalAndFlow?.fundFlowSummary)}
      ${renderModuleAnalysis("基本面与机构预期", report.fundamentals, fundamental)}
      ${renderModuleAnalysis("估值与行业", report.valuationAndIndustry, valuation)}
      ${renderPeerValuationTable(report.valuationAndIndustry?.industryValuation)}
      ${renderAgentList("催化剂", report.catalysts)}
      ${renderAgentList("风险", report.risks)}
      ${renderAgentList("证据冲突", report.conflicts)}
      ${renderAgentList("缺失数据", report.missingData)}
      ${renderAgentList("判断失效条件", report.invalidationConditions)}
      ${renderReportSources(report.sources)}
    </div>
    ${renderModelDiagnostic(report.modelDiagnostic)}
    <small>${escapeText(report.disclaimer || "仅供学习研究，不构成投资建议")}</small>`;
}

function unwrapReport(payload) {
  return payload?.report && typeof payload.report === "object" ? payload.report : payload;
}

function isInstitutionalReport(report) {
  return Boolean(report && (
    "executiveSummary" in report ||
    "technicalAndFlow" in report ||
    "fundamentals" in report ||
    "valuationAndIndustry" in report ||
    "generationMode" in report ||
    "direction" in report
  ));
}

function renderLegacyReport(report) {
  const summary = report?.factualSummary || "旧版报告未返回事实摘要";
  const trend = report?.trendAndRegime || "旧版报告未返回趋势判断";
  const evidence = [
    ...(report?.technicalEvidence || []),
    ...(report?.capitalAndFundamentalEvidence || []),
  ];
  const conclusion = report?.conclusion || "旧版报告未返回结论，请重新编译并重启后端服务";
  return `<span class="source-status" data-status="DEGRADED"><span></span>兼容旧版报告</span>
    <h3>${escapeText(summary)}</h3>
    <p>${escapeText(trend)}</p>
    ${evidence.length ? `<ul>${evidence.map((item) => `<li>${escapeText(item)}</li>`).join("")}</ul>` : ""}
    <strong>${escapeText(conclusion)}</strong>
    <small>${escapeText(report?.disclaimer || "仅供学习研究，不构成投资建议")}</small>`;
}

function renderOverallList(title, values, tone = "neutral") {
  const items = Array.isArray(values) ? values : [];
  return `<section class="overall-evidence" data-tone="${escapeText(tone)}"><h5>${escapeText(title)}</h5>${items.length
    ? `<ul>${items.map((value) => `<li>${escapeText(value)}</li>`).join("")}</ul>`
    : '<p class="muted">暂无可靠证据</p>'}</section>`;
}

function renderOverallSection(title, content, open = false) {
  return `<details class="overall-section" ${open ? "open" : ""}><summary>${escapeText(title)}</summary><p>${escapeText(content || "暂无可靠证据")}</p></details>`;
}

function renderOverallScenarios(scenarios = {}) {
  const entries = [["偏强情景", scenarios.stronger], ["中性情景", scenarios.neutral], ["偏弱情景", scenarios.weaker]];
  return `<section class="overall-scenarios"><h5>条件式情景</h5>${entries.map(([label, value]) => `<div><strong>${label}</strong><p>${escapeText(value || "条件不足")}</p></div>`).join("")}</section>`;
}

function renderOverallSources(sources) {
  const items = Array.isArray(sources) ? sources : [];
  return `<details class="overall-section overall-sources"><summary>来源引用</summary>${items.length
    ? `<ul>${items.map((source) => `<li><strong>${escapeText(source.section || "数据")}</strong> · ${escapeText(source.provider || "未知来源")}${source.sourceUrl ? ` · <a href="${escapeText(source.sourceUrl)}" target="_blank" rel="noopener noreferrer">查看来源</a>` : ""}</li>`).join("")}</ul>`
    : '<p class="muted">当前报告未返回可验证来源</p>'}</details>`;
}

function renderOverallFailure(response = {}) {
  const diagnostic = response.diagnostic || {};
  const diagnosticMarkup = Object.keys(diagnostic).length ? renderModelDiagnostic(diagnostic, "总体报告校验诊断") : "";
  return `<div class="overall-report-failure"><span class="source-status" data-status="UNAVAILABLE"><span></span>${escapeText(response.status || "MODEL_FAILED")}</span><p>${escapeText(response.message || "总体报告暂不可用")}</p>${diagnostic.traceId ? `<small>追踪 ID：${escapeText(diagnostic.traceId)}</small>` : ""}${diagnosticMarkup}</div>`;
}

function renderOverallReportResponse(response = {}) {
  if (response?.status !== "MODEL_ASSISTED" || !response.report) return renderOverallFailure(response);
  const report = response.report;
  const diagnosticMarkup = response.diagnostic
    ? renderModelDiagnostic(response.diagnostic, "总体报告校验提示")
    : "";
  return `<article class="overall-report">
    <header class="overall-report-header"><span class="source-status" data-status="HEALTHY"><span></span>总体报告</span><h3>总体结论</h3><p>${escapeText(report.overallConclusion || "暂无总体结论")}</p><dl><dt>模型</dt><dd>${escapeText(report.modelName || "未知模型")}</dd><dt>快照</dt><dd>${escapeText(report.snapshotAt || "--")}</dd></dl></header>
    ${renderOverallSection("数据质量", report.dataQualitySummary, true)}
    ${renderOverallSection("公司与基本面", report.companyAndFundamentals)}
    ${renderOverallSection("技术与资金", report.technicalAndCapital)}
    ${renderFundFlowSummary(report.fundFlowSummary)}
    ${renderOverallSection("估值与行业", report.valuationAndIndustry)}
    ${renderPeerValuationTable(report.industryValuation)}
    ${renderOverallSection("事件与情绪", report.eventsAndSentiment)}
    ${renderOverallList("支持证据", report.bullishEvidence, "support")}
    ${renderOverallList("反向证据", report.bearishEvidence, "oppose")}
    ${renderOverallList("风险因素", report.riskFactors, "risk")}
    ${renderOverallScenarios(report.scenarios)}
    ${renderOverallList("冲突与缺失", report.conflictsAndMissingData)}
    ${renderOverallSources(report.sourceReferences)}
    ${report.disclaimer ? `<small class="report-disclaimer">${escapeText(report.disclaimer)}</small>` : ""}
    ${diagnosticMarkup}
  </article>`;
}

function renderOverallRequestFeedback() {
  const requestStatus = $("#overall-report-request-status");
  if (!requestStatus) return;
  const feedback = state.overallReportFeedback;
  if (!feedback) {
    requestStatus.replaceChildren();
    return;
  }
  if (feedback.kind === "loading") {
    requestStatus.innerHTML = '<span class="source-status" data-status="DEGRADED"><span></span>正在生成总体报告</span><p>所选模型正在读取当前股票的完整数据快照。</p>';
    return;
  }
  if (feedback.kind === "response-failure") {
    requestStatus.innerHTML = renderOverallFailure(feedback.payload);
    return;
  }
  requestStatus.innerHTML = `<span class="source-status" data-status="UNAVAILABLE"><span></span>总体报告不可用</span><p>${escapeText(feedback.message || "请检查所选模型的本地配置")}</p>`;
}

function bindAgentAction() {
  // “生成研究报告”与“生成总体报告”是两个独立请求和两个独立输出区域。
  const button = $("#run-agent");
  if (!button) return;
  button.addEventListener("click", async () => {
    const output = $("#agent-output");
    const reportCode = state.currentCode;
    const generation = state.loadGeneration;
    state.institutionalReportPhase = "loading";
    button.disabled = true;
    output.innerHTML = '<span class="source-status" data-status="DEGRADED"><span></span>正在综合</span><p>Agent 正在调用受限股票研究工具。</p>';
    try {
      // 请求期间记录股票和加载代数；返回时如果页面已切换，丢弃旧结果。
      const report = unwrapReport(await stockApi.quantReport(reportCode));
      if (state.currentCode !== reportCode || state.loadGeneration !== generation) return;
      state.institutionalReport = report;
      if (isQuantResearchReport(report)) {
        output.innerHTML = renderQuantResearchReport(report);
        refreshIcons();
        return;
      }
      if (isInstitutionalReport(report)) {
        output.innerHTML = renderInstitutionalReport(report);
        refreshIcons();
        return;
      }
      output.innerHTML = renderLegacyReport(report);
    } catch (error) {
      output.innerHTML = `<span class="source-status" data-status="UNAVAILABLE"><span></span>Agent 不可用</span><p>${escapeText(error.message || "请检查本地模型配置")}</p>`;
    } finally {
      state.institutionalReportPhase = "idle";
      button.disabled = false;
    }
  });
}

function syncOverallModelControls() {
  const select = $("#overall-model-select");
  const button = $("#run-overall-report");
  const help = $("#overall-model-help");
  const modelName = $("#overall-model-name");
  if (!select || !button || !help || !modelName) return;

  const models = state.overallModels;
  const selected = models.find((model) => model.id === state.selectedOverallModelId);
  select.innerHTML = models.length
    ? models.map((model) => `<option value="${escapeText(model.id)}">${escapeText(model.id)} · ${escapeText(model.modelName)}</option>`).join("")
    : `<option value="">${state.overallModelsPhase === "loading" ? "正在加载可用模型" : "没有可用模型"}</option>`;
  select.value = selected?.id || "";
  select.disabled = !models.length || state.overallReportPhase === "loading";
  button.disabled = !selected || state.overallReportPhase === "loading";
  modelName.textContent = selected?.modelName || "未选择";
  help.textContent = state.overallModelsPhase === "failed"
    ? "模型目录加载失败，请稍后重试"
    : models.length
      ? "请选择生成本次总体报告的模型"
      : state.overallModelsPhase === "loading"
        ? "正在读取本地模型配置"
        : "没有可用模型，请检查本地配置";
}

async function loadOverallModels() {
  // 模型目录只描述安全的 ID、实际模型名和默认标记，不包含任何连接秘密。
  if (state.overallModelsPhase === "loading" || state.overallModelsPhase === "ready") {
    syncOverallModelControls();
    return;
  }
  state.overallModelsPhase = "loading";
  syncOverallModelControls();
  try {
    const response = await stockApi.overallModels();
    state.overallModels = Array.isArray(response?.models) ? response.models : [];
    const stillSelected = state.overallModels.some((model) => model.id === state.selectedOverallModelId);
    if (!stillSelected) {
      state.selectedOverallModelId = state.overallModels.find((model) => model.defaultModel)?.id
        || state.overallModels[0]?.id
        || null;
    }
    state.overallModelsPhase = "ready";
  } catch {
    state.overallModels = [];
    state.selectedOverallModelId = null;
    state.overallModelsPhase = "failed";
  }
  syncOverallModelControls();
}

function bindFinancialAction() {
  // 财报分析按需生成:评分与趋势由后端确定性计算,DeepSeek 只负责叙事,失败时后端回退确定性文字。
  const button = $("#run-financial-report");
  if (!button) return;
  button.addEventListener("click", async () => {
    const reportCode = state.currentCode;
    const generation = state.loadGeneration;
    const requestId = ++state.financialReportRequestId;
    const isCurrent = () => state.financialReportRequestId === requestId
      && state.currentCode === reportCode && state.loadGeneration === generation;
    state.financialReportPhase = "loading";
    button.disabled = true;
    const status = $("#financial-request-status");
    if (status) status.innerHTML = '<span class="source-status" data-status="DEGRADED"><span></span>正在生成财报分析</span>';
    const output = $("#financial-output");
    try {
      const report = await stockApi.financialReport(reportCode);
      if (!isCurrent()) return;
      state.financialReportResult = report;
      if (output) output.innerHTML = renderFinancialReport(report);
      state.financialController = activateFinancialChart(report, $("#financial-trend-chart"));
      refreshIcons();
    } catch (error) {
      if (!isCurrent()) return;
      if (output) output.innerHTML = `<span class="source-status" data-status="UNAVAILABLE"><span></span>财报分析不可用</span><p>${escapeText(error.message || "请检查数据源配置")}</p>`;
    } finally {
      if (isCurrent()) {
        state.financialReportPhase = "idle";
        button.disabled = false;
        if (status) status.replaceChildren();
      }
    }
  });
}

function bindOverallModelControls() {
  const select = $("#overall-model-select");
  if (!select) return;
  select.addEventListener("change", () => {
    state.selectedOverallModelId = select.value || null;
    syncOverallModelControls();
  });
  loadOverallModels();
}

function bindOverallReportAction() {
  // 总体报告把当前选中的 modelId 发给后端，后端再次校验该 ID 是否存在。
  const button = $("#run-overall-report");
  if (!button) return;
  button.addEventListener("click", async () => {
    const reportCode = state.currentCode;
    const generation = state.loadGeneration;
    const selectedModelId = state.selectedOverallModelId;
    if (!selectedModelId) return;
    const requestId = ++state.overallReportRequestId;
    const isCurrentRequest = () => state.overallReportRequestId === requestId
      && state.currentCode === reportCode
      && state.loadGeneration === generation;
    state.overallReportPhase = "loading";
    state.overallReportFeedback = { kind: "loading" };
    syncOverallModelControls();
    renderOverallRequestFeedback();
    try {
      const payload = await stockApi.overallReport(reportCode, selectedModelId);
      if (!isCurrentRequest()) return;
      if (payload?.status !== "MODEL_ASSISTED" || !payload.report) {
        state.overallReportFeedback = { kind: "response-failure", payload };
        renderOverallRequestFeedback();
        return;
      }
      state.overallReportResponse = payload;
      state.overallReportFeedback = null;
      renderOverallRequestFeedback();
      const currentOutput = $("#overall-report-output");
      if (currentOutput) currentOutput.innerHTML = renderOverallReportResponse(payload);
      refreshIcons();
    } catch (error) {
      if (!isCurrentRequest()) return;
      state.overallReportFeedback = {
        kind: "transport-failure",
        message: error.message || "请检查所选模型的本地配置",
      };
      renderOverallRequestFeedback();
    } finally {
      if (isCurrentRequest()) {
        state.overallReportPhase = "idle";
        syncOverallModelControls();
      }
    }
  });
}

async function loadStock(code) {
  // 快照加载是页面状态机的根请求；其余视图都从同一份 snapshot 派生。
  if (!/^\d{6}$/.test(code)) return;
  state.loadGeneration += 1;
  state.overallReportRequestId += 1;
  state.currentCode = code;
  state.overallReportPhase = "idle";
  state.overallReportFeedback = null;
  state.institutionalReportPhase = "idle";
  state.overallReportResponse = null;
  state.institutionalReport = null;
  state.financialReportResult = null;
  state.financialReportRequestId += 1;
  setPhase("loading", `正在获取 ${code} 的公开市场数据`);
  $("#workspace-state").hidden = true;
  $("#view-content").hidden = false;
  $("#view-content").innerHTML = renderLoading();
  $("#stock-query").value = code;
  closeResults();
  try {
    const snapshot = await stockApi.snapshot(code);
    state.snapshot = snapshot;
    renderOverview(snapshot);
    renderCurrentView();
    const partial = isPartialSnapshot(snapshot);
    setPhase(partial ? "partial" : "success", partial ? `${code} 已加载，部分数据源降级` : `${code} 数据加载完成`);
  } catch (error) {
    state.snapshot = null;
    $("#workspace-state").hidden = true;
    $("#view-content").hidden = false;
    $("#view-content").innerHTML = `<div class="state-message"><div><i data-lucide="circle-x" aria-hidden="true"></i><h2>研究数据加载失败</h2><p>${String(error.message || "未知错误").replace(/[<>]/g, "")}</p></div></div>`;
    refreshIcons();
    setPhase("error", `${code} 加载失败`);
  }
}

function renderSearchResultsLegacy(results) {
  return renderSafeSearchResults(results);
  const panel = $("#search-results");
  panel.innerHTML = results.length ? results.map((item) => `<button class="search-result" type="button" role="option" data-code="${item.code}"><strong>${item.name}</strong><span>${item.code} · ${marketName(item.exchange)}</span></button>`).join("") : '<div class="search-result"><span>未找到匹配股票</span></div>';
  panel.hidden = false;
  $("#stock-query").setAttribute("aria-expanded", "true");
}

function renderSafeSearchResults(results) {
  const panel = $("#search-results");
  panel.innerHTML = results.length ? results.map((item) => {
    const code = escapeText(item.code);
    return `<button class="search-result" type="button" role="option" data-code="${code}"><strong>${escapeText(item.name)}</strong><span>${code} · ${escapeText(marketName(item.exchange))}</span></button>`;
  }).join("") : '<div class="search-result"><span>未找到匹配股票</span></div>';
  panel.hidden = false;
  $("#stock-query").setAttribute("aria-expanded", "true");
}

function closeResults() {
  $("#search-results").hidden = true;
  $("#stock-query").setAttribute("aria-expanded", "false");
}

async function search(query) {
  const normalized = query.trim();
  if (!normalized) { closeResults(); return; }
  try { renderSafeSearchResults(await stockApi.search(normalized)); }
  catch { closeResults(); }
}

function selectView(button) {
  $$(".view-tab").forEach((tab) => { const active = tab === button; tab.classList.toggle("is-active", active); tab.setAttribute("aria-selected", String(active)); });
  state.currentView = button.dataset.view;
  if (state.snapshot) renderCurrentView();
}

async function loadAgentStatus() {
  try {
    const status = await stockApi.agentStatus();
    const enabled = status.enabled === true || String(status.status || "").includes("READY");
    $("#agent-status").innerHTML = `<span class="status-dot"></span>${enabled ? "Agent 可用" : "Agent 未配置"}`;
    $("#agent-status").dataset.tone = enabled ? "ok" : "muted";
  } catch {
    $("#agent-status").dataset.tone = "warning";
  }
}

$("#stock-search").addEventListener("submit", (event) => {
  event.preventDefault();
  const query = $("#stock-query").value.trim();
  if (/^\d{6}$/.test(query)) loadStock(query);
  else search(query);
});
$("#stock-query").addEventListener("input", (event) => {
  window.clearTimeout(state.searchTimer);
  state.searchTimer = window.setTimeout(() => search(event.target.value), 180);
});
$("#search-results").addEventListener("click", (event) => {
  const option = event.target.closest("[data-code]");
  if (option) loadStock(option.dataset.code);
});
document.addEventListener("click", (event) => { if (!event.target.closest("#stock-search")) closeResults(); });
$$('[data-symbol]').forEach((button) => button.addEventListener("click", () => loadStock(button.dataset.symbol)));
$$(".view-tab").forEach((button) => button.addEventListener("click", () => selectView(button)));
$("#refresh-data").addEventListener("click", () => state.currentCode && loadStock(state.currentCode));

refreshIcons();
loadAgentStatus();
