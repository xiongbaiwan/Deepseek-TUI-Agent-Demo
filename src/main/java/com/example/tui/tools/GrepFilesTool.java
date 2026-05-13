package com.example.tui.tools;

import com.example.tui.model.ChatRequest.ToolDefinition;
import com.fasterxml.jackson.databind.*;

import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class GrepFilesTool implements Tool {

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", ".idea", ".vscode", "target", "build",
        ".gradle", "venv", ".venv", "__pycache__", ".next", "dist",
        ".class", ".jar", ".zip", ".png", ".jpg", ".gif", ".ico", ".woff", ".woff2", ".ttf", ".eot"
    );

    private static final Set<String> SKIP_EXTS = Set.of(
        "class", "jar", "zip", "png", "jpg", "gif", "ico", "woff", "woff2", "ttf", "eot",
        "pdf", "exe", "so", "dylib", "dll"
    );

    @Override
    public ToolDefinition definition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pattern", Map.of(
            "type", "string",
            "description", "要搜索的正则表达式模式"
        ));
        properties.put("path", Map.of(
            "type", "string",
            "description", "搜索的根目录，默认为当前工作目录"
        ));
        properties.put("context_lines", Map.of(
            "type", "number",
            "description", "匹配行前后各显示多少行上下文，默认 0"
        ));
        params.put("properties", properties);
        params.put("required", List.of("pattern"));

        return new ToolDefinition(new ToolDefinition.ToolFunction(
            "grep_files",
            "用正则表达式搜索文件内容，返回匹配的文件、行号和上下文",
            params
        ));
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(argumentsJson);
            String pattern = node.get("pattern").asText();
            String searchPath = node.has("path") ? node.get("path").asText() : ".";
            int ctx = node.has("context_lines") ? node.get("context_lines").asInt() : 0;

            Path root = Path.of(searchPath);
            Pattern regex = Pattern.compile(pattern, Pattern.MULTILINE);

            StringBuilder sb = new StringBuilder();
            int totalMatches = 0;

            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !shouldSkip(p, root))
                    .sorted()
                    .collect(Collectors.toList());

                for (Path file : files) {
                    try {
                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        List<MatchResult> matches = findMatches(regex, content);
                        if (matches.isEmpty()) continue;

                        String[] lines = content.split("\n", -1);
                        sb.append(file.relativize(root.isAbsolute() ? root : root.toAbsolutePath())).append(":\n");

                        for (MatchResult m : matches) {
                            int matchLine = lineIndexOf(content, m.start());
                            int start = Math.max(0, matchLine - ctx);
                            int end = Math.min(lines.length, matchLine + ctx + 1);

                            for (int i = start; i < end; i++) {
                                String marker = (i == matchLine) ? ">>>" : "   ";
                                sb.append(marker).append(" ").append(i + 1).append(": ").append(lines[i]).append("\n");
                            }
                            totalMatches++;
                        }
                        sb.append("\n");
                    } catch (Exception e) {
                        // Skip files that can't be read as text
                    }
                }
            }

            if (totalMatches == 0) {
                return "未找到匹配: " + pattern;
            }
            return sb.toString() + "共找到 " + totalMatches + " 处匹配。";
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    private boolean shouldSkip(Path p, Path root) {
        Path rel = root.relativize(p);
        for (Path component : rel) {
            String name = component.toString();
            if (SKIP_DIRS.contains(name)) return true;
        }
        String ext = getFileExt(p.getFileName().toString());
        return SKIP_EXTS.contains(ext);
    }

    private static String getFileExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private static List<MatchResult> findMatches(Pattern regex, String content) {
        List<MatchResult> results = new ArrayList<>();
        Matcher m = regex.matcher(content);
        while (m.find()) {
            results.add(m.toMatchResult());
        }
        return results;
    }

    private static int lineIndexOf(String content, int charIndex) {
        int line = 0;
        for (int i = 0; i < charIndex && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
