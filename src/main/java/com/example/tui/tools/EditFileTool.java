package com.example.tui.tools;

import com.example.tui.model.ChatRequest.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.*;
import java.util.*;

public class EditFileTool implements Tool {

    private final java.nio.file.Path workspaceRoot;

    public EditFileTool() {
        this(java.nio.file.Paths.get(System.getProperty("user.dir")));
    }

    public EditFileTool(java.nio.file.Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
            "type", "string",
            "description", "要编辑的文件路径"
        ));
        properties.put("old_string", Map.of(
            "type", "string",
            "description", "要替换的原始文本（需精确匹配，包括缩进和换行）"
        ));
        properties.put("new_string", Map.of(
            "type", "string",
            "description", "替换后的新文本"
        ));
        params.put("properties", properties);
        params.put("required", List.of("path", "old_string", "new_string"));

        return new ToolDefinition(new ToolDefinition.ToolFunction(
            "edit_file",
            "在单个文件内执行搜索替换，比完整重写更高效",
            params
        ));
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode node = SharedMapper.INSTANCE.readTree(argumentsJson);
            String path = node.get("path").asText();
            String oldString = node.get("old_string").asText();
            String newString = node.get("new_string").asText();

            Path p = WorkspaceGuard.resolveWithin(workspaceRoot, path);
            String content = Files.readString(p);

            int firstIdx = content.indexOf(oldString);
            if (firstIdx == -1) {
                return "编辑失败: 在文件中未找到原始文本。请检查内容是否匹配。";
            }
            int secondIdx = content.indexOf(oldString, firstIdx + 1);
            if (secondIdx != -1) {
                return "编辑失败: 原始文本在文件中出现多次。请使用 apply_patch 或提供更多上下文使其唯一。";
            }

            String updated = content.replaceFirst(
                java.util.regex.Pattern.quote(oldString),
                java.util.regex.Matcher.quoteReplacement(newString)
            );
            Files.writeString(p, updated);
            return "已编辑: " + path;
        } catch (Exception e) {
            return "编辑文件失败: " + e.getMessage();
        }
    }
}
