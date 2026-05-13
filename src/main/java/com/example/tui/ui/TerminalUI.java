package com.example.tui.ui;

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
}
