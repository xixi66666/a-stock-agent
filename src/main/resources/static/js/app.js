import { isPartialSnapshot, sectionPayload, stockApi } from "./api.js";
import { renderGenericView, renderLoading, renderUnavailable } from "./views.js";
import { activateTechnicalView, renderTechnicalView } from "./technical-view.js";

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
  institutionalReportPhase: "idle",
  institutionalReport: null,
  overallReportResponse: null,
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
  if (!state.snapshot) return;
  const content = $("#view-content");
  content.hidden = false;
  $("#workspace-state").hidden = true;
  state.technicalController?.dispose();
  state.technicalController = null;
  if (state.currentView === "technical") {
    content.innerHTML = renderTechnicalView(state.snapshot.technical);
  } else {
    content.innerHTML = renderGenericView(state.currentView, state.snapshot);
  }
  refreshIcons();
  if (state.currentView === "technical") state.technicalController = activateTechnicalView(state.snapshot.technical, content);
  if (state.currentView === "agent") {
    bindOverallReportAction();
    bindAgentAction();
    if (state.overallReportResponse) $("#overall-report-output").innerHTML = renderOverallReportResponse(state.overallReportResponse);
    if (state.institutionalReport) $("#agent-output").innerHTML = isInstitutionalReport(state.institutionalReport)
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
      ${renderModuleAnalysis("基本面与机构预期", report.fundamentals, fundamental)}
      ${renderModuleAnalysis("估值与行业", report.valuationAndIndustry, valuation)}
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
    <header class="overall-report-header"><span class="source-status" data-status="HEALTHY"><span></span>DeepSeek 总体报告</span><h3>总体结论</h3><p>${escapeText(report.overallConclusion || "暂无总体结论")}</p><dl><dt>模型</dt><dd>${escapeText(report.modelName || "deepseek-chat")}</dd><dt>快照</dt><dd>${escapeText(report.snapshotAt || "--")}</dd></dl></header>
    ${renderOverallSection("数据质量", report.dataQualitySummary, true)}
    ${renderOverallSection("公司与基本面", report.companyAndFundamentals)}
    ${renderOverallSection("技术与资金", report.technicalAndCapital)}
    ${renderOverallSection("估值与行业", report.valuationAndIndustry)}
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

function bindAgentAction() {
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
      const report = unwrapReport(await stockApi.analyze(reportCode));
      if (state.currentCode !== reportCode || state.loadGeneration !== generation) return;
      state.institutionalReport = report;
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

function bindOverallReportAction() {
  const button = $("#run-overall-report");
  if (!button) return;
  button.addEventListener("click", async () => {
    const output = $("#overall-report-output");
    const reportCode = state.currentCode;
    const generation = state.loadGeneration;
    state.overallReportPhase = "loading";
    button.disabled = true;
    output.innerHTML = '<span class="source-status" data-status="DEGRADED"><span></span>正在生成总体报告</span><p>DeepSeek 正在读取当前股票的完整数据快照。</p>';
    try {
      const payload = await stockApi.overallReport(reportCode);
      if (state.currentCode !== reportCode || state.loadGeneration !== generation) return;
      state.overallReportResponse = payload;
      output.innerHTML = renderOverallReportResponse(payload);
      refreshIcons();
    } catch (error) {
      output.innerHTML = `<span class="source-status" data-status="UNAVAILABLE"><span></span>总体报告不可用</span><p>${escapeText(error.message || "请检查本地 DeepSeek 配置")}</p>`;
    } finally {
      state.overallReportPhase = "idle";
      button.disabled = false;
    }
  });
}

async function loadStock(code) {
  if (!/^\d{6}$/.test(code)) return;
  state.loadGeneration += 1;
  state.currentCode = code;
  state.overallReportPhase = "idle";
  state.institutionalReportPhase = "idle";
  state.overallReportResponse = null;
  state.institutionalReport = null;
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
