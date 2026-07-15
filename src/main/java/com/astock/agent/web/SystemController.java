package com.astock.agent.web;

import com.astock.agent.marketdata.provider.ProviderAvailability;
import com.astock.agent.marketdata.provider.ProviderHealthRegistry;
import com.astock.agent.marketdata.provider.ProviderId;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final ProviderHealthRegistry healthRegistry;

    public SystemController(ProviderHealthRegistry healthRegistry) {
        this.healthRegistry = healthRegistry;
    }

    @GetMapping("/data-sources")
    public Map<ProviderId, ProviderAvailability> dataSources() {
        Map<ProviderId, ProviderAvailability> result = new EnumMap<>(ProviderId.class);
        for (ProviderId provider : ProviderId.values()) {
            result.put(provider, healthRegistry.availability(provider));
        }
        return result;
    }
}
