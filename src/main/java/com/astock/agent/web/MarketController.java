package com.astock.agent.web;

import com.astock.agent.analysis.MarketIndexService;
import com.astock.agent.analysis.MarketIndexSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market")
public final class MarketController {
    private final MarketIndexService service;

    public MarketController(MarketIndexService service) {
        this.service = service;
    }

    @GetMapping("/indices")
    public MarketIndexSnapshot indices() {
        return service.snapshot();
    }
}
