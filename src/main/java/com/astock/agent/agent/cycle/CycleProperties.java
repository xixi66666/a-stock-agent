package com.astock.agent.agent.cycle;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本地只读资料位置；服务器部署时通过配置覆盖，不向模型开放路径参数。 */
@ConfigurationProperties("app.cycle")
public record CycleProperties(String skillPath, String bookPath, String python, String reportPath) {
    public CycleProperties {
        skillPath = fallback(skillPath, "D:/codex-book/skills/howard-marks-cycle");
        bookPath = fallback(bookPath, "D:/codex-book/books/howard-marks-market-cycle");
        python = fallback(python, "python");
        reportPath = fallback(reportPath, "data/cycle-reports");
    }
    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
