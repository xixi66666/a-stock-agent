package com.astock.agent.web;

import com.astock.agent.analysis.ResearchAggregationService;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.TechnicalAnalysisService;
import com.astock.agent.technical.Timeframe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private static final List<StockSearchResult> POPULAR = List.of(
            new StockSearchResult("600519", "贵州茅台", "SHANGHAI"),
            new StockSearchResult("000001", "平安银行", "SHENZHEN"),
            new StockSearchResult("300750", "宁德时代", "SHENZHEN"),
            new StockSearchResult("601318", "中国平安", "SHANGHAI"),
            new StockSearchResult("000858", "五粮液", "SHENZHEN"));
    private final Function<SecurityId, StockResearchSnapshot> research;
    private final TechnicalAnalysisService technicalService;

    @Autowired
    public StockController(ResearchAggregationService research, TechnicalAnalysisService technicalService) {
        this(research::research, technicalService);
    }

    StockController(Function<SecurityId, StockResearchSnapshot> research) {
        this(research, null);
    }

    private StockController(
            Function<SecurityId, StockResearchSnapshot> research,
            TechnicalAnalysisService technicalService) {
        this.research = research;
        this.technicalService = technicalService;
    }

    @GetMapping("/search")
    public List<StockSearchResult> search(@RequestParam("q") String query) {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 20
                || query.contains("<") || query.contains(">")) {
            throw new IllegalArgumentException("Search query is invalid");
        }
        String normalized = query.trim();
        if (normalized.matches("\\d{6}")) {
            SecurityId id = SecurityId.parse(normalized);
            return POPULAR.stream().filter(item -> item.code().equals(id.code())).findFirst()
                    .map(List::of)
                    .orElseGet(() -> List.of(new StockSearchResult(id.code(), id.code(), id.exchange().name())));
        }
        return POPULAR.stream().filter(item -> item.name().contains(normalized)).toList();
    }

    @GetMapping("/{code}/snapshot")
    public StockResearchSnapshot snapshot(@PathVariable String code) {
        return research.apply(SecurityId.parse(code));
    }

    @GetMapping("/{code}/technical")
    public DataSection<?> technical(
            @PathVariable String code,
            @RequestParam(defaultValue = "DAILY") Timeframe timeframe) {
        StockResearchSnapshot snapshot = snapshot(code);
        if (timeframe == Timeframe.DAILY || technicalService == null) {
            return snapshot.technical();
        }
        if (snapshot.bars().payload().isEmpty() || snapshot.bars().provenance().isEmpty()) {
            return DataSection.unavailable("K-line data is unavailable for " + timeframe);
        }
        return DataSection.healthy(
                technicalService.analyze(snapshot.bars().payload().orElseThrow(), timeframe),
                snapshot.bars().provenance().orElseThrow());
    }

    @GetMapping("/{code}/sources")
    public Map<String, Object> sources(@PathVariable String code) {
        StockResearchSnapshot snapshot = snapshot(code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quality", snapshot.quality());
        result.put("quote", snapshot.quote());
        result.put("bars", snapshot.bars());
        result.put("technical", snapshot.technical());
        result.put("sectors", snapshot.sectors());
        result.put("industryValuation", snapshot.industryValuation());
        result.put("fundFlow", snapshot.fundFlow());
        result.put("fundFlowSummary", snapshot.fundFlowSummary());
        result.put("capital", snapshot.capital());
        result.put("fundamentals", snapshot.fundamentals());
        result.put("research", snapshot.research());
        result.put("news", snapshot.news());
        result.put("announcements", snapshot.announcements());
        return result;
    }

    public record StockSearchResult(String code, String name, String exchange) {
    }
}
