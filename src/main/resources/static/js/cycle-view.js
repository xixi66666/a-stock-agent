import { stockApi } from './api.js';

const escape = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[char]);
const date = value => value && Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未提供';
const list = items => `<ul>${(items || []).map(item => `<li>${escape(item)}</li>`).join('')}</ul>`;
const statuses = { HEALTHY:'可用', DEGRADED:'降级', STALE:'已过期', UNVERIFIED:'未核验', UNAVAILABLE:'不可用' };
const labels = { quote:'行情', bars:'K 线', technical:'技术指标', sectors:'行业', industryValuation:'行业估值', fundFlow:'资金流', fundFlowSummary:'资金汇总', capital:'资本与筹码', fundamentals:'基本面', research:'研报', news:'新闻', announcements:'公告' };
function sourceLink(value) {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) ? `<a href="${escape(url.href)}" target="_blank" rel="noopener noreferrer">查看来源</a>` : ''; }
  catch { return ''; }
}
function renderTrace(task) {
  return `<details class="cycle-trace"><summary>原书阅读与执行记录</summary>
    <ul>${(task.chapters || []).map(chapter => `<li id="cycle-chapter-${escape(chapter.id)}">《周期》· ${escape(chapter.title)} <small>${escape(chapter.id)}</small></li>`).join('')}</ul>
    <ol>${(task.trace || []).map(event => `<li><strong>${escape(event.action)}</strong> · ${escape(event.detail)}</li>`).join('')}</ol>
    ${task.skillDigest ? `<p class="cycle-digest">方法版本校验：${escape(task.skillDigest)}</p>` : ''}</details>`;
}
function renderReport(task) {
  const report = task.report;
  return `<article class="cycle-report">
    <section><h3>综合判断</h3><p>${escape(report.conclusion)}</p>
      <p class="cycle-meta">${escape(task.modelName)} · 生成于 ${escape(date(task.updatedAt))} · 快照获取于 ${escape(date(task.snapshotAt))}</p></section>
    ${report.dimensions.map(d => `<section class="cycle-dimension"><h3>${escape(d.title)}</h3><dl><dt>书中观点</dt><dd>${escape(d.bookView)}
      <span class="cycle-citations">${(d.chapterIds || []).map(id => `<a href="#cycle-chapter-${escape(id)}" data-cycle-chapter>${escape(task.chapters?.find(c => c.id === id)?.title || id)}</a>`).join(' · ')}</span></dd>
      <dt>当前事实</dt><dd>${d.facts?.length ? `<ul>${d.facts.map(f => `<li>${escape(f.text)} <span class="cycle-citations">${f.evidenceIds.map(id => `<a href="#cycle-evidence-${escape(id)}" data-cycle-evidence>${escape(labels[id] || id)}</a>`).join(' · ')}</span></li>`).join('')}</ul>` : '暂无足够的直接证据'}</dd>
      <dt>我的分析</dt><dd>${escape(d.analysis)}</dd></dl></section>`).join('')}
    <section class="cycle-section"><h3>矛盾与替代解释</h3>${list(report.conflicts)}</section>
    <section class="cycle-section"><h3>条件式情景</h3><div class="cycle-scenarios">${report.scenarios.map(s => `<div><h4>${escape(s.condition)}</h4><p>${escape(s.interpretation)}</p></div>`).join('')}</div></section>
    <section class="cycle-section"><h3>攻守校准原则</h3><p>${escape(report.calibration)}</p></section>
    <section class="cycle-section"><h3>失效条件与后续观察</h3>${list(report.watchItems)}</section>
    <section class="cycle-section"><h3>证据缺口与判断局限</h3>${list([...(task.limitations || []), ...report.limitations])}</section>
    <details class="cycle-evidence"><summary>市场证据与来源</summary><ul>${(task.evidence || []).map(item => {
      const data = item.data || {}, p = data.provenance || {};
      return `<li id="cycle-evidence-${escape(item.id)}"><strong>${escape(labels[item.id] || item.id)}</strong> · ${escape(statuses[data.status] || data.status || '未知状态')}
        <span>${escape(p.provider || '无可用来源')} ${sourceLink(p.sourceUrl)}</span>
        <small>数据所属时间 ${escape(date(p.providerTimestamp))} · 获取时间 ${escape(date(p.fetchedAt))}${p.cached ? ' · 缓存' : ''}${p.fallbackProvider ? ` · 备用来源 ${escape(p.fallbackProvider)}` : ''}</small>
        ${data.issues?.length ? list(data.issues) : ''}</li>`;
    }).join('')}</ul></details>${renderTrace(task)}
    <p class="report-disclaimer">仅供学习研究，不构成投资建议。周期定位是基于证据的推论，不是确定性预测。</p></article>`;
}

/** 离开标签或切换证券时停止轮询，后台任务可从 latest 恢复。 */
export function activateCycleView(root, code) {
  let disposed = false, timer = null, task = null, models = [], selected = '', busy = true, error = '';
  const controller = new AbortController(), signal = controller.signal;
  function render() {
    if (disposed) return;
    const running = task?.status === 'RUNNING';
    root.innerHTML = `<section class="cycle-workbench" aria-label="周期研究">
      <div class="cycle-toolbar"><div><h2>周期研究</h2><p>结合《周期》原书与当前证据，研究盈利、估值、信贷和市场心理。</p></div>
      <div class="cycle-actions"><label for="cycle-model">研究模型</label><select id="cycle-model" ${busy || running ? 'disabled' : ''}>
        ${models.length ? models.map(m => `<option value="${escape(m.id)}" ${m.id === selected ? 'selected' : ''}>${escape(m.modelName)}</option>`).join('') : '<option>暂无可用模型</option>'}</select>
        <button id="run-cycle" ${busy || (running && !error) || !models.length ? 'disabled' : ''}>${running ? error ? '重试获取进度' : '研究进行中' : task ? '重新生成周期研究' : '生成周期研究'}</button></div></div>
      <p id="cycle-progress" role="status">${escape(error || (busy && !task ? '正在加载研究状态…' : task?.stage || '按需生成，通常需要多轮检索和阅读。'))}</p>
      <div id="cycle-output" ${running ? 'aria-busy="true"' : ''}>
        ${task?.status === 'COMPLETED' && task.report ? renderReport(task) : task?.status === 'FAILED' ? `<div class="cycle-empty" role="alert"><h3>周期研究未完成</h3><p>${escape(task.error)}</p></div>${renderTrace(task)}` : running ? `<div class="cycle-empty"><h3>${escape(task.stage)}</h3><p>研究会逐步核对原书和市场证据。切换标签后可回来继续查看。</p></div>${renderTrace(task)}` : `<div class="cycle-empty"><h3>从证据出发，理解周期位置</h3><p>报告将分别呈现书中观点、当前事实与分析推论，保留矛盾信号和数据缺口。</p>${!models.length && !busy ? '<p>暂无可用研究模型，请先在本地配置中启用支持工具调用的模型。</p>' : ''}</div>`}
      </div></section>`;
    root.querySelector('#cycle-model').addEventListener('change', event => { selected = event.target.value; });
    root.querySelector('#run-cycle').addEventListener('click', () => running ? poll() : start());
    root.querySelectorAll('[data-cycle-chapter]').forEach(link => link.addEventListener('click', () => { root.querySelector('.cycle-trace').open = true; }));
    root.querySelectorAll('[data-cycle-evidence]').forEach(link => link.addEventListener('click', () => { root.querySelector('.cycle-evidence').open = true; }));
  }
  function schedule() { clearTimeout(timer); if (!disposed && task?.status === 'RUNNING') timer = setTimeout(poll, 1500); }
  async function poll() {
    if (disposed) return;
    busy = true; error = ''; render();
    try {
      const result = await stockApi.cycleTask(task.id, signal);
      if (disposed) return;
      task = result;
      if (task.status === 'COMPLETED' && task.error) error = task.error;
      schedule();
    } catch (failure) { if (!disposed) error = `进度获取失败：${failure.message}`; }
    finally { busy = false; render(); }
  }
  async function start() {
    busy = true; error = ''; render();
    try {
      const result = await stockApi.startCycle(code, selected, signal);
      if (disposed) return;
      task = result;
      if (task.status === 'COMPLETED' && task.error) error = task.error;
      schedule();
    } catch (failure) { if (!disposed) error = failure.message; }
    finally { busy = false; render(); }
  }
  render();
  Promise.allSettled([stockApi.cycleModels(signal), stockApi.latestCycle(code, signal)]).then(results => {
    if (disposed) return;
    if (results[0].status === 'fulfilled') {
      models = Array.isArray(results[0].value) ? results[0].value : [];
      selected = models.find(m => m.defaultModel)?.id || models[0]?.id || '';
    } else error = `模型目录加载失败：${results[0].reason.message}`;
    if (results[1].status === 'fulfilled') { task = results[1].value; if (task.error && task.status === 'COMPLETED') error = task.error; }
    else if (results[1].reason.status !== 404) error = `历史研究加载失败：${results[1].reason.message}`;
    busy = false; render(); schedule();
  });
  return { dispose() { disposed = true; clearTimeout(timer); controller.abort(); } };
}
