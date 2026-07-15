package com.astock.agent.technical;

import java.time.LocalDate;
import java.util.List;

public record TechnicalSnapshot(Timeframe timeframe, LocalDate calculatedAt, List<IndicatorCard> cards) {

    public TechnicalSnapshot {
        cards = List.copyOf(cards);
    }

    public IndicatorCard card(String id) {
        return cards.stream()
                .filter(card -> card.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown indicator: " + id));
    }
}
