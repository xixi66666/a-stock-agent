package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.FinancialStatementHistory;
import com.astock.agent.marketdata.model.IndustryValuationData;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.List;

public interface ResearchGateway {
    default DataSection<com.astock.agent.marketdata.model.ValuationSnapshot> valuation(SecurityId security) {
        return DataSection.unavailable("估值供应商未配置");
    }
    DataSection<Quote> quote(SecurityId security);
    DataSection<List<DailyBar>> bars(SecurityId security);
    DataSection<List<DailyBar>> crossCheckBars(SecurityId security);
    DataSection<?> sectors(SecurityId security);
    DataSection<IndustryValuationData> industryValuation(SecurityId security);
    DataSection<?> fundFlow(SecurityId security);
    DataSection<?> capital(SecurityId security);
    DataSection<?> fundamentals(SecurityId security);
    DataSection<FinancialStatementHistory> financialHistory(SecurityId security);
    DataSection<?> research(SecurityId security);
    DataSection<?> news(SecurityId security);
    DataSection<?> announcements(SecurityId security);
}
