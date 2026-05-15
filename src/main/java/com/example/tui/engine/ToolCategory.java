package com.example.tui.engine;

/**
 * 工具风险分类，用于决定是否需要用户审批。
 */
public enum ToolCategory {
    /** 只读操作，无需审批 */
    SAFE,
    /** 文件写操作，需要审批 */
    FILE_WRITE,
    /** Shell 命令，高风险，需要审批 */
    SHELL,
    /** 网络请求，需要审批 */
    NETWORK,
    /** 未知工具，默认需要审批 */
    UNKNOWN;

    private static final java.util.Map<String, ToolCategory> CATEGORY_MAP = new java.util.HashMap<>();
    static {
        CATEGORY_MAP.put("read_file", SAFE);
        CATEGORY_MAP.put("list_dir", SAFE);
        CATEGORY_MAP.put("grep_files", SAFE);
        CATEGORY_MAP.put("file_search", SAFE);
        CATEGORY_MAP.put("write_file", FILE_WRITE);
        CATEGORY_MAP.put("edit_file", FILE_WRITE);
        CATEGORY_MAP.put("exec_shell", SHELL);
        CATEGORY_MAP.put("fetch_url", NETWORK);
    }

    public static ToolCategory classify(String toolName) {
        return CATEGORY_MAP.getOrDefault(toolName, UNKNOWN);
    }

    public boolean isSafe() {
        return this == SAFE;
    }
}
