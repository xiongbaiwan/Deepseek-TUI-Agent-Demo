package com.example.tui.tools;

import com.example.tui.model.ChatRequest.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 读取文件内容工具。
 */
public class ReadFileTool implements Tool {

    private static final int MAX_FILE_SIZE = 100 * 1024; // 100KB
    private final Path workspaceRoot;

    public ReadFileTool(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
            "type", "string",
            "description", "要读取的文件路径（相对或绝对路径，不能超出工作区）"
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
    public boolean isParallelSafe() { return true; }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode node = SharedMapper.INSTANCE.readTree(argumentsJson);
            String path = node.get("path").asText();

            Path resolved = resolveSafe(path);

            if (!Files.exists(resolved)) {
                return "错误: 文件不存在: " + path;
            }
            long size = Files.size(resolved);
            if (size > MAX_FILE_SIZE) {
                return String.format("错误: 文件过大 (%.1fKB)，超过限制 %dKB。请使用 offset/limit 参数",
                        size / 1024.0, MAX_FILE_SIZE / 1024);
            }

            String content = Files.readString(resolved);
            return content;
        } catch (IOException e) {
            return "读取文件失败: " + e.getMessage();
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
