package com.astock.agent.agent.finrobot;

import com.astock.agent.agent.StockAgentTools;
import com.astock.agent.agent.model.AiModelProperties;
import com.astock.agent.analysis.ResearchGateway;
import com.astock.agent.marketdata.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 子进程协议：密钥仅经 stdin 传递，不写入命令行、文件或日志。 */
public final class OfficialFinRobotWorker implements OfficialFinRobotService.Runner {
    public static final String UPSTREAM = "6d6ccd32c1b8b1904dc656cf06897438aba3daec";
    private final OfficialFinRobotProperties properties;
    private final StockAgentTools tools;
    private final ResearchGateway gateway;
    // Python 边界只接受 ISO-8601 日期字符串；Jackson 默认时间戳数组会导致 fromisoformat 报错。
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public OfficialFinRobotWorker(OfficialFinRobotProperties properties, StockAgentTools tools, ResearchGateway gateway) {
        this.properties = properties; this.tools = tools; this.gateway = gateway;
    }

    @Override public JsonNode run(String code, AiModelProperties.Model model, Path output) throws Exception {
        SecurityId security = SecurityId.parse(code);
        var snapshot = tools.getResearchSnapshot(code);
        DataSection<FinancialStatementHistory> history;
        try { history = gateway.financialHistory(security); }
        catch (RuntimeException failure) { history = DataSection.unavailable("年度财报获取失败"); }
        Path root = Path.of(properties.reportPath()).toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        if (!output.startsWith(root)) throw new IOException("输出目录无效");
        Files.createDirectories(output);
        if (!output.toRealPath().startsWith(root.toRealPath())) throw new IOException("输出目录无效");
        String base = model.baseUrl().replaceAll("/+$", "");
        String endpoint = model.completionsPath();
        if (!endpoint.endsWith("/chat/completions")) throw new IOException("当前模型接口不支持 Chat Completions");
        base += endpoint.substring(0, endpoint.length() - "/chat/completions".length());
        var input = Map.of("security", security, "snapshot", snapshot, "financialHistory", history,
                "model", Map.of("apiKey", model.apiKey(), "baseUrl", base, "model", model.model()));
        var builder = new ProcessBuilder(properties.python(),
                Path.of("scripts/finrobot_worker.py").toAbsolutePath().toString(), output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("OPENAI_AGENTS_DISABLE_TRACING", "1");
        Process process = builder.start();
        try {
            try (var stream = process.getOutputStream()) { mapper.writeValue(stream, input); }
            if (!process.waitFor(properties.taskTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    || process.exitValue() != 0) throw new IOException("官方 Worker 执行失败");
            Path bundle = output.resolve("bundle.json");
            if (!Files.isRegularFile(bundle) || Files.isSymbolicLink(bundle) || Files.size(bundle) > 10_000_000) {
                throw new IOException("报告文件不可用");
            }
            return mapper.readTree(bundle.toFile());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            }
        }
    }
}
