package com.astock.agent.marketdata.model;

import java.time.LocalDate;

public record Announcement(String title, String type, LocalDate publishedAt, String url) {
}
