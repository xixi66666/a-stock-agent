package com.astock.agent.web;

import com.astock.agent.agent.finrobot.OfficialFinRobotService;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/finrobot")
public class OfficialFinRobotController {
    private final OfficialFinRobotService service;
    public OfficialFinRobotController(OfficialFinRobotService service) { this.service = service; }
    @GetMapping("/runtime") public Map<String, Object> runtime() { return service.runtime(); }
    @PostMapping("/tasks") @ResponseStatus(HttpStatus.ACCEPTED)
    public OfficialFinRobotService.Task start(@RequestBody Request request) { return service.start(request.code(), request.modelId()); }
    @GetMapping("/tasks/{id}") public OfficialFinRobotService.Task get(@PathVariable String id) { return service.get(id); }
    @GetMapping("/latest/{code}") public ResponseEntity<OfficialFinRobotService.Task> latest(@PathVariable String code) {
        var result = service.latest(code);
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }
    @PostMapping("/tasks/{id}/cancel") public OfficialFinRobotService.Task cancel(@PathVariable String id) { return service.cancel(id); }
    @GetMapping("/tasks/{id}/artifacts/{kind}")
    public ResponseEntity<FileSystemResource> artifact(@PathVariable String id, @PathVariable String kind) throws Exception {
        var path = service.artifact(id, kind);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(path.getFileName().toString()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .contentType(kind.equals("html") ? MediaType.TEXT_HTML : MediaType.APPLICATION_JSON)
                .body(new FileSystemResource(path));
    }
    // 保留正确的 404/409/429 状态，不让全局异常处理把任务错误改成 500。
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> taskError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(
                ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason()));
    }
    public record Request(String code, String modelId) {}
}
