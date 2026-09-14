package com.astock.agent.agent.finrobot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.finrobot")
public record OfficialFinRobotProperties(String engine, String python, String reportPath,
        Duration taskTimeout, int maxConcurrentTasks) {
    public OfficialFinRobotProperties {
        engine = engine == null ? "official" : engine;
        if (!java.util.List.of("official", "legacy").contains(engine)) throw new IllegalArgumentException("FinRobot engine 无效");
        python = python == null ? "tools/finrobot/.venv/Scripts/python.exe" : python;
        reportPath = reportPath == null ? "data/finrobot-reports" : reportPath;
        taskTimeout = taskTimeout == null ? Duration.ofMinutes(20) : taskTimeout;
        if (taskTimeout.isNegative() || taskTimeout.isZero()) throw new IllegalArgumentException("FinRobot 超时配置无效");
        maxConcurrentTasks = Math.max(1, Math.min(maxConcurrentTasks, 4));
    }
}
