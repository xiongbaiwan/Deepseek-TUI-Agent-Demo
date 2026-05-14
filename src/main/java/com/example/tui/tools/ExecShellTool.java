package com.example.tui.tools;

import com.example.tui.model.ChatRequest.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 执行 Shell 命令工具。
 */
public class ExecShellTool implements Tool {

    @Override
    public ToolDefinition definition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", Map.of(
            "type", "string",
            "description", "要执行的 Shell 命令"
        ));
        params.put("properties", properties);
        params.put("required", List.of("command"));

        return new ToolDefinition(new ToolDefinition.ToolFunction(
            "exec_shell",
            "在当前目录执行一条 Shell 命令",
            params
        ));
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode node = SharedMapper.INSTANCE.readTree(argumentsJson);
            String command = node.get("command").asText();

            // 根据操作系统选择 Shell
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 读取命令输出，设置超时防止阻塞
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            int exitCode;
            if (!finished) {
                process.destroyForcibly();
                exitCode = -1;
                return "命令执行超时（30s），已强制终止进程\n" + output.toString();
            }
            exitCode = process.exitValue();

            return "退出码: " + exitCode + "\n" + output.toString();
        } catch (Exception e) {
            return "执行命令失败: " + e.getMessage();
        }
    }
}
