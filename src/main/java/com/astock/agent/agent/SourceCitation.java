package com.astock.agent.agent;

import java.time.Instant;

public record SourceCitation(
        String section,
        String provider,
        String url,
        Instant fetchedAt,
        String status) {
}
