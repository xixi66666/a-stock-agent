package com.astock.agent.marketdata.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.astock.agent.marketdata.model.Exchange;
import com.astock.agent.marketdata.model.FinancialPeriodStatement;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.marketdata.provider.sina.SinaFinanceClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SinaStatementHistoryTest {

    private final SinaFinanceClient sina = new SinaFinanceClient();

    @Test
    void parsesTwelvePeriodsPerStatement() throws Exception {
        Map<LocalDate, Map<String, java.math.BigDecimal>> lrb =
                sina.parseStatementHistory(fixture("sina/statements-history-lrb-600519.json"));

        assertThat(lrb).hasSize(12);
        assertThat(lrb.get(LocalDate.parse("2026-03-31"))).containsKey("营业总收入");
        assertThat(lrb.get(LocalDate.parse("2023-06-30"))).containsKey("归属于母公司所有者的净利润");
    }

    @Test
    void alignsThreeStatementsIntoAscendingHistory() throws Exception {
        SecurityId security = new SecurityId("600519", Exchange.SHANGHAI);
        FinancialStatementHistory history = sina.alignStatementHistory(security,
                sina.parseStatementHistory(fixture("sina/statements-history-lrb-600519.json")),
                sina.parseStatementHistory(fixture("sina/statements-history-fzb-600519.json")),
                sina.parseStatementHistory(fixture("sina/statements-history-llb-600519.json")));

        assertThat(history.periodCount()).isEqualTo(12);
        List<FinancialPeriodStatement> periods = history.periods();
        assertThat(periods.get(0).reportPeriod()).isEqualTo(LocalDate.parse("2023-06-30"));
        assertThat(periods.get(11).reportPeriod()).isEqualTo(LocalDate.parse("2026-03-31"));
        FinancialPeriodStatement latest = periods.get(11);
        assertThat(latest.operatingRevenue()).isEqualByComparingTo("57130000000");
        assertThat(latest.totalAssets()).isEqualByComparingTo("345900000000");
        assertThat(latest.operatingCashFlow()).isEqualByComparingTo("12400000000");
        assertThat(latest.equityAttributable()).isEqualByComparingTo("286800000000");
        assertThat(latest.shareCapital()).isEqualByComparingTo("1256000000");
    }

    @Test
    void toleratesMissingTableAndPeriod() throws Exception {
        SecurityId security = new SecurityId("600519", Exchange.SHANGHAI);
        FinancialStatementHistory history = sina.alignStatementHistory(security,
                sina.parseStatementHistory(fixture("sina/statements-history-lrb-600519.json")),
                sina.parseStatementHistory(fixture("sina/statements-history-fzb-600519.json")),
                Map.of());

        assertThat(history.periodCount()).isEqualTo(12);
        assertThat(history.periods().get(0).operatingCashFlow()).isNull();
    }

    private static String fixture(String name) throws Exception {
        try (var stream = SinaStatementHistoryTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
