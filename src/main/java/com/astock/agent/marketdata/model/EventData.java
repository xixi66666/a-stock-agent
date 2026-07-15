package com.astock.agent.marketdata.model;

import java.util.List;

public record EventData(
        List<NewsItem> news,
        List<Announcement> announcements,
        List<ResearchItem> research) {

    public EventData {
        news = news == null ? List.of() : List.copyOf(news);
        announcements = announcements == null ? List.of() : List.copyOf(announcements);
        research = research == null ? List.of() : List.copyOf(research);
    }
}
