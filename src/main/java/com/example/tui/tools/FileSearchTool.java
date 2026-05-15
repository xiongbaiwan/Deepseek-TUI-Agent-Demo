package com.example.tui.tools;

import com.example.tui.model.ChatRequest.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class FileSearchTool implements Tool {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", Map.of(
            "type", "string",
            "description", "要模糊匹配的文件名片段"
        ));
        properties.put("path", Map.of(
            "type", "string",
            "description", "搜索的根目录，默认为当前工作目录"
        ));
        params.put("properties", properties);
        params.put("required", List.of("pattern"));

        return new ToolDefinition(new ToolDefinition.ToolFunction(
            "file_search",
            "根据文件名模糊搜索文件，在已知大致文件名时使用",
            params
        ));
    }

    @Override
    public boolean isParallelSafe() { return true; }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode node = SharedMapper.INSTANCE.readTree(argumentsJson);
            String pattern = node.get("pattern").asText().toLowerCase();
            String searchPath = node.has("path") ? node.get("path").asText() : ".";

            Path root = Path.of(searchPath);
            List<String> results = new ArrayList<>();

            try (Stream<Path> stream = Files.walk(root)) {
                results = stream
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.toLowerCase().contains(pattern))
                    .sorted()
                    .limit(50)
                    .collect(Collectors.toList());
            }

            if (results.isEmpty()) {
                return "未找到匹配 \"" + pattern + "\" 的文件";
            }
            return "匹配 \"" + pattern + "\" 的文件:\n" + results.stream()
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "文件搜索失败: " + e.getMessage();
        }
    }
}
