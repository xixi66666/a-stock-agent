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

export const stockApi = {
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
  agentStatus() {
    return request("/api/agent/status");
  },
  analyze(code) {
    // 研究报告固定走 institutional-report 后端角色，不携带总体报告的 modelId。
    return request("/api/agent/analyze", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    });
  },
  quantReport(code) {
    return request("/api/agent/quant-report", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    });
  },
  overallModels() {
    return request("/api/agent/models?capability=overall-report");
  },
  overallReport(code, modelId) {
    // 总体报告才把用户选择的命名模型 ID 传给服务端；浏览器不接触密钥和 Base URL。
    return request("/api/agent/overall-report", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, modelId }),
    });
  },
  financialReport(code) {
    // 财报分析固定走 financial-report 后端角色；模型失败时后端返回确定性回退内容。
    return request("/api/agent/financial-report", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    });
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
