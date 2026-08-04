package com.astock.agent.analysis.institutional;

import com.astock.agent.marketdata.model.IndustryPeerQuote;
import com.astock.agent.marketdata.model.IndustryPeerComparison;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.PeerSelectionReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IndustryValuationCalculator {

    public IndustryValuationCalculation calculate(
            String industryCode, String industryName, String targetCode, List<IndustryPeerQuote> peers) {
        List<IndustryPeerQuote> safePeers = peers == null ? List.of()
                : peers.stream().filter(peer -> peer != null).toList();
        List<BigDecimal> pe = positive(safePeers.stream().map(IndustryPeerQuote::peDynamic).toList());
        List<BigDecimal> pb = positive(safePeers.stream().map(IndustryPeerQuote::pb).toList());
        IndustryPeerQuote target = safePeers.stream()
                .filter(peer -> targetCode.equals(peer.code()))
                .findFirst()
                .orElse(null);
        BigDecimal targetPe = positiveValue(target == null ? null : target.peDynamic());
        BigDecimal targetPb = positiveValue(target == null ? null : target.pb());
        BigDecimal targetMarketValue = positiveValue(target == null ? null : target.totalMarketValueYuan());
        List<IndustryPeerQuote> eligible = safePeers.stream()
                .filter(peer -> peer.code() != null && !peer.code().isBlank())
                .filter(peer -> !targetCode.equals(peer.code()))
                .filter(peer -> positiveValue(peer.totalMarketValueYuan()) != null)
                .toList();
        List<IndustryPeerQuote> nearby = targetMarketValue == null ? List.of() : eligible.stream()
                .sorted(Comparator
                        .comparing((IndustryPeerQuote peer) -> peer.totalMarketValueYuan()
                                .subtract(targetMarketValue).abs())
                        .thenComparing(IndustryPeerQuote::code))
                .limit(5)
                .toList();
        List<IndustryPeerQuote> leaders = eligible.stream()
                .sorted(Comparator.comparing(IndustryPeerQuote::totalMarketValueYuan)
                        .reversed().thenComparing(IndustryPeerQuote::code))
                .limit(3)
                .toList();

        Map<String, IndustryPeerQuote> selected = new LinkedHashMap<>();
        Map<String, EnumSet<PeerSelectionReason>> reasons = new LinkedHashMap<>();
        addSelections(nearby, PeerSelectionReason.MARKET_CAP_NEARBY, selected, reasons);
        addSelections(leaders, PeerSelectionReason.INDUSTRY_LEADER, selected, reasons);
        List<IndustryPeerComparison> comparisons = selected.entrySet().stream()
                .map(entry -> comparison(entry.getValue(), targetPe, targetPb, reasons.get(entry.getKey())))
                .toList();
        IndustryValuationData data = new IndustryValuationData(
                industryCode, industryName, safePeers.size(), pe.size(), safePeers.size() - pe.size(),
                pb.size(), safePeers.size() - pb.size(), targetPe, median(pe), percentile(pe, targetPe),
                targetPb, median(pb), percentile(pb, targetPb), comparisons);
        List<String> issues = targetMarketValue == null
                ? List.of("Target market value is unavailable; nearby peers were omitted")
                : List.of();
        return new IndustryValuationCalculation(data, issues);
    }

    private static void addSelections(
            List<IndustryPeerQuote> peers,
            PeerSelectionReason reason,
            Map<String, IndustryPeerQuote> selected,
            Map<String, EnumSet<PeerSelectionReason>> reasons) {
        for (IndustryPeerQuote peer : peers) {
            selected.putIfAbsent(peer.code(), peer);
            reasons.computeIfAbsent(peer.code(), ignored -> EnumSet.noneOf(PeerSelectionReason.class)).add(reason);
        }
    }

    private static IndustryPeerComparison comparison(
            IndustryPeerQuote peer,
            BigDecimal targetPe,
            BigDecimal targetPb,
            EnumSet<PeerSelectionReason> reasons) {
        return new IndustryPeerComparison(
                peer.code(), peer.name(), peer.peDynamic(), peer.pb(), peer.totalMarketValueYuan(),
                premium(targetPe, peer.peDynamic()), premium(targetPb, peer.pb()), List.copyOf(reasons));
    }

    private static BigDecimal premium(BigDecimal target, BigDecimal peer) {
        if (positiveValue(target) == null || positiveValue(peer) == null) {
            return null;
        }
        return target.divide(peer, 10, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private static List<BigDecimal> positive(List<BigDecimal> values) {
        return values.stream()
                .filter(value -> value != null && value.signum() > 0)
                .sorted()
                .toList();
    }

    private static BigDecimal positiveValue(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) {
            return values.get(middle);
        }
        return values.get(middle - 1).add(values.get(middle))
                .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static BigDecimal percentile(List<BigDecimal> values, BigDecimal target) {
        if (values.isEmpty() || target == null) {
            return null;
        }
        long atOrBelow = values.stream().filter(value -> value.compareTo(target) <= 0).count();
        return BigDecimal.valueOf(atOrBelow * 100L)
                .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }
}
