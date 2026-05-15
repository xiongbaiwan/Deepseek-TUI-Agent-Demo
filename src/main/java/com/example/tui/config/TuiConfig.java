package com.example.tui.config;

import com.example.tui.engine.ApprovalMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 配置加载器。支持从 TOML 配置文件、环境变量、CLI 参数三层加载。
 * 优先级: 配置文件 < 环境变量 < CLI 参数
 */
public class TuiConfig {

    private String apiKey;
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-chat";
    private ApprovalMode approvalMode = ApprovalMode.AGENT;
    private String systemPrompt;
    private String workspaceRoot = System.getProperty("user.dir");
    private boolean showReasoning = true;
    private int maxContextTokens = 128_000;

    // Getters
    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public ApprovalMode getApprovalMode() { return approvalMode; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getWorkspaceRoot() { return workspaceRoot; }
    public boolean isShowReasoning() { return showReasoning; }
    public int getMaxContextTokens() { return maxContextTokens; }

    // Setters (for CLI/env overrides)
    public TuiConfig setApiKey(String v) { this.apiKey = v; return this; }
    public TuiConfig setBaseUrl(String v) { this.baseUrl = v; return this; }
    public TuiConfig setModel(String v) { this.model = v; return this; }
    public TuiConfig setApprovalMode(ApprovalMode v) { this.approvalMode = v; return this; }
    public TuiConfig setSystemPrompt(String v) { this.systemPrompt = v; return this; }
    public TuiConfig setWorkspaceRoot(String v) { this.workspaceRoot = v; return this; }
    public TuiConfig setShowReasoning(boolean v) { this.showReasoning = v; return this; }
    public TuiConfig setMaxContextTokens(int v) { this.maxContextTokens = v; return this; }

    /**
     * 加载配置：尝试从项目级 .tui/config.toml 和全局 ~/.tui/config.toml 加载，
     * 然后用环境变量和 CLI 参数覆盖。
     */
    public static TuiConfig load(String[] cliArgs) {
        TuiConfig config = new TuiConfig();
        config.loadFromFiles();
        config.loadFromEnv();
        config.loadFromCli(cliArgs);
        return config;
    }

    private void loadFromFiles() {
        // 1. 全局配置 ~/.tui/config.toml
        Path global = Paths.get(System.getProperty("user.home"), ".tui", "config.toml");
        // 2. 项目级配置 .tui/config.toml（覆盖全局）
        Path local = Paths.get(System.getProperty("user.dir"), ".tui", "config.toml");

        for (Path p : new Path[]{global, local}) {
            if (!Files.exists(p)) continue;
            try {
                TomlParser parser = TomlParser.parse(p);
                Map<String, String> main = parser.getSection("");
                if (main.containsKey("api_key")) this.apiKey = main.get("api_key");
                if (main.containsKey("base_url")) this.baseUrl = main.get("base_url");
                if (main.containsKey("model")) this.model = main.get("model");
                if (main.containsKey("approval_mode")) this.approvalMode = ApprovalMode.fromConfigValue(main.get("approval_mode"));
                if (main.containsKey("system_prompt")) this.systemPrompt = main.get("system_prompt");
                if (main.containsKey("workspace_root")) this.workspaceRoot = main.get("workspace_root");

                Map<String, String> ui = parser.getSection("ui");
                if (ui.containsKey("show_reasoning")) this.showReasoning = Boolean.parseBoolean(ui.get("show_reasoning"));

                Map<String, String> ctx = parser.getSection("context");
                if (ctx.containsKey("max_tokens")) {
                    try { this.maxContextTokens = Integer.parseInt(ctx.get("max_tokens")); }
                    catch (NumberFormatException ignored) {}
                }
            } catch (IOException e) {
                System.err.println("警告: 无法读取配置文件 " + p + ": " + e.getMessage());
            }
        }
    }

    private void loadFromEnv() {
        String envKey = System.getenv("DEEPSEEK_API_KEY");
        if (envKey != null && !envKey.isEmpty()) this.apiKey = envKey;

        String envUrl = System.getenv("DEEPSEEK_BASE_URL");
        if (envUrl != null && !envUrl.isEmpty()) this.baseUrl = envUrl;

        String envModel = System.getenv("DEEPSEEK_MODEL");
        if (envModel != null && !envModel.isEmpty()) this.model = envModel;

        String envMode = System.getenv("DEEPSEEK_APPROVAL_MODE");
        if (envMode != null) this.approvalMode = ApprovalMode.fromConfigValue(envMode);

        String envReasoning = System.getenv("DEEPSEEK_SHOW_REASONING");
        if ("0".equals(envReasoning) || "false".equalsIgnoreCase(envReasoning)) this.showReasoning = false;
    }

    private void loadFromCli(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--api-key":
                    if (i + 1 < args.length) this.apiKey = args[++i];
                    break;
                case "--base-url":
                    if (i + 1 < args.length) this.baseUrl = args[++i];
                    break;
                case "--model":
                    if (i + 1 < args.length) this.model = args[++i];
                    break;
                case "--mode":
                    if (i + 1 < args.length) this.approvalMode = ApprovalMode.fromConfigValue(args[++i]);
                    break;
                case "--demo":
                    this.apiKey = "demo-key";
                    break;
                case "--workspace":
                    if (i + 1 < args.length) this.workspaceRoot = args[++i];
                    break;
                case "--no-reasoning":
                    this.showReasoning = false;
                    break;
            }
        }
    }
}
