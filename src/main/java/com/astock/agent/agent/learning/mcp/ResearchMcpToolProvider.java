package com.astock.agent.agent.learning.mcp;

import com.astock.agent.agent.learning.rag.ResearchRetriever;
import com.astock.agent.marketdata.model.SecurityId;
import com.astock.agent.technical.Timeframe;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * MCP 学习适配层，只暴露有边界的领域操作，不接受 URL、命令或文件路径。
 */
public final class ResearchMcpToolProvider {

    private static final List<McpToolDescriptor> TOOLS = List.of(
            descriptor("search_stock", "Search a bounded A-share code or name", "query"),
            descriptor("get_research_snapshot", "Get an offline-safe research snapshot", "code"),
            descriptor("get_technical_analysis", "Get technical evidence for a timeframe", "code"),
            descriptor("search_knowledge", "Search indexed research evidence", "code"));

    private final ResearchRetriever retriever;
    private final Function<String, Object> snapshotLookup;

    public ResearchMcpToolProvider(ResearchRetriever retriever, Function<String, Object> snapshotLookup) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.snapshotLookup = Objects.requireNonNull(snapshotLookup, "snapshotLookup");
    }

    public List<McpToolDescriptor> tools() {
        return TOOLS;
    }

    public McpToolExecution execute(String tool, Map<String, Object> input) {
        if (tool == null || TOOLS.stream().noneMatch(item -> item.name().equals(tool))) {
            return McpToolExecution.failure(String.valueOf(tool), "UNKNOWN_TOOL");
        }
        try {
            Map<String, Object> safeInput = input == null ? Map.of() : input;
            return switch (tool) {
                case "search_stock" -> McpToolExecution.success(tool, search(requireText(safeInput, "query", 20)));
                case "get_research_snapshot" -> McpToolExecution.success(
                        tool, snapshotLookup.apply(requireCode(safeInput)));
                case "get_technical_analysis" -> {
                    String code = requireCode(safeInput);
                    String timeframe = String.valueOf(safeInput.getOrDefault("timeframe", "DAILY"));
                    Timeframe.valueOf(timeframe.toUpperCase(java.util.Locale.ROOT));
                    yield McpToolExecution.success(tool, Map.of(
                            "code", code, "timeframe", timeframe.toUpperCase(java.util.Locale.ROOT),
                            "snapshot", snapshotLookup.apply(code)));
                }
                case "search_knowledge" -> {
                    String code = requireCode(safeInput);
                    String question = requireText(safeInput, "question", 2_000);
                    yield McpToolExecution.success(tool, retriever.retrieve(question, code, 4));
                }
                default -> McpToolExecution.failure(tool, "UNKNOWN_TOOL");
            };
        } catch (IllegalArgumentException exception) {
            String code = "get_technical_analysis".equals(tool) ? "INVALID_TIMEFRAME" : "INVALID_INPUT";
            if (input != null && input.containsKey("code")) {
                code = "INVALID_SECURITY_CODE";
            }
            return McpToolExecution.failure(tool, code);
        }
    }

    private static List<Map<String, String>> search(String query) {
        return Map.of(
                        "600519", "贵州茅台",
                        "000001", "平安银行",
                        "300750", "宁德时代")
                .entrySet().stream()
                .filter(item -> item.getKey().equals(query) || item.getValue().contains(query))
                .map(item -> Map.of("code", item.getKey(), "name", item.getValue()))
                .toList();
    }

    private static String requireCode(Map<String, Object> input) {
        return SecurityId.parse(String.valueOf(input.getOrDefault("code", ""))).code();
    }

    private static String requireText(Map<String, Object> input, String name, int maxLength) {
        String value = String.valueOf(input.getOrDefault(name, "")).trim();
        if (value.isBlank() || value.codePointCount(0, value.length()) > maxLength
                || value.contains("<") || value.contains(">") || value.contains(";")
                || value.contains("\\") || value.contains("/")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static McpToolDescriptor descriptor(String name, String description, String required) {
        return new McpToolDescriptor(name, description, Map.of(
                "type", "object", "required", List.of(required)));
    }
}
