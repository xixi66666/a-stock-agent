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
  if (state.currentView === "agent") bindAgentAction();
}

function escapeText(value) {
  return String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

function bindAgentAction() {
  const button = $("#run-agent");
  if (!button) return;
  button.addEventListener("click", async () => {
    const output = $("#agent-output");
    button.disabled = true;
    output.innerHTML = '<span class="source-status" data-status="DEGRADED"><span></span>正在综合</span><p>Agent 正在调用受限股票研究工具。</p>';
    try {
      const report = await stockApi.analyze(state.currentCode);
      const evidence = [...(report.technicalEvidence || []), ...(report.capitalAndFundamentalEvidence || [])];
      output.innerHTML = `<span class="source-status" data-status="HEALTHY"><span></span>报告完成</span>
        <h3>${escapeText(report.factualSummary || "结构化研究摘要")}</h3>
        <p>${escapeText(report.trendAndRegime || "")}</p>
        ${evidence.length ? `<ul>${evidence.map((item) => `<li>${escapeText(item)}</li>`).join("")}</ul>` : ""}
        <strong>${escapeText(report.conclusion || "")}</strong>
        <small>${escapeText(report.disclaimer || "仅供学习研究，不构成投资建议")}</small>`;
    } catch (error) {
      output.innerHTML = `<span class="source-status" data-status="UNAVAILABLE"><span></span>Agent 不可用</span><p>${escapeText(error.message || "请检查本地模型配置")}</p>`;
    } finally {
      button.disabled = false;
    }
  });
}

async function loadStock(code) {
  if (!/^\d{6}$/.test(code)) return;
  state.currentCode = code;
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

function renderSearchResults(results) {
  const panel = $("#search-results");
  panel.innerHTML = results.length ? results.map((item) => `<button class="search-result" type="button" role="option" data-code="${item.code}"><strong>${item.name}</strong><span>${item.code} · ${marketName(item.exchange)}</span></button>`).join("") : '<div class="search-result"><span>未找到匹配股票</span></div>';
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
  try { renderSearchResults(await stockApi.search(normalized)); }
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
