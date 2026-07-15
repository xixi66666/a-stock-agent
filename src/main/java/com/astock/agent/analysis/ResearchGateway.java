package com.astock.agent.analysis;

import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.DailyBar;
import com.astock.agent.marketdata.model.Quote;
import com.astock.agent.marketdata.model.SecurityId;
import java.util.List;

public interface ResearchGateway {
    DataSection<Quote> quote(SecurityId security);
    DataSection<List<DailyBar>> bars(SecurityId security);
    DataSection<List<DailyBar>> crossCheckBars(SecurityId security);
    DataSection<?> sectors(SecurityId security);
    DataSection<?> fundFlow(SecurityId security);
    DataSection<?> capital(SecurityId security);
    DataSection<?> fundamentals(SecurityId security);
    DataSection<?> research(SecurityId security);
    DataSection<?> news(SecurityId security);
    DataSection<?> announcements(SecurityId security);
}
