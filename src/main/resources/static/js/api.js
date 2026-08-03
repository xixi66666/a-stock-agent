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
  sources(code) {
    return request(`/api/stocks/${encodeURIComponent(code)}/sources`);
  },
  agentStatus() {
    return request("/api/agent/status");
  },
  analyze(code) {
    return request("/api/agent/analyze", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    });
  },
  overallReport(code) {
    return request("/api/agent/overall-report", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    });
  },
};

export function sectionPayload(section, fallback = null) {
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
