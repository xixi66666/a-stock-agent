package com.astock.agent.marketdata.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityIdTest {

    @ParameterizedTest
    @CsvSource({
        "600519,SHANGHAI,sh600519,1.600519",
        "000001,SHENZHEN,sz000001,0.000001",
        "300750,SHENZHEN,sz300750,0.300750",
        "830799,BEIJING,bj830799,0.830799",
        "920001,BEIJING,bj920001,0.920001"
    })
    void normalizesMarketPrefixes(String code, Exchange exchange, String tencentCode, String eastmoneySecId) {
        SecurityId id = SecurityId.parse(code);

        assertThat(id.exchange()).isEqualTo(exchange);
        assertThat(id.tencentCode()).isEqualTo(tencentCode);
        assertThat(id.eastmoneySecId()).isEqualTo(eastmoneySecId);
        assertThat(id.code()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "ABC519", "700001", " 600519"})
    void rejectsUnsupportedCodes(String code) {
        assertThatThrownBy(() -> SecurityId.parse(code))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
