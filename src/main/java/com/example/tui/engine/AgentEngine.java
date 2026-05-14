package com.example.tui.engine;

import com.example.tui.client.DeepSeekClient;
import com.example.tui.client.DeepSeekClient.Cancellable;
import com.example.tui.model.*;
import com.example.tui.tools.Tool;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * 核心 Agent 循环：用户输入 → LLM → 工具执行 → 循环，直到模型输出最终文本。
 *
 * 对应 Rust 项目的 core/engine.rs 和 core/engine/turn_loop.rs。
 * 这里做了大幅简化，只保留最小可用版本。
 */
public class AgentEngine {

    private static final String MODEL = "deepseek-chat";
    private static final int MAX_TURNS = 20; // 最大保留对话轮数，超出后压缩旧消息

    private final DeepSeekClient client;
    private final List<Tool> tools;
    private final Map<String, Tool> toolMap = new HashMap<>();
    private final List<Message> messages = new ArrayList<>();
    private final Consumer<String> onStatusChange;
    private final AtomicReference<Cancellable> currentCancellable = new AtomicReference<>();
    private int turnCount = 0;

    public AgentEngine(DeepSeekClient client, List<Tool> tools, Consumer<String> onStatusChange) {
        this(client, tools, onStatusChange, null);
    }

    public AgentEngine(DeepSeekClient client, List<Tool> tools, Consumer<String> onStatusChange, String systemPrompt) {
        this.client = client;
        this.tools = tools;
        this.onStatusChange = onStatusChange;
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
        messages.add(Message.user(userInput));

        // 对话历史管理：超过最大轮数时压缩旧消息，只保留最近 N 轮
        if (turnCount > MAX_TURNS) {
            trimHistory();
        }

        // 构建工具定义列表，发送给 API
        List<ChatRequest.ToolDefinition> toolDefs = new ArrayList<>();
        for (Tool t : tools) {
            toolDefs.add(t.definition());
        }

        boolean continuing = true;
        while (continuing) {
            ChatRequest request = new ChatRequest(MODEL, messages, toolDefs);
            ToolCallResult result = streamAndCollect(request);

            // 先把 assistant 响应消息加入历史（必须放在 tool 消息之前）
            messages.add(Message.assistantWithToolCalls(result.toolCalls, result.text.toString()));

            // 如果没有工具调用，说明模型已给出最终回复，结束本轮
            if (result.toolCalls.isEmpty()) {
                continuing = false;
            } else {
                for (ToolCall tc : result.toolCalls) {
                    String name = tc.getFunction().getName();
                    Tool tool = toolMap.get(name);
                    if (tool == null) {
                        messages.add(Message.toolResult(tc.getId(),
                                "错误: 未知工具 '" + name + "'"));
                        continue;
                    }

                    onStatusChange.accept("[工具] " + name + " " + tc.getFunction().getArguments());
                    String toolResult = tool.execute(tc.getFunction().getArguments());
                    onStatusChange.accept("[工具结果] " + name + " -> "
                            + toolResult.substring(0, Math.min(200, toolResult.length())));
                    messages.add(Message.toolResult(tc.getId(), toolResult));
                }
            }
        }
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

    /** 压缩旧消息：只保留最近 MAX_TURNS 轮，前面的替换为摘要 */
    private void trimHistory() {
        int keepPairs = Math.min(MAX_TURNS, messages.size());
        // 每轮至少包含一条用户消息+一条助手消息，保留最近的几轮
        int removeCount = messages.size() - keepPairs;
        if (removeCount > 0) {
            int firstAssistantIndex = -1;
            for (int i = 0; i < messages.size(); i++) {
                if ("assistant".equals(messages.get(i).getRole())) {
                    firstAssistantIndex = i;
                    break;
                }
            }
            if (firstAssistantIndex > 0 && firstAssistantIndex < messages.size() - 2) {
                String summary = String.format("[已省略 %d 条旧消息，以控制上下文长度]", removeCount);
                Message summaryMsg = Message.assistant(summary);
                // 保留 system 消息（如果有的话）和最近的消息
                List<Message> trimmed = new ArrayList<>();
                // 保留第一条（可能是 system）
                if (firstAssistantIndex > 0) {
                    trimmed.add(messages.get(0));
                }
                trimmed.add(summaryMsg);
                trimmed.addAll(messages.subList(messages.size() - keepPairs, messages.size()));
                messages.clear();
                messages.addAll(trimmed);
            }
        }
        turnCount = Math.max(1, messages.size() / 2);
    }

    /** 收集一次流式响应的结果 */
    private static class ToolCallResult {
        final StringBuilder text = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<>();
    }
}
