package com.astock.agent.agent.model;

import com.astock.agent.agent.report.ModelFailureClassifier;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 启动时一次性连通性探测：串行遍历已配置模型，每模型发一次最小请求并限时。
 *
 * <p>只把稳定状态和延迟写入 {@link ModelConnectivityRegistry}；任何异常都经过
 * {@link ModelFailureClassifier} 脱敏，不会保存密钥、URL 或原始请求信息。</p>
 */
public final class ModelConnectivityProbe {

    @FunctionalInterface
    public interface ProbeCall {
        String ping(ChatClient client);
    }

    private final NamedChatClientRegistry registry;
    private final ModelConnectivityRegistry states;
    private final ModelFailureClassifier classifier;
    private final Clock clock;
    private final Duration timeout;
    private final ProbeCall call;

    public ModelConnectivityProbe(NamedChatClientRegistry registry, ModelConnectivityRegistry states,
            ModelFailureClassifier classifier, Clock clock, Duration timeout) {
        this(registry, states, classifier, clock, timeout, ModelConnectivityProbe::defaultPing);
    }

    public ModelConnectivityProbe(NamedChatClientRegistry registry, ModelConnectivityRegistry states,
            ModelFailureClassifier classifier, Clock clock, Duration timeout, ProbeCall call) {
        this.registry = registry;
        this.states = states;
        this.classifier = classifier;
        this.clock = clock;
        this.timeout = timeout;
        this.call = call;
    }

    static String defaultPing(ChatClient client) {
        return client.prompt().user("ping").call().content();
    }

    public void probeAll() {
        for (NamedChatClientRegistry.ModelReference reference : registry.availableModels(null)) {
            probe(reference.id(), reference.modelName());
        }
    }

    private void probe(String modelId, String modelName) {
        long started = System.nanoTime();
        try {
            ChatClient client = registry.byId(modelId)
                    .orElseThrow(ModelNotAvailableException::new).client();
            var future = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try {
                    future.complete(call.ping(client));
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
            String content = future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
            // 端点已应答即算连通：空文本可能来自推理模型或极短输出，不作为不可用依据。
            if (content == null) {
                states.recordFailed(modelId, "MODEL_EMPTY_RESPONSE", clock.instant());
            } else {
                states.recordOk(modelId, elapsedMillis(started), clock.instant());
            }
        } catch (RuntimeException failure) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            if (cause instanceof java.util.concurrent.TimeoutException) {
                states.recordFailed(modelId, "MODEL_TIMEOUT", clock.instant());
                return;
            }
            var diagnostic = classifier.classify(cause, modelName, elapsedMillis(started), "probe-" + modelId);
            states.recordFailed(modelId, diagnostic.errorCode(), clock.instant());
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
