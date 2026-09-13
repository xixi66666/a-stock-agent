/*
 * 研究工作台的页面状态机。
 *
 * state.snapshot 是当前股票的规范化快照，state.finRobotResponse 是唯一的投研结果。
 * 所有异步回调都要检查 code 和
 * loadGeneration，防止用户切换股票后旧请求覆盖新页面。
 */
import { isPartialSnapshot, sectionPayload, stockApi } from "./api.js";
import { renderGenericView, renderLoading, renderUnavailable } from "./views.js";
import { activateTechnicalView, renderTechnicalView } from "./technical-view.js";
import { activateCandlestickWorkbench, renderCandlestickWorkbench } from "./candlestick-view.js?v=20260911-quotes";
import { renderFundFlowSummary, renderPeerValuationTable } from "./derived-market-view.js";
import { activateFinancialChart, renderFinancialReport, renderFinancialViewShell } from "./financial-view.js";
import { activateCycleView } from "./cycle-view.js";
import { activateUziView } from "./uzi-view.js";

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

const state = {
  phase: "idle",
  currentCode: null,
  currentView: "technical",
  snapshot: null,
  searchTimer: null,
  technicalController: null,
  candlestickController: null,
  candlestickTimeframe: "DAILY",
  candlestickPhase: "idle",
  candlestickSections: {},
  candlestickRequestId: 0,
  finRobotPhase: "idle",
  finRobotModelsPhase: "idle",
  finRobotModels: [],
  selectedFinRobotModelId: null,
  finRobotResponse: null,
  finRobotFeedback: null,
  finRobotRequestId: 0,
  financialReportPhase: "idle",
  financialReportResult: null,
  financialReportRequestId: 0,
  financialController: null,
  cycleController: null,
  uziController: null,
  loadGeneration: 0,
};

function refreshIcons() {
  if (window.lucide?.createIcons) window.lucide.createIcons({ attrs: { "stroke-width": 1.8 } });
}

function formatNumber(value, digits = 2) {
  if (value == null || value === "") return "--";
  const number = Number(value);
  return Number.isFinite(number) ? number.toLocaleString("zh-CN", { minimumFractionDigits: digits, maximumFractionDigits: digits }) : "--";
}

function formatCompact(value) {
  if (value == null || value === "") return "--";
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
  state.candlestickController?.dispose();
  state.candlestickController = null;
  state.financialController?.dispose();
  state.financialController = null;
  state.cycleController?.dispose();
  state.cycleController = null;
  state.uziController?.dispose();
  state.uziController = null;
  if (state.currentView === "cycle") {
    state.cycleController = activateCycleView(content, state.currentCode);
    return;
  }
  if (state.currentView === "uzi") {
    state.uziController = activateUziView(content, state.currentCode);
    return;
  }
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
    const candlestick = state.candlestickSections[state.candlestickTimeframe] || null;
    content.innerHTML = renderCandlestickWorkbench(candlestick, {
      timeframe: state.candlestickTimeframe,
      loading: state.candlestickPhase === "loading",
    }) + renderTechnicalView(state.snapshot.technical);
  } else {
    content.innerHTML = renderGenericView(state.currentView, state.snapshot);
  }
  refreshIcons();
  if (state.currentView === "technical") {
    const candlestick = state.candlestickSections[state.candlestickTimeframe] || null;
    state.candlestickController = activateCandlestickWorkbench(candlestick, {
      onTimeframeChange: (timeframe) => loadCandlestick(timeframe),
    }, content);
    state.technicalController = activateTechnicalView(state.snapshot.technical, content);
    if (!candlestick && state.candlestickPhase !== "loading") loadCandlestick(state.candlestickTimeframe);
  }
  if (state.currentView === "finrobot") {
    bindFinRobotModelControls();
    bindFinRobotAction();
    if (state.finRobotResponse) $("#finrobot-output").innerHTML = renderFinRobotResponse(state.finRobotResponse);
    renderFinRobotFeedback();
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

function renderModelDiagnostic(diagnostic, titleOverride = null) {
  if (!diagnostic) return "";
  const issueLabels = { MISSING_EVIDENCE_REFERENCE: "缺少证据引用", UNKNOWN_EVIDENCE: "引用未知证据", UNSUPPORTED_NUMBER: "包含证据包未支持的数字", TRADE_INSTRUCTION: "包含禁止的交易指令", MISSING_CONFLICT: "缺少冲突说明", EMPTY_NARRATIVE: "叙述字段为空", NARRATIVE_TOO_LONG: "叙述过长", EMPTY_REQUIRED_SECTION: "必填章节为空", INVALID_DISCLAIMER: "免责声明不正确", UNKNOWN_SOURCE_REFERENCE: "引用未知来源", MISSING_CORE_DATA_LIMITATION: "缺少核心数据限制说明" };
  const issues = Array.isArray(diagnostic.validationIssues) && diagnostic.validationIssues.length
    ? `<dt>校验问题</dt><dd>${diagnostic.validationIssues.map((value) => escapeText(issueLabels[value] ? `${value}（${issueLabels[value]}）` : value)).join("；")}</dd>` : "";
  const title = titleOverride || (diagnostic.errorCode === "MODEL_NARRATIVE_VALIDATION_WARNING" ? "模型叙述校验提示"
    : diagnostic.errorCode === "MODEL_NARRATIVE_VALIDATION_FAILED" ? "模型叙述部分回退" : "模型叙述回退");
  return `<details class="model-diagnostic"><summary>${title} · ${escapeText(diagnostic.errorCode || "MODEL_FAILURE")}</summary><dl><dt>阶段</dt><dd>${escapeText(diagnostic.failureStage || "--")}</dd><dt>原因</dt><dd>${escapeText(diagnostic.message || "--")}</dd><dt>异常</dt><dd>${escapeText(diagnostic.exceptionType || "--")}</dd><dt>模型</dt><dd>${escapeText(diagnostic.modelName || "--")}</dd><dt>耗时</dt><dd>${escapeText(diagnostic.durationMs == null ? "--" : `${diagnostic.durationMs} ms`)}</dd><dt>时间</dt><dd>${escapeText(diagnostic.occurredAt || "--")}</dd>${issues}<dt>追踪 ID</dt><dd>${escapeText(diagnostic.traceId || "--")}</dd></dl></details>`;
}

function renderOverallList(title, values, tone = "neutral") {
  const items = Array.isArray(values) ? values : [];
  return `<section class="finrobot-evidence" data-tone="${escapeText(tone)}"><h5>${escapeText(title)}</h5>${items.length
    ? `<ul>${items.map((value) => `<li>${escapeText(value)}</li>`).join("")}</ul>`
    : '<p class="muted">暂无可靠证据</p>'}</section>`;
}

function renderOverallSection(title, content, open = false) {
  return `<details class="finrobot-section" ${open ? "open" : ""}><summary>${escapeText(title)}</summary><p>${escapeText(content || "暂无可靠证据")}</p></details>`;
}

function renderOverallScenarios(scenarios = {}) {
  const entries = [["偏强情景", scenarios.stronger], ["中性情景", scenarios.neutral], ["偏弱情景", scenarios.weaker]];
  return `<section class="finrobot-scenarios"><h5>条件式情景</h5>${entries.map(([label, value]) => `<div><strong>${label}</strong><p>${escapeText(value || "条件不足")}</p></div>`).join("")}</section>`;
}

function renderOverallSources(sources) {
  const items = Array.isArray(sources) ? sources : [];
  return `<details class="finrobot-section finrobot-sources"><summary>来源引用</summary>${items.length
    ? `<ul>${items.map((source) => `<li><strong>${escapeText(source.section || "数据")}</strong> · ${escapeText(source.provider || "未知来源")}${source.sourceUrl ? ` · <a href="${escapeText(source.sourceUrl)}" target="_blank" rel="noopener noreferrer">查看来源</a>` : ""}</li>`).join("")}</ul>`
    : '<p class="muted">当前报告未返回可验证来源</p>'}</details>`;
}

function renderFinRobotFailure(response = {}) {
  const diagnostic = response.diagnostic || {};
  const diagnosticMarkup = Object.keys(diagnostic).length ? renderModelDiagnostic(diagnostic, "FinRobot 校验诊断") : "";
  const modeLabel = response.status === "MODEL_NOT_CONFIGURED" ? "确定性回退" : "模型调用失败";
  const modelName = diagnostic.modelName || response.modelName || "未配置";
  return `<div class="finrobot-report-failure"><p class="generation-line">生成模式：${modeLabel} · 模型：${escapeText(modelName)}</p><span class="source-status" data-status="UNAVAILABLE"><span></span>${escapeText(response.status || "MODEL_FAILED")}</span><p>${escapeText(response.message || "FinRobot 投研暂不可用")}</p>${diagnostic.traceId ? `<small>追踪 ID：${escapeText(diagnostic.traceId)}</small>` : ""}${diagnosticMarkup}</div>`;
}

function renderFinRobotResponse(response = {}) {
  if (!response.report) return renderFinRobotFailure(response);
  const report = response.report;
  const modelAssisted = response.status === "MODEL_ASSISTED";
  const status = modelAssisted ? "HEALTHY" : report.generationMode === "REPORT_UNAVAILABLE" ? "UNAVAILABLE" : "DEGRADED";
  const statusLabel = modelAssisted ? "FinRobot 模型已生成" : response.status === "MODEL_NOT_CONFIGURED" ? "确定性研究回退" : "FinRobot 确定性回退";
  const modeLabels = { MODEL_ASSISTED: "模型生成", DETERMINISTIC_FALLBACK: "确定性回退", REPORT_UNAVAILABLE: "报告不可用" };
  const generationMode = report.generationMode || (modelAssisted ? "MODEL_ASSISTED" : "DETERMINISTIC_FALLBACK");
  const generationLabel = modeLabels[generationMode] || generationMode;
  const diagnosticMarkup = response.diagnostic ? renderModelDiagnostic(response.diagnostic, "FinRobot 校验诊断") : "";
  return `<article class="finrobot-report">
    <header class="finrobot-report-header"><p class="generation-line">生成模式：${escapeText(generationLabel)} · 模型：${escapeText(report.modelName || "未配置")}</p><span class="source-status" data-status="${status}"><span></span>${statusLabel}</span><h3>${escapeText(report.ticker || state.currentCode || "--")} · ${escapeText(report.companyName || "FinRobot Equity Research")}</h3><p>${escapeText(report.tagline || "暂无研究摘要")}</p><dl><dt>模型</dt><dd>${escapeText(report.modelName || "未配置")}</dd><dt>流水线</dt><dd>${escapeText(report.pipelineVersion || "finrobot-equity-v1")}</dd><dt>快照</dt><dd>${escapeText(report.snapshotAt || "--")}</dd></dl></header>
    ${renderOverallSection("数据质量", report.dataQualitySummary, true)}
    ${renderOverallSection("公司概览", report.companyOverview)}
    ${renderOverallSection("投资逻辑", report.investmentOverview)}
    ${renderOverallSection("技术与资金", report.technicalAndCapital)}
    ${renderFundFlowSummary(report.fundFlowSummary)}
    ${renderOverallSection("估值分析", report.valuationOverview)}
    ${renderPeerValuationTable(report.industryValuation)}
    ${renderOverallSection("竞争格局", report.competitorAnalysis)}
    ${renderOverallSection("事件与新闻", report.newsSummary)}
    ${renderOverallSection("主要结论", report.majorTakeaways)}
    ${renderOverallList("支持证据", report.bullishEvidence, "support")}
    ${renderOverallList("反向证据", report.bearishEvidence, "oppose")}
    ${renderOverallSection("风险评估", report.risks)}
    ${renderOverallList("风险因素", report.riskFactors, "risk")}
    ${renderOverallScenarios(report.scenarios)}
    ${renderOverallList("冲突与缺失", report.conflictsAndMissingData)}
    ${renderOverallSources(report.sourceReferences)}
    ${report.disclaimer ? `<small class="report-disclaimer">${escapeText(report.disclaimer)}</small>` : ""}
    ${diagnosticMarkup}
  </article>`;
}

function renderFinRobotFeedback() {
  const requestStatus = $("#finrobot-request-status");
  if (!requestStatus) return;
  const feedback = state.finRobotFeedback;
  if (!feedback) {
    requestStatus.replaceChildren();
    return;
  }
  if (feedback.kind === "loading") {
    requestStatus.innerHTML = '<span class="source-status" data-status="DEGRADED"><span></span>FinRobot 正在运行</span><p>研究角色正在读取当前股票的完整规范化快照。</p>';
    return;
  }
  if (feedback.kind === "response-failure") {
    requestStatus.innerHTML = renderFinRobotFailure(feedback.payload);
    return;
  }
  requestStatus.innerHTML = `<span class="source-status" data-status="UNAVAILABLE"><span></span>FinRobot 不可用</span><p>${escapeText(feedback.message || "请检查所选模型的本地配置")}</p>`;
}

function bindFinRobotAction() {
  // FinRobot 只有一个投研请求：同一份快照依次进入确定性分析、角色叙述和报告校验。
  const button = $("#run-finrobot");
  if (!button) return;
  button.addEventListener("click", async () => {
    const output = $("#finrobot-output");
    const reportCode = state.currentCode;
    const generation = state.loadGeneration;
    const requestId = ++state.finRobotRequestId;
    state.finRobotPhase = "loading";
    state.finRobotFeedback = { kind: "loading" };
    button.disabled = true;
    syncFinRobotModelControls();
    renderFinRobotFeedback();
    output.innerHTML = '<span class="source-status" data-status="DEGRADED"><span></span>FinRobot 正在综合</span><p>正在运行证据、分析、估值、风险与报告角色。</p>';
    try {
      // 请求期间记录股票和加载代数；返回时如果页面已切换，丢弃旧结果。
      const payload = await stockApi.finRobotResearch(reportCode, state.selectedFinRobotModelId);
      if (state.currentCode !== reportCode || state.loadGeneration !== generation || state.finRobotRequestId !== requestId) return;
      state.finRobotResponse = payload;
      state.finRobotFeedback = null;
      output.innerHTML = renderFinRobotResponse(payload);
      refreshIcons();
    } catch (error) {
      if (state.currentCode === reportCode && state.loadGeneration === generation && state.finRobotRequestId === requestId) {
        state.finRobotFeedback = { kind: "transport-failure", message: error.message || "请检查本地模型配置" };
        renderFinRobotFeedback();
        output.innerHTML = renderFinRobotFailure({ status: "MODEL_FAILED", message: error.message || "FinRobot 投研请求失败" });
      }
    } finally {
      if (state.currentCode === reportCode && state.loadGeneration === generation && state.finRobotRequestId === requestId) {
        state.finRobotPhase = "idle";
        button.disabled = false;
        syncFinRobotModelControls();
      }
    }
  });
}

function syncFinRobotModelControls() {
  const select = $("#finrobot-model-select");
  const button = $("#run-finrobot");
  const help = $("#finrobot-model-help");
  if (!select || !button || !help) return;

  const models = state.finRobotModels;
  const selected = models.find((model) => model.id === state.selectedFinRobotModelId);
  select.innerHTML = models.length
    ? models.map((model) => `<option value="${escapeText(model.id)}">${escapeText(model.id)} · ${escapeText(model.modelName)}</option>`).join("")
    : `<option value="">${state.finRobotModelsPhase === "loading" ? "正在加载可用模型" : "没有可用模型，将使用确定性研究"}</option>`;
  select.value = selected?.id || "";
  select.disabled = !models.length || state.finRobotPhase === "loading";
  button.disabled = state.finRobotPhase === "loading";
  help.textContent = state.finRobotModelsPhase === "failed"
    ? "模型目录加载失败，请稍后重试"
    : models.length
      ? "请选择本次 FinRobot 投研使用的模型"
      : state.finRobotModelsPhase === "loading"
        ? "正在读取本地模型配置"
        : "没有可用模型，将保留确定性研究结果";
}

function renderFinRobotModelStatus() {
  const status = $("#finrobot-model-status");
  if (!status) return;
  if (state.finRobotModelsPhase === "loading" || state.finRobotModelsPhase === "idle") {
    status.innerHTML = '<span class="status-dot"></span>模型：读取中';
    status.dataset.tone = "muted";
    status.title = "正在读取本地模型配置";
    return;
  }
  if (state.finRobotModelsPhase === "failed") {
    status.innerHTML = '<span class="status-dot"></span>模型：读取失败';
    status.dataset.tone = "warning";
    status.title = "模型目录读取失败，可切换 FinRobot 标签重试";
    return;
  }
  if (!state.finRobotModels.length) {
    status.innerHTML = '<span class="status-dot"></span>模型：未配置';
    status.dataset.tone = "muted";
    status.title = "没有已注册的可用模型，将使用确定性研究结果";
    return;
  }
  const labels = state.finRobotModels.map((model) => `${model.id} · ${model.modelName}`).join(" / ");
  status.innerHTML = `<span class="status-dot"></span>模型：${escapeText(labels)}`;
  status.dataset.tone = "ok";
  status.title = `可用模型：${labels}`;
}

async function loadFinRobotModels() {
  // 模型目录只描述安全的 ID、实际模型名和默认标记，不包含任何连接秘密。
  if (state.finRobotModelsPhase === "loading" || state.finRobotModelsPhase === "ready") {
    syncFinRobotModelControls();
    renderFinRobotModelStatus();
    return;
  }
  state.finRobotModelsPhase = "loading";
  syncFinRobotModelControls();
  renderFinRobotModelStatus();
  try {
    const response = await stockApi.finRobotModels();
    state.finRobotModels = Array.isArray(response?.models) ? response.models : [];
    const stillSelected = state.finRobotModels.some((model) => model.id === state.selectedFinRobotModelId);
    if (!stillSelected) {
      state.selectedFinRobotModelId = state.finRobotModels.find((model) => model.defaultModel)?.id
        || state.finRobotModels[0]?.id
        || null;
    }
    state.finRobotModelsPhase = "ready";
  } catch {
    state.finRobotModels = [];
    state.selectedFinRobotModelId = null;
    state.finRobotModelsPhase = "failed";
  }
  syncFinRobotModelControls();
  renderFinRobotModelStatus();
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

async function loadCandlestick(timeframe) {
  // 周期切换只请求结构化研判；K 线来源仍由后端快照缓存统一提供，避免浏览器直接访问 Provider。
  if (!state.currentCode || !["DAILY", "WEEKLY", "MONTHLY"].includes(timeframe)) return;
  state.candlestickTimeframe = timeframe;
  if (state.candlestickSections[timeframe]) {
    state.candlestickPhase = "ready";
    if (state.currentView === "technical") renderCurrentView();
    return;
  }
  const code = state.currentCode;
  const generation = state.loadGeneration;
  const requestId = ++state.candlestickRequestId;
  state.candlestickPhase = "loading";
  if (state.currentView === "technical") renderCurrentView();
  try {
    const section = await stockApi.candlestick(code, timeframe);
    if (state.currentCode !== code || state.loadGeneration !== generation || requestId !== state.candlestickRequestId) return;
    state.candlestickSections[timeframe] = section;
  } catch (error) {
    if (state.currentCode !== code || state.loadGeneration !== generation || requestId !== state.candlestickRequestId) return;
    state.candlestickSections[timeframe] = {
      status: "UNAVAILABLE", payload: null, provenance: null,
      issues: [error.message || "蜡烛图研判请求失败，请稍后重试"],
    };
  } finally {
    if (state.currentCode === code && state.loadGeneration === generation && requestId === state.candlestickRequestId) {
      state.candlestickPhase = "ready";
      if (state.currentView === "technical") renderCurrentView();
    }
  }
}

function bindFinRobotModelControls() {
  const select = $("#finrobot-model-select");
  if (!select) return;
  select.addEventListener("change", () => {
    state.selectedFinRobotModelId = select.value || null;
    syncFinRobotModelControls();
  });
  loadFinRobotModels();
}

async function loadStock(code) {
  // 快照加载是页面状态机的根请求；其余视图都从同一份 snapshot 派生。
  if (!/^\d{6}$/.test(code)) return;
  const newSecurity = state.currentCode !== code || !state.snapshot;
  state.loadGeneration += 1;
  const generation = state.loadGeneration;
  const isCurrent = () => generation === state.loadGeneration && code === state.currentCode;
  state.snapshot = null;
  state.cycleController?.dispose();
  state.cycleController = null;
  state.uziController?.dispose();
  state.uziController = null;
  state.finRobotRequestId += 1;
  state.currentCode = code;
  state.finRobotPhase = "idle";
  state.finRobotFeedback = null;
  state.finRobotResponse = null;
  state.financialReportResult = null;
  state.financialReportRequestId += 1;
  state.candlestickRequestId += 1;
  state.candlestickTimeframe = "DAILY";
  state.candlestickPhase = "idle";
  state.candlestickSections = {};
  setPhase("loading", `正在获取 ${code} 的公开市场数据`);
  $("#workspace-state").hidden = true;
  $("#view-content").hidden = false;
  $("#view-content").innerHTML = renderLoading();
  $("#stock-query").value = code;
  closeResults();
  // 从下方快捷入口选择股票后回到概览；同一股票的刷新保留阅读位置。
  if (newSecurity) window.scrollTo(0, 0);
  try {
    const snapshot = await stockApi.snapshot(code);
    if (!isCurrent()) return;
    state.snapshot = snapshot;
    renderOverview(snapshot);
    renderCurrentView();
    const partial = isPartialSnapshot(snapshot);
    setPhase(partial ? "partial" : "success", partial ? `${code} 已加载，部分数据源降级` : `${code} 数据加载完成`);
  } catch (error) {
    if (!isCurrent()) return;
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

async function loadFinRobotStatus() {
  try {
    const status = await stockApi.finRobotStatus();
    const enabled = status.enabled === true || String(status.status || "").includes("READY");
    $("#finrobot-status").innerHTML = `<span class="status-dot"></span>${enabled ? "FinRobot 可用" : "FinRobot 未配置"}`;
    $("#finrobot-status").dataset.tone = enabled ? "ok" : "muted";
  } catch {
    $("#finrobot-status").dataset.tone = "warning";
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
loadFinRobotStatus();
loadFinRobotModels();

// The cinematic home links to a bounded security code and an existing research tab.
const entryParameters = new URLSearchParams(window.location.search);
const entryView = $$('.view-tab').find(button => button.dataset.view === entryParameters.get('view'));
if (entryView) selectView(entryView);
const entryCode = entryParameters.get('code') || '';
if (/^\d{6}$/.test(entryCode)) void loadStock(entryCode);
