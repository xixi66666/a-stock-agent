package com.astock.agent.agent.learning.mcp;

public record McpToolExecution(String tool, boolean success, Object data, String errorCode) {
    public static McpToolExecution success(String tool, Object data) {
        return new McpToolExecution(tool, true, data, "");
    }

    public static McpToolExecution failure(String tool, String errorCode) {
        return new McpToolExecution(tool, false, null, errorCode);
    }
}
