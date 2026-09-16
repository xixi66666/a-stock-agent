import { getSelectedModelId } from './model-selection.js';
import { tracedFetch } from './interaction-tracing.js';

const escape = value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');
const labels = { RUNNING: '研究中', COMPLETED: '已完成', PARTIAL: '部分完成', FAILED: '生成失败',
  CANCELLED: '已取消', UNVERIFIED: '模型叙述 · 待核验', UNAVAILABLE: '不可用', HEALTHY: '可用',
  STALE: '已过期', DEGRADED: '降级' };

export function activateOfficialFinRobot(root, code, onLegacy) {
  const controller = new AbortController();
  let disposed = false, timer, task = null, runtime, error = '', busy = true;
  const request = async (path, body) => {
    const response = await tracedFetch('/api/finrobot/' + path, { signal: controller.signal,
      ...(body !== undefined ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}) });
    if (response.status === 204) return null;
    const result = await response.json();
    if (!response.ok) throw new Error(result.detail || '请求失败，请稍后重试');
    return result;
  };
  function render() {
    if (disposed) return;
    const running = task?.status === 'RUNNING';
    const report = task?.report;
    root.innerHTML = `<section class="official-finrobot" aria-label="官方 FinRobot 投研">
      <header><span class="section-kicker">FINROBOT · EQUITY RESEARCH</span><h2>官方 FinRobot Equity</h2>
      <p>八个专题分别研究公司、投资逻辑、估值、竞争、风险与事件，使用同一份 A 股证据。</p>
      <p class="muted">${escape(runtime?.message || '正在检查研究环境')}</p></header>
      <div class="official-controls">
      <button class="primary-command" id="official-finrobot-run" ${busy || running || !runtime?.installed ? 'disabled' : ''}><i data-lucide="sparkles" aria-hidden="true"></i>运行官方投研</button>
      ${running ? '<button class="secondary-command" id="official-finrobot-cancel">取消研究</button>' : ''}</div>
      <div class="official-task-status" role="status">${task ? `<strong>${escape(labels[task.status] || task.status)}</strong><span>${escape(task.stage || '')}</span>${task.modelName ? `<span class="official-task-model">模型：${escape(task.modelName)}</span>` : ''}${running ? `<progress aria-label="专题完成进度" max="8" value="${Math.max(0, Math.min(8, task.completed || 0))}"></progress>` : ''}` : '等待开始研究'}</div>
      ${error || task?.error ? `<p role="alert">${escape(error || task.error)}</p>` : ''}
      ${report ? `<div class="official-downloads"><a href="/api/finrobot/tasks/${encodeURIComponent(task.id)}/artifacts/html">下载 HTML 报告</a>
      <a href="/api/finrobot/tasks/${encodeURIComponent(task.id)}/artifacts/json">下载结构化报告</a><a href="/api/finrobot/tasks/${encodeURIComponent(task.id)}/artifacts/evidence">下载原始证据</a></div>
      <p class="muted">模型：${escape(report.modelName)} · 源码：${escape(report.upstreamCommit?.slice(0, 12))} · 快照：${escape(report.snapshotAt || '未提供')}</p>
      <p>模型叙述尚待核验；来源与计算结果可在附件中复查。</p>
      ${Object.values(report.sections || {}).map(section => `<section class="official-section"><h3>${escape(section.title)}</h3>
        <small>${escape(labels[section.status] || section.status)}</small><p class="official-narrative">${escape(section.text)}</p>
        ${(section.issues || []).map(issue => `<p class="muted">${escape(issue)}</p>`).join('')}</section>`).join('')}
      <section class="official-section"><h3>估值与预测限制</h3>${(report.valuation?.issues || []).map(issue => `<p>${escape(issue)}</p>`).join('')}</section>
      <section class="official-section"><h3>数据来源</h3>${(report.sources || []).map(source => `<p><strong>[${escape(source.id)}] ${escape(source.provider)}</strong> · ${escape(source.section)} · ${escape(labels[source.status] || source.status)} · ${source.cached ? '缓存' : '实时请求'}<br><small>采集时间：${escape(source.fetchedAt || '未提供')} · 数据时间：${escape(source.providerTimestamp || '未提供')}</small></p>`).join('')}</section>
      <p class="muted">${escape((report.limitations || []).join('；'))}</p><p>仅供学习研究，不构成投资建议</p>` : ''}
    </section>`;
    root.querySelector('#official-finrobot-run')?.addEventListener('click', start);
    root.querySelector('#official-finrobot-cancel')?.addEventListener('click', cancel);
    window.lucide?.createIcons();
  }
  async function poll() {
    if (disposed || task?.status !== 'RUNNING') return;
    try { task = await request('tasks/' + encodeURIComponent(task.id)); error = ''; render(); schedule(); }
    catch (failure) { if (!disposed) { error = failure.message; render(); timer = setTimeout(poll, 3000); } }
  }
  function schedule() { if (!disposed && task?.status === 'RUNNING') timer = setTimeout(poll, 1000); }
  async function start() {
    busy = true; error = ''; render();
    try { task = await request('tasks', { code, modelId: getSelectedModelId() }); schedule(); }
    catch (failure) { error = failure.message; }
    finally { busy = false; render(); }
  }
  async function cancel() {
    clearTimeout(timer);
    try { task = await request('tasks/' + encodeURIComponent(task.id) + '/cancel', {}); }
    catch (failure) { error = failure.message; schedule(); }
    render();
  }
  async function initialize() {
    try {
      runtime = await request('runtime');
      if (disposed) return;
      if (runtime.engine === 'legacy') { onLegacy(); return; }
      task = await request('latest/' + encodeURIComponent(code));
      schedule();
    } catch (failure) { error = failure.message; }
    finally { busy = false; render(); }
  }
  render(); initialize();
  return { dispose() { disposed = true; clearTimeout(timer); controller.abort(); } };
}
