package com.astock.agent.agent.learning.advisor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

public final class TraceAdvisor implements CallAdvisor {

    public static final String TRACE_KEY = "learning.advisorTrace";

    @Override
    public String getName() {
        return "TraceAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest traced = append(request, "TraceAdvisor:before");
        try {
            return append(chain.nextCall(traced), "TraceAdvisor:after");
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    static ChatClientRequest append(ChatClientRequest request, String stage) {
        List<String> trace = new ArrayList<>(trace(request.context()));
        trace.add(stage);
        return request.mutate().context(TRACE_KEY, List.copyOf(trace)).build();
    }

    static ChatClientResponse append(ChatClientResponse response, String stage) {
        List<String> trace = new ArrayList<>(trace(response.context()));
        trace.add(stage);
        return response.mutate().context(TRACE_KEY, List.copyOf(trace)).build();
    }

    @SuppressWarnings("unchecked")
    static List<String> trace(java.util.Map<String, Object> context) {
        Object value = context == null ? null : context.get(TRACE_KEY);
        return value instanceof List<?> values
                ? values.stream().map(String::valueOf).toList()
                : List.of();
    }
}
