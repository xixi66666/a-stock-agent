package com.astock.agent.marketdata.model;

import java.time.LocalDateTime;

public record NewsItem(String title, String summary, String source, LocalDateTime publishedAt, String url) {
}
