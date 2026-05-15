package com.example.tui.engine;

/**
 * 工具审批请求，展示给用户确认。
 */
public class ApprovalRequest {
    private final String toolName;
    private final ToolCategory category;
    private final String argumentsJson;

    public ApprovalRequest(String toolName, ToolCategory category, String argumentsJson) {
        this.toolName = toolName;
        this.category = category;
        this.argumentsJson = argumentsJson;
    }

    public String getToolName() { return toolName; }
    public ToolCategory getCategory() { return category; }
    public String getArgumentsJson() { return argumentsJson; }

    /** 格式化审批请求，用于终端展示 */
    public String formatForDisplay() {
        String categoryLabel = category.name();
        String summary = argumentsJson.length() > 100
                ? argumentsJson.substring(0, 100) + "..."
                : argumentsJson;
        return String.format("[%s] %s -> %s", categoryLabel, toolName, summary);
    }

    /** 审批决策 */
    public enum Decision {
        /** 批准本次执行 */
        APPROVE_ONCE,
        /** 批准本次会话中此工具的所有调用 */
        APPROVE_SESSION,
        /** 拒绝执行 */
        DENY,
        /** 中止当前轮次 */
        ABORT
    }
}
