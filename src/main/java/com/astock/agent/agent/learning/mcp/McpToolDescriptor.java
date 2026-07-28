package com.astock.agent.agent.learning.mcp;

import java.util.Map;

public record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
    public McpToolDescriptor {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
