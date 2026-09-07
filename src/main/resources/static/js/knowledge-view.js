/* 书本查询独立于行情加载；返回笔记是方法依据，不替代当前数据。 */
const escapeHtml = value => String(value ?? "").replaceAll("&", "&amp;")
  .replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;");

function entryView(entry) {
  const items = values => (values || []).map(value => `<li>${escapeHtml(value)}</li>`).join("");
  return `<article class="knowledge-entry"><header><h3>${escapeHtml(entry.title)}</h3>
    <a href="/?knowledge=${encodeURIComponent(entry.id)}#book-knowledge" data-knowledge-id="${escapeHtml(entry.id)}">查看笔记链接</a></header>
    <p class="knowledge-citation">${escapeHtml(entry.bookTitle)} · ${escapeHtml(entry.chapter)} · ${escapeHtml(entry.author)}</p>
    <p><strong>书本原则（概括）</strong> ${escapeHtml(entry.summary)}</p>
    <p><strong>项目应用</strong> ${escapeHtml(entry.application)}</p>
    <div class="knowledge-evidence"><section><h4>所需证据</h4><ul>${items(entry.requiredEvidence)}</ul></section>
    <section><h4>适用边界</h4><ul>${items(entry.limitations)}</ul></section></div>
    <p class="knowledge-citation">修订 ${escapeHtml(entry.revision)} · 核对日期 ${escapeHtml(entry.reviewedOn)} · 书内定位 ${escapeHtml(entry.sourceLocator)}</p></article>`;
}

export function activateBookKnowledge(root) {
  if (!root) return;
  root.innerHTML = `<details><summary><strong>书本知识库</strong><span>查原则、章节与适用条件</span></summary>
    <div class="knowledge-body"><p>查询尼森、《周期》和《纳瓦尔宝典》的章节笔记。内容为研究概括，行情判断仍需当前证据。</p>
    <form class="knowledge-form"><label>知识问题<input name="q" aria-label="知识问题" maxlength="200" required placeholder="例如：孕线的影线可以越界吗"></label>
    <label>书籍范围<select name="bookId" aria-label="书籍范围"><option value="">全部书籍</option><option value="nison">尼森 · 蜡烛图</option><option value="marks">马克斯 · 周期</option><option value="naval">纳瓦尔 · 决策</option></select></label>
    <button type="submit">检索</button></form>
    <p class="knowledge-status" role="status" aria-live="polite">输入问题查询，或从形态分析打开对应笔记。</p>
    <div class="knowledge-results"></div></div></details>`;
  const form = root.querySelector("form");
  const status = root.querySelector("[role=status]");
  const results = root.querySelector(".knowledge-results");
  let requestId = 0;
  let controller;

  async function load(url, detail = false) {
    const current = ++requestId;
    controller?.abort();
    controller = new AbortController();
    const activeController = controller;
    const timeout = setTimeout(() => activeController.abort(), 10000);
    results.innerHTML = "";
    status.textContent = "正在检索章节笔记…";
    root.setAttribute("aria-busy", "true");
    try {
      const response = await fetch(url, { signal: controller.signal, headers: { Accept: "application/json" } });
      if (!response.ok) throw new Error("knowledge unavailable");
      const data = await response.json();
      if (current !== requestId) return;
      const entries = detail ? [data] : (data.matches || []).map(match => match.entry);
      results.innerHTML = entries.map(entryView).join("");
      status.textContent = entries.length ? `找到 ${entries.length} 条相关笔记；相关度仅用于检索排序。`
        : "未找到相关笔记，请尝试形态名称、英文术语或调整书籍范围。";
    } catch {
      if (current !== requestId) return;
      results.innerHTML = "";
      status.textContent = detail ? "笔记暂不可用或链接不存在，请输入关键词重新检索。" : "检索暂不可用，请稍后再次点击检索。";
    } finally {
      clearTimeout(timeout);
      if (current === requestId) root.removeAttribute("aria-busy");
    }
  }

  form.addEventListener("submit", event => {
    event.preventDefault();
    const query = form.elements.q.value.trim();
    if (!query) { status.textContent = "请输入需要查询的问题。"; return; }
    const params = new URLSearchParams({ q: query, limit: "5" });
    if (form.elements.bookId.value) params.set("bookId", form.elements.bookId.value);
    load(`/api/knowledge/search?${params}`);
  });
  function openEntry(id) {
    root.querySelector("details").open = true;
    root.scrollIntoView({ block: "start" });
    load(`/api/knowledge/entries/${encodeURIComponent(id)}`, true);
  }
  document.addEventListener("click", event => {
    const link = event.target.closest("[data-knowledge-id]");
    if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    const id = link.dataset.knowledgeId;
    const url = new URL(location.href);
    url.searchParams.set("knowledge", id);
    url.hash = "book-knowledge";
    history.replaceState(null, "", url);
    openEntry(id);
  });
  const initial = new URLSearchParams(location.search).get("knowledge");
  if (initial) openEntry(initial);
}
