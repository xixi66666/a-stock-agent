package com.astock.agent.technical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class CustomIndicatorsTest {

    @Test
    void calculatesHistoricalVarAndCvarFromWorstTail() {
        List<Double> returns = List.of(-0.10, -0.05, -0.02, 0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.08);

        assertThat(CustomIndicators.historicalVar(returns, 0.80)).isCloseTo(-0.05, within(0.0001));
        assertThat(CustomIndicators.historicalCvar(returns, 0.80)).isCloseTo(-0.075, within(0.0001));
    }

    @Test
    void calculatesPsychologicalLineAsUpDayRatio() {
        assertThat(CustomIndicators.psy(List.of(10.0, 11.0, 10.0, 12.0, 13.0), 4))
                .isCloseTo(75.0, within(0.0001));
    }
}
