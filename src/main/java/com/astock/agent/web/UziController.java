package com.astock.agent.web;

import com.astock.agent.agent.uzi.UziResearchService;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** UZI 深度分析的独立页面接口。 */
@RestController
@RequestMapping("/api/uzi")
public class UziController {

    private final UziResearchService service;

    public UziController(UziResearchService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public UziResearchService.Status status() {
        return service.status();
    }

    @GetMapping("/models")
    public List<NamedChatClientRegistry.ModelReference> models() {
        return service.models();
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UziResearchService.Task start(@RequestBody Request request) {
        return service.start(request.code(), request.depth(), request.school());
    }

    @GetMapping("/tasks/{id}")
    public UziResearchService.Task get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/latest/{code}")
    public UziResearchService.Task latest(@PathVariable String code) {
        return service.latest(code);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail problem(ResponseStatusException failure) {
        return ProblemDetail.forStatusAndDetail(failure.getStatusCode(), failure.getReason());
    }

    public record Request(String code, String depth, String school) {
    }
}
