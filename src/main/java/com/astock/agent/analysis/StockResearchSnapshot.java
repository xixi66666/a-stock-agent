package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.FundFlowSummary;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.TechnicalSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * 一次研究请求形成的不可变规范化快照。
 *
 * <p>快照是分析层和 Agent 层之间的稳定契约：每个分区都保留状态、payload、来源和问题，
 * 同一次请求的报告、API 和 UI 应该尽量基于同一快照，而不是各自重新请求 Provider。</p>
 */
public record StockResearchSnapshot(
        SecurityId security,
        DataSection<Quote> quote,
        DataSection<List<DailyBar>> bars,
        DataSection<TechnicalSnapshot> technical,
        DataSection<?> sectors,
        DataSection<IndustryValuationData> industryValuation,
        DataSection<?> fundFlow,
        DataSection<FundFlowSummary> fundFlowSummary,
        DataSection<?> capital,
        DataSection<?> fundamentals,
        DataSection<?> research,
        DataSection<?> news,
        DataSection<?> announcements,
        DataQualityBreakdown quality,
        boolean crossSourceConsistent,
        boolean coreCompleteness,
        boolean authoritativeSources,
        Instant fetchedAt,
        DataSection<com.astock.agent.marketdata.model.ValuationSnapshot> valuation) {

    public StockResearchSnapshot(SecurityId security, DataSection<Quote> quote,
            DataSection<List<DailyBar>> bars, DataSection<TechnicalSnapshot> technical,
            DataSection<?> sectors, DataSection<IndustryValuationData> industryValuation,
            DataSection<?> fundFlow, DataSection<FundFlowSummary> fundFlowSummary,
            DataSection<?> capital, DataSection<?> fundamentals, DataSection<?> research,
            DataSection<?> news, DataSection<?> announcements, DataQualityBreakdown quality,
            boolean crossSourceConsistent, boolean coreCompleteness, boolean authoritativeSources, Instant fetchedAt) {
        this(security,quote,bars,technical,sectors,industryValuation,fundFlow,fundFlowSummary,capital,
                fundamentals,research,news,announcements,quality,crossSourceConsistent,coreCompleteness,
                authoritativeSources,fetchedAt,DataSection.unavailable("估值供应商未配置"));
    }

    public StockResearchSnapshot withValuation(DataSection<com.astock.agent.marketdata.model.ValuationSnapshot> value) {
        return new StockResearchSnapshot(security,quote,bars,technical,sectors,industryValuation,fundFlow,
                fundFlowSummary,capital,fundamentals,research,news,announcements,quality,crossSourceConsistent,
                coreCompleteness,authoritativeSources,fetchedAt,value);
    }

    public StockResearchSnapshot(
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
        this(security, quote, bars, technical, sectors, industryValuation, fundFlow,
                DataSection.unavailable("fund flow summary not provided"), capital, fundamentals,
                research, news, announcements, quality, crossSourceConsistent, coreCompleteness,
                authoritativeSources, fetchedAt);
    }

    public static StockResearchSnapshot empty(SecurityId security) {
        // empty 快照用于离线测试和局部失败场景；不可用不是“数值为 0”。
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
                security, quoteValue, barsValue, technical, sectors, industryValuation, fundFlow, fundFlowSummary,
                capital, fundamentals,
                research, news, announcements, quality, consistent, complete, authoritative, fetchedAt, valuation);
    }
}
