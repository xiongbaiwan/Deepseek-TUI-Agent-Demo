package com.example.tui.tools;

import com.example.tui.model.ChatRequest.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 写入文件内容工具。
 */
public class WriteFileTool implements Tool {

    private static final int MAX_CONTENT_SIZE = 500 * 1024; // 500KB
    private final Path workspaceRoot;

    public WriteFileTool(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
            "type", "string",
            "description", "要写入的文件路径（相对或绝对路径，不能超出工作区）"
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
            JsonNode node = SharedMapper.INSTANCE.readTree(argumentsJson);
            String path = node.get("path").asText();
            String content = node.get("content").asText();
            boolean createOnly = node.has("create") && node.get("create").asBoolean();

            if (content.length() > MAX_CONTENT_SIZE) {
                return String.format("错误: 内容过大 (%.1fKB)，超过限制 %dKB",
                        content.length() / 1024.0, MAX_CONTENT_SIZE / 1024);
            }

            Path resolved = resolveSafe(path);
            if (createOnly && Files.exists(resolved)) {
                return "错误: 文件已存在，create 模式拒绝覆盖: " + path;
            }

            Path parent = resolved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(resolved, content);
            return "已写入: " + path;
        } catch (Exception e) {
            return "写入文件失败: " + e.getMessage();
        }
    }

    private Path resolveSafe(String path) throws IOException {
        Path p = Path.of(path);
        if (!p.isAbsolute()) {
            p = workspaceRoot.resolve(p);
        }
        Path resolved = p.toAbsolutePath().normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IOException("路径超出工作区范围: " + path);
        }
        return resolved;
    }
}
