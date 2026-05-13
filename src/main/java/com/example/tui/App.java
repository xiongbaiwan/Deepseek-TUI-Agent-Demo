package com.example.tui;

import com.example.tui.client.DeepSeekClient;
import com.example.tui.engine.AgentEngine;
import com.example.tui.tools.*;
import com.example.tui.ui.TerminalUI;

import java.util.*;

/**
 * Java TUI 演示程序入口。
 *
 * 架构与 Rust 项目的对应关系：
 * - TerminalUI    ~ crates/tui（ratatui 界面）
 * - AgentEngine   ~ crates/core（engine.rs + turn_loop.rs）
 * - DeepSeekClient ~ client.rs（SSE 流式 HTTP）
 * - Tool 接口及实现 ~ crates/tools
 */
public class App {
    
    public static void main(String[] args) throws Exception {
        // 1. 解析命令行参数
        String apiKey = null;
        String baseUrl = null;
        boolean demoMode = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--api-key":
                    if (i + 1 < args.length) {
                        apiKey = args[++i];
                    } else {
                        System.err.println("错误: --api-key 需要提供参数值");
                        System.exit(1);
                    }
                    break;
                case "--base-url":
                    if (i + 1 < args.length) {
                        baseUrl = args[++i];
                    } else {
                        System.err.println("错误: --base-url 需要提供参数值");
                        System.exit(1);
                    }
                    break;
                case "--demo":
                    demoMode = true;
                    break;
                default:
                    System.err.println("未知参数: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }

        // 参数优先级: --api-key > 环境变量 > --demo
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getenv("DEEPSEEK_API_KEY");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            if (demoMode) {
                apiKey = "demo-key";
            } else {
                printUsage();
                System.exit(1);
            }
        }

        if (baseUrl == null) {
            baseUrl = System.getenv("DEEPSEEK_BASE_URL");
        }
        if (baseUrl == null) {
            baseUrl = "https://api.deepseek.com";
        }

        TerminalUI ui = new TerminalUI();
        ui.printBanner();

        // 2. 注册工具（对应 Rust 项目中的工具注册表）
        List<Tool> tools = Arrays.asList(
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new ListDirTool(),
                new GrepFilesTool(),
                new FileSearchTool(),
                new FetchUrlTool(),
                new ExecShellTool()
        );

        // 3. 创建客户端和引擎
        DeepSeekClient client = new DeepSeekClient(apiKey, baseUrl);
        AgentEngine engine = new AgentEngine(client, tools, ui::printStatus);

        // 4. 主 REPL 循环
        ui.printWelcome("已就绪！随时提问。我可以读写文件、编辑代码、搜索内容、获取网页和执行命令。");
        ui.println();

        while (true) {
            String input = ui.readInput();
            if (input == null) break;

            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;

            // 处理斜杠命令
            if (trimmed.startsWith("/")) {
                switch (trimmed) {
                    case "/quit":
                    case "/exit":
                    case "/q":
                        ui.printStatus("再见！");
                        return;
                    case "/help":
                        printHelp(ui);
                        break;
                    case "/clear":
                        engine.reset();
                        ui.printStatus("对话历史已清除。");
                        break;
                    default:
                        ui.printError("未知命令: " + trimmed);
                        ui.printStatus("输入 /help 查看可用命令。");
                }
                continue;
            }

            // 5. 执行 Agent 轮次
            ui.printStatus("思考中...");
            try {
                engine.runTurn(trimmed);
            } catch (Exception e) {
                ui.printError(e.getMessage());
            }
            ui.println();
        }
    }

    /** 打印启动参数使用说明 */
    private static void printUsage() {
        System.err.println("用法:");
        System.err.println("  mvn exec:java -Dexec.args=\"--api-key YOUR_KEY\"");
        System.err.println("  mvn exec:java -Dexec.args=\"--api-key YOUR_KEY --base-url https://xxx\"");
        System.err.println("  mvn exec:java -Dexec.args=\"--demo\"              (仅测试，无真实 API)");
        System.err.println("");
        System.err.println("或通过环境变量:");
        System.err.println("  export DEEPSEEK_API_KEY=YOUR_KEY");
        System.err.println("  mvn exec:java");
    }

    /** 打印帮助信息（运行时 /help 命令） */
    private static void printHelp(TerminalUI ui) {
        ui.println(TerminalUI.CYAN + "可用命令：" + TerminalUI.RESET);
        ui.println("  /help     - 显示此帮助");
        ui.println("  /quit     - 退出程序");
        ui.println("  /clear    - 清除对话历史");
        ui.println("");
        ui.println("已加载的工具：");
        ui.println("  read_file   - 读取文件内容");
        ui.println("  write_file  - 创建或覆盖写入文件");
        ui.println("  edit_file   - 在文件内搜索替换文本");
        ui.println("  list_dir    - 列出目录内容");
        ui.println("  grep_files  - 用正则搜索文件内容");
        ui.println("  file_search - 按文件名模糊搜索");
        ui.println("  fetch_url   - HTTP GET 获取网页内容");
        ui.println("  exec_shell  - 执行 Shell 命令");
    }
}
