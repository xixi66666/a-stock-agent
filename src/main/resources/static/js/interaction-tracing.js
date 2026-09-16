/* 页面操作统一入口：捕获阶段监听可覆盖动态控件；上报失败不能影响业务操作。 */
const endpoint = '/api/observability/events';
// HTTP 局域网地址可能没有 randomUUID；这些 ID 仅用于日志关联，不用于认证。
const newId = () => globalThis.crypto?.randomUUID?.()
  || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
const pageId = newId();
let interactionId = newId();
const token = (value, limit = 80) => String(value || '-').replace(/[^A-Za-z0-9_.:-]/g, '_').slice(0, limit);
const pathOnly = value => new URL(value, location.href).pathname.replace(/[^A-Za-z0-9/_.%~-]/g, '_').slice(0, 240);

function context(element) {
  const code = element?.closest('[data-code], [data-symbol]')?.dataset;
  const candidate = code?.code || code?.symbol || document.querySelector('#security-code')?.textContent || '';
  return {
    pageId, interactionId, path: pathOnly(location.href),
    view: token(element?.closest('[data-view]')?.dataset.view
      || document.querySelector('.view-tab.is-active')?.dataset.view || 'home', 50),
    code: /^\d{6}$/.test(candidate) ? candidate : '-',
  };
}

function send(type, target, details = context()) {
  // 不采集 textContent、input.value、URL 查询参数、请求体或异常消息。
  try {
    void fetch(endpoint, {
      method: 'POST', keepalive: true,
      headers: { 'Content-Type': 'application/json', 'X-Page-Id': details.pageId,
        'X-Interaction-Id': details.interactionId },
      body: JSON.stringify({ ...details, type, target }),
    }).catch(() => { /* 本地服务断开时不递归上报，也不阻塞点击。 */ });
  } catch { /* 浏览器退出期间允许丢弃遥测。 */ }
}

function describe(element) {
  if (!element) return 'unknown';
  let result = token(element.tagName.toLowerCase());
  if (element.id) result += `#${token(element.id)}`;
  else result += [...element.classList].slice(0, 2).map(name => `.${token(name, 40)}`).join('');
  for (const key of ['view', 'action', 'timeframe', 'tab']) {
    if (element.dataset[key]) result += `[${key}=${token(element.dataset[key], 40)}]`;
  }
  return result.slice(0, 240);
}

for (const type of ['click', 'submit', 'change']) {
  document.addEventListener(type, event => {
    const raw = event.target instanceof Element ? event.target : event.target?.parentElement;
    const element = type === 'click'
      ? raw?.closest('button, a, input, select, summary, [role="button"], [role="tab"], [data-action]') || raw
      : raw;
    interactionId = newId();
    send(type, describe(element), context(element));
  }, true);
}
for (const type of ['popstate', 'hashchange']) {
  window.addEventListener(type, () => {
    interactionId = newId();
    send('navigation', type);
  });
}
send('page', 'document');

/** 只给同源业务 API 附带关联信息；后台轮询记录的是请求发起时最近的操作。 */
export async function tracedFetch(input, options = {}) {
  const url = new URL(input instanceof Request ? input.url : input, location.href);
  if (url.origin !== location.origin || !url.pathname.startsWith('/api/') || url.pathname === endpoint) {
    return fetch(input, options);
  }
  const details = context();
  const headers = new Headers(options.headers || (input instanceof Request ? input.headers : undefined));
  headers.set('X-Page-Id', details.pageId);
  headers.set('X-Interaction-Id', details.interactionId);
  try {
    return await fetch(input, { ...options, headers });
  } catch (failure) {
    send('request-failed', pathOnly(url), details);
    throw failure;
  }
}
