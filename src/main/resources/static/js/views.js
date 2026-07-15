import { sectionIssues, sectionPayload } from "./api.js";

const escapeHtml = (value) => String(value ?? "")
  .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;").replaceAll("'", "&#039;");

export function renderUnavailable(title, section) {
  const issues = sectionIssues(section);
  return `<div class="state-message"><div><i data-lucide="circle-alert" aria-hidden="true"></i><h2>${escapeHtml(title)}暂不可用</h2><p>${escapeHtml(issues[0] || "数据源未返回有效数据")}</p></div></div>`;
}

export function renderLoading() {
  return `<div class="loading-grid" aria-label="正在加载研究数据">${Array.from({ length: 12 }, () => '<div class="skeleton" aria-hidden="true"></div>').join("")}</div>`;
}

export function renderGenericView(view, snapshot) {
  const mapping = {
    capital: ["资金与筹码", snapshot.capital],
    fundamentals: ["基本面", snapshot.fundamentals],
    valuation: ["估值与机构预期", snapshot.research],
    events: ["事件与资讯", snapshot.news],
    sources: ["数据来源与质量", snapshot.quote],
    agent: ["Agent 分析", null],
  };
  const [title, section] = mapping[view] || ["研究数据", null];
  if (view === "agent") {
    return `<div class="state-message"><div><i data-lucide="sparkles" aria-hidden="true"></i><h2>${title}</h2><p>模型未配置时，行情与技术研究功能仍可独立使用。</p></div></div>`;
  }
  const payload = sectionPayload(section);
  if (payload == null) return renderUnavailable(title, section);
  return `<section class="data-section"><header><div><span class="section-kicker">${escapeHtml(section.status)}</span><h2>${title}</h2></div></header><pre class="raw-data">${escapeHtml(JSON.stringify(payload, null, 2))}</pre></section>`;
}
