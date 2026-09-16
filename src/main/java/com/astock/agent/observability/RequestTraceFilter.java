package com.astock.agent.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** 所有入站路径的统一日志边界；不读取请求体、查询参数或认证信息。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        String requestId = UUID.randomUUID().toString();
        String interactionId = identifier(request.getHeader("X-Interaction-Id"));
        String pageId = identifier(request.getHeader("X-Page-Id"));
        String path = path(request.getRequestURI());
        long started = System.nanoTime();
        String failureType = "none";
        MDC.put("requestId", requestId);
        MDC.put("interactionId", interactionId);
        MDC.put("pageId", pageId);
        response.setHeader("X-Request-Id", requestId);
        // UI 上报自身只输出 UI_EVENT，避免一次点击产生三条重复访问日志。
        boolean telemetry = request.getRequestURI().equals("/api/observability/events");
        try {
            if (!telemetry) log.info("HTTP_START requestId={} interactionId={} pageId={} method={} path={}",
                    requestId, interactionId, pageId, request.getMethod(), path);
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            failureType = failure.getClass().getSimpleName();
            throw failure;
        } finally {
            try {
                Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
                String destination = handler instanceof HandlerMethod method
                        ? method.getBeanType().getSimpleName() + "#" + method.getMethod().getName()
                        : "resource-or-unmapped";
                int status = failureType.equals("none") ? response.getStatus() : 500;
                if (!telemetry || status >= 400) {
                    String message = "HTTP_END requestId={} interactionId={} pageId={} method={} path={} handler={} status={} durationMs={} failureType={}";
                    Object[] args = {requestId, interactionId, pageId, request.getMethod(), path,
                            destination, status, (System.nanoTime() - started) / 1_000_000, failureType};
                    if (status >= 500) log.error(message, args);
                    else if (status >= 400) log.warn(message, args);
                    else log.info(message, args);
                }
            } finally {
                if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
            }
        }
    }

    static String identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,100}") ? value : "-";
    }

    static String path(String value) {
        if (value == null) return "-";
        // 双重截断查询/片段，并限制单条日志长度，防止换行注入。
        String clean = value.split("[?#]", 2)[0].replaceAll("[^A-Za-z0-9/_.%~:@+-]", "_");
        return clean.substring(0, Math.min(clean.length(), 240));
    }
}
