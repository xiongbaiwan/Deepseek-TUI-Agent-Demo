package com.example.tui.tools;

import com.example.tui.model.ChatRequest.ToolDefinition;
import com.fasterxml.jackson.databind.*;

import java.nio.file.*;
import java.util.*;

public class WriteFileTool implements Tool {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
            "type", "string",
            "description", "要写入的文件路径"
        ));
        properties.put("content", Map.of(
            "type", "string",
            "description", "要写入的文件内容"
        ));
        properties.put("create", Map.of(
            "type", "boolean",
            "description", "如果为 true，仅当文件不存在时创建，已存在则报错"
        ));
        params.put("properties", properties);
        params.put("required", List.of("path", "content"));

        return new ToolDefinition(new ToolDefinition.ToolFunction(
            "write_file",
            "创建或覆盖写入一个文件",
            params
        ));
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(argumentsJson);
            String path = node.get("path").asText();
            String content = node.get("content").asText();
            boolean createOnly = node.has("create") && node.get("create").asBoolean();

            Path p = Path.of(path);
            if (createOnly && Files.exists(p)) {
                return "错误: 文件已存在，create 模式拒绝覆盖: " + path;
            }

            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
            return "已写入: " + path;
        } catch (Exception e) {
            return "写入文件失败: " + e.getMessage();
        }
    }
}
