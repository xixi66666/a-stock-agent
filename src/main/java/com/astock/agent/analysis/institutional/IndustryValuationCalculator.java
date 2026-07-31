package com.astock.agent.analysis.institutional;

import com.astock.agent.marketdata.model.IndustryPeerQuote;
import com.astock.agent.marketdata.model.IndustryValuationData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

public final class IndustryValuationCalculator {

    public IndustryValuationData calculate(
            String industryCode, String industryName, String targetCode, List<IndustryPeerQuote> peers) {
        List<IndustryPeerQuote> safePeers = peers == null ? List.of() : List.copyOf(peers);
        List<BigDecimal> pe = positive(safePeers.stream().map(IndustryPeerQuote::peDynamic).toList());
        List<BigDecimal> pb = positive(safePeers.stream().map(IndustryPeerQuote::pb).toList());
        IndustryPeerQuote target = safePeers.stream()
                .filter(peer -> peer.code().equals(targetCode))
                .findFirst()
                .orElse(null);
        BigDecimal targetPe = positiveValue(target == null ? null : target.peDynamic());
        BigDecimal targetPb = positiveValue(target == null ? null : target.pb());
        return new IndustryValuationData(
                industryCode, industryName, safePeers.size(), pe.size(), safePeers.size() - pe.size(),
                pb.size(), safePeers.size() - pb.size(), targetPe, median(pe), percentile(pe, targetPe),
                targetPb, median(pb), percentile(pb, targetPb));
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
