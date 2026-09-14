import { stockApi } from './api.js';
import { getSelectedModelId } from './model-selection.js';

const escape = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[char]);
const date = value => value && Number.isFinite(Date.parse(value))
  ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未提供';
const statusLabels = { READY: '环境可用', RUNNING: '研究中', COMPLETED: '已完成', FAILED: '未完成' };
const dimensionLabels = {
  companyProfile: '公司画像', competitors: '竞争对手', earningsForecast: '盈利预测', structuredValuation: '结构化估值',
};

function renderValue(value, depth = 0) {
  if (value == null || value === '') return '<span class="uzi-missing">暂无可靠数据</span>';
  if (typeof value !== 'object') return `<span>${escape(value)}</span>`;
  if (depth > 2) return `<span>${escape(JSON.stringify(value))}</span>`;
  if (Array.isArray(value)) {
    return value.length ? `<ul>${value.slice(0, 12).map(item => `<li>${renderValue(item, depth + 1)}</li>`).join('')}</ul>` : '<span class="uzi-missing">空列表</span>';
  }
  const entries = Object.entries(value).slice(0, 20);
  return entries.length ? `<dl>${entries.map(([key, item]) => `<dt>${escape(key)}</dt><dd>${renderValue(item, depth + 1)}</dd>`).join('')}</dl>` : '<span class="uzi-missing">空对象</span>';
}

function sourceLink(source) {
  try {
    const url = new URL(source.url);
    return ['http:', 'https:'].includes(url.protocol)
      ? `<a href="${escape(url.href)}" target="_blank" rel="noopener noreferrer">查看来源</a>` : '';
  } catch { return ''; }
}

function renderBundle(task) {
  const bundle = task?.bundle || {};
  const structured = bundle.structured || {};
  const dimensions = Object.entries(dimensionLabels);
  return `<article class="uzi-report">
    <header class="uzi-report-header"><p class="generation-line">生成模式：UZI 多维分析 · 模型：${escape(task.modelName || '由本地 UZI 配置决定')}</p><span class="uzi-status" data-status="COMPLETED">UZI 研究完成</span><h3>${escape(bundle.ticker || task.code)} · 22 维度深度分析</h3><p>生成于 ${escape(date(bundle.generatedAt))} · 模型 ${escape(task.modelName || '由本地 UZI 配置决定')}</p></header>
    <div class="uzi-dimension-grid">${dimensions.map(([key, label]) => `<section class="uzi-dimension"><h4>${label}</h4>${renderValue(structured[key])}</section>`).join('')}</div>
    <section class="uzi-section"><h4>22 维度原始明细</h4>${renderValue(bundle.dimensions)}</section>
    <section class="uzi-section"><h4>UZI 综合面板</h4>${renderValue(bundle.panel)}</section>
    <section class="uzi-section"><h4>综合结论</h4>${renderValue(bundle.synthesis)}</section>
    <section class="uzi-section"><h4>数据缺口与限制</h4>${(bundle.dataGaps?.length || task.limitations?.length) ? `<ul>${[...(bundle.dataGaps || []), ...(task.limitations || [])].map(item => `<li>${escape(item)}</li>`).join('')}</ul>` : '<p class="uzi-missing">当前未返回数据缺口说明</p>'}</section>
    <details class="uzi-section uzi-sources"><summary>来源引用（${bundle.sources?.length || 0}）</summary>${bundle.sources?.length ? `<ul>${bundle.sources.map(source => `<li><strong>${escape(source.dimension)}</strong> · ${escape(source.provider)} · ${escape(source.observedAt || '未提供')} ${sourceLink(source)}</li>`).join('')}</ul>` : '<p class="uzi-missing">当前没有可展示的来源引用</p>'}</details>
  </article>`;
}

/** UZI 独立研究页：离开标签时停止轮询，后台任务可从 latest 恢复。 */
export function activateUziView(root, code) {
  let disposed = false, timer = null, task = null, status = null;
  let depth = 'medium', school = '', busy = true, error = '';
  const controller = new AbortController();
  const signal = controller.signal;

  function render() {
    if (disposed) return;
    const running = task?.status === 'RUNNING';
    const statusText = status ? (statusLabels[status.reason] || status.reason || '环境状态未知') : '正在探测环境…';
    root.innerHTML = `<section class="uzi-workbench" aria-label="UZI 投研">
      <div class="uzi-toolbar"><div><span class="section-kicker">UZI · 22-DIMENSION RESEARCH</span><h2>UZI 投研</h2><p>完整执行 UZI 的多维度研究，并保留公司画像、竞争对手、盈利预测、结构化估值及来源缺口。</p></div>
      <div class="uzi-runtime" data-ready="${status?.enabled === true}"><span class="uzi-status-dot"></span>${escape(statusText)} · ${escape(status?.python || 'python')}</div></div>
      <div class="uzi-actions"><label for="uzi-depth">分析深度</label><select id="uzi-depth" ${busy || running ? 'disabled' : ''}><option value="lite" ${depth === 'lite' ? 'selected' : ''}>Lite · 快速</option><option value="medium" ${depth === 'medium' ? 'selected' : ''}>Medium · 标准</option><option value="deep" ${depth === 'deep' ? 'selected' : ''}>Deep · 完整</option></select>
        <label for="uzi-school">投资流派</label><select id="uzi-school" ${busy || running ? 'disabled' : ''}><option value="">自动/不指定</option>${'ABCDEFGHI'.split('').map(value => `<option value="${value}" ${school === value ? 'selected' : ''}>${value} 流派</option>`).join('')}</select>
        <button id="run-uzi" class="primary-command" type="button" ${busy || running || status?.enabled !== true ? 'disabled' : ''}>${running ? '研究进行中' : task ? '重新运行 UZI 投研' : '运行 UZI 投研'}</button></div>
      <p id="uzi-progress" role="status">${escape(error || (busy && !task ? '正在加载 UZI 环境和历史任务…' : task?.stage || (status?.enabled === false ? `UZI 暂不可运行：${statusText}` : '按需运行，Deep 模式可能需要较长时间。')))}</p>
      <div id="uzi-output" ${running ? 'aria-busy="true"' : ''}>${task?.status === 'COMPLETED' && task.bundle ? renderBundle(task) : task?.status === 'FAILED' ? `<div class="uzi-empty" role="alert"><h3>UZI 研究未完成</h3><p>${escape(task.error || error || '请检查 UZI 安装、数据源和模型配置')}</p></div>` : running ? `<div class="uzi-empty"><h3>${escape(task.stage)}</h3><p>研究任务在后台运行，切换标签后可以回来继续查看。</p></div>` : '<div class="uzi-empty"><h3>从 22 个维度建立研究底稿</h3><p>先完成环境准备，再运行 UZI。缺失证据会保留为数据缺口，不会被页面补成估算值。</p></div>'}</div>
    </section>`;
    root.querySelector('#uzi-depth')?.addEventListener('change', event => { depth = event.target.value; });
    root.querySelector('#uzi-school')?.addEventListener('change', event => { school = event.target.value; });
    root.querySelector('#run-uzi')?.addEventListener('click', () => running ? poll() : start());
  }

  function schedule() { clearTimeout(timer); if (!disposed && task?.status === 'RUNNING') timer = setTimeout(poll, 1500); }
  async function poll() {
    if (disposed || !task) return;
    busy = true; error = ''; render();
    try { task = await stockApi.uziTask(task.id, signal); if (!disposed) schedule(); }
    catch (failure) { if (!disposed) error = `进度获取失败：${failure.message}`; }
    finally { busy = false; render(); }
  }
  async function start() {
    busy = true; error = ''; render();
    try { task = await stockApi.startUzi(code, depth, school || null, getSelectedModelId(), signal); if (!disposed) schedule(); }
    catch (failure) { if (!disposed) error = failure.message; }
    finally { busy = false; render(); }
  }

  render();
  Promise.allSettled([stockApi.uziStatus(signal), stockApi.latestUzi(code, signal)]).then(results => {
    if (disposed) return;
    if (results[0].status === 'fulfilled') status = results[0].value;
    else error = `UZI 环境探测失败：${results[0].reason.message}`;
    if (results[1].status === 'fulfilled') task = results[1].value;
    else if (results[1].reason.status !== 404) error = `历史 UZI 任务加载失败：${results[1].reason.message}`;
    busy = false; render(); schedule();
  });
  return { dispose() { disposed = true; clearTimeout(timer); controller.abort(); } };
}
