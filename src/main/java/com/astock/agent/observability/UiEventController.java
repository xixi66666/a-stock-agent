package com.astock.agent.observability;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 接收不触发业务 API 的浏览器操作；只允许有界元数据，不接收文本或表单值。 */
@RestController
public class UiEventController {
    private static final Logger log = LoggerFactory.getLogger(UiEventController.class);
    private static final Set<String> TYPES = Set.of("page", "click", "submit", "change", "navigation", "request-failed");

    @PostMapping("/api/observability/events")
    public ResponseEntity<Void> record(@RequestBody UiEvent event) {
        if (event.type() == null || !TYPES.contains(event.type())
                || !matches(event.pageId(), "[A-Za-z0-9-]{1,80}")
                || !matches(event.interactionId(), "[A-Za-z0-9-]{1,80}")
                || !matches(event.path(), "/[A-Za-z0-9/_.%~-]{0,239}")
                || !matches(event.view(), "[A-Za-z0-9_-]{1,50}")
                || !matches(event.target(), "[A-Za-z0-9_.:#/\\[\\]= -]{1,240}")
                || !matches(event.code(), "(?:[0-9]{6}|-)")) {
            return ResponseEntity.badRequest().build();
        }
        log.info("UI_EVENT requestId={} pageId={} interactionId={} type={} path={} view={} target={} code={}",
                MDC.get("requestId"), event.pageId(), event.interactionId(), event.type(), event.path(),
                event.view(), event.target(), event.code());
        return ResponseEntity.noContent().build();
    }

    private static boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }

    public record UiEvent(String type, String pageId, String interactionId, String path,
            String view, String target, String code) {}
}
