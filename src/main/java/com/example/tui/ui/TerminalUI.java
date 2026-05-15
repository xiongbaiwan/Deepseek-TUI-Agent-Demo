package com.example.tui.ui;

import com.example.tui.engine.ApprovalRequest;
import com.example.tui.engine.ApprovalRequest.Decision;
import com.example.tui.engine.AgentEngine.PlanAction;
import com.example.tui.engine.plan.Plan;
import com.example.tui.engine.plan.PlanFormatter;
import com.example.tui.engine.plan.PlanStep;
import org.jline.reader.*;
import org.jline.reader.impl.*;
import org.jline.terminal.*;
import org.jline.utils.InfoCmp;

/**
 * 基于 JLine 3 的终端界面。
 * 提供 REPL 循环、命令历史、状态提示和彩色输出。
 * 对应 Rust 项目中 crates/tui 的 ratatui 界面部分。
 */
public class TerminalUI {

    private final Terminal terminal;
    private final LineReader reader;

    // ANSI 颜色码（与 Rust 项目中 crossterm 的样式原理相同）
    public static final String RESET = "[0m";
    public static final String BOLD = "[1m";
    public static final String CYAN = "[36m";
    public static final String GREEN = "[32m";
    public static final String YELLOW = "[33m";
    public static final String GRAY = "[90m";
    public static final String RED = "[31m";
    public static final String DIM = "[2m";

    private volatile boolean showReasoning = true;

    public TerminalUI() throws Exception {
        this.terminal = TerminalBuilder.builder()
                .system(true)
                .dumb(true) // 非 TTY 环境的降级方案
                .build();
        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
    }

    /** 打印欢迎横幅 */
    public void printBanner() {
        println(CYAN + BOLD + "DeepSeek TUI (Java 演示版)" + RESET);
        println(GRAY + "输入 /help 查看命令，/quit 退出" + RESET);
        println(GRAY + "---" + RESET);
        println();
    }

    /** 打印状态信息（灰色） */
    public void printStatus(String status) {
        println(GRAY + status + RESET);
    }

    /** 打印错误信息（红色） */
    public void printError(String error) {
        println(RED + "错误: " + error + RESET);
    }

    /** 打印欢迎消息（绿色） */
    public void printWelcome(String message) {
        println(GREEN + message + RESET);
    }

    /** 读取用户输入，返回一行文本 */
    public String readInput() {
        try {
            String prompt = YELLOW + ">>> " + RESET;
            return reader.readLine(prompt);
        } catch (EndOfFileException e) {
            return "/quit";
        } catch (UserInterruptException e) {
            return "/quit";
        }
    }

    public void println(String text) {
        terminal.writer().println(text);
        terminal.writer().flush();
    }

    public void println() {
        terminal.writer().println();
        terminal.writer().flush();
    }

    /** 清屏 */
    public void clear() {
        terminal.puts(InfoCmp.Capability.clear_screen);
        terminal.flush();
    }

    /** 打印推理/思考内容（灰色显示） */
    public void printReasoning(String reasoning) {
        if (!showReasoning) return;
        terminal.writer().println(DIM + reasoning + RESET);
        terminal.writer().flush();
    }

    /** 切换推理内容显示开关 */
    public void toggleShowReasoning() {
        showReasoning = !showReasoning;
        if (showReasoning) {
            printStatus("推理内容显示: 开启");
        } else {
            printStatus("推理内容显示: 关闭");
        }
    }

    /** 获取推理显示状态 */
    public boolean isShowReasoning() {
        return showReasoning;
    }

    /** 设置推理显示状态 */
    public void setShowReasoning(boolean showReasoning) {
        this.showReasoning = showReasoning;
    }

    /**
     * 展示工具审批请求，等待用户按键决策。
     * y = 批准本次, n = 拒绝, a = 本次会话全部批准, esc = 中止
     */
    public Decision askApproval(ApprovalRequest request) {
        println();
        println(YELLOW + BOLD + "[审批请求]" + RESET);
        println(YELLOW + "  " + request.formatForDisplay() + RESET);
        println(YELLOW + "  [y] 批准  [n] 拒绝  [a] 会话全部批准  [Esc] 中止" + RESET);

        try {
            terminal.writer().flush();
            int ch = terminal.reader().read();
            println();
            switch (ch) {
                case 'y':
                case 'Y':
                    printStatus("已批准: " + request.getToolName());
                    return Decision.APPROVE_ONCE;
                case 'a':
                case 'A':
                    printStatus("会话全部批准: " + request.getToolName());
                    return Decision.APPROVE_SESSION;
                case 27: // Esc
                    printStatus("已中止执行");
                    return Decision.ABORT;
                case 'n':
                case 'N':
                default:
                    printStatus("已拒绝: " + request.getToolName());
                    return Decision.DENY;
            }
        } catch (Exception e) {
            printError("读取用户输入失败，默认拒绝: " + e.getMessage());
            return Decision.DENY;
        }
    }

    /**
     * 展示计划，等待用户操作。
     */
    public PlanAction askPlanAction(Plan plan) {
        println();
        println(CYAN + BOLD + "[计划]" + RESET);
        println(PlanFormatter.renderSteps(plan));
        println(YELLOW + "  [y] 接受并执行  [e] 编辑  [n] 取消  [Esc] 取消" + RESET);

        try {
            terminal.writer().flush();
            int ch = terminal.reader().read();
            println();
            if (ch == 'y' || ch == 'Y') {
                return PlanAction.ACCEPT;
            } else if (ch == 'e' || ch == 'E') {
                return PlanAction.EDIT;
            } else {
                return PlanAction.CANCEL;
            }
        } catch (Exception e) {
            return PlanAction.CANCEL;
        }
    }

    /**
     * 简单的计划行编辑器。
     */
    public Plan editPlan(Plan plan) {
        println();
        println(CYAN + BOLD + "[编辑计划]" + RESET);
        println(DIM + "命令: /edit N <新描述>  /delete N  /add <描述>  /accept  /cancel" + RESET);
        println();

        while (true) {
            for (PlanStep step : plan.getSteps()) {
                println(String.format("  [%d] %s", step.getId(), step.getDescription()));
            }
            println();

            String input = reader.readLine(YELLOW + "plan> " + RESET);
            if (input == null) return null;
            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.equals("/accept")) {
                return plan;
            }
            if (trimmed.equals("/cancel")) {
                return null;
            }
            if (trimmed.startsWith("/edit ")) {
                try {
                    String rest = trimmed.substring(6).trim();
                    int spaceIdx = rest.indexOf(' ');
                    int stepId = Integer.parseInt(rest.substring(0, spaceIdx));
                    String newDesc = rest.substring(spaceIdx + 1);
                    PlanStep step = plan.getStep(stepId);
                    if (step != null) {
                        step.setDescription(newDesc);
                        printStatus("步骤 " + stepId + " 已更新");
                    }
                } catch (Exception e) {
                    printError("用法: /edit <编号> <新描述>");
                }
                continue;
            }
            if (trimmed.startsWith("/delete ")) {
                try {
                    int stepId = Integer.parseInt(trimmed.substring(8).trim());
                    plan.getSteps().removeIf(s -> s.getId() == stepId);
                    // 重新编号
                    int i = 1;
                    for (PlanStep s : plan.getSteps()) {
                        s.setId(i++);
                    }
                    printStatus("步骤 " + stepId + " 已删除");
                } catch (Exception e) {
                    printError("用法: /delete <编号>");
                }
                continue;
            }
            if (trimmed.startsWith("/add ")) {
                String desc = trimmed.substring(5).trim();
                PlanStep newStep = new PlanStep();
                newStep.setId(plan.getSteps().size() + 1);
                newStep.setDescription(desc);
                plan.getSteps().add(newStep);
                printStatus("步骤已添加");
                continue;
            }
            printError("未知命令。可用: /edit, /delete, /add, /accept, /cancel");
        }
    }
}
