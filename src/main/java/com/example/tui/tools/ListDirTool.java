package com.example.tui.tools;

import com.example.tui.model.ChatRequest.ToolDefinition;
import com.fasterxml.jackson.databind.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class ListDirTool implements Tool {

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", ".idea", ".vscode", "target", "build",
        ".gradle", "venv", ".venv", "__pycache__", ".next", "dist"
    );

    @Override
    public ToolDefinition definition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of(
            "type", "string",
            "description", "要列出的目录路径，默认为当前工作目录"
        ));
        properties.put("recursive", Map.of(
            "type", "boolean",
            "description", "是否递归列出子目录，默认 false"
        ));
        params.put("properties", properties);
        params.put("required", Collections.emptyList());

        return new ToolDefinition(new ToolDefinition.ToolFunction(
            "list_dir",
            "结构化列出目录内容，优先于 exec_shell ls 使用",
            params
        ));
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(argumentsJson);
            String dirPath = node.has("path") ? node.get("path").asText() : ".";
            boolean recursive = node.has("recursive") && node.get("recursive").asBoolean();

            Path dir = Path.of(dirPath);
            if (!Files.isDirectory(dir)) {
                return "错误: 路径不是目录: " + dirPath;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("目录: ").append(dir.toAbsolutePath()).append("\n\n");

            Stream<Path> stream = recursive ? Files.walk(dir) : Files.list(dir);
            List<Path> entries = stream
                .filter(p -> !p.equals(dir))
                .filter(p -> !SKIP_DIRS.contains(p.getFileName().toString()))
                .sorted()
                .collect(Collectors.toList());

            for (Path p : entries) {
                String indent = "  ";
                String relPath = dir.relativize(p).toString();
                if (recursive) {
                    int depth = (int) relPath.chars().filter(c -> c == '/').count();
                    indent = "  ".repeat(depth + 1);
                }
                String kind = Files.isDirectory(p) ? "[dir] " : "[file]";
                long size = Files.isRegularFile(p) ? Files.size(p) : 0;
                sb.append(indent).append(kind).append(" ").append(relPath);
                if (size > 0) {
                    sb.append(" (").append(formatSize(size)).append(")");
                }
                sb.append("\n");
            }

            return sb.toString().isEmpty() ? "目录为空" : sb.toString();
        } catch (Exception e) {
            return "列出目录失败: " + e.getMessage();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return (bytes / (1024 * 1024)) + "MB";
    }
}
