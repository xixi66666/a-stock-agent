package com.astock.agent.web;

import com.astock.agent.agent.cycle.CycleResearchService;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 周期研究接口与现有报告接口隔离。 */
@RestController
@RequestMapping("/api/agent/cycle")
public class CycleController {
    private final CycleResearchService service;
    public CycleController(CycleResearchService service) { this.service = service; }
    @GetMapping("/models") public List<NamedChatClientRegistry.ModelReference> models() { return service.models(); }
    @PostMapping("/tasks") @ResponseStatus(HttpStatus.ACCEPTED)
    public CycleResearchService.Task start(@RequestBody Request request) { return service.start(request.code(), request.modelId()); }
    @GetMapping("/tasks/{id}") public CycleResearchService.Task get(@PathVariable String id) { return service.get(id); }
    @GetMapping("/latest/{code}") public CycleResearchService.Task latest(@PathVariable String code) { return service.latest(code); }
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail problem(ResponseStatusException failure) {
        return ProblemDetail.forStatusAndDetail(failure.getStatusCode(), failure.getReason());
    }
    public record Request(String code, String modelId) {}
}
