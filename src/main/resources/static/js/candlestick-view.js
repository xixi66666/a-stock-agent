/*
 * THESIS: 把蜡烛图研判呈现为可逐项复核的证据链，而不是孤立的“看涨/看跌”标签。
 * OWN-WORLD: 继承浅色研究台、细分隔线、紫色交互；红涨绿跌只用于方向并始终配文字。
 * STORY: 先读趋势与主警告，再核对七步证据、汇聚因子、失效位与风险报偿。
 * FIRST VIEWPORT: 周期控制与主结论同屏，形态索引紧邻完整证据，指标矩阵在其后继续。
 * FORM: 现有技术页的 Operate 模式扩展；不改变全局视觉系统。
 */
import { sectionIssues, sectionPayload } from "./api.js";

const directionLabels = { BULLISH: "看涨警告", BEARISH: "看跌警告", NEUTRAL: "暂无方向" };
const trendLabels = { UP: "上升", DOWN: "下降", SIDEWAYS: "横向", INSUFFICIENT: "样本不足" };
const confirmationLabels = {
  CONFIRMED: "已确认", AWAITING_CONFIRMATION: "等待确认", NOT_REQUIRED: "形态已完成", INVALIDATED: "未获确认",
};
const factorLabels = { CANDLESTICK: "蜡烛图", TREND: "趋势", LOCATION: "位置", MOMENTUM: "动量", VOLUME: "成交量", LEVEL: "结构位" };
const roleLabels = { SUPPORT: "支撑", RESISTANCE: "阻挡" };
const levelStatusLabels = { ACTIVE: "有效", TESTED: "已测试", BROKEN: "已失效" };

const escapeHtml = (value) => String(value ?? "")
  .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;").replaceAll("'", "&#039;");

function number(value, digits = 2) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed.toLocaleString("zh-CN", { minimumFractionDigits: digits, maximumFractionDigits: digits }) : "--";
}

function direction(value) {
  return `<span class="candlestick-direction" data-direction="${escapeHtml(value || "NEUTRAL")}"><span aria-hidden="true"></span>${escapeHtml(directionLabels[value] || value || "暂无方向")}</span>`;
}

function timeframeControls(selected, disabled = false) {
  return `<div class="candlestick-timeframes" role="group" aria-label="蜡烛图周期">
    ${[["DAILY", "日线"], ["WEEKLY", "周线"], ["MONTHLY", "月线"]].map(([value, label]) =>
    `<button type="button" data-candlestick-timeframe="${value}" aria-pressed="${selected === value}"${disabled ? " disabled" : ""}>${label}</button>`).join("")}
  </div>`;
}

function loadingPanel(timeframe) {
  return `<div class="candlestick-loading" role="status"><span class="candlestick-loading-mark" aria-hidden="true"></span><div><strong>正在构建证据链</strong><p>校验完整周期、识别形态并计算相互验证。</p></div></div>${timeframeControls(timeframe, true)}`;
}

function unavailablePanel(section, timeframe) {
  const issue = sectionIssues(section)[0] || "有效 K 线历史不足，无法完成严格形态确认";
  return `<div class="candlestick-unavailable" role="status"><i data-lucide="circle-alert" aria-hidden="true"></i><div><strong>蜡烛图研判暂不可用</strong><p>${escapeHtml(issue)}</p></div></div>${timeframeControls(timeframe)}`;
}

function signalButton(signal, selected) {
  return `<button type="button" class="candlestick-signal${selected ? " is-selected" : ""}" data-candlestick-signal="${escapeHtml(signal.id)}" aria-pressed="${selected}">
    <span><strong>${escapeHtml(signal.name)}</strong><small>${escapeHtml(signal.family)} · ${escapeHtml(signal.endDate)}</small></span>
    <span class="candlestick-signal-meta">${escapeHtml(confirmationLabels[signal.confirmationStatus] || signal.confirmationStatus)}<b>${escapeHtml(signal.evidenceScore)}</b></span>
  </button>`;
}

function evidenceItem(label, content, tone = "") {
  return `<div class="candlestick-evidence-item"${tone ? ` data-tone="${tone}"` : ""}><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(content || "--")}</dd></div>`;
}

function signalDetail(signal) {
  if (!signal) return `<div class="candlestick-no-signal"><strong>没有满足规则阈值的近期形态</strong><p>这不是中性行情的保证；模块不会根据相似轮廓强行命名。</p></div>`;
  return `<article class="candlestick-signal-detail" data-active-candlestick-signal="${escapeHtml(signal.id)}">
    <header><div>${direction(signal.direction)}<h3>${escapeHtml(signal.name)}</h3><p>${escapeHtml(signal.englishName)} · ${escapeHtml(signal.startDate)}—${escapeHtml(signal.endDate)}</p></div><span class="candlestick-grade"><b>${escapeHtml(signal.evidenceScore)}</b><small>证据分 / 非概率</small></span></header>
    <dl class="candlestick-evidence-grid">
      ${evidenceItem("前置趋势", signal.trendEvidence)}
      ${evidenceItem("相对位置", signal.locationEvidence)}
      ${evidenceItem("后续确认", `${confirmationLabels[signal.confirmationStatus] || signal.confirmationStatus}：${signal.confirmationEvidence}`, signal.confirmationStatus === "CONFIRMED" ? "confirmed" : "pending")}
      ${evidenceItem("失效位", `${number(signal.invalidationPrice)} 元；${signal.invalidationRule}`, "risk")}
    </dl>
    <section class="candlestick-construction"><h4>形态构成</h4><ol>${(signal.constructionEvidence || []).map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ol><p>章节依据：${escapeHtml(signal.sourceChapter)}</p></section>
  </article>`;
}

function summary(analysis) {
  const confluence = analysis.confluence || {};
  return `<div class="candlestick-summary">
    <div class="candlestick-summary-conclusion"><span>当前主结论</span>${direction(confluence.direction)}<p>${escapeHtml(confluence.conclusion || "暂无可用结论")}</p></div>
    <div class="candlestick-score"><strong>${escapeHtml(confluence.score ?? 0)}</strong><span>/ 100</span><small>${escapeHtml(confluence.grade || "NO_SIGNAL")} · 证据覆盖度</small></div>
    <dl class="candlestick-trends"><div><dt>短期趋势</dt><dd>${escapeHtml(trendLabels[analysis.trend?.shortTerm] || "--")} <small>${number(analysis.trend?.shortReturnPercent)}%</small></dd></div><div><dt>主要趋势</dt><dd>${escapeHtml(trendLabels[analysis.trend?.primary] || "--")} <small>距 SMA20 ${number(analysis.trend?.closeVsSma20Percent)}%</small></dd></div><div><dt>分析截止</dt><dd>${escapeHtml(analysis.asOf || "--")} <small>${escapeHtml(analysis.analyzedBars)} 根完整 K 线</small></dd></div></dl>
  </div>`;
}

function confluenceFactors(analysis) {
  const factors = analysis.confluence?.factors || [];
  return `<section class="candlestick-factors" aria-labelledby="candlestick-factors-title"><header><h3 id="candlestick-factors-title">多技术相互验证</h3><p>同价位附近的独立证据越多，形态越值得关注；未同向项也完整保留。</p></header><div class="candlestick-factor-list">${factors.map((factor) => `<div class="candlestick-factor" data-aligned="${factor.aligned}"><span>${escapeHtml(factorLabels[factor.kind] || factor.kind)}</span><strong>${escapeHtml(factor.label)}</strong><p>${escapeHtml(factor.evidence)}</p><small>${factor.aligned ? "同向" : "未同向"} · 权重 ${escapeHtml(factor.weight)}</small></div>`).join("") || '<p class="candlestick-inline-empty">暂无可用汇聚因子</p>'}</div></section>`;
}

function riskAndLevels(analysis) {
  const risk = analysis.risk || {};
  const levels = analysis.levels || [];
  return `<div class="candlestick-structure-grid">
    <section class="candlestick-risk"><header><h3>风险报偿</h3><span>${escapeHtml(risk.quality || "INSUFFICIENT")}</span></header><dl><div><dt>参考价</dt><dd>${number(risk.entryReference)} 元</dd></div><div><dt>失效位</dt><dd>${number(risk.invalidationPrice)} 元</dd></div><div><dt>每股风险</dt><dd>${number(risk.riskPerShare)} 元</dd></div><div><dt>结构目标</dt><dd>${number(risk.targetReference)} 元</dd></div><div><dt>报偿 / 风险</dt><dd>${number(risk.rewardRiskRatio)} R</dd></div></dl><ul>${(risk.notes || []).map((note) => `<li>${escapeHtml(note)}</li>`).join("")}</ul></section>
    <section class="candlestick-levels"><header><h3>支撑、阻挡与失效</h3><span>${levels.length} 个结构位</span></header><div class="candlestick-level-list">${levels.map((level) => `<div><span>${escapeHtml(roleLabels[level.role] || level.role)}</span><strong>${number(level.lower)}${Number(level.upper) !== Number(level.lower) ? `—${number(level.upper)}` : ""}</strong><p>${escapeHtml(level.origin)}</p><small>${escapeHtml(levelStatusLabels[level.status] || level.status)} · ${escapeHtml(level.validationRule)}</small></div>`).join("") || '<p class="candlestick-inline-empty">暂无可验证结构位</p>'}</div></section>
  </div>`;
}

function methodology(analysis, provenance) {
  const method = analysis.methodology || {};
  const notes = (method.knowledge || []).map(entry => `<li><a href="/?knowledge=${encodeURIComponent(entry.id)}#book-knowledge" data-knowledge-id="${escapeHtml(entry.id)}">${escapeHtml(entry.title)}</a><span>${escapeHtml(entry.chapter)} · ${escapeHtml(entry.summary)}</span></li>`).join("");
  return `<details class="candlestick-method"><summary><span><strong>方法、章节与限制</strong><small>${escapeHtml(method.ruleVersion || "--")} · ${escapeHtml(provenance?.provider || "未知来源")}</small></span><i data-lucide="chevron-down" aria-hidden="true"></i></summary>
    <div class="candlestick-method-body">
      <section><h4>七步分析顺序</h4><ol>${(method.analysisSequence || []).map(item => `<li>${escapeHtml(item)}</li>`).join("")}</ol></section>
      <section><h4>原书章节依据</h4><ul>${(method.bookReferences || []).map(item => `<li><strong>${escapeHtml(item.chapter)}</strong><span>${escapeHtml(item.topic)}</span></li>`).join("")}</ul></section>
      <section><h4>透明阈值</h4><dl>${Object.entries(method.transparentThresholds || {}).map(([key, value]) => `<div><dt>${escapeHtml(key)}</dt><dd>${escapeHtml(value)}</dd></div>`).join("")}</dl></section>
      <section><h4>边界</h4><ul>${(analysis.limitations || []).map(item => `<li>${escapeHtml(item)}</li>`).join("")}</ul><p>${escapeHtml(method.scoreMeaning || "")}</p></section>
      ${notes ? `<section><h4>相关知识笔记</h4><ul>${notes}</ul></section>` : ""}
    </div></details>`;
}

export function renderCandlestickWorkbench(section, { timeframe = "DAILY", loading = false } = {}) {
  const analysis = sectionPayload(section);
  const body = loading ? loadingPanel(timeframe) : !analysis ? unavailablePanel(section, timeframe) : `${timeframeControls(timeframe)}${summary(analysis)}<div class="candlestick-primary-grid"><nav class="candlestick-signal-list" aria-label="近期蜡烛图形态">${(analysis.signals || []).map((signal, index) => signalButton(signal, index === 0)).join("") || '<p class="candlestick-inline-empty">近期无严格命名形态</p>'}</nav><div class="candlestick-detail-slot">${signalDetail(analysis.signals?.[0])}</div></div>${confluenceFactors(analysis)}${riskAndLevels(analysis)}${methodology(analysis, section?.provenance)}`;
  return `<section class="candlestick-workbench" aria-labelledby="candlestick-title"><header class="candlestick-heading"><div><span>OHLCV · EVIDENCE FIRST</span><h2 id="candlestick-title">尼森蜡烛图研判</h2><p>形态是趋势变化的警告；结论必须经过位置、确认、结构与多技术证据复核。</p></div>${analysis?.completion?.latestPeriodComplete === false ? `<span class="candlestick-period-warning"><i data-lucide="clock-3" aria-hidden="true"></i>${escapeHtml(analysis.completion.note)}</span>` : ""}</header><div class="candlestick-body">${body}</div></section>`;
}

export function activateCandlestickWorkbench(section, { onTimeframeChange } = {}, root = document) {
  const analysis = sectionPayload(section);
  root.querySelectorAll("[data-candlestick-timeframe]").forEach((button) => button.addEventListener("click", () => onTimeframeChange?.(button.dataset.candlestickTimeframe)));
  if (!analysis) return null;
  const byId = new Map((analysis.signals || []).map((signal) => [signal.id, signal]));
  root.querySelectorAll("[data-candlestick-signal]").forEach((button) => button.addEventListener("click", () => {
    const signal = byId.get(button.dataset.candlestickSignal);
    root.querySelectorAll("[data-candlestick-signal]").forEach((item) => {
      const selected = item === button;
      item.classList.toggle("is-selected", selected);
      item.setAttribute("aria-pressed", String(selected));
    });
    const slot = root.querySelector(".candlestick-detail-slot");
    if (slot) slot.innerHTML = signalDetail(signal);
  }));
  return { dispose() {} };
}
