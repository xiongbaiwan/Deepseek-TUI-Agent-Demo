package com.example.tui.model;

import com.fasterxml.jackson.annotation.*;
import java.util.*;

/**
 * 发送到 DeepSeek Chat Completions API 的请求体。
 * 对应 OpenAI 兼容的 /chat/completions 接口。
 */
public class ChatRequest {

    private String model;
    private List<Message> messages;
    private List<ToolDefinition> tools;
    private boolean stream;
    private double temperature = 0;
    private int maxTokens = 8192;
    private StreamOptions streamOptions;

    public ChatRequest() {}

    public ChatRequest(String model, List<Message> messages, List<ToolDefinition> tools) {
        this.model = model;
        this.messages = messages;
        this.tools = tools;
        this.stream = true;
        this.streamOptions = new StreamOptions();
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }
    public List<ToolDefinition> getTools() { return tools; }
    public void setTools(List<ToolDefinition> tools) { this.tools = tools; }
    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    @JsonProperty("stream_options")
    public StreamOptions getStreamOptions() { return streamOptions; }
    public void setStreamOptions(StreamOptions streamOptions) { this.streamOptions = streamOptions; }

    /** 流式选项，用于获取 token 用量统计 */
    public static class StreamOptions {
        @JsonProperty("include_usage")
        private boolean includeUsage = true;
        public boolean isIncludeUsage() { return includeUsage; }
        public void setIncludeUsage(boolean includeUsage) { this.includeUsage = includeUsage; }
    }

    /** 工具定义，注册到 LLM 时使用的格式 */
    public static class ToolDefinition {
        private String type = "function";
        private ToolFunction function;

        public ToolDefinition(ToolFunction function) {
            this.function = function;
        }

        public String getType() { return type; }
        public ToolFunction getFunction() { return function; }

        /** 工具函数的声明，包含名称、描述和参数 schema */
        public static class ToolFunction {
            private String name;
            private String description;
            private Map<String, Object> parameters;

            public ToolFunction(String name, String description, Map<String, Object> parameters) {
                this.name = name;
                this.description = description;
                this.parameters = parameters;
            }

            public String getName() { return name; }
            public String getDescription() { return description; }
            public Map<String, Object> getParameters() { return parameters; }
        }
    }
}
