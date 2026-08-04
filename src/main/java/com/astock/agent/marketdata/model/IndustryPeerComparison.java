package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.util.List;

public record IndustryPeerComparison(
        String code,
        String name,
        BigDecimal peDynamic,
        BigDecimal pb,
        BigDecimal totalMarketValueYuan,
        BigDecimal targetPePremiumPercent,
        BigDecimal targetPbPremiumPercent,
        List<PeerSelectionReason> selectionReasons) {

    public IndustryPeerComparison {
        selectionReasons = selectionReasons == null ? List.of() : List.copyOf(selectionReasons);
    }
}
