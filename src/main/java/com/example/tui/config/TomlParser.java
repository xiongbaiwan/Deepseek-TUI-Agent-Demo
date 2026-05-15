package com.example.tui.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 轻量级 TOML 解析器，仅支持基本语法：
 * - 注释以 # 开头
 * - [section] 分组
 * - key = "value" 或 key = value（字符串无需引号）
 * - key = true/false（布尔值）
 * - key = 123（整数）
 * 不处理数组、内联表、多行字符串等高级特性。
 */
public class TomlParser {

    private final Map<String, Map<String, String>> data = new HashMap<>();

    public static TomlParser parse(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            return parse(reader);
        }
    }

    public static TomlParser parse(Reader reader) throws IOException {
        TomlParser parser = new TomlParser();
        try (BufferedReader br = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader)) {
            String line;
            String currentSection = "";
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // 解析 [section]
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).trim();
                    parser.data.putIfAbsent(currentSection, new HashMap<>());
                    continue;
                }

                // 解析 key = value
                int eqIdx = line.indexOf('=');
                if (eqIdx < 0) continue;

                String key = line.substring(0, eqIdx).trim();
                String value = line.substring(eqIdx + 1).trim();

                // 去除引号
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }

                Map<String, String> section = parser.data.computeIfAbsent(currentSection, k -> new HashMap<>());
                section.put(key, value);
            }
        }
        return parser;
    }

    public String getString(String section, String key) {
        Map<String, String> s = data.get(section);
        return s != null ? s.get(key) : null;
    }

    public String getString(String section, String key, String defaultValue) {
        String val = getString(section, key);
        return val != null ? val : defaultValue;
    }

    public int getInt(String section, String key, int defaultValue) {
        String val = getString(section, key);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return defaultValue; }
    }

    public boolean getBool(String section, String key, boolean defaultValue) {
        String val = getString(section, key);
        if (val == null) return defaultValue;
        return "true".equalsIgnoreCase(val);
    }

    public Map<String, String> getSection(String section) {
        return data.getOrDefault(section, Map.of());
    }
}
