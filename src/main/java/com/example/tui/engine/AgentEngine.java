package com.example.tui.engine;

import com.example.tui.client.DeepSeekClient;
import com.example.tui.client.DeepSeekClient.Cancellable;
import com.example.tui.engine.plan.Plan;
import com.example.tui.engine.plan.PlanFormatter;
import com.example.tui.engine.plan.PlanStep;
import com.example.tui.model.*;
import com.example.tui.tools.Tool;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * 核心 Agent 循环：用户输入 → LLM → 工具执行 → 循环，直到模型输出最终文本。
 *
 * 对应 Rust 项目的 core/engine.rs 和 core/engine/turn_loop.rs。
 * 这里做了大幅简化，只保留最小可用版本。
 */
public class AgentEngine {

    private static final int MAX_TURNS = 20; // 最大保留对话轮数，超出后压缩旧消息

    private final DeepSeekClient client;
    private final List<Tool> tools;
    private final Map<String, Tool> toolMap = new HashMap<>();
    private final List<Message> messages = new ArrayList<>();
    private final Consumer<String> onStatusChange;
    private final Consumer<String> onReasoningDelta;
    private final AtomicReference<Cancellable> currentCancellable = new AtomicReference<>();
    private int turnCount = 0;
    private String currentModel = "deepseek-chat";
    private ApprovalMode approvalMode = ApprovalMode.AGENT;
    private final Set<String> sessionApprovedTools = new HashSet<>();
    private final ApprovalHandler approvalHandler;
    private volatile boolean turnAborted = false;
    private final ExecutorService parallelExecutor = Executors.newCachedThreadPool();
    private SessionStore sessionStore;
    private String currentSessionId;
    private ContextManager contextManager = new ContextManager();
    private boolean planMode = false;
    private PlanCallback planCallback;

    /** 审批处理器接口 */
    @FunctionalInterface
    public interface ApprovalHandler {
        ApprovalRequest.Decision ask(ApprovalRequest request);
    }

    /** 计划交互回调接口，由 UI 层实现 */
    public interface PlanCallback {
        /** 向用户展示计划并等待操作: ACCEPT, EDIT, CANCEL */
        PlanAction askForAction(Plan plan);
        /** 进入简单编辑器，返回修改后的计划或 null（用户取消编辑） */
        Plan editPlan(Plan plan);
    }

    /** 用户对计划的操作 */
    public enum PlanAction {
        ACCEPT, EDIT, CANCEL
    }

    public AgentEngine(DeepSeekClient client, List<Tool> tools, Consumer<String> onStatusChange) {
        this(client, tools, onStatusChange, null);
    }

    public AgentEngine(DeepSeekClient client, List<Tool> tools, Consumer<String> onStatusChange, String systemPrompt) {
        this(client, tools, onStatusChange, systemPrompt, null);
    }

    public AgentEngine(DeepSeekClient client, List<Tool> tools, Consumer<String> onStatusChange,
                       String systemPrompt, Consumer<String> onReasoningDelta) {
        this(client, tools, onStatusChange, systemPrompt, onReasoningDelta, "deepseek-chat");
    }

    public AgentEngine(DeepSeekClient client, List<Tool> tools, Consumer<String> onStatusChange,
                       String systemPrompt, Consumer<String> onReasoningDelta, String model) {
        this(client, tools, onStatusChange, systemPrompt, onReasoningDelta, model, null);
    }

    public AgentEngine(DeepSeekClient client, List<Tool> tools, Consumer<String> onStatusChange,
                       String systemPrompt, Consumer<String> onReasoningDelta, String model,
                       ApprovalHandler approvalHandler) {
        this.client = client;
        this.tools = tools;
        this.onStatusChange = onStatusChange;
        this.onReasoningDelta = onReasoningDelta;
        this.currentModel = model != null ? model : "deepseek-chat";
        this.approvalHandler = approvalHandler;
        // 将工具注册到名称映射表，方便执行时查找
        for (Tool t : tools) {
            toolMap.put(t.definition().getFunction().getName(), t);
        }
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(Message.system(systemPrompt));
        }
    }

    /** 取消当前正在进行的 LLM 请求 */
    public void cancelCurrentTurn() {
        Cancellable c = currentCancellable.get();
        if (c != null) {
            onStatusChange.accept("[已取消]");
            c.cancel();
        }
    }

    /**
     * 执行一轮 Agent 交互：接收用户输入，调用 LLM，如果有工具调用则执行并循环。
     */
    public void runTurn(String userInput) throws Exception {
        turnCount++;
        turnAborted = false;
        messages.add(Message.user(userInput));

        // 对话历史管理：超过最大轮数时按策略裁剪
        if (turnCount > MAX_TURNS) {
            List<Message> trimmed = contextManager.trim(messages);
            if (trimmed.size() < messages.size()) {
                messages.clear();
                messages.addAll(trimmed);
            }
        }

        executeToolLoop();
        saveCurrentSession();
    }

    /**
     * 计划-执行两阶段模式：先生成结构化计划，用户确认后逐步执行。
     */
    public void runPlanTurn(String userInput) throws Exception {
        turnCount++;
        turnAborted = false;
        messages.add(Message.user(userInput));

        // Phase 1: 生成计划
        onStatusChange.accept("正在生成计划...");
        Plan plan = generatePlan(userInput);
        if (plan == null || plan.getSteps().isEmpty()) {
            onStatusChange.accept("计划生成失败，回退到普通模式。");
            // 移除刚添加的用户消息，让 runTurn 重新处理
            messages.remove(messages.size() - 1);
            runTurn(userInput);
            return;
        }
        plan.setOriginalTask(userInput);

        // 展示计划给用户
        if (planCallback != null) {
            PlanAction action = planCallback.askForAction(plan);
            if (action == PlanAction.CANCEL) {
                onStatusChange.accept("计划已取消。");
                // 移除用户消息，不执行
                messages.remove(messages.size() - 1);
                return;
            }
            while (action == PlanAction.EDIT) {
                plan = planCallback.editPlan(plan);
                if (plan == null) {
                    onStatusChange.accept("编辑已取消。");
                    messages.remove(messages.size() - 1);
                    return;
                }
                action = planCallback.askForAction(plan);
            }
        }

        // 将计划作为上下文添加到消息历史
        messages.add(Message.assistant(PlanFormatter.renderSteps(plan)));

        // Phase 2: 逐步执行
        for (PlanStep step : plan.getSteps()) {
            if (turnAborted) {
                step.setStatus(PlanStep.Status.CANCELLED);
                continue;
            }

            step.setStatus(PlanStep.Status.IN_PROGRESS);
            onStatusChange.accept("执行步骤 " + step.getId() + ": " + step.getDescription());

            // 构建步骤指令
            StringBuilder stepInstruction = new StringBuilder();
            stepInstruction.append("执行步骤 ").append(step.getId()).append(": ").append(step.getDescription());
            stepInstruction.append("\n\n只执行此步骤所需的操作，不要跳至后续步骤。");
            stepInstruction.append("\n完成后简要总结你做了什么。");

            // 添加上一步的结果作为上下文
            if (step.getId() > 1) {
                PlanStep prev = plan.getStep(step.getId() - 1);
                if (prev != null && prev.getResult() != null) {
                    stepInstruction.append("\n\n上一步结果: ").append(prev.getResult());
                }
            }

            messages.add(Message.user(stepInstruction.toString()));

            // 复用现有的工具执行循环
            String stepSummary = executeToolLoop();

            step.setResult(stepSummary != null ? stepSummary : "(无输出)");
            if (turnAborted) {
                step.setStatus(PlanStep.Status.CANCELLED);
            } else {
                step.setStatus(PlanStep.Status.COMPLETE);
            }
        }

        // 标记被取消的后续步骤
        for (PlanStep step : plan.getSteps()) {
            if (step.getStatus() == PlanStep.Status.PENDING) {
                step.setStatus(PlanStep.Status.SKIPPED);
            }
        }

        onStatusChange.accept(PlanFormatter.renderSummary(plan));
        saveCurrentSession();
    }

    /**
     * 生成计划：调用 LLM 获取结构化步骤列表。
     */
    private Plan generatePlan(String userInput) throws Exception {
        // 极简 prompt：只发任务本身，不塞 JSON 模板（50 token 限制太严格）
        ChatRequest request = new ChatRequest(currentModel,
                Arrays.asList(Message.user(userInput)), Collections.emptyList());
        request.setStream(false);
        request.setMaxTokens(2048);

        ChatResponse response;
        try {
            response = client.chat(request);
        } catch (Exception e) {
            // 遍历异常链，打印所有层次的错误信息
            Throwable t = e;
            while (t != null) {
                onStatusChange.accept("[DEBUG] " + t.getClass().getSimpleName() + ": " + t.getMessage());
                t = t.getCause();
            }
            onStatusChange.accept("计划生成 API 调用失败: " + e.getMessage());
            return null;
        }
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return null;
        }

        String content = response.getChoices().get(0).getMessage().getContent();
        if (content == null || content.isEmpty()) return null;

        // 清理可能的 markdown 包裹
        content = content.trim();
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            int lastBacktick = content.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                content = content.substring(firstNewline + 1, lastBacktick).trim();
            }
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Plan plan = mapper.readValue(content, Plan.class);
            // 重新编号确保 id 连续
            int i = 1;
            for (PlanStep step : plan.getSteps()) {
                step.setId(i++);
            }
            return plan;
        } catch (Exception e) {
            onStatusChange.accept("计划 JSON 解析失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 核心工具执行循环：调用 LLM，执行工具调用，循环直到无工具调用或中止。
     * 返回 LLM 最终输出的文本摘要。
     */
    private String executeToolLoop() throws Exception {
        List<ChatRequest.ToolDefinition> toolDefs = new ArrayList<>();
        for (Tool t : tools) {
            toolDefs.add(t.definition());
        }

        String lastText = null;
        boolean continuing = true;
        while (continuing) {
            ChatRequest request = new ChatRequest(currentModel, messages, toolDefs);
            ToolCallResult result = streamAndCollect(request);

            // 先把 assistant 响应消息加入历史（必须放在 tool 消息之前）
            Message assistantMsg;
            if (result.toolCalls.isEmpty()) {
                assistantMsg = Message.assistant(result.text.toString());
            } else {
                assistantMsg = Message.assistantWithToolCalls(result.toolCalls, result.text.toString());
            }
            if (result.reasoning.length() > 0) {
                assistantMsg.setReasoningContent(result.reasoning.toString());
            }
            messages.add(assistantMsg);
            lastText = result.text.toString();

            // 如果没有工具调用，说明模型已给出最终回复，结束本轮
            if (result.toolCalls.isEmpty()) {
                continuing = false;
            } else {
                // 按是否可并行执行分组
                List<ToolCall> parallelSafe = new ArrayList<>();
                List<ToolCall> sequential = new ArrayList<>();
                for (ToolCall tc : result.toolCalls) {
                    String name = tc.getFunction().getName();
                    Tool tool = toolMap.get(name);
                    if (tool == null) {
                        messages.add(Message.toolResult(tc.getId(),
                                "错误: 未知工具 '" + name + "'"));
                        continue;
                    }
                    if (tool.isParallelSafe() && approvalMode == ApprovalMode.YOLO) {
                        parallelSafe.add(tc);
                    } else {
                        sequential.add(tc);
                    }
                }

                // 并行执行安全的工具调用
                Map<String, String> parallelResults = executeParallelTools(parallelSafe);
                for (ToolCall tc : parallelSafe) {
                    messages.add(Message.toolResult(tc.getId(), parallelResults.get(tc.getId())));
                }

                // 顺序执行其余工具
                for (ToolCall tc : sequential) {
                    if (turnAborted) {
                        continuing = false;
                        break;
                    }
                    String name = tc.getFunction().getName();
                    Tool tool = toolMap.get(name);
                    if (tool == null) {
                        messages.add(Message.toolResult(tc.getId(),
                                "错误: 未知工具 '" + name + "'"));
                        continue;
                    }

                    // 审批检查
                    ToolCategory category = ToolCategory.classify(name);
                    ApprovalRequest.Decision decision = checkApproval(name, category, tc.getFunction().getArguments());
                    if (decision == ApprovalRequest.Decision.ABORT) {
                        turnAborted = true;
                        messages.add(Message.toolResult(tc.getId(), "用户中止执行"));
                        continuing = false;
                        break;
                    } else if (decision == ApprovalRequest.Decision.DENY) {
                        onStatusChange.accept("[已拒绝] " + name);
                        messages.add(Message.toolResult(tc.getId(),
                                "用户拒绝了工具调用: " + name));
                        continue;
                    } else if (decision == ApprovalRequest.Decision.APPROVE_SESSION) {
                        sessionApprovedTools.add(name);
                    }

                    onStatusChange.accept("[工具] " + name + " " + tc.getFunction().getArguments());
                    String toolResult = tool.execute(tc.getFunction().getArguments());
                    onStatusChange.accept("[工具结果] " + name + " -> "
                            + toolResult.substring(0, Math.min(200, toolResult.length())));
                    messages.add(Message.toolResult(tc.getId(), toolResult));
                }
            }
        }
        return lastText;
    }

    /**
     * 流式接收 LLM 响应，通过回调逐字输出到终端，流结束时返回收集到的文本和工具调用。
     */
    private ToolCallResult streamAndCollect(ChatRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ToolCallResult result = new ToolCallResult();

        Cancellable cancellable = client.streamChat(request,
                // 文本增量回调 —— 逐字打印到终端
                text -> {
                    System.out.print(text);
                    System.out.flush();
                    result.text.append(text);
                },
                // 推理/思考内容回调 —— 灰色打印
                reasoning -> {
                    if (onReasoningDelta != null) {
                        onReasoningDelta.accept(reasoning);
                    }
                    result.reasoning.append(reasoning);
                },
                // 工具调用回调
                tc -> {
                    result.toolCalls.add(tc);
                    onStatusChange.accept("[收到工具调用] " + tc.getFunction().getName());
                },
                // 流结束
                () -> {
                    currentCancellable.set(null);
                    System.out.println();
                    latch.countDown();
                },
                // token 用量
                usage -> onStatusChange.accept(usage),
                // 出错
                err -> {
                    currentCancellable.set(null);
                    System.err.println("\n错误: " + err);
                    latch.countDown();
                }
        );
        currentCancellable.set(cancellable);

        // 等待流结束（最长等待 5 分钟）
        if (!latch.await(5, TimeUnit.MINUTES)) {
            throw new TimeoutException("LLM 流式响应超时（5 分钟）");
        }

        return result;
    }

    /** 设置使用的模型 */
    public void setModel(String model) {
        this.currentModel = model;
    }

    /** 获取当前使用的模型 */
    public String getModel() {
        return currentModel;
    }

    /** 设置审批模式 */
    public void setApprovalMode(ApprovalMode mode) {
        this.approvalMode = mode;
    }

    /** 获取当前审批模式 */
    public ApprovalMode getApprovalMode() {
        return approvalMode;
    }

    /** 设置计划模式 */
    public void setPlanMode(boolean enabled) {
        this.planMode = enabled;
    }

    /** 获取当前计划模式 */
    public boolean isPlanMode() {
        return planMode;
    }

    /** 设置计划回调 */
    public void setPlanCallback(PlanCallback callback) {
        this.planCallback = callback;
    }

    /**
     * 并行执行一组安全的工具调用，返回 Map<toolCallId, result>。
     */
    private Map<String, String> executeParallelTools(List<ToolCall> toolCalls) {
        if (toolCalls.isEmpty()) return Map.of();

        List<CompletableFuture<Map.Entry<String, String>>> futures = toolCalls.stream()
            .map(tc -> CompletableFuture.supplyAsync(() -> {
                String name = tc.getFunction().getName();
                Tool tool = toolMap.get(name);
                onStatusChange.accept("[并行工具] " + name);
                String result = tool != null ? tool.execute(tc.getFunction().getArguments()) : "错误: 未知工具";
                onStatusChange.accept("[工具结果] " + name + " -> "
                        + result.substring(0, Math.min(200, result.length())));
                return (Map.Entry<String, String>) new AbstractMap.SimpleEntry<>(tc.getId(), result);
            }, parallelExecutor))
            .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Map<String, String> results = new LinkedHashMap<>();
        for (CompletableFuture<Map.Entry<String, String>> f : futures) {
            Map.Entry<String, String> entry = f.join();
            results.put(entry.getKey(), entry.getValue());
        }
        return results;
    }

    /** 设置会话存储 */
    public void setSessionStore(SessionStore store) {
        this.sessionStore = store;
    }

    /** 获取当前会话 ID */
    public String getCurrentSessionId() {
        return currentSessionId;
    }

    /** 开始新会话 */
    public String newSession() {
        this.currentSessionId = UUID.randomUUID().toString();
        if (sessionStore != null) {
            sessionStore.save(currentSessionId, new ArrayList<>(messages), turnCount);
        }
        return currentSessionId;
    }

    /** 保存当前会话 */
    public void saveCurrentSession() {
        if (sessionStore != null && currentSessionId != null) {
            sessionStore.save(currentSessionId, new ArrayList<>(messages), turnCount);
        }
    }

    /** 加载指定会话 */
    public boolean loadSession(String sessionId) {
        if (sessionStore == null) return false;
        SessionStore.SessionData data = sessionStore.load(sessionId);
        if (data == null) return false;
        messages.clear();
        messages.addAll(data.messages);
        turnCount = data.turnCount;
        currentSessionId = sessionId;
        return true;
    }

    /** 列出所有可用会话 */
    public List<SessionStore.SessionInfo> listSessions() {
        return sessionStore != null ? sessionStore.list() : Collections.emptyList();
    }

    /**
     * 检查是否需要审批，返回审批决策。
     */
    private ApprovalRequest.Decision checkApproval(String toolName, ToolCategory category, String argsJson) {
        // YOLO 模式：全部自动批准
        if (approvalMode == ApprovalMode.YOLO) {
            return ApprovalRequest.Decision.APPROVE_ONCE;
        }
        // Plan 模式：只允许安全工具
        if (approvalMode == ApprovalMode.PLAN) {
            if (!category.isSafe()) {
                onStatusChange.accept("[Plan 模式] 已拒绝非安全工具: " + toolName);
                return ApprovalRequest.Decision.DENY;
            }
            return ApprovalRequest.Decision.APPROVE_ONCE;
        }
        // Agent 模式：安全工具或已会话审批的直接通过
        if (category.isSafe() || sessionApprovedTools.contains(toolName)) {
            return ApprovalRequest.Decision.APPROVE_ONCE;
        }
        // 需要用户审批
        if (approvalHandler == null) {
            // 无审批处理器，降级为 YOLO
            return ApprovalRequest.Decision.APPROVE_ONCE;
        }
        ApprovalRequest request = new ApprovalRequest(toolName, category, argsJson);
        return approvalHandler.ask(request);
    }

    /** 清空对话历史，但保留 system 消息 */
    public void reset() {
        Message systemMsg = messages.isEmpty() || !"system".equals(messages.get(0).getRole())
                ? null : messages.remove(0);
        messages.clear();
        turnCount = 0;
        if (systemMsg != null) {
            messages.add(systemMsg);
        }
    }

    /** 设置上下文管理器 */
    public void setContextManager(ContextManager manager) {
        this.contextManager = manager;
    }

    /** 收集一次流式响应的结果 */
    private static class ToolCallResult {
        final StringBuilder text = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<>();
    }
}
