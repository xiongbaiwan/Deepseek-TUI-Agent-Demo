package com.example.tui.model;

import com.fasterxml.jackson.annotation.*;

/**
 * 模型发起的工具调用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolCall {

    private String id;
    private String type = "function";
    private FunctionCall function;

    public ToolCall() {}

    public ToolCall(String id, String name, String arguments) {
        this.id = id;
        this.type = "function";
        if (name != null) {
            this.function = new FunctionCall(name, arguments != null ? arguments : "");
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public FunctionCall getFunction() { return function; }
    public void setFunction(FunctionCall function) { this.function = function; }

    /** 工具函数的具体调用 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionCall {
        private String name;
        private String arguments;

        public FunctionCall() {}
        public FunctionCall(String name, String arguments) {
            this.name = name;
            this.arguments = arguments != null ? arguments : "";
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getArguments() { return arguments; }
        public void setArguments(String arguments) { this.arguments = arguments != null ? arguments : ""; }
    }
}
