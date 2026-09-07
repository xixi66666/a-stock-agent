package com.astock.agent.web;

import com.astock.agent.knowledge.BookKnowledgeService;
import com.astock.agent.knowledge.KnowledgeEntry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final BookKnowledgeService knowledge;

    public KnowledgeController(BookKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/search")
    public BookKnowledgeService.SearchResult search(@RequestParam String q,
            @RequestParam(required = false) String bookId, @RequestParam(defaultValue = "5") int limit) {
        return knowledge.search(q, bookId, limit);
    }

    @GetMapping("/entries/{id}")
    public ResponseEntity<KnowledgeEntry> entry(@PathVariable String id) {
        return ResponseEntity.of(knowledge.find(id));
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalidQuery(Exception ignored) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "q 须为 1—200 个字符，limit 须为 1—10，bookId 可选 nison、marks、naval");
        problem.setTitle("知识检索参数无效");
        problem.setProperty("code", "INVALID_KNOWLEDGE_QUERY");
        return problem;
    }
}
