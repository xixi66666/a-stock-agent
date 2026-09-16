package com.astock.agent.observability;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** 任务创建时冻结来源；线程切换与后续轮询不会改变原始关联 ID。 */
public final class TaskTrace {
    private static final Logger log = LoggerFactory.getLogger(TaskTrace.class);
    private final String module;
    private final String taskId;
    private final String code;
    private final Map<String, String> context;
    private final long started = System.nanoTime();

    public TaskTrace(String module, String taskId, String code) {
        this.module = RequestTraceFilter.identifier(module);
        this.taskId = RequestTraceFilter.identifier(taskId);
        this.code = code != null && code.matches("[0-9]{6}") ? code : "-";
        this.context = Map.of("requestId", RequestTraceFilter.identifier(MDC.get("requestId")),
                "interactionId", RequestTraceFilter.identifier(MDC.get("interactionId")),
                "pageId", RequestTraceFilter.identifier(MDC.get("pageId")));
        event("CREATED");
    }

    public Runnable wrap(Runnable work) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                MDC.setContextMap(context);
                MDC.put("taskId", taskId);
                event("START");
                work.run();
            } finally {
                if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
            }
        };
    }

    public void event(String state) {
        String message = "TASK_{} requestId={} interactionId={} pageId={} module={} taskId={} code={} durationMs={}";
        Object[] values = {RequestTraceFilter.identifier(state), context.get("requestId"),
                context.get("interactionId"), context.get("pageId"), module, taskId, code,
                (System.nanoTime() - started) / 1_000_000};
        if (state.equals("FAILED") || state.equals("TIMEOUT")) log.warn(message, values);
        else log.info(message, values);
    }

    /** 异常消息可能含上游凭证，仅输出类型及应用内抛出位置。 */
    public void failure(Throwable failure) {
        String location = java.util.Arrays.stream(failure.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("com.astock.agent."))
                .findFirst().map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
                .orElse("upstream");
        log.warn("TASK_ERROR requestId={} interactionId={} module={} taskId={} failureType={} location={}",
                context.get("requestId"), context.get("interactionId"), module, taskId,
                failure.getClass().getSimpleName(), location);
    }
}
