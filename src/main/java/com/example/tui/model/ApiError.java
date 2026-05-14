package com.example.tui.model;

import com.example.tui.tools.SharedMapper;
import com.fasterxml.jackson.annotation.*;

/**
 * 结构化 API 错误，对应 OpenAI 兼容的错误响应格式。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {

    private String message;
    private String type;
    private String code;
    private String param;

    public ApiError() {}

    public ApiError(String message) {
        this.message = message;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getParam() { return param; }
    public void setParam(String param) { this.param = param; }

    /** 友好的错误描述 */
    public String description() {
        StringBuilder sb = new StringBuilder();
        sb.append(message != null ? message : "unknown error");
        if (type != null) sb.append(" (type=").append(type).append(")");
        if (code != null) sb.append(" [").append(code).append("]");
        return sb.toString();
    }

    /** 从原始响应体解析，解析失败返回降级版本 */
    public static ApiError parse(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            return new ApiError("空响应体");
        }
        try {
            // 标准格式: {"error": {"message": "...", "type": "...", ...}}
            var root = SharedMapper.INSTANCE.readTree(rawBody);
            var errorNode = root.get("error");
            if (errorNode != null && errorNode.isObject()) {
                ApiError err = new ApiError();
                err.message = errorNode.path("message").asText(null);
                err.type = errorNode.path("type").asText(null);
                err.code = errorNode.path("code").asText(null);
                err.param = errorNode.path("param").asText(null);
                return err;
            }
            // 扁平格式: {"message": "...", ...}
            if (root.has("message")) {
                ApiError err = new ApiError();
                err.message = root.path("message").asText(null);
                err.type = root.path("type").asText(null);
                err.code = root.path("code").asText(null);
                return err;
            }
        } catch (Exception e) {
            // 忽略 JSON 解析错误，返回降级版本
        }
        return new ApiError(rawBody.length() > 500 ? rawBody.substring(0, 500) + "..." : rawBody);
    }
}
