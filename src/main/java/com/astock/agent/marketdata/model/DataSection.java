package com.astock.agent.marketdata.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DataSection<T>(
        SectionStatus status,
        Optional<T> payload,
        Optional<Provenance> provenance,
        List<String> issues) {

    public DataSection {
        Objects.requireNonNull(status, "status");
        payload = payload == null ? Optional.empty() : payload;
        provenance = provenance == null ? Optional.empty() : provenance;
        issues = issues == null ? List.of() : List.copyOf(issues);
        if (status == SectionStatus.HEALTHY && (payload.isEmpty() || provenance.isEmpty())) {
            throw new IllegalArgumentException("A healthy section requires payload and provenance");
        }
        if (status == SectionStatus.UNAVAILABLE && payload.isPresent()) {
            throw new IllegalArgumentException("An unavailable section cannot contain payload");
        }
    }

    public static <T> DataSection<T> healthy(T payload, Provenance provenance) {
        requirePayloadAndProvenance(payload, provenance);
        return new DataSection<>(SectionStatus.HEALTHY, Optional.of(payload), Optional.of(provenance), List.of());
    }

    public static <T> DataSection<T> degraded(T payload, Provenance provenance, List<String> issues) {
        requirePayloadAndProvenance(payload, provenance);
        return new DataSection<>(SectionStatus.DEGRADED, Optional.of(payload), Optional.of(provenance), issues);
    }

    public static <T> DataSection<T> stale(T payload, Provenance provenance, List<String> issues) {
        requirePayloadAndProvenance(payload, provenance);
        return new DataSection<>(SectionStatus.STALE, Optional.of(payload), Optional.of(provenance), issues);
    }

    public static <T> DataSection<T> unverified(T payload, Provenance provenance, List<String> issues) {
        requirePayloadAndProvenance(payload, provenance);
        return new DataSection<>(SectionStatus.UNVERIFIED, Optional.of(payload), Optional.of(provenance), issues);
    }

    public static <T> DataSection<T> unavailable(String issue) {
        String message = Objects.requireNonNull(issue, "issue").trim();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("Unavailable section requires an issue");
        }
        return new DataSection<>(SectionStatus.UNAVAILABLE, Optional.empty(), Optional.empty(), List.of(message));
    }

    private static void requirePayloadAndProvenance(Object payload, Provenance provenance) {
        if (payload == null || provenance == null) {
            throw new IllegalArgumentException("Section requires payload and provenance");
        }
    }
}
