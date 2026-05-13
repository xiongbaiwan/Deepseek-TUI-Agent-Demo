package com.example.tui.model;

import com.fasterxml.jackson.annotation.*;
import java.util.*;

/**
 * 流式响应中的单个 chunk。
 * 对应 DeepSeek Chat Completions 流式返回格式。
 */
public class ChatResponse {

    private List<Choice> choices;

    public List<Choice> getChoices() { return choices; }
    public void setChoices(List<Choice> choices) { this.choices = choices; }

    public static class Choice {
        private int index;
        private Delta delta;
        private String finish_reason;

        public int getIndex() { return index; }
        public Delta getDelta() { return delta; }
        public String getFinishReason() { return finish_reason; }

        /** 判断流是否结束 */
        public boolean isDone() {
            return "stop".equals(finish_reason) || "tool_calls".equals(finish_reason);
        }
    }

    /** 增量内容，流式返回时会逐步推送 */
    public static class Delta {
        private String role;
        private String content;
        private List<ToolCall> tool_calls;

        public String getRole() { return role; }
        public String getContent() { return content; }
        public List<ToolCall> getToolCalls() { return tool_calls; }
    }
}
