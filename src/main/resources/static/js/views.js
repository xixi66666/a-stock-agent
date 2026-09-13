/*
 * 通用研究数据和报告视图。
 *
 * 这里的职责是把后端的状态、事实、来源和限制转成可读 HTML；不做指标计算，不补造缺失
 * 数字。任何来自接口的文本都必须经过 escapeHtml，避免新闻标题或模型叙述变成 HTML。
 */
import { sectionIssues, sectionPayload } from "./api.js";
import { renderFundFlowSummary, renderPeerValuationTable } from "./derived-market-view.js";

const escapeHtml = (value) => {
  const raw = String(value ?? "");
  const scheme = raw.match(/^\s*([a-z][a-z0-9+.-]*):/i)?.[1]?.toLowerCase();
  if (scheme && scheme !== "http" && scheme !== "https") return "";
  return raw.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#039;");
};

const format = (value, digits = 2) => {
  if (value == null || value === "") return "--";
  const number = Number(value);
  return Number.isFinite(number) ? number.toLocaleString("zh-CN", { maximumFractionDigits: digits, minimumFractionDigits: digits }) : "--";
};
const money = (value) => {
  if (value == null || value === "") return "--";
  const number = Number(value);
  if (!Number.isFinite(number)) return "--";
  if (Math.abs(number) >= 1e8) return `${format(number / 1e8)} 亿`;
  if (Math.abs(number) >= 1e4) return `${format(number / 1e4)} 万`;
  return `${format(number, 0)} 元`;
};

function statusLabel(status) {
  return ({ HEALTHY: "正常", DEGRADED: "部分可用", STALE: "已过期", UNVERIFIED: "待核验", UNAVAILABLE: "不可用" })[status] || status || "未知";
}

export function renderUnavailable(title, section) {
  // 不可用状态显示 issues，而不是展示空卡片，让用户知道“没有数据”和“值为 0”不同。
  const issues = sectionIssues(section);
  return `<div class="state-message"><div><i data-lucide="circle-alert" aria-hidden="true"></i><h2>${escapeHtml(title)}暂不可用</h2><p>${escapeHtml(issues[0] || "数据源未返回有效数据")}</p></div></div>`;
}

export function renderLoading() {
  return `<div class="loading-grid" aria-label="正在加载研究数据">${Array.from({ length: 12 }, () => '<div class="skeleton" aria-hidden="true"></div>').join("")}</div>`;
}

function heading(kicker, title, section) {
  const issue = sectionIssues(section)[0];
  return `<header class="section-heading"><div><span class="section-kicker">${escapeHtml(kicker)}</span><h2>${escapeHtml(title)}</h2></div>${issue ? `<span class="section-notice"><i data-lucide="triangle-alert"></i>${escapeHtml(issue)}</span>` : ""}</header>`;
}

function emptyRow(label) {
  return `<div class="inline-empty"><i data-lucide="inbox" aria-hidden="true"></i><span>${escapeHtml(label)}</span></div>`;
}

function renderCapital(snapshot) {
  const flowSummary = sectionPayload(snapshot.fundFlowSummary);
  const capital = sectionPayload(snapshot.capital);
  if (!flowSummary && !capital) return renderUnavailable("资金与筹码", snapshot.fundFlowSummary || snapshot.capital);
  const latest = flowSummary?.latestDay || {};
  const metrics = [
    ["主力净流入", money(latest.mainNetYuan), latest.mainNetYuan], ["超大单净流入", money(latest.superLargeNetYuan), latest.superLargeNetYuan],
    ["大单净流入", money(latest.largeNetYuan), latest.largeNetYuan],
    ["中单净流入", money(latest.mediumNetYuan), latest.mediumNetYuan],
    ["小单净流入", money(latest.smallNetYuan), latest.smallNetYuan],
  ];
  return `<section>${heading(`${statusLabel(snapshot.fundFlowSummary?.status)} · ${flowSummary?.latestDate || "--"}`, "资金与筹码", snapshot.fundFlowSummary || snapshot.fundFlow)}
    <h3>最新日资金流</h3><div class="metric-grid">${metrics.map(([label, value, tone]) => `<div class="metric-tile"><span>${label}</span><strong class="${tone > 0 ? "is-up" : tone < 0 ? "is-down" : ""}">${value}</strong></div>`).join("")}</div>
    ${renderFundFlowSummary(snapshot.fundFlowSummary)}
    <div class="data-columns"><section><h3>融资融券</h3>${renderSimpleTable(capital?.marginHistory, [["日期","date"],["融资余额","financingBalanceYuan",money],["两融余额","totalBalanceYuan",money]], "暂无融资融券记录")}</section><section><h3>大宗交易</h3>${renderSimpleTable(capital?.blockTrades, [["日期","date"],["成交价","price",format],["溢价率","premiumPercent",(v)=>`${format(v)}%`]], "暂无大宗交易记录")}</section></div>
    <div class="data-columns"><section><h3>股东户数变化</h3>${renderSimpleTable(capital?.shareholderChanges, [["日期","date"],["股东户数","holderCount",(v)=>format(v,0)],["环比","changePercent",(v)=>`${format(v)}%`]], "暂无股东户数记录")}</section><section><h3>限售解禁</h3>${renderSimpleTable(capital?.unlocks, [["日期","date"],["类型","type"],["占总股本","totalShareRatio",(v)=>`${format(v)}%`]], "暂无近期解禁记录")}</section></div>
    ${capital?.components?.dividends ? `<section class="capital-dividends"><h3>分红与送股（同花顺优先）</h3>
      <p class="section-description">${escapeHtml(capital.components.dividends.provenance?.provider || "来源缺失")} · ${statusLabel(capital.components.dividends.status)}</p>
      ${renderSimpleTable(capital.dividends, [["除权除息日","exDate"],["每股现金（税前元）","cashPerShareYuan",format],["每十股送股","bonusPerTenShares",format],["每十股转增","transferPerTenShares",format]], "查询窗口内无分红送股事件")}
      <p class="section-description">${(capital.components.dividends.issues || []).map(escapeHtml).join("；")}</p>
      <p class="section-description">其余筹码来源：${escapeHtml(capital.components.legacyCapital?.provenance?.provider || "不可用")} · ${statusLabel(capital.components.legacyCapital?.status)}</p>
    </section>` : ""}
    ${capital?.components?.dragonTiger ? `<section class="capital-dragon-tiger"><h3>龙虎榜（同花顺优先）</h3>
      <p class="section-description">${escapeHtml(capital.components.dragonTiger.provenance?.provider || "来源缺失")} · ${statusLabel(capital.components.dragonTiger.status)}</p>
      ${renderSimpleTable(capital.dragonTigerRecords, [["交易日","date"],["原因","reason"],["净买入（元）","netBuyYuan",money],["换手率","turnoverPercent",(v)=>v == null ? "--" : `${format(v)}%`]], "最新交易日不在龙虎榜")}
      <p class="section-description">${(capital.components.dragonTiger.issues || []).map(escapeHtml).join("；")}</p>
    </section>` : ""}
  </section>`;
}

function renderSimpleTable(rows, columns, emptyLabel) {
  if (!Array.isArray(rows) || !rows.length) return emptyRow(emptyLabel);
  return `<div class="table-scroll"><table><thead><tr>${columns.map(([label]) => `<th>${label}</th>`).join("")}</tr></thead><tbody>${rows.slice(0, 12).map((row) => `<tr>${columns.map(([, key, formatter]) => `<td>${escapeHtml(formatter ? formatter(row[key]) : row[key] ?? "--")}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
}

function renderFundamentals(snapshot) {
  const section = snapshot.fundamentals;
  const data = sectionPayload(section);
  if (!data) return renderUnavailable("基本面", section);
  const entries = Object.entries(data.metrics || {});
  return `<section>${heading(`${statusLabel(section.status)} · 报告期 ${data.reportPeriod || "--"}`, "基本面质量", section)}<div class="metric-grid fundamentals-grid">${entries.map(([key, value]) => `<div class="metric-tile"><span>${escapeHtml(key)}</span><strong>${format(value)}</strong><small>同比 ${data.yearOverYearPercent?.[key] == null ? "--" : `${format(data.yearOverYearPercent[key])}%`}</small></div>`).join("") || emptyRow("暂无可展示财务指标")}</div></section>`;
}

function renderValuation(snapshot) {
  const quote = sectionPayload(snapshot.quote, {});
  const research = sectionPayload(snapshot.research, []);
  const metrics = [["市盈率 TTM",format(quote.peTtm)],["静态市盈率",format(quote.peStatic)],["市净率",format(quote.pb)],["总市值",money(quote.totalMarketValueYuan)]];
  const target = {
    code: snapshot.security?.code || quote.security?.code,
    name: quote.name,
    peTtm: quote.peTtm,
    pb: quote.pb,
    totalMarketValueYuan: quote.totalMarketValueYuan,
  };
  return `<section>${heading(`${statusLabel(snapshot.research?.status)} · 市场与机构口径`, "估值与机构预期", snapshot.research)}<div class="metric-grid">${metrics.map(([label,value])=>`<div class="metric-tile"><span>${label}</span><strong>${value}</strong></div>`).join("")}</div>${renderHithinkValuation(snapshot.valuation)}${renderPeerValuationTable(snapshot.industryValuation, target)}<section class="table-section"><h3>最新机构研报</h3>${renderSimpleTable(research, [["发布日期","publishedAt"],["机构","organization"],["评级","rating"],["标题","title"],["下年 EPS","nextYearEps",format]], "暂无近期机构研报")}</section></section>`;
}

function renderHithinkValuation(section) {
  if (!section) return "";
  const data = sectionPayload(section);
  if (!data) return `<section class="hithink-valuation table-section"><h3>同花顺估值快照</h3>${emptyRow(sectionIssues(section)[0] || "估值暂不可用")}</section>`;
  const source = section.provenance || {};
  const fields = [["市盈率 TTM", "peTtm"], ["市盈率 MRQ", "peMrq"], ["市净率 MRQ", "pbMrq"], ["市销率 TTM", "psTtm"], ["市现率 TTM", "pcfTtm"]];
  return `<section class="hithink-valuation table-section"><h3>同花顺估值快照</h3>
    <p>${escapeHtml(source.provider || "来源未知")} · ${escapeHtml(statusLabel(section.status))} · ${source.cached ? "缓存" : "本次获取"}</p>
    <div class="metric-grid">${fields.map(([label,key]) => `<div class="metric-tile"><span>${label}</span><strong>${data[key] == null ? "--" : format(data[key])}</strong></div>`).join("")}</div>
    <p class="muted">源时间 ${escapeHtml(source.providerTimestamp || "未提供")} · 获取时间 ${escapeHtml(source.fetchedAt || "未提供")}</p>
    <p class="muted">TTM 为滚动十二个月，MRQ 为最近季度口径；源时间是返回指标中的最新有效时间，不代表所有指标同时更新。负值原样保留，缺失项显示 --。</p>
    ${sectionIssues(section).map(issue => `<p class="muted">${escapeHtml(issue)}</p>`).join("")}</section>`;
}

function renderEvents(snapshot) {
  const news = sectionPayload(snapshot.news, []);
  const announcements = sectionPayload(snapshot.announcements, []);
  return `<section>${heading("公开披露与媒体资讯", "事件与资讯", snapshot.news)}<div class="feed-columns"><section><h3>相关新闻 <span>${news.length}</span></h3>${news.length ? `<div class="feed-list">${news.slice(0,12).map(item=>`<a href="${escapeHtml(item.url || "#")}" target="_blank" rel="noreferrer"><span>${escapeHtml(item.source || "资讯")}</span><strong>${escapeHtml(item.title)}</strong><time>${escapeHtml(item.publishedAt || "--")}</time></a>`).join("")}</div>` : emptyRow("暂无相关新闻")}</section><section><h3>公司公告 <span>${announcements.length}</span></h3>${announcements.length ? `<div class="feed-list">${announcements.slice(0,12).map(item=>`<a href="${escapeHtml(item.url || "#")}" target="_blank" rel="noreferrer"><span>${escapeHtml(item.type || "公告")}</span><strong>${escapeHtml(item.title)}</strong><time>${escapeHtml(item.publishedAt || "--")}</time></a>`).join("")}</div>` : emptyRow(sectionIssues(snapshot.announcements)[0] || "暂无公司公告")}</section></div></section>`;
}

function renderSources(snapshot) {
  const keys = [["quote","实时行情"],["bars","K 线"],["technical","技术指标"],["sectors","行业概念"],["fundFlow","资金流"],["capital","筹码事件"],["fundamentals","财务报表"],["valuation","同花顺估值"],["research","机构研报"],["news","新闻资讯"],["announcements","公司公告"]];
  const quality = snapshot.quality || {};
  return `<section>${heading(`总分 ${quality.total ?? "--"} / 100`, "数据来源与质量", snapshot.quote)}<div class="quality-components">${[["新鲜度",quality.freshness,30],["一致性",quality.consistency,30],["完整度",quality.completeness,25],["权威性",quality.authority,15]].map(([label,value,max])=>`<div><span>${label}</span><strong>${value ?? "--"} / ${max}</strong><progress max="${max}" value="${value || 0}"></progress></div>`).join("")}</div><div class="source-list">${keys.map(([key,label])=>{const section=snapshot[key]||{};const source=section.provenance;return `<article><span class="source-status" data-status="${section.status || "UNAVAILABLE"}"><span></span>${statusLabel(section.status)}</span><div><strong>${label}</strong><p>${escapeHtml(source?.provider || sectionIssues(section)[0] || "无可用来源")}</p></div><div class="source-time"><span>${source?.cached ? "缓存" : "实时请求"}</span><time>${source?.fetchedAt ? new Date(source.fetchedAt).toLocaleString("zh-CN",{hour12:false}) : "--"}</time></div></article>`;}).join("")}</div></section>`;
}

function renderFinRobot(snapshot) {
  return `<section class="finrobot-view">${heading("FINROBOT · EQUITY RESEARCH", "FinRobot 投研", null)}
    <div class="finrobot-layout">
      <aside class="finrobot-sidebar" aria-label="FinRobot 投研工作流">
        <div class="finrobot-controls">
          <span class="section-kicker">RESEARCH DESK · EVIDENCE BOUNDED</span>
          <h3>多角色研究流程</h3>
          <p>基于当前股票的规范化快照，依次组织证据、分析、估值、风险与报告综合。</p>
          <ol class="finrobot-steps" aria-label="FinRobot 投研阶段">
            <li class="finrobot-step" data-state="ready"><span class="finrobot-step-marker">1</span><span><strong>证据快照</strong><small>行情、财务、资金与事件</small></span><b>已就绪</b></li>
            <li class="finrobot-step" data-state="ready"><span class="finrobot-step-marker">2</span><span><strong>研究分析</strong><small>技术、基本面与机构预期</small></span><b>可运行</b></li>
            <li class="finrobot-step" data-state="next"><span class="finrobot-step-marker">3</span><span><strong>估值建模</strong><small>估值证据与条件式情景</small></span><b>待生成</b></li>
            <li class="finrobot-step" data-state="next"><span class="finrobot-step-marker">4</span><span><strong>风险复核</strong><small>冲突、缺失与失效条件</small></span><b>待生成</b></li>
            <li class="finrobot-step" data-state="next"><span class="finrobot-step-marker">5</span><span><strong>报告综合</strong><small>可追溯的投研结论</small></span><b>待生成</b></li>
          </ol>
          <button id="run-finrobot" class="primary-command finrobot-command" type="button"><i data-lucide="sparkles" aria-hidden="true"></i><span>运行 FinRobot 投研</span></button>
          <label class="finrobot-model-field" for="finrobot-model-select">
            <span>FinRobot 模型</span>
            <select id="finrobot-model-select" disabled>
              <option value="">正在加载可用模型</option>
            </select>
          </label>
          <small id="finrobot-model-help" class="finrobot-model-help">正在读取本地模型配置</small>
          <div id="finrobot-request-status" class="finrobot-request-status" aria-live="polite"></div>
        </div>
      </aside>
      <div id="finrobot-output" class="finrobot-output" aria-live="polite"><span class="source-status" data-status="UNAVAILABLE"><span></span>等待生成</span><p>运行后将显示 FinRobot 的公司概览、投资逻辑、估值、竞争、风险与事件研究。</p></div>
    </div>
  </section>`;
}

export function renderGenericView(view, snapshot) {
  // 视图选择只影响展示，所有数据仍来自同一规范化快照。
  return ({ capital: renderCapital, fundamentals: renderFundamentals, valuation: renderValuation, events: renderEvents, sources: renderSources, finrobot: renderFinRobot }[view] || renderSources)(snapshot);
}
