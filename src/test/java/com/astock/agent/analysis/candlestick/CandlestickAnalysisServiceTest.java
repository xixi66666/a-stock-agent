package com.astock.agent.analysis.candlestick;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.technical.BarSeriesFactory;
import com.astock.agent.technical.Timeframe;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandlestickAnalysisServiceTest {

    private final CandlestickAnalysisService service = new CandlestickAnalysisService(
            new BarSeriesFactory(), Clock.fixed(Instant.parse("2026-09-04T08:00:00Z"), ZoneOffset.UTC));

    @Test
    void identifiesBullishEngulfingOnlyWithPriorDowntrendAndExplainsInvalidation() {
        List<DailyBar> bars = decliningBars(24);
        bars.add(bar("2026-08-25", 88.0, 88.5, 84.5, 85.0, 1_000_000));
        bars.add(bar("2026-08-26", 84.0, 89.5, 83.5, 89.0, 1_600_000));

        CandlestickAnalysis result = service.analyze(bars, Timeframe.DAILY);

        CandlestickAnalysis.PatternSignal signal = result.signals().stream()
                .filter(item -> item.id().equals("BULLISH_ENGULFING"))
                .findFirst()
                .orElseThrow();
        assertThat(signal.direction()).isEqualTo(CandlestickAnalysis.Direction.BULLISH);
        assertThat(signal.startDate()).isEqualTo(LocalDate.parse("2026-08-25"));
        assertThat(signal.endDate()).isEqualTo(LocalDate.parse("2026-08-26"));
        assertThat(signal.constructionEvidence()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(signal.invalidationPrice()).isEqualByComparingTo("83.50");
        assertThat(signal.sourceChapter()).contains("第四章");
        assertThat(result.trend().shortTerm()).isEqualTo(CandlestickAnalysis.Trend.DOWN);
        assertThat(result.methodology().analysisSequence())
                .containsExactly("前置趋势", "形态构成", "相对位置", "后续确认", "支撑阻挡与失效", "风险报偿", "其他技术信号");
    }

    @Test
    void classifiesTheSameUmbrellaGeometryByTrendAndRequiresHangingManConfirmation() {
        List<DailyBar> falling = decliningBars(24);
        falling.add(bar("2026-08-25", 86.0, 86.4, 80.0, 86.3, 1_300_000));

        CandlestickAnalysis hammerAnalysis = service.analyze(falling, Timeframe.DAILY);

        CandlestickAnalysis.PatternSignal hammer = signal(hammerAnalysis, "HAMMER");
        assertThat(hammer.name()).isEqualTo("锤子线");
        assertThat(hammer.confirmationStatus()).isEqualTo(CandlestickAnalysis.ConfirmationStatus.NOT_REQUIRED);
        assertThat(hammer.invalidationPrice()).isEqualByComparingTo("80.00");

        List<DailyBar> rising = risingBars(24);
        rising.add(bar("2026-08-25", 104.0, 104.4, 97.5, 104.3, 1_400_000));
        rising.add(bar("2026-08-26", 103.8, 104.0, 99.0, 99.5, 1_700_000));

        CandlestickAnalysis.PatternSignal hangingMan = signal(service.analyze(rising, Timeframe.DAILY), "HANGING_MAN");
        assertThat(hangingMan.name()).isEqualTo("上吊线");
        assertThat(hangingMan.confirmationStatus()).isEqualTo(CandlestickAnalysis.ConfirmationStatus.CONFIRMED);
        assertThat(hangingMan.confirmationEvidence()).contains("下一期");
    }

    @Test
    void excludesTheCurrentDailyBarBeforeTheMarketClose() {
        CandlestickAnalysisService intradayService = new CandlestickAnalysisService(
                new BarSeriesFactory(), Clock.fixed(Instant.parse("2026-09-04T06:00:00Z"), ZoneOffset.UTC));
        List<DailyBar> bars = decliningBarsEndingOn(LocalDate.parse("2026-09-03"), 24);
        DailyBar previous = bars.getLast();
        bars.set(bars.size() - 1, new DailyBar(previous.date(), bd(88), bd(88.5), bd(84), bd(85),
                bd(1_000_000), bd(85_000_000)));
        bars.add(bar("2026-09-04", 84, 90, 83.5, 89.5, 1_800_000));

        CandlestickAnalysis result = intradayService.analyze(bars, Timeframe.DAILY);

        assertThat(result.completion().latestPeriodComplete()).isFalse();
        assertThat(result.completion().excludedDate()).isEqualTo(LocalDate.parse("2026-09-04"));
        assertThat(result.asOf()).isEqualTo(LocalDate.parse("2026-09-03"));
        assertThat(result.signals()).noneMatch(signal -> signal.endDate().equals(LocalDate.parse("2026-09-04")));
    }

    @Test
    void confirmsEveningStarWithWesternConfluenceLevelsAndRiskReward() {
        List<DailyBar> bars = risingBars(22);
        bars.add(bar("2026-08-23", 101.0, 107.0, 100.5, 106.0, 1_200_000));
        bars.add(bar("2026-08-24", 107.2, 108.0, 106.8, 107.5, 850_000));
        bars.add(bar("2026-08-25", 106.8, 107.0, 100.8, 101.5, 1_900_000));

        CandlestickAnalysis result = service.analyze(bars, Timeframe.DAILY);

        CandlestickAnalysis.PatternSignal signal = signal(result, "EVENING_STAR");
        assertThat(signal.sourceChapter()).contains("第五章");
        assertThat(signal.confirmationStatus()).isEqualTo(CandlestickAnalysis.ConfirmationStatus.CONFIRMED);
        assertThat(signal.constructionEvidence()).anyMatch(text -> text.contains("中点"));
        assertThat(result.confluence().direction()).isEqualTo(CandlestickAnalysis.Direction.BEARISH);
        assertThat(result.confluence().factors()).extracting(CandlestickAnalysis.ConfluenceFactor::kind)
                .contains(CandlestickAnalysis.EvidenceKind.CANDLESTICK,
                        CandlestickAnalysis.EvidenceKind.TREND,
                        CandlestickAnalysis.EvidenceKind.MOMENTUM,
                        CandlestickAnalysis.EvidenceKind.VOLUME,
                        CandlestickAnalysis.EvidenceKind.LEVEL);
        assertThat(result.levels()).anyMatch(level -> level.role() == CandlestickAnalysis.LevelRole.RESISTANCE
                && level.origin().contains("黄昏星"));
        assertThat(result.risk().direction()).isEqualTo(CandlestickAnalysis.Direction.BEARISH);
        assertThat(result.risk().invalidationPrice()).isEqualByComparingTo("108.00");
        assertThat(result.risk().notes()).anyMatch(text -> text.contains("价格目标") || text.contains("结构"));
    }

    @Test
    void identifiesDarkCloudOnlyAfterDeepMidpointPenetration() {
        List<DailyBar> bars = risingBars(24);
        bars.add(bar("2026-08-25", 103.0, 108.5, 102.5, 108.0, 1_200_000));
        bars.add(bar("2026-08-26", 109.0, 109.5, 104.0, 105.0, 1_700_000));

        CandlestickAnalysis.PatternSignal signal = signal(service.analyze(bars, Timeframe.DAILY), "DARK_CLOUD_COVER");

        assertThat(signal.name()).isEqualTo("乌云盖顶形态");
        assertThat(signal.direction()).isEqualTo(CandlestickAnalysis.Direction.BEARISH);
        assertThat(signal.constructionEvidence()).anyMatch(text -> text.contains("中点"));
        assertThat(signal.invalidationPrice()).isEqualByComparingTo("109.50");
    }

    @Test
    void turnsDownwardWindowIntoAClosingPriceResistanceZone() {
        List<DailyBar> bars = risingBars(23);
        bars.add(bar("2026-08-24", 102.0, 103.5, 101.5, 103.0, 1_100_000));
        bars.add(bar("2026-08-25", 98.0, 100.5, 97.5, 99.0, 1_600_000));

        CandlestickAnalysis result = service.analyze(bars, Timeframe.DAILY);
        CandlestickAnalysis.PatternSignal signal = signal(result, "DOWNWARD_WINDOW");

        assertThat(signal.family()).isEqualTo("持续/结构");
        assertThat(signal.sourceChapter()).contains("第七章");
        assertThat(result.levels()).anyMatch(level -> level.role() == CandlestickAnalysis.LevelRole.RESISTANCE
                && level.lower().compareTo(bd(100.5)) == 0
                && level.upper().compareTo(bd(101.5)) == 0
                && level.validationRule().contains("收市价"));
    }

    @Test
    void supportsMirroredBullishAndBearishTwoLineReversals() {
        List<DailyBar> rising = risingBars(24);
        rising.add(bar("2026-08-25", 103.5, 106.5, 103.0, 106.0, 1_050_000));
        rising.add(bar("2026-08-26", 107.0, 107.5, 102.8, 103.0, 1_650_000));

        CandlestickAnalysis.PatternSignal bearish = signal(
                service.analyze(rising, Timeframe.DAILY), "BEARISH_ENGULFING");
        assertThat(bearish.direction()).isEqualTo(CandlestickAnalysis.Direction.BEARISH);
        assertThat(bearish.constructionEvidence()).anyMatch(text -> text.contains("包裹"));

        List<DailyBar> falling = decliningBars(24);
        falling.add(bar("2026-08-25", 88.0, 88.5, 83.5, 84.0, 1_100_000));
        falling.add(bar("2026-08-26", 83.0, 87.0, 82.5, 86.5, 1_700_000));

        CandlestickAnalysis.PatternSignal bullish = signal(
                service.analyze(falling, Timeframe.DAILY), "PIERCING_PATTERN");
        assertThat(bullish.direction()).isEqualTo(CandlestickAnalysis.Direction.BULLISH);
        assertThat(bullish.name()).isEqualTo("刺透形态");
        assertThat(bullish.constructionEvidence()).anyMatch(text -> text.contains("中点"));
    }

    @Test
    void classifiesUpperShadowGeometryByTrendAndWaitsForConfirmation() {
        List<DailyBar> falling = decliningBars(24);
        falling.add(bar("2026-08-25", 84.0, 91.0, 83.8, 84.3, 1_200_000));
        falling.add(bar("2026-08-26", 85.0, 88.5, 84.8, 88.0, 1_600_000));

        CandlestickAnalysis.PatternSignal invertedHammer = signal(
                service.analyze(falling, Timeframe.DAILY), "INVERTED_HAMMER");
        assertThat(invertedHammer.confirmationStatus()).isEqualTo(CandlestickAnalysis.ConfirmationStatus.CONFIRMED);
        assertThat(invertedHammer.name()).isEqualTo("倒锤子线");

        List<DailyBar> rising = risingBars(24);
        rising.add(bar("2026-08-25", 104.0, 111.0, 103.7, 103.8, 1_250_000));
        rising.add(bar("2026-08-26", 103.0, 103.2, 99.5, 100.0, 1_700_000));

        CandlestickAnalysis.PatternSignal shootingStar = signal(
                service.analyze(rising, Timeframe.DAILY), "SHOOTING_STAR");
        assertThat(shootingStar.confirmationStatus()).isEqualTo(CandlestickAnalysis.ConfirmationStatus.CONFIRMED);
        assertThat(shootingStar.invalidationPrice()).isEqualByComparingTo("111.00");
    }

    @Test
    void treatsDojiAtAnUptrendHighAsAWarningAndNotAnAutomaticReversal() {
        List<DailyBar> bars = risingBars(24);
        bars.add(bar("2026-08-25", 104.00, 108.0, 100.0, 104.04, 1_200_000));

        CandlestickAnalysis.PatternSignal doji = signal(service.analyze(bars, Timeframe.DAILY), "DOJI_TOP");

        assertThat(doji.name()).isEqualTo("高位十字线");
        assertThat(doji.confirmationStatus()).isEqualTo(CandlestickAnalysis.ConfirmationStatus.AWAITING_CONFIRMATION);
        assertThat(doji.confirmationEvidence()).contains("不会自动");
        assertThat(doji.sourceChapter()).contains("第八章");
    }

    @Test
    void identifiesHaramiBodiesEvenWithProtrudingWicksAndSameColor() {
        List<DailyBar> bars = decliningBars(24);
        bars.add(bar("2026-08-25", 88, 88.5, 83.5, 84, 1_000_000));
        bars.add(bar("2026-08-26", 86, 90, 82, 85, 900_000));
        var candidate = signal(service.analyze(bars, Timeframe.DAILY), "BULLISH_HARAMI");
        assertThat(candidate.confirmationStatus()).isEqualTo(CandlestickAnalysis.ConfirmationStatus.AWAITING_CONFIRMATION);
        assertThat(candidate.sourceChapter()).contains("第六章");
        assertThat(candidate.invalidationPrice()).isEqualByComparingTo("82");
        bars.add(bar("2026-08-27", 87, 92, 86, 91, 1_300_000));
        assertThat(signal(service.analyze(bars, Timeframe.DAILY), "BULLISH_HARAMI").confirmationStatus())
                .isEqualTo(CandlestickAnalysis.ConfirmationStatus.CONFIRMED);

        List<DailyBar> rising = risingBars(24);
        rising.add(bar("2026-08-25", 103, 109, 102, 108, 1_000_000));
        rising.add(bar("2026-08-26", 106, 109.5, 104, 106, 900_000));
        var cross = signal(service.analyze(rising, Timeframe.DAILY), "BEARISH_HARAMI_CROSS");
        assertThat(cross.name()).isEqualTo("看跌十字孕线");
        assertThat(cross.confirmationStatus()).isEqualTo(CandlestickAnalysis.ConfirmationStatus.AWAITING_CONFIRMATION);
    }

    @Test
    void invalidatedWindowStaysBrokenAfterPriceReturnsAndCannotDriveConfluence() {
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 1; i <= 24; i++) bars.add(bar("2026-08-" + String.format("%02d", i), 100, 101, 99, 100, 1_000_000));
        bars.add(bar("2026-08-25", 103, 104, 102, 103, 1_000_000));
        bars.add(bar("2026-08-26", 103, 104, 100, 100.5, 1_000_000));
        bars.add(bar("2026-08-27", 101, 104, 100, 103, 1_000_000));
        var result = service.analyze(bars, Timeframe.DAILY);
        assertThat(signal(result, "UPWARD_WINDOW").confirmationStatus())
                .isEqualTo(CandlestickAnalysis.ConfirmationStatus.INVALIDATED);
        assertThat(result.confluence().direction()).isEqualTo(CandlestickAnalysis.Direction.NEUTRAL);
        assertThat(result.risk().rewardRiskRatio()).isNull();
        assertThat(result.levels()).anyMatch(level -> level.origin().startsWith("向上窗口")
                && level.status() == CandlestickAnalysis.LevelStatus.BROKEN);
    }

    @Test
    void attachesRelevantBookPrinciplesWithoutMixingCycleOrDecisionAdviceIntoTechnicalScores() throws Exception {
        var bars = decliningBars(24);
        bars.add(bar("2026-08-25", 88, 88.5, 83.5, 84, 1_000_000));
        bars.add(bar("2026-08-26", 86, 90, 82, 85, 900_000));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .valueToTree(service.analyze(bars, Timeframe.DAILY));
        assertThat(json.path("methodology").path("knowledge").toString())
                .contains("nison-harami", "nison-reversal", "requiredEvidence", "sourceLocator")
                .doesNotContain("marks-credit", "naval-judgment");
    }

    @Test
    void awaitingConfirmationDoesNotCountAsAlignedCandlestickEvidence() {
        var bars = decliningBars(24);
        bars.add(bar("2026-08-25", 88, 88.5, 83.5, 84, 1_000_000));
        bars.add(bar("2026-08-26", 86, 90, 82, 85, 900_000));
        var result = service.analyze(bars, Timeframe.DAILY);
        assertThat(result.confluence().factors().stream()
                .filter(factor -> factor.kind() == CandlestickAnalysis.EvidenceKind.CANDLESTICK).findFirst().orElseThrow().aligned())
                .isFalse();
        assertThat(result.confluence().score()).isLessThanOrEqualTo(60);
        assertThat(result.methodology().ruleVersion()).isEqualTo("NISON-CANDLESTICK-1.1");
    }

    @Test
    void coversMirroredHaramiAndRejectsUncontainedBodiesAndMissingTrend() {
        var rising = risingBars(24);
        rising.add(bar("2026-08-25", 103, 109, 102, 108, 1_000_000));
        rising.add(bar("2026-08-26", 105, 110, 104, 106, 900_000));
        assertThat(signal(service.analyze(rising, Timeframe.DAILY), "BEARISH_HARAMI").direction())
                .isEqualTo(CandlestickAnalysis.Direction.BEARISH);
        var falling = decliningBars(24);
        falling.add(bar("2026-08-25", 88, 88.5, 83.5, 84, 1_000_000));
        falling.add(bar("2026-08-26", 86, 90, 82, 86, 900_000));
        assertThat(signal(service.analyze(falling, Timeframe.DAILY), "BULLISH_HARAMI_CROSS").direction())
                .isEqualTo(CandlestickAnalysis.Direction.BULLISH);
        falling.set(falling.size() - 1, bar("2026-08-26", 88, 90, 87, 89, 900_000));
        assertThat(service.analyze(falling, Timeframe.DAILY).signals()).noneMatch(s -> s.id().contains("HARAMI"));
        var flat = new ArrayList<DailyBar>();
        for (int i = 1; i <= 24; i++) flat.add(bar("2026-08-" + String.format("%02d", i), 100, 101, 99, 100, 1_000_000));
        flat.add(bar("2026-08-25", 100, 105, 99, 104, 1_000_000));
        flat.add(bar("2026-08-26", 102, 103, 101, 102.5, 1_000_000));
        assertThat(service.analyze(flat, Timeframe.DAILY).signals()).noneMatch(s -> s.id().contains("HARAMI"));
    }

    private static CandlestickAnalysis.PatternSignal signal(CandlestickAnalysis analysis, String id) {
        return analysis.signals().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<DailyBar> decliningBars(int count) {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-08-01");
        for (int index = 0; index < count; index++) {
            double close = 112 - index;
            bars.add(bar(start.plusDays(index).toString(), close + 0.8, close + 1.2, close - 0.8, close,
                    900_000 + index * 3_000));
        }
        return bars;
    }

    private static List<DailyBar> decliningBarsEndingOn(LocalDate end, int count) {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate start = end.minusDays(count - 1L);
        for (int index = 0; index < count; index++) {
            double close = 112 - index;
            bars.add(bar(start.plusDays(index).toString(), close + 0.8, close + 1.2, close - 0.8, close,
                    900_000 + index * 3_000));
        }
        return bars;
    }

    private static List<DailyBar> risingBars(int count) {
        List<DailyBar> bars = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-08-01");
        for (int index = 0; index < count; index++) {
            double close = 80 + index;
            bars.add(bar(start.plusDays(index).toString(), close - 0.8, close + 0.8, close - 1.2, close,
                    900_000 + index * 3_000));
        }
        return bars;
    }

    private static DailyBar bar(String date, double open, double high, double low, double close, double volume) {
        return new DailyBar(LocalDate.parse(date), bd(open), bd(high), bd(low), bd(close), bd(volume), bd(close * volume));
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }
}
