package com.astock.agent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.market-data")
public record MarketDataProperties(
        Duration connectTimeout,
        Duration requestTimeout,
        Duration eastmoneyMinInterval,
        Duration eastmoneyJitter,
        Duration providerCooldown,
        int maxRetries) {

    public MarketDataProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(8) : requestTimeout;
        eastmoneyMinInterval = eastmoneyMinInterval == null ? Duration.ofSeconds(1) : eastmoneyMinInterval;
        eastmoneyJitter = eastmoneyJitter == null ? Duration.ofMillis(400) : eastmoneyJitter;
        providerCooldown = providerCooldown == null ? Duration.ofMinutes(30) : providerCooldown;
        maxRetries = Math.max(0, Math.min(maxRetries, 2));
    }
}
