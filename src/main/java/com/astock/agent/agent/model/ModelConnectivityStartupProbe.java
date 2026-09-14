package com.astock.agent.agent.model;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * 应用就绪后异步执行一次连通性探测；探测失败不影响应用，也不阻塞启动线程。
 */
public final class ModelConnectivityStartupProbe {

    private final ModelConnectivityProbe probe;

    public ModelConnectivityStartupProbe(ModelConnectivityProbe probe) {
        this.probe = probe;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void probeOnStartup() {
        Thread.ofVirtual().name("model-connectivity-probe").start(probe::probeAll);
    }
}
