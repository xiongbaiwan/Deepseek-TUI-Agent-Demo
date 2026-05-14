package com.example.tui.model;

import com.example.tui.tools.SharedMapper;
import com.fasterxml.jackson.annotation.*;
import java.io.IOException;
import java.util.*;

/**
 * Chat Completions API 响应体。
 * 同时兼容流式 chunk 和非流式完整响应的格式。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {

    private String id;
    private String object;
    private long created;
    private String model;
    private String systemFingerprint;
    private List<Choice> choices;
    private Usage usage;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getObject() { return object; }
    public void setObject(String object) { this.object = object; }
    public long getCreated() { return created; }
    public void setCreated(long created) { this.created = created; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getSystemFingerprint() { return systemFingerprint; }
    public void setSystemFingerprint(String systemFingerprint) { this.systemFingerprint = systemFingerprint; }
    public List<Choice> getChoices() { return choices; }
    public void setChoices(List<Choice> choices) { this.choices = choices; }
    public Usage getUsage() { return usage; }
    public void setUsage(Usage usage) { this.usage = usage; }

    /** 从 JSON 字符串解析 */
    public static ChatResponse fromJson(String json) throws IOException {
        return SharedMapper.INSTANCE.readValue(json, ChatResponse.class);
    }

    /** 流式响应中的单个 choice */
    public static class Choice {
        private int index;
        private Delta delta;
        private Message message;
        private String finish_reason;

        public int getIndex() { return index; }
        public Delta getDelta() { return delta; }
        public Message getMessage() { return message; }
        public String getFinishReason() { return finish_reason; }

        /** 判断流是否结束 */
        public boolean isDone() {
            return "stop".equals(finish_reason) || "tool_calls".equals(finish_reason);
        }
    }

    /** 流式增量内容 */
    public static class Delta {
        private String role;
        private String content;
        private List<ToolCall> tool_calls;

        public String getRole() { return role; }
        public String getContent() { return content; }
        public List<ToolCall> getToolCalls() { return tool_calls; }
    }

    /** 非流式响应中的完整消息 */
    public static class Message {
        private String role;
        private String content;
        private List<ToolCall> tool_calls;

        public String getRole() { return role; }
        public String getContent() { return content; }
        public List<ToolCall> getToolCalls() { return tool_calls; }
    }

    /** Token 用量统计 */
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;
        @JsonProperty("completion_tokens")
        private int completionTokens;
        @JsonProperty("total_tokens")
        private int totalTokens;
        @JsonProperty("prompt_tokens_details")
        private PromptTokensDetails promptTokensDetails;

        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int getTotalTokens() { return totalTokens; }
        public PromptTokensDetails getPromptTokensDetails() { return promptTokensDetails; }
    }

    /** 详细 prompt token 分类（缓存命中/未命中等） */
    public static class PromptTokensDetails {
        @JsonProperty("cached_tokens")
        private int cachedTokens;

        public int getCachedTokens() { return cachedTokens; }
    }
}
