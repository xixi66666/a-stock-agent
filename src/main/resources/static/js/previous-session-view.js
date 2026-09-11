// 最近已收盘日线复盘：继承研究台浅色与细分隔线，用真实比例图配合可复核的数字。
// 左图右读，窄屏上下排列；本模块只展示后端计算结果，不在浏览器推导交易信号。
const escape = value => String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;")
  .replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;");
const numeric = value => value !== null && value !== undefined && value !== "" && Number.isFinite(Number(value));
const format = (value, suffix = "") => numeric(value)
  ? `${Number(value).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}${suffix}` : "不可计算";

function bookExcerpts(excerpts) {
  if (!Array.isArray(excerpts) || !excerpts.length) return "";
  return `<div class="previous-session-excerpts"><strong>书中原文（节选）</strong>${excerpts.map(excerpt => `
    <blockquote><p>${escape(excerpt.text)}</p><cite>${escape(excerpt.author)}《${escape(excerpt.bookTitle)}》 · ${escape(excerpt.chapter)}</cite></blockquote>
    <p class="previous-session-excerpt-scope">引用说明：${escape(excerpt.scope)}</p>`).join("")}</div>`;
}

function candleDiagram(bar) {
  if (![bar.open, bar.high, bar.low, bar.close].every(numeric)) return "<p>缺少完整价格，无法绘图</p>";
  const [open, high, low, close] = [bar.open, bar.high, bar.low, bar.close].map(Number);
  if (high < Math.max(open, close) || low > Math.min(open, close) || high < low) return "<p>价格关系异常，无法绘图</p>";
  const y = price => high === low ? 90 : 20 + (high - price) / (high - low) * 140;
  const top = y(Math.max(open, close));
  const height = Math.abs(y(open) - y(close));
  const tone = close > open ? "up" : close < open ? "down" : "flat";
  return `<svg class="previous-session-candle" data-tone="${tone}" width="120" height="180" color="${tone === "up" ? "#d83b53" : tone === "down" ? "#1f8a65" : "#5a5852"}" viewBox="0 0 120 180" role="img" aria-label="${escape(bar.date)} 日线结构，开 ${format(open)}，高 ${format(high)}，低 ${format(low)}，收 ${format(close)}">
    <line x1="60" y1="${y(high)}" x2="60" y2="${y(low)}" stroke="currentColor" stroke-width="2" />
    ${height > 0 ? `<rect x="44" y="${top}" width="32" height="${height}" fill="currentColor" />`
      : `<line x1="44" y1="${top}" x2="76" y2="${top}" stroke="currentColor" stroke-width="2" />`}
  </svg>`;
}

export function renderPreviousSession(review, section) {
  const header = `<header class="previous-session-heading"><div><h3 id="previous-session-title">最近已收盘日线分析</h3><p>收盘前看前一交易日，15:05 后优先使用当天收盘日线</p></div>${review?.bar?.date ? `<time datetime="${escape(review.bar.date)}">${escape(review.bar.date)} · 日线复盘</time>` : ""}</header>`;
  if (!review?.bar) return `<section class="previous-session" aria-labelledby="previous-session-title">${header}<p>暂无可用的完整日线，暂不能进行收盘复盘。</p></section>`;
  const bar = review.bar;
  const states = { CONFIRMED: "已确认", AWAITING_CONFIRMATION: "等待后续确认", NOT_REQUIRED: "形态完成", INVALIDATED: "已失效" };
  const matches = review.signals || [];
  const issues = (section?.issues || []).map(escape).join("；");
  return `<section class="previous-session" aria-labelledby="previous-session-title">${header}
    <div class="previous-session-layout">
      <figure class="previous-session-figure">${candleDiagram(bar)}<figcaption>${escape(review.candleType)} · 实体颜色按开收价判断</figcaption></figure>
      <div class="previous-session-reading">
        <div class="previous-session-verdict"><strong>${escape(review.shape)}</strong><span>${escape(review.candleType)}</span></div>
        <p class="previous-session-interpretation">${escape(review.interpretation)}</p>
        ${bookExcerpts(review.bookExcerpts)}
        <dl class="previous-session-prices">${[["开盘", bar.open], ["最高", bar.high], ["最低", bar.low], ["收盘", bar.close]].map(([label, value]) => `<div><dt>${label}</dt><dd>${format(value)} <small>元</small></dd></div>`).join("")}</dl>
        <dl class="previous-session-metrics">${[["实体 / 振幅", review.bodyPercent, "%"], ["上影 / 振幅", review.upperShadowPercent, "%"], ["下影 / 振幅", review.lowerShadowPercent, "%"], ["涨跌 / 前收", review.changePercent, "%"], ["成交量 / 前20日均量", review.volumeRatio, " 倍"]].map(([label, value, unit]) => `<div><dt>${label}</dt><dd>${format(value, unit)}</dd></div>`).join("")}</dl>
      </div>
    </div>
    <dl class="previous-session-context"><div><dt>前置趋势与位置</dt><dd>${escape(review.trendEvidence)}；${escape(review.locationEvidence)}</dd></div><div><dt>后续观察</dt><dd>${escape(review.followUp)}</dd></div></dl>
    <div class="previous-session-matches"><strong>该日形态</strong><span>${matches.length ? matches.map(signal => `${escape(signal.name)}（${escape(states[signal.confirmationStatus] || signal.confirmationStatus)}）`).join(" · ") : "未识别到截至该日完成的命名形态，以上为单根结构解读。"}</span></div>
    <details class="previous-session-notes"><summary>日期、来源与判读口径</summary><p>${escape(review.dateNote)}</p><p>来源：${escape(section?.provenance?.provider || "未知")} · 数据状态：${escape(({ HEALTHY: "正常", DEGRADED: "降级", STALE: "过期", UNVERIFIED: "未验证" })[section?.status] || "未知")}${issues ? ` · ${issues}` : ""}</p><p>基础结构依据尼森第三章；此处为工程化解读。实体≤5%为十字轮廓，≤30%为小实体，≥70%为实体主导，单侧影线≥60%为长影线。比例均以当日高低振幅为分母，长实体不等于相对历史的大阳线或大阴线。阴阳取决于开收价，日涨跌取决于前收价；该复盘固定使用日线。</p></details>
  </section>`;
}
