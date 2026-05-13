package com.example.tui.tools;

import com.example.tui.model.ChatRequest.ToolDefinition;
import com.fasterxml.jackson.databind.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 读取文件内容工具。
 */
public class ReadFileTool implements Tool {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
            "type", "string",
            "description", "要读取的文件路径"
        ));
        params.put("properties", properties);
        params.put("required", List.of("path"));

        return new ToolDefinition(new ToolDefinition.ToolFunction(
            "read_file",
            "读取指定路径的文件内容",
            params
        ));
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(argumentsJson);
            String path = node.get("path").asText();

            String content = Files.readString(Path.of(path));
            return content;
        } catch (IOException e) {
            return "读取文件失败: " + e.getMessage();
        }
    }
}
