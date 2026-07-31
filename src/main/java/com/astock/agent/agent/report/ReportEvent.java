package com.astock.agent.agent.report;

import java.time.Instant;

public record ReportEvent(String title, String interpretation, String sourceId, Instant observedAt) {
}
