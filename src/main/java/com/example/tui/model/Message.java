package com.example.tui.model;

import com.fasterxml.jackson.annotation.*;
import java.util.*;

/**
 * 对话消息体，对应 OpenAI 兼容协议的 message 对象。
 * 支持 user、assistant、tool 三种角色。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {

    private String role;
    private String content;

    private List<ToolCall> tool_calls;
    private String tool_call_id;

    public static Message user(String content) {
        Message m = new Message();
        m.role = "user";
        m.content = content;
        return m;
    }

    public static Message assistant(String content) {
        Message m = new Message();
        m.role = "assistant";
        m.content = content;
        return m;
    }

    public static Message assistantWithToolCalls(List<ToolCall> toolCalls, String content) {
        Message m = new Message();
        m.role = "assistant";
        m.content = content;
        m.tool_calls = toolCalls;
        return m;
    }

    /** 构造工具调用结果消息 */
    public static Message toolResult(String toolCallId, String content) {
        Message m = new Message();
        m.role = "tool";
        m.tool_call_id = toolCallId;
        m.content = content;
        return m;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    @JsonProperty("tool_calls")
    public List<ToolCall> getToolCalls() { return tool_calls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.tool_calls = toolCalls; }
    @JsonProperty("tool_call_id")
    public String getToolCallId() { return tool_call_id; }
    public void setToolCallId(String toolCallId) { this.tool_call_id = toolCallId; }
}
