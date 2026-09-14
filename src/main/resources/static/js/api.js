/*
 * 前端 API 边界：只负责 HTTP 请求、JSON 解析和 Problem Details 转换。
 * 业务状态（例如 DataSection 的 UNAVAILABLE）交给 app.js/views.js 解释，避免请求层
 * 偷偷把缺失数据变成默认数值。
 */
const DEFAULT_HEADERS = { Accept: "application/json" };

export class ApiError extends Error {
  constructor(message, status, problem = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }
}

async function request(path, options = {}) {
  // 统一解析 JSON 或文本错误，页面层只处理 ApiError，不重复编写 fetch 错误逻辑。
  const response = await fetch(path, {
    ...options,
    headers: { ...DEFAULT_HEADERS, ...(options.headers || {}) },
  });
  const contentType = response.headers.get("content-type") || "";
  const body = contentType.includes("json") ? await response.json() : await response.text();
  if (!response.ok) {
    const detail = typeof body === "object" ? body.detail || body.title : "";
    throw new ApiError(detail || `请求失败 (${response.status})`, response.status, body);
  }
  return body;
}

export const aiApi = {
  models() {
    return request('/api/ai/models');
  },
};

export const stockApi = {
  uziStatus(signal) { return request('/api/uzi/status', { signal }); },
  uziModels(signal) { return request('/api/uzi/models', { signal }); },
  latestUzi(code, signal) { return request(`/api/uzi/latest/${encodeURIComponent(code)}`, { signal }); },
  uziTask(id, signal) { return request(`/api/uzi/tasks/${encodeURIComponent(id)}`, { signal }); },
  startUzi(code, depth, school, modelId, signal) {
    return request('/api/uzi/tasks', { method: 'POST', signal,
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code, depth, school, modelId }) });
  },
  cycleModels(signal) { return request('/api/agent/cycle/models', { signal }); },
  latestCycle(code, signal) { return request(`/api/agent/cycle/latest/${encodeURIComponent(code)}`, { signal }); },
  cycleTask(id, signal) { return request(`/api/agent/cycle/tasks/${encodeURIComponent(id)}`, { signal }); },
  startCycle(code, modelId, signal) {
    return request('/api/agent/cycle/tasks', { method: 'POST', signal,
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code, modelId }) });
  },
  search(query) {
    return request(`/api/stocks/search?q=${encodeURIComponent(query)}`);
  },
  snapshot(code) {
    return request(`/api/stocks/${encodeURIComponent(code)}/snapshot`);
  },
  technical(code, timeframe = "DAILY") {
    return request(`/api/stocks/${encodeURIComponent(code)}/technical?timeframe=${timeframe}`);
  },
  candlestick(code, timeframe = "DAILY") {
    return request(`/api/stocks/${encodeURIComponent(code)}/candlestick?timeframe=${encodeURIComponent(timeframe)}`);
  },
  sources(code) {
    return request(`/api/stocks/${encodeURIComponent(code)}/sources`);
  },
  finRobotStatus() {
    return request("/api/finrobot/status");
  },
  finRobotModels() {
    return request("/api/finrobot/models");
  },
  finRobotResearch(code, modelId) {
    // FinRobot 是页面唯一的投研流水线；浏览器只发送安全的模型 ID。
    return request("/api/finrobot/research", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, modelId }),
    });
  },
  financialReport(code, modelId) {
    // 财报分析固定走 financial-report 后端角色；模型失败时后端返回确定性回退内容。
    return request("/api/agent/financial-report", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, modelId }),
    });
  },
};

export const marketApi = {
  indices() {
    return request('/api/market/indices');
  },
};

export function sectionPayload(section, fallback = null) {
  // UNAVAILABLE 没有可信 payload；禁止 UI 把缺失分区渲染成 0 或空的健康状态。
  if (!section || section.status === "UNAVAILABLE") return fallback;
  return section.payload ?? fallback;
}

export function sectionIssues(section) {
  return Array.isArray(section?.issues) ? section.issues : [];
}

export function isPartialSnapshot(snapshot) {
  return Object.values(snapshot || {}).some((value) =>
    value && typeof value === "object" && ["DEGRADED", "STALE", "UNVERIFIED", "UNAVAILABLE"].includes(value.status),
  );
}
