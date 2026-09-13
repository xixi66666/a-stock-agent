package com.astock.agent.agent.uzi;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** UZI Python Worker 的本地运行配置；页面和模型不能覆盖这些路径或命令。 */
@ConfigurationProperties("app.uzi")
public record UziProperties(String rootPath, String python, String reportPath,
        Duration taskTimeout, int maxConcurrentTasks) {

    public UziProperties {
        rootPath = fallback(rootPath, "tools/uzi/UZI-Skill");
        python = fallback(python, "python");
        reportPath = fallback(reportPath, "data/uzi-reports");
        taskTimeout = taskTimeout == null || taskTimeout.isNegative() || taskTimeout.isZero()
                ? Duration.ofMinutes(30) : taskTimeout;
        maxConcurrentTasks = maxConcurrentTasks < 1 ? 1 : Math.min(maxConcurrentTasks, 4);
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
