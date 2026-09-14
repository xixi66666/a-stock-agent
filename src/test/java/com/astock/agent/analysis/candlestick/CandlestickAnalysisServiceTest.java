package com.astock.agent.analysis.candlestick;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.technical.BarSeriesFactory;
import com.astock.agent.technical.Timeframe;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
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

    @Test
    void latestSessionReviewsYesterdayBeforeCloseWithoutFutureConfirmation() {
        var reviewService = new CandlestickAnalysisService(new BarSeriesFactory(),
                Clock.fixed(Instant.parse("2026-09-08T06:00:00Z"), ZoneOffset.UTC));
        var bars = decliningBarsEndingOn(LocalDate.parse("2026-09-04"), 24);
        bars.add(bar("2026-09-07", 84, 91, 83.8, 84.3, 1_200_000));
        bars.add(bar("2026-09-08", 85, 89, 84.8, 88, 1_600_000));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .valueToTree(reviewService.analyze(bars, Timeframe.DAILY));
        var review = json.path("previousSession");
        assertThat(review.path("bar").path("close").decimalValue()).isEqualByComparingTo("84.3");
        assertThat(review.path("bodyLength").decimalValue()).isEqualByComparingTo("0.3");
        assertThat(review.path("upperShadow").decimalValue()).isEqualByComparingTo("6.7");
        assertThat(review.path("lowerShadow").decimalValue()).isEqualByComparingTo("0.2");
        assertThat(review.path("volumeRatio").decimalValue()).isEqualByComparingTo("1.28");
        assertThat(review.path("candleType").asText()).isEqualTo("阳线");
        assertThat(review.path("changePercent").decimalValue()).isNegative();
        assertThat(review.path("signals").toString()).contains("INVERTED_HAMMER", "AWAITING_CONFIRMATION");
        assertThat(review.path("signals").toString()).doesNotContain("2026-09-08");
    }

    @Test
    void previousSessionUsesFridayOnMondayAndHandlesFlatCandleWithoutFakeRatios() {
        var reviewService = new CandlestickAnalysisService(new BarSeriesFactory(),
                Clock.fixed(Instant.parse("2026-09-07T02:00:00Z"), ZoneOffset.UTC));
        var bars = decliningBarsEndingOn(LocalDate.parse("2026-09-03"), 24);
        bars.add(bar("2026-09-04", 89, 89, 89, 89, 0));
        bars.add(bar("2026-09-07", 85, 89, 84, 88, 100));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var review = mapper.valueToTree(reviewService.analyze(bars, Timeframe.DAILY)).path("previousSession");
        assertThat(review.path("bar").path("date").asText()).isEqualTo("2026-09-04");
        assertThat(review.path("shape").asText()).isEqualTo("无振幅线");
        assertThat(review.path("bodyPercent").isNull()).isTrue();
        assertThat(review.path("signals").isEmpty()).isTrue();
        assertThat(review.path("interpretation").asText()).contains("无法");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "2026-09-08T07:04:59Z,2026-09-07",
            "2026-09-08T07:05:00Z,2026-09-08",
            "2026-09-08T08:00:00Z,2026-09-08"})
    void latestCompletedSessionSwitchesAtCloseSafetyTime(String instant, String expectedDate) {
        var reviewService = new CandlestickAnalysisService(new BarSeriesFactory(),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        var bars = decliningBarsEndingOn(LocalDate.parse("2026-09-04"), 24);
        bars.add(bar("2026-09-07", 85, 90, 84, 88, 1_000_000));
        bars.add(bar("2026-09-08", 88, 93, 87, 91, 1_300_000));
        // 即使供应商误带未来日期，也不能用它代替当天已收盘数据。
        bars.add(bar("2026-09-09", 91, 95, 90, 94, 1_500_000));
        var review = reviewService.analyze(bars, Timeframe.DAILY).previousSession();
        assertThat(review.bar().date()).isEqualTo(LocalDate.parse(expectedDate));
        assertThat(review.dateNote()).contains("15:05", expectedDate);
    }

    @Test
    void afterCloseWithMissingTodayKeepsActualAvailableDate() {
        var reviewService = new CandlestickAnalysisService(new BarSeriesFactory(),
                Clock.fixed(Instant.parse("2026-09-08T08:00:00Z"), ZoneOffset.UTC));
        var bars = decliningBarsEndingOn(LocalDate.parse("2026-09-07"), 24);
        assertThat(reviewService.analyze(bars, Timeframe.DAILY).previousSession().bar().date())
                .isEqualTo(LocalDate.parse("2026-09-07"));
    }

    @Test
    void previousSessionFollowsSelectedTimeframeInsteadOfAlwaysUsingDailyBars() {
        var reviewService = new CandlestickAnalysisService(new BarSeriesFactory(),
                Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC));
        var bars = weekdayBars(LocalDate.parse("2024-01-02"), LocalDate.parse("2026-09-08"));

        var daily = reviewService.analyze(bars, Timeframe.DAILY).previousSession();
        var weekly = reviewService.analyze(bars, Timeframe.WEEKLY).previousSession();

        assertThat(daily.bar().date()).isEqualTo(LocalDate.parse("2026-09-07"));
        assertThat(daily.trendEvidence()).contains("此前 5 根日线");
        assertThat(daily.followUp()).contains("后续完整日线", "跨日");
        assertThat(weekly.bar().date()).isEqualTo(LocalDate.parse("2026-09-04"));
        assertThat(weekly.trendEvidence()).contains("此前 5 根周线");
        assertThat(weekly.followUp()).contains("后续完整周线", "跨周");
        assertThat(weekly.dateNote()).contains("自然周聚合");
        assertThat(weekly.interpretation()).contains("周线");
    }

    @Test
    void previousSessionAggregatesWeeklyOpenHighLowCloseVolumeAndChange() {
        var reviewService = new CandlestickAnalysisService(new BarSeriesFactory(),
                Clock.fixed(Instant.parse("2026-09-09T02:00:00Z"), ZoneOffset.UTC));
        var bars = weekdayBars(LocalDate.parse("2024-01-02"), LocalDate.parse("2026-09-08"));
        // 上一完整周：2026-08-31（周一）至 2026-09-04（周五）
        replaceBar(bars, bar("2026-08-31", 10.0, 12.0, 9.0, 11.0, 100));
        replaceBar(bars, bar("2026-09-01", 11.0, 15.0, 10.5, 14.0, 200));
        replaceBar(bars, bar("2026-09-02", 14.0, 14.2, 12.5, 13.0, 300));
        replaceBar(bars, bar("2026-09-03", 13.0, 13.6, 12.0, 12.5, 400));
        replaceBar(bars, bar("2026-09-04", 12.5, 14.5, 12.4, 13.5, 500));
        // 再上一周周五收盘，用于验证周环比而不是日环比。
        replaceBar(bars, bar("2026-08-28", 9.8, 10.2, 9.5, 10.0, 1_000));

        var weekly = reviewService.analyze(bars, Timeframe.WEEKLY).previousSession();

        assertThat(weekly.bar().date()).isEqualTo(LocalDate.parse("2026-09-04"));
        assertThat(weekly.bar().open()).isEqualByComparingTo("10.00");
        assertThat(weekly.bar().high()).isEqualByComparingTo("15.00");
        assertThat(weekly.bar().low()).isEqualByComparingTo("9.00");
        assertThat(weekly.bar().close()).isEqualByComparingTo("13.50");
        assertThat(weekly.bar().volumeShares()).isEqualByComparingTo("1500");
        assertThat(weekly.changePercent()).isEqualByComparingTo("35.00");
        assertThat(weekly.locationEvidence()).contains("20 根周线区间");
    }

    @Test
    void previousSessionUsesLastCompletedMonthlyCandleWhenMonthlySelected() {
        var reviewService = new CandlestickAnalysisService(new BarSeriesFactory(),
                Clock.fixed(Instant.parse("2026-09-09T02:00:00Z"), ZoneOffset.UTC));
        var bars = weekdayBars(LocalDate.parse("2024-01-02"), LocalDate.parse("2026-09-08"));

        var monthly = reviewService.analyze(bars, Timeframe.MONTHLY).previousSession();

        assertThat(monthly.bar().date()).isEqualTo(LocalDate.parse("2026-08-31"));
        assertThat(monthly.trendEvidence()).contains("此前 5 根月线");
        assertThat(monthly.locationEvidence()).contains("20 根月线区间");
        assertThat(monthly.dateNote()).contains("自然月聚合");
        assertThat(monthly.interpretation()).contains("月线");
    }

    @Test
    void upperShadowExplainsFailedBreakoutUsingPriorRangeAndVolume() {
        var bars = risingBars(24);
        bars.add(bar("2026-08-25", 102, 110, 101, 103, 1_600_000));
        var review = service.analyze(bars, Timeframe.DAILY).previousSession();
        assertThat(review.shape()).isEqualTo("长上影线");
        assertThat(review.interpretation()).contains("103.80", "110.00", "103.00",
                "冲高未站稳前高", "压力尚未消化", "此前 5 根日线收盘趋势向上", "倍", "放量")
                .doesNotContain("结合所处位置观察");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "104,112,103,105,收盘已突破前高",
            "102,110,101,103.8,冲高未站稳前高",
            "101,103.8,100,101.3,冲高未站稳前高",
            "96,102,95,97,尚未触及前高",
            "80,88,79,81,收盘跌破此前区间低点"})
    void upperShadowDistinguishesClosingPosition(double open, double high, double low,
            double close, String assessment) {
        var bars = risingBars(24);
        bars.add(bar("2026-08-25", open, high, low, close, 900_000));
        var review = service.analyze(bars, Timeframe.DAILY).previousSession();
        assertThat(review.shape()).isEqualTo("长上影线");
        assertThat(review.interpretation()).contains(assessment, "未达到放量阈值", "尚无后续完整日线");
        if (close > 103.8) assertThat(review.interpretation()).doesNotContain("前高压力尚未消化");
    }

    @Test
    void upperShadowDisclosesShortHistoryAndUnavailableVolume() {
        var bars = risingBars(19);
        bars.add(bar("2026-08-25", 97, 105, 96, 98, 0));
        var review = service.analyze(bars, Timeframe.DAILY).previousSession();
        assertThat(review.interpretation()).contains("仅有 19 根", "不足 20 根", "量能证据不足");
        assertThat(review.volumeRatio()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "88,90,80,89,下探前低后收回",
            "85,87,78,86,收盘仍低于前低",
            "87.2,89,80,88.2,收盘仅回到前低",
            "96,98,89,97,区间内回落后的承接",
            "110,114,95,112,收盘已突破前高"})
    void lowerShadowAssessesSupportFromActualClosingPosition(double open, double high,
            double low, double close, String conclusion) {
        var bars = decliningBars(24);
        bars.add(bar("2026-08-25", open, high, low, close, 1_600_000));
        var review = service.analyze(bars, Timeframe.DAILY).previousSession();
        assertThat(review.shape()).isEqualTo("长下影线");
        assertThat(review.interpretation()).contains(conclusion, "88.20", "109.20",
                "此前 5 根日线收盘趋势向下", "倍", "放量")
                .doesNotContain("后续能否守住低点仍需验证");
        assertThat(review.followUp()).contains("收盘严格高于", "收盘严格低于", "尚无后续完整日线")
                .doesNotContain("观察后续", "结合成交量复核");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "100,100,100,100,无振幅线",
            "100,105,95,100,十字线轮廓",
            "100,105,95,101,小实体线",
            "98,105,95,102,普通实体线",
            "96,105,95,104,实体主导"})
    void everySessionShapeIncludesItsComputedContext(double open, double high, double low,
            double close, String shape) {
        var bars = decliningBars(24);
        bars.add(bar("2026-08-25", open, high, low, close, 900_000));
        var review = service.analyze(bars, Timeframe.DAILY).previousSession();
        assertThat(review.shape()).isEqualTo(shape);
        assertThat(review.interpretation()).contains("此前 5 根日线收盘趋势向下", "88.20", "109.20",
                "区间内", "倍", "当前结论")
                .doesNotContain("需结合", "继续观察", "也可能只是", "仍需验证");
    }

    @Test
    void lowerShadowWithShortHistoryDoesNotInventVolumeConfirmation() {
        var bars = decliningBars(19);
        bars.add(bar("2026-08-25", 92.2, 95, 80, 93.2, 0));
        var review = service.analyze(bars, Timeframe.DAILY).previousSession();
        assertThat(review.interpretation()).contains("收盘仅回到前低", "仅有 19 根", "量能证据不足");
        assertThat(review.volumeRatio()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "100,100,100,100,第三章", "100,105,95,100,第三章",
            "100,105,95,101,第三章", "98,105,95,102,第三章",
            "96,105,95,104,第三章", "102,110,101,103,第五章",
            "88,90,80,89,第四章"})
    void sessionProvidesOriginalBookExcerptSeparatelyFromItsInterpretation(double open,
            double high, double low, double close, String chapter) {
        var bars = decliningBars(24);
        bars.add(bar("2026-08-25", open, high, low, close, 900_000));
        var review = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .valueToTree(service.analyze(bars, Timeframe.DAILY)).path("previousSession");
        var excerpts = review.path("bookExcerpts");
        assertThat(excerpts.isArray()).isTrue();
        assertThat(excerpts.size()).isEqualTo(1);
        assertThat(excerpts.get(0).path("chapter").asText()).contains(chapter);
        assertThat(excerpts.get(0).path("text").asText()).isNotBlank();
        assertThat(excerpts.get(0).path("sourceLocator").asText()).startsWith("content/chapters/");
        assertThat(excerpts.get(0).path("scope").asText()).isNotBlank();
    }

    private static List<DailyBar> weekdayBars(LocalDate start, LocalDate endInclusive) {
        List<DailyBar> bars = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(endInclusive); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            double base = 100 + Math.floorMod(date.toEpochDay(), 17);
            bars.add(bar(date.toString(), base, base + 2, base - 2, base + 1, 1_000_000));
        }
        return bars;
    }

    private static void replaceBar(List<DailyBar> bars, DailyBar replacement) {
        for (int index = 0; index < bars.size(); index++) {
            if (bars.get(index).date().equals(replacement.date())) {
                bars.set(index, replacement);
                return;
            }
        }
        throw new IllegalArgumentException("Missing bar for " + replacement.date());
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
