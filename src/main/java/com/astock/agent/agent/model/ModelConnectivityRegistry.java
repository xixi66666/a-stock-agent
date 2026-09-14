package com.astock.agent.agent.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型连通性状态：只保存启动探测产出的安全字段，不保存密钥、URL 或请求头。
 */
public final class ModelConnectivityRegistry {

    public enum State { UNKNOWN, OK, FAILED }

    public record Connectivity(State state, Long latencyMs, Instant checkedAt, String errorCode) {
        public static Connectivity unknown() {
            return new Connectivity(State.UNKNOWN, null, null, null);
        }
    }

    private final Map<String, Connectivity> states = new ConcurrentHashMap<>();

    public void recordOk(String modelId, long latencyMs, Instant checkedAt) {
        if (modelId == null || modelId.isBlank()) return;
        states.put(modelId, new Connectivity(State.OK, latencyMs, checkedAt, null));
    }

    public void recordFailed(String modelId, String errorCode, Instant checkedAt) {
        if (modelId == null || modelId.isBlank()) return;
        states.put(modelId, new Connectivity(State.FAILED, null, checkedAt, errorCode));
    }

    public Connectivity get(String modelId) {
        if (modelId == null || modelId.isBlank()) return Connectivity.unknown();
        return states.getOrDefault(modelId, Connectivity.unknown());
    }

    public Map<String, Connectivity> snapshot() {
        return Collections.unmodifiableMap(Map.copyOf(states));
    }
}
