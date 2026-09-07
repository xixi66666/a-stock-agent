package com.astock.agent.analysis.candlestick;

import com.astock.agent.technical.Timeframe;
import com.astock.agent.knowledge.KnowledgeEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 尼森蜡烛图分析的结构化结果。
 *
 * <p>记录将行情事实、确定性计算、形态解释和方法边界分开，分数仅表示当前证据的
 * 汇聚程度，不是上涨或下跌概率，也不构成交易指令。</p>
 */
public record CandlestickAnalysis(
        Timeframe timeframe,
        LocalDate asOf,
        int analyzedBars,
        CompletionStatus completion,
        TrendContext trend,
        List<PatternSignal> signals,
        ConfluenceAssessment confluence,
        RiskAssessment risk,
        List<PriceLevel> levels,
        Methodology methodology,
        List<String> limitations) {

    public CandlestickAnalysis {
        signals = List.copyOf(signals);
        levels = List.copyOf(levels);
        limitations = List.copyOf(limitations);
    }

    public enum Direction { BULLISH, BEARISH, NEUTRAL }
    public enum Trend { UP, DOWN, SIDEWAYS, INSUFFICIENT }
    public enum ConfirmationStatus { CONFIRMED, AWAITING_CONFIRMATION, NOT_REQUIRED, INVALIDATED }
    public enum LevelRole { SUPPORT, RESISTANCE }
    public enum LevelStatus { ACTIVE, TESTED, BROKEN }
    public enum EvidenceKind { CANDLESTICK, TREND, LOCATION, MOMENTUM, VOLUME, LEVEL }

    public record CompletionStatus(boolean latestPeriodComplete, LocalDate excludedDate, String note) {}

    public record TrendContext(
            Trend shortTerm,
            Trend primary,
            BigDecimal shortReturnPercent,
            BigDecimal closeVsSma20Percent,
            String evidence) {}

    public record PatternSignal(
            String id,
            String name,
            String englishName,
            String family,
            Direction direction,
            LocalDate startDate,
            LocalDate endDate,
            int evidenceScore,
            String evidenceGrade,
            ConfirmationStatus confirmationStatus,
            boolean idealGeometry,
            List<String> constructionEvidence,
            String trendEvidence,
            String locationEvidence,
            String confirmationEvidence,
            BigDecimal invalidationPrice,
            String invalidationRule,
            String sourceChapter) {

        public PatternSignal {
            constructionEvidence = List.copyOf(constructionEvidence);
        }
    }

    public record ConfluenceFactor(
            EvidenceKind kind,
            String label,
            Direction direction,
            boolean aligned,
            int weight,
            String evidence) {}

    public record ConfluenceAssessment(
            Direction direction,
            int score,
            String grade,
            List<ConfluenceFactor> factors,
            String conclusion) {

        public ConfluenceAssessment {
            factors = List.copyOf(factors);
        }
    }

    public record RiskAssessment(
            Direction direction,
            BigDecimal entryReference,
            BigDecimal invalidationPrice,
            BigDecimal riskPerShare,
            BigDecimal targetReference,
            BigDecimal rewardRiskRatio,
            String quality,
            List<String> notes) {

        public RiskAssessment {
            notes = List.copyOf(notes);
        }
    }

    public record PriceLevel(
            LevelRole role,
            BigDecimal lower,
            BigDecimal upper,
            String origin,
            LevelStatus status,
            String validationRule) {}

    public record BookReference(String chapter, String topic) {}

    public record Methodology(
            String ruleVersion,
            List<String> analysisSequence,
            List<BookReference> bookReferences,
            Map<String, String> transparentThresholds,
            String scoreMeaning,
            List<KnowledgeEntry> knowledge) {

        public Methodology {
            analysisSequence = List.copyOf(analysisSequence);
            bookReferences = List.copyOf(bookReferences);
            transparentThresholds = Map.copyOf(transparentThresholds);
            knowledge = List.copyOf(knowledge);
        }
    }
}
