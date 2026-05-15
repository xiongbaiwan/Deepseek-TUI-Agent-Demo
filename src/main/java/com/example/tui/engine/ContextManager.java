package com.example.tui.engine;

import com.example.tui.model.Message;

import java.util.*;

/**
 * 上下文管理器：提供多种策略管理对话历史，防止超出上下文窗口。
 * 包含粗略 token 计数（字符数/4 英文，/2 中文）。
 */
public class ContextManager {

    /** 上下文管理策略 */
    public enum Strategy {
        /** 直接截断旧消息（当前默认行为） */
        TRUNCATE,
        /** 滑动窗口：只保留最近 N 条消息 */
        SLIDING_WINDOW,
        /** 占位符摘要：将旧消息替换为一条摘要消息 */
        SUMMARIZE
    }

    private final Strategy strategy;
    private final int maxContextTokens;
    private final int maxMessages;

    public ContextManager(Strategy strategy, int maxContextTokens, int maxMessages) {
        this.strategy = strategy;
        this.maxContextTokens = maxContextTokens;
        this.maxMessages = maxMessages;
    }

    public ContextManager() {
        this(Strategy.TRUNCATE, 128_000, 40);
    }

    /**
     * 估算文本的 token 数（粗略估计）。
     * 英文约 4 字符/token，中文约 2 字符/token。
     */
    public static int estimateTokens(String text) {
        if (text == null) return 0;
        int cjkCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '⺀' && c <= '鿿') cjkCount++;
        }
        int latinCount = text.length() - cjkCount;
        return (cjkCount / 2) + (latinCount / 4);
    }

    /**
     * 裁剪消息列表，根据策略控制上下文大小。
     */
    public List<Message> trim(List<Message> messages) {
        if (messages.size() <= 2) return messages;

        switch (strategy) {
            case SLIDING_WINDOW:
                return slidingWindow(messages);
            case SUMMARIZE:
                return summarize(messages);
            case TRUNCATE:
            default:
                return truncate(messages);
        }
    }

    /**
     * 策略 1：截断 —— 保留最近 maxMessages 条消息
     */
    private List<Message> truncate(List<Message> messages) {
        if (messages.size() <= maxMessages) return messages;

        // 始终保留 system 消息
        Message systemMsg = null;
        if ("system".equals(messages.get(0).getRole())) {
            systemMsg = messages.get(0);
        }

        int keepFrom = Math.max(1, messages.size() - maxMessages);
        List<Message> result = new ArrayList<>();
        if (systemMsg != null) {
            result.add(systemMsg);
        }
        result.add(Message.assistant(String.format(
                "[已省略 %d 条旧消息，以控制上下文长度]", keepFrom - 1)));
        result.addAll(messages.subList(keepFrom, messages.size()));
        return result;
    }

    /**
     * 策略 2：滑动窗口 —— 只保留最近 maxMessages 条消息，无摘要
     */
    private List<Message> slidingWindow(List<Message> messages) {
        if (messages.size() <= maxMessages) return messages;

        Message systemMsg = null;
        if ("system".equals(messages.get(0).getRole())) {
            systemMsg = messages.get(0);
        }

        int keepFrom = messages.size() - maxMessages;
        List<Message> result = new ArrayList<>();
        if (systemMsg != null) {
            result.add(systemMsg);
        }
        result.addAll(messages.subList(keepFrom, messages.size()));
        return result;
    }

    /**
     * 策略 3：摘要 —— 将旧消息替换为一条摘要消息
     */
    private List<Message> summarize(List<Message> messages) {
        if (messages.size() <= maxMessages) return messages;

        Message systemMsg = null;
        if ("system".equals(messages.get(0).getRole())) {
            systemMsg = messages.get(0);
        }

        // 统计被省略的消息类型
        int omittedUser = 0, omittedAssistant = 0, omittedTool = 0;
        for (int i = 1; i < messages.size() - maxMessages + 1; i++) {
            String role = messages.get(i).getRole();
            if ("user".equals(role)) omittedUser++;
            else if ("assistant".equals(role)) omittedAssistant++;
            else if ("tool".equals(role)) omittedTool++;
        }

        String summary = String.format(
                "[已省略 %d 轮对话: %d 条用户消息, %d 条助手回复, %d 条工具结果，以控制上下文长度]",
                omittedUser, omittedUser, omittedAssistant, omittedTool);

        List<Message> result = new ArrayList<>();
        if (systemMsg != null) result.add(systemMsg);
        result.add(Message.assistant(summary));

        int keepFrom = messages.size() - maxMessages + 1;
        result.addAll(messages.subList(keepFrom, messages.size()));
        return result;
    }

    /**
     * 估算当前消息列表的总 token 数。
     */
    public int estimateTotalTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += estimateTokens(m.getContent());
            if (m.getReasoningContent() != null) {
                total += estimateTokens(m.getReasoningContent());
            }
        }
        return total;
    }
}
