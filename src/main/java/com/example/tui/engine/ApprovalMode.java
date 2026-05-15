package com.example.tui.engine;

/**
 * 工具审批模式，对应 DeepSeek-TUI 的 Plan / Agent / YOLO 三种模式。
 */
public enum ApprovalMode {
    /** Plan 模式：拒绝所有非安全工具，只读探索 */
    PLAN,
    /** Agent 模式：需要用户审批后执行 */
    AGENT,
    /** YOLO 模式：自动批准所有工具 */
    YOLO;

    public String label() {
        switch (this) {
            case PLAN: return "Plan";
            case AGENT: return "Agent";
            case YOLO: return "YOLO";
            default: return name();
        }
    }

    public static ApprovalMode fromConfigValue(String value) {
        if (value == null) return AGENT;
        switch (value.toLowerCase()) {
            case "plan": return PLAN;
            case "agent": return AGENT;
            case "yolo": return YOLO;
            default: return AGENT;
        }
    }
}
