package com.example.tui;

import com.example.tui.client.DeepSeekClient;
import com.example.tui.config.TuiConfig;
import com.example.tui.engine.AgentEngine;
import com.example.tui.engine.ApprovalMode;
import com.example.tui.engine.ApprovalRequest;
import com.example.tui.engine.ApprovalRequest.Decision;
import com.example.tui.engine.SessionStore;
import com.example.tui.tools.*;
import com.example.tui.ui.TerminalUI;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Java TUI 演示程序入口。
 *
 * 架构与 Rust 项目的对应关系：
 * - TerminalUI    ~ crates/tui（ratatui 界面）
 * - AgentEngine   ~ crates/core（engine.rs + turn_loop.rs）
 * - DeepSeekClient ~ client.rs（SSE 流式 HTTP）
 * - Tool 接口及实现 ~ crates/tools
 * - TuiConfig     ~ config.rs / settings.rs（配置加载）
 */
public class App {

    public static void main(String[] args) throws Exception {
        // 1. 加载配置（配置文件 < 环境变量 < CLI 参数）
        TuiConfig config = TuiConfig.load(args);

        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            printUsage();
            System.exit(1);
        }

        TerminalUI ui = new TerminalUI();
        ui.printBanner();

        // 2. 注册工具
        Path workspaceRoot = Paths.get(config.getWorkspaceRoot());
        List<Tool> tools = Arrays.asList(
                new ReadFileTool(workspaceRoot),
                new WriteFileTool(workspaceRoot),
                new EditFileTool(workspaceRoot),
                new ListDirTool(),
                new GrepFilesTool(),
                new FileSearchTool(),
                new FetchUrlTool(),
                new ExecShellTool()
        );

        // 3. 创建客户端和引擎
        DeepSeekClient client = new DeepSeekClient(config.getApiKey(), config.getBaseUrl());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { client.close(); } catch (Exception ignored) {}
        }));

        String systemPrompt = config.getSystemPrompt();
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            systemPrompt = "你是一个专业的 AI 编程助手，运行在终端 TUI 环境中。你的工作目录是: "
                    + workspaceRoot.toAbsolutePath()
                    + "\n\n规则：\n"
                    + "1. 优先使用工具而不是 shell 命令\n"
                    + "2. 读写文件时注意路径，不要超出工作区范围\n"
                    + "3. 代码修改要精确，不要做无关的改动\n"
                    + "4. 回答要简洁，代码要有注释";
        }

        // 审批处理器：通过 TerminalUI 向用户展示审批请求
        AgentEngine.ApprovalHandler approvalHandler = request -> {
            return ui.askApproval(request);
        };

        AgentEngine engine = new AgentEngine(client, tools, ui::printStatus,
                systemPrompt, ui::printReasoning, config.getModel(), approvalHandler);
        engine.setApprovalMode(config.getApprovalMode());
        engine.setPlanCallback(new AgentEngine.PlanCallback() {
            @Override
            public AgentEngine.PlanAction askForAction(com.example.tui.engine.plan.Plan plan) {
                return ui.askPlanAction(plan);
            }
            @Override
            public com.example.tui.engine.plan.Plan editPlan(com.example.tui.engine.plan.Plan plan) {
                return ui.editPlan(plan);
            }
        });

        // 会话持久化
        SessionStore sessionStore = new SessionStore(workspaceRoot);
        engine.setSessionStore(sessionStore);
        engine.newSession();

        ui.setShowReasoning(config.isShowReasoning());
        ui.printStatus("审批模式: " + engine.getApprovalMode().label()
                + " | 模型: " + engine.getModel()
                + " | 会话: " + engine.getCurrentSessionId().substring(0, 8));

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
                        printHelp(ui, engine);
                        break;
                    case "/clear":
                        engine.reset();
                        ui.printStatus("对话历史已清除。");
                        break;
                    case "/cancel":
                    case "/stop":
                    case "/c":
                        engine.cancelCurrentTurn();
                        break;
                    case "/reasoning":
                        ui.toggleShowReasoning();
                        break;
                    case "/mode":
                        ui.printStatus("当前模式: " + engine.getApprovalMode().label()
                                + "（用法: /mode plan|agent|yolo）");
                        break;
                    default:
                        if (trimmed.startsWith("/mode ")) {
                            String modeName = trimmed.substring(6).trim().toLowerCase();
                            ApprovalMode newMode = ApprovalMode.fromConfigValue(modeName);
                            engine.setApprovalMode(newMode);
                            ui.printStatus("模式已切换为: " + newMode.label());
                        } else if (trimmed.equals("/session")) {
                            ui.printStatus("会话: " + engine.getCurrentSessionId());
                            ui.printStatus("用法: /session new|save|load <id>|list");
                        } else if (trimmed.equals("/session new")) {
                            String id = engine.newSession();
                            ui.printStatus("新会话: " + id.substring(0, 8));
                        } else if (trimmed.equals("/session save")) {
                            engine.saveCurrentSession();
                            ui.printStatus("会话已保存: " + engine.getCurrentSessionId().substring(0, 8));
                        } else if (trimmed.startsWith("/session load ")) {
                            String id = trimmed.substring(14).trim();
                            if (engine.loadSession(id)) {
                                ui.printStatus("已加载会话: " + id.substring(0, 8));
                            } else {
                                ui.printError("会话不存在: " + id);
                            }
                        } else if (trimmed.equals("/session list")) {
                            var sessions = engine.listSessions();
                            if (sessions.isEmpty()) {
                                ui.printStatus("无已保存的会话");
                            } else {
                                ui.println(TerminalUI.CYAN + "已保存的会话：" + TerminalUI.RESET);
                                for (var s : sessions) {
                                    ui.println("  " + s);
                                }
                            }
                        } else if (trimmed.equals("/plan")) {
                            ui.printStatus("计划模式: " + (engine.isPlanMode() ? "ON" : "OFF")
                                    + "（用法: /plan on|off）");
                        } else if (trimmed.startsWith("/plan ")) {
                            String val = trimmed.substring(6).trim().toLowerCase();
                            if ("on".equals(val) || "enable".equals(val) || "true".equals(val)) {
                                engine.setPlanMode(true);
                                ui.printStatus("计划模式: ON - 任务将在执行前生成计划");
                            } else if ("off".equals(val) || "disable".equals(val) || "false".equals(val)) {
                                engine.setPlanMode(false);
                                ui.printStatus("计划模式: OFF - 直接执行模式");
                            } else {
                                ui.printError("用法: /plan on|off");
                            }
                        } else {
                            ui.printError("未知命令: " + trimmed);
                            ui.printStatus("输入 /help 查看可用命令。");
                        }
                }
                continue;
            }

            // 5. 执行 Agent 轮次
            ui.printStatus("思考中...");
            try {
                if (engine.isPlanMode()) {
                    engine.runPlanTurn(trimmed);
                } else {
                    engine.runTurn(trimmed);
                }
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
        System.err.println("  mvn exec:java -Dexec.args=\"--model deepseek-chat\"");
        System.err.println("  mvn exec:java -Dexec.args=\"--mode yolo\"           (自动审批)");
        System.err.println("  mvn exec:java -Dexec.args=\"--demo\"              (仅测试，无真实 API)");
        System.err.println("");
        System.err.println("或通过环境变量:");
        System.err.println("  export DEEPSEEK_API_KEY=YOUR_KEY");
        System.err.println("  mvn exec:java");
        System.err.println("");
        System.err.println("配置文件: ~/.tui/config.toml 或 .tui/config.toml");
    }

    /** 打印帮助信息（运行时 /help 命令） */
    private static void printHelp(TerminalUI ui, AgentEngine engine) {
        ui.println(TerminalUI.CYAN + "可用命令：" + TerminalUI.RESET);
        ui.println("  /help               - 显示此帮助");
        ui.println("  /quit               - 退出程序");
        ui.println("  /clear              - 清除对话历史");
        ui.println("  /cancel             - 取消当前 AI 请求");
        ui.println("  /reasoning          - 切换推理内容显示(开启/关闭)");
        ui.println("  /mode               - 查看当前模式 (plan|agent|yolo)");
        ui.println("  /mode <plan|agent|yolo> - 切换模式");
        ui.println("  /plan               - 查看计划模式状态");
        ui.println("  /plan <on|off>      - 开关先计划后执行模式");
        ui.println("  /session            - 查看当前会话");
        ui.println("  /session new        - 开始新会话");
        ui.println("  /session save       - 保存当前会话");
        ui.println("  /session load <id>  - 加载指定会话");
        ui.println("  /session list       - 列出已保存的会话");
        ui.println("");
        ui.println("审批模式: " + engine.getApprovalMode().label()
                + " | 计划模式: " + (engine.isPlanMode() ? "ON" : "OFF")
                + " | 模型: " + engine.getModel()
                + " | 会话: " + (engine.getCurrentSessionId() != null
                        ? engine.getCurrentSessionId().substring(0, 8) : "无"));
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
