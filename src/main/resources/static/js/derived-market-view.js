/*
 * 市场派生展示组件：把同行估值和资金流窗口汇总渲染成独立区块。
 * 这些值已经由后端确定性计算；前端只格式化和展示，不重新计算投资结论。
 */
const escapeHtml = (value) => String(value ?? "")
  .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;").replaceAll("'", "&#039;");

const number = (value, digits = 2) => {
  if (value == null || value === "") return "--";
  const parsed = Number(value);
  return Number.isFinite(parsed)
    ? parsed.toLocaleString("zh-CN", { minimumFractionDigits: digits, maximumFractionDigits: digits })
    : "--";
};

const money = (value) => {
  if (value == null || value === "") return "--";
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return "--";
  if (Math.abs(parsed) >= 1e8) return `${number(parsed / 1e8)} 亿`;
  if (Math.abs(parsed) >= 1e4) return `${number(parsed / 1e4)} 万`;
  return `${number(parsed, 0)} 元`;
};

const percent = (value) => value == null || value === "" ? "--" : `${number(value)}%`;
const reasonLabel = (reason) => ({
  MARKET_CAP_NEARBY: "市值接近",
  INDUSTRY_LEADER: "行业龙头",
})[reason] || reason;

function unavailable(title, section) {
  const issue = section?.issues?.[0] || "数据不可用";
  return `<section class="derived-market-block"><h4>${escapeHtml(title)}</h4><p class="muted">${escapeHtml(issue)}</p></section>`;
}

export function renderPeerValuationTable(section, target = {}) {
  // 同行选择理由和缺失状态必须随表格保留，不能只显示一个看似精确的 PE/PB 数字。
  const data = section?.payload;
  if (!data) return section ? unavailable("同行估值对比", section) : "";
  const targetRow = {
    code: target.code || "--",
    name: target.name || "目标股票",
    peDynamic: target.peTtm ?? data.targetPe,
    pb: target.pb ?? data.targetPb,
    totalMarketValueYuan: target.totalMarketValueYuan,
    selectionReasons: ["TARGET"],
  };
  const rows = [targetRow, ...(Array.isArray(data.selectedPeers) ? data.selectedPeers : [])];
  const body = rows.map((peer, index) => {
    const tags = index === 0
      ? '<span class="peer-selection-tag">目标</span>'
      : (peer.selectionReasons || []).map((reason) =>
          `<span class="peer-selection-tag">${escapeHtml(reasonLabel(reason))}</span>`).join("");
    return `<tr class="${index === 0 ? "target-row" : ""}"><td>${escapeHtml(peer.code || "--")}</td>`
      + `<td>${escapeHtml(peer.name || "--")}</td><td><span class="peer-selection-tags">${tags}</span></td>`
      + `<td>${number(peer.peDynamic)}</td><td>${number(peer.pb)}</td><td>${money(peer.totalMarketValueYuan)}</td>`
      + `<td>${index === 0 ? "--" : percent(peer.targetPePremiumPercent)}</td>`
      + `<td>${index === 0 ? "--" : percent(peer.targetPbPremiumPercent)}</td></tr>`;
  }).join("");
  return `<section class="derived-market-block"><h4>同行估值对比</h4>`
    + `<div class="table-scroll"><table aria-label="同行估值对比"><thead><tr>`
    + `<th>代码</th><th>名称</th><th>筛选</th><th>动态 PE</th><th>PB</th><th>总市值</th>`
    + `<th>目标 PE 溢价</th><th>目标 PB 溢价</th></tr></thead><tbody>${body}</tbody></table></div></section>`;
}

export function renderFundFlowSummary(section) {
  // 资金流摘要按窗口展示 latest、5d、20d 等范围，避免把不同时间尺度混在一起。
  const summary = section?.payload;
  if (!summary) return section ? unavailable("资金流窗口汇总", section) : "";
  const rows = [["近 5 日", summary.fiveDay], ["近 20 日", summary.twentyDay]]
    .map(([label, window]) => `<tr><td>${label}</td>`
      + `<td>${money(window?.mainNetYuan)}</td><td>${money(window?.superLargeNetYuan)}</td>`
      + `<td>${money(window?.largeNetYuan)}</td><td>${money(window?.mediumNetYuan)}</td>`
      + `<td>${money(window?.smallNetYuan)}</td>`
      + `<td>${window ? `${window.sampleDays} / ${window.requestedDays} 日` : "--"}</td></tr>`).join("");
  return `<section class="derived-market-block"><h4>资金流窗口汇总</h4>`
    + `<div class="table-scroll"><table class="flow-window-table" aria-label="资金流窗口汇总">`
    + `<thead><tr><th>窗口</th><th>主力</th><th>超大单</th><th>大单</th><th>中单</th><th>小单</th><th>样本</th></tr></thead>`
    + `<tbody>${rows}</tbody></table></div></section>`;
}
