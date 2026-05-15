package com.example.tui.tools;

import com.example.tui.model.ChatRequest.ToolDefinition;

/**
 * 工具接口。每个工具需要实现两个方法：声明自身定义，以及执行逻辑。
 * 对应 Rust 项目中 crates/tools/ 目录下的工具 trait。
 */
public interface Tool {

    /** 返回工具声明，注册到 LLM 时使用 */
    ToolDefinition definition();

    /** 执行工具，参数为 JSON 字符串，返回执行结果 */
    String execute(String argumentsJson);

    /** 是否可以安全地并行执行（只读工具返回 true） */
    default boolean isParallelSafe() {
        return false;
    }
}
