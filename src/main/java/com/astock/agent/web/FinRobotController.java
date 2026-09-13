package com.astock.agent.web;

import com.astock.agent.agent.AgentAvailability;
import com.astock.agent.agent.AgentStatusService;
import com.astock.agent.agent.finrobot.FinRobotResearchResponse;
import com.astock.agent.agent.finrobot.FinRobotResearchService;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FinRobot equity research 的唯一投研报告 REST 边界。 */
@RestController
@RequestMapping("/api/finrobot")
public class FinRobotController {

    private final AgentStatusService statusService;
    private final FinRobotResearchService researchService;

    @Autowired
    public FinRobotController(AgentStatusService statusService,
            FinRobotResearchService researchService) {
        this.statusService = statusService;
        this.researchService = researchService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        AgentAvailability availability = statusService.status(FinRobotResearchService.ROLE);
        return Map.of(
                "enabled", availability == AgentAvailability.READY,
                "status", availability.name(),
                "details", statusService.details(FinRobotResearchService.ROLE));
    }

    @GetMapping("/models")
    public ModelsResponse models() {
        return new ModelsResponse(researchService == null ? List.of() : researchService.availableModels());
    }

    @PostMapping("/research")
    public FinRobotResearchResponse research(@RequestBody ResearchRequest request) {
        if (researchService == null) throw new IllegalStateException("FinRobot research service is unavailable");
        return researchService.generate(request.code(), request.modelId());
    }

    public record ResearchRequest(String code, String modelId) {
    }

    public record ModelsResponse(List<NamedChatClientRegistry.ModelReference> models) {
        public ModelsResponse {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }
}
