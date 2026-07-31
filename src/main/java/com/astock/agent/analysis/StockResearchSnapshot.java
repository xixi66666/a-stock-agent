package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.TechnicalSnapshot;
import java.time.Instant;
import java.util.List;

public record StockResearchSnapshot(
        SecurityId security,
        DataSection<Quote> quote,
        DataSection<List<DailyBar>> bars,
        DataSection<TechnicalSnapshot> technical,
        DataSection<?> sectors,
        DataSection<IndustryValuationData> industryValuation,
        DataSection<?> fundFlow,
        DataSection<?> capital,
        DataSection<?> fundamentals,
        DataSection<?> research,
        DataSection<?> news,
        DataSection<?> announcements,
        DataQualityBreakdown quality,
        boolean crossSourceConsistent,
        boolean coreCompleteness,
        boolean authoritativeSources,
        Instant fetchedAt) {

    public static StockResearchSnapshot empty(SecurityId security) {
        return new StockResearchSnapshot(
                security,
                DataSection.unavailable("not loaded"),
                DataSection.unavailable("not loaded"),
                DataSection.unavailable("not loaded"),
                DataSection.unavailable("not loaded"),
                DataSection.unavailable("not loaded"),
                DataSection.unavailable("not loaded"),
                DataSection.unavailable("not loaded"),
                DataSection.unavailable("not loaded"),
                DataSection.unavailable("not loaded"),
                DataSection.unavailable("not loaded"),
                DataSection.unavailable("not loaded"),
                null, false, false, false, Instant.now());
    }

    public StockResearchSnapshot withQuote(DataSection<Quote> value) {
        return copy(value, bars, crossSourceConsistent, coreCompleteness, authoritativeSources);
    }

    public StockResearchSnapshot withBars(DataSection<List<DailyBar>> value) {
        return copy(quote, value, crossSourceConsistent, coreCompleteness, authoritativeSources);
    }

    public StockResearchSnapshot withCrossSourceConsistent(boolean value) {
        return copy(quote, bars, value, coreCompleteness, authoritativeSources);
    }

    public StockResearchSnapshot withCoreCompleteness(boolean value) {
        return copy(quote, bars, crossSourceConsistent, value, authoritativeSources);
    }

    public StockResearchSnapshot withAuthoritativeSources(boolean value) {
        return copy(quote, bars, crossSourceConsistent, coreCompleteness, value);
    }

    private StockResearchSnapshot copy(
            DataSection<Quote> quoteValue,
            DataSection<List<DailyBar>> barsValue,
            boolean consistent,
            boolean complete,
            boolean authoritative) {
        return new StockResearchSnapshot(
                security, quoteValue, barsValue, technical, sectors, industryValuation, fundFlow, capital, fundamentals,
                research, news, announcements, quality, consistent, complete, authoritative, fetchedAt);
    }
}
