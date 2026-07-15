import { sectionIssues, sectionPayload } from "./api.js";

const stateLabels = {
  STRONG: "偏强", WEAK: "偏弱", NEUTRAL: "中性", OVERBOUGHT: "超买",
  OVERSOLD: "超卖", EXPANDING: "扩张", CONTRACTING: "收敛", INSUFFICIENT: "样本不足",
};

const escapeHtml = (value) => String(value ?? "")
  .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;").replaceAll("'", "&#039;");

function formatValue(value, unit = "") {
  const number = Number(value);
  if (!Number.isFinite(number)) return "--";
  const digits = Math.abs(number) >= 1000 ? 0 : Math.abs(number) >= 100 ? 1 : 2;
  return `${number.toLocaleString("zh-CN", { maximumFractionDigits: digits, minimumFractionDigits: digits })}${unit || ""}`;
}

function sparkline(values) {
  const clean = (values || []).map(Number).filter(Number.isFinite);
  if (clean.length < 2) return '<span class="sparkline-empty">--</span>';
  const min = Math.min(...clean);
  const range = Math.max(...clean) - min || 1;
  const points = clean.map((value, index) => {
    const x = index / (clean.length - 1) * 100;
    const y = 30 - ((value - min) / range) * 26;
    return `${x.toFixed(2)},${y.toFixed(2)}`;
  }).join(" ");
  return `<svg class="sparkline" viewBox="0 0 100 32" preserveAspectRatio="none" aria-hidden="true"><polyline points="${points}" vector-effect="non-scaling-stroke"></polyline></svg>`;
}

function parameters(card) {
  const entries = Object.entries(card.parameters || {});
  return entries.length ? entries.map(([key, value]) => `${key} ${value}`).join(" · ") : "标准参数";
}

function cardTemplate(card, index) {
  return `<button class="indicator-card${index === 0 ? " is-selected" : ""}" type="button" data-indicator-id="${escapeHtml(card.id)}" aria-pressed="${index === 0}">
    <span class="indicator-card-head"><span class="indicator-group">${escapeHtml(card.group)}</span><span class="indicator-status" data-state="${escapeHtml(card.state)}"><span></span>${escapeHtml(stateLabels[card.state] || card.state)}</span></span>
    <span class="indicator-name">${escapeHtml(card.name)}</span>
    <span class="indicator-value">${escapeHtml(formatValue(card.value, card.unit))}</span>
    <span class="indicator-parameters">${escapeHtml(parameters(card))}</span>
    ${sparkline(card.series)}
    <span class="indicator-trigger">${escapeHtml(card.trigger)}</span>
    <span class="indicator-meta"><span>${escapeHtml(card.calculatedAt || "--")}</span><span>${escapeHtml(card.sectionStatus || "UNVERIFIED")}</span></span>
  </button>`;
}

function chartOptions(card) {
  const values = (card.series || []).map(Number).filter(Number.isFinite);
  const data = values.length ? values : [Number(card.value) || 0];
  return {
    animationDuration: 220,
    grid: { left: 52, right: 18, top: 22, bottom: 34 },
    tooltip: { trigger: "axis", backgroundColor: "#26251e", borderWidth: 0, textStyle: { color: "#ffffff", fontSize: 12 } },
    xAxis: { type: "category", boundaryGap: false, data: data.map((_, index) => String(index + 1)), axisLine: { lineStyle: { color: "#cfcdc4" } }, axisTick: { show: false }, axisLabel: { color: "#807d72", interval: Math.max(1, Math.floor(data.length / 6)) } },
    yAxis: { type: "value", scale: true, splitNumber: 4, axisLabel: { color: "#807d72" }, splitLine: { lineStyle: { color: "#e6e5e0" } } },
    series: [{ name: card.name, type: "line", data, showSymbol: false, smooth: false, lineStyle: { color: "#7158d9", width: 2 }, areaStyle: { color: "rgba(113,88,217,.08)" } }],
  };
}

export function renderTechnicalView(section) {
  const technical = sectionPayload(section);
  const cards = technical?.cards || [];
  if (!cards.length) {
    const issue = sectionIssues(section)[0] || "有效 K 线不足，无法计算技术指标";
    return `<div class="state-message"><div><i data-lucide="circle-alert" aria-hidden="true"></i><h2>技术分析暂不可用</h2><p>${escapeHtml(issue)}</p></div></div>`;
  }
  const groups = [...new Set(cards.map((card) => card.group))];
  return `<section class="technical-workbench">
    <header class="section-heading">
      <div><span class="section-kicker">${escapeHtml(technical.timeframe)} · ${cards.length} INDICATORS</span><h2>技术指标矩阵</h2></div>
      <div class="group-filters" role="group" aria-label="指标分组">
        <button type="button" class="is-active" data-filter="all">全部</button>
        ${groups.map((group) => `<button type="button" data-filter="${escapeHtml(group)}">${escapeHtml(group)}</button>`).join("")}
      </div>
    </header>
    <div class="technical-detail">
      <div class="technical-detail-copy"><span id="detail-group" class="section-kicker">${escapeHtml(cards[0].group)}</span><h3 id="detail-name">${escapeHtml(cards[0].name)}</h3><strong id="detail-value">${escapeHtml(formatValue(cards[0].value, cards[0].unit))}</strong><p id="detail-trigger">${escapeHtml(cards[0].trigger)}</p></div>
      <div id="technical-chart" role="img" aria-label="${escapeHtml(cards[0].name)} 最近序列图"></div>
    </div>
    <div class="indicator-grid">${cards.map(cardTemplate).join("")}</div>
  </section>`;
}

export function activateTechnicalView(section, root = document) {
  const technical = sectionPayload(section);
  const cards = technical?.cards || [];
  if (!cards.length) return null;
  const chartElement = root.querySelector("#technical-chart");
  const chart = chartElement && window.echarts ? window.echarts.init(chartElement, null, { renderer: "canvas" }) : null;
  const byId = new Map(cards.map((card) => [card.id, card]));

  function select(card) {
    root.querySelectorAll(".indicator-card").forEach((element) => {
      const selected = element.dataset.indicatorId === card.id;
      element.classList.toggle("is-selected", selected);
      element.setAttribute("aria-pressed", String(selected));
    });
    root.querySelector("#detail-group").textContent = card.group;
    root.querySelector("#detail-name").textContent = card.name;
    root.querySelector("#detail-value").textContent = formatValue(card.value, card.unit);
    root.querySelector("#detail-trigger").textContent = card.trigger;
    chartElement?.setAttribute("aria-label", `${card.name} 最近序列图`);
    chart?.setOption(chartOptions(card), true);
  }

  root.querySelectorAll(".indicator-card").forEach((element) => element.addEventListener("click", () => select(byId.get(element.dataset.indicatorId))));
  root.querySelectorAll("[data-filter]").forEach((button) => button.addEventListener("click", () => {
    root.querySelectorAll("[data-filter]").forEach((item) => item.classList.toggle("is-active", item === button));
    root.querySelectorAll(".indicator-card").forEach((element) => {
      const card = byId.get(element.dataset.indicatorId);
      element.hidden = button.dataset.filter !== "all" && card.group !== button.dataset.filter;
    });
  }));
  select(cards[0]);
  const resize = () => chart?.resize();
  window.addEventListener("resize", resize, { passive: true });
  return { dispose() { window.removeEventListener("resize", resize); chart?.dispose(); } };
}
