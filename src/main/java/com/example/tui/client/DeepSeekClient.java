package com.example.tui.client;

import com.example.tui.model.*;
import com.fasterxml.jackson.databind.*;
import okhttp3.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/**
 * DeepSeek Chat Completions API 的 HTTP 客户端，支持 SSE 流式响应。
 * 对应 Rust 项目中的 client.rs。
 */
public class DeepSeekClient {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper mapper;

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public DeepSeekClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * 流式调用对话。每个 SSE chunk 解析后通过回调传递给调用方。
     * 流结束时触发 onDone，出错时触发 onError。
     */
    public void streamChat(ChatRequest request,
                           Consumer<String> onTextDelta,
                           Consumer<ToolCall> onToolCallDelta,
                           Runnable onDone,
                           Consumer<String> onError) {
        try {
            String body = mapper.writeValueAsString(request);

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .post(RequestBody.create(body, JSON))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            httpClient.newCall(httpRequest).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    onError.accept("HTTP 请求失败: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    if (!response.isSuccessful()) {
                        try (ResponseBody err = response.body()) {
                            onError.accept("API 错误 " + response.code() + ": "
                                    + (err != null ? err.string() : "无响应体"));
                        } catch (IOException e) {
                            onError.accept("API 错误 " + response.code() + ": 读取响应体失败");
                        }
                        return;
                    }

                    try (ResponseBody responseBody = response.body()) {
                        if (responseBody == null) {
                            onError.accept("响应体为空");
                            return;
                        }

                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
                            StringBuilder accumulatedText = new StringBuilder();
                            Map<Integer, StringBuilder> toolCallJsonBuffers = new HashMap<>();
                            Map<Integer, ToolCall> toolCallIdBuffers = new HashMap<>();

                            String line;
                            while ((line = reader.readLine()) != null) {
                                line = line.trim();
                                if (line.isEmpty() || !line.startsWith("data:")) continue;

                                String data = line.substring(5).trim();
                                if ("[DONE]".equals(data)) break;

                                try {
                                    JsonNode node = mapper.readTree(data);
                                    JsonNode choices = node.get("choices");
                                    if (choices == null || !choices.isArray()) continue;

                                    for (JsonNode choice : choices) {
                                        JsonNode delta = choice.get("delta");
                                        if (delta == null) continue;

                                        // 文本增量
                                        JsonNode contentNode = delta.get("content");
                                        if (contentNode != null && contentNode.isTextual()) {
                                            String text = contentNode.asText();
                                            accumulatedText.append(text);
                                            onTextDelta.accept(text);
                                        }

                                        // 工具调用增量（JSON 参数可能分片推送）
                                        JsonNode toolCallsNode = delta.get("tool_calls");
                                        if (toolCallsNode != null && toolCallsNode.isArray()) {
                                            for (JsonNode tc : toolCallsNode) {
                                                int index = tc.get("index").asInt();

                                                // ID 只在第一个 chunk 中出现
                                                JsonNode idNode = tc.get("id");
                                                if (idNode != null) {
                                                    toolCallIdBuffers.put(index, new ToolCall(
                                                            idNode.asText(), null, ""));
                                                }

                                                JsonNode funcNode = tc.get("function");
                                                if (funcNode != null) {
                                                    JsonNode nameNode = funcNode.get("name");
                                                    if (nameNode != null) {
                                                        ToolCall existing = toolCallIdBuffers.get(index);
                                                        if (existing != null) {
                                                            existing.setFunction(
                                                                    new ToolCall.FunctionCall(nameNode.asText(), ""));
                                                        }
                                                    }
                                                    JsonNode argsNode = funcNode.get("arguments");
                                                    if (argsNode != null) {
                                                        String argsChunk = argsNode.asText();
                                                        toolCallJsonBuffers
                                                                .computeIfAbsent(index, k -> new StringBuilder())
                                                                .append(argsChunk);
                                                    }
                                                }
                                            }
                                        }

                                        // 检查结束原因
                                        JsonNode finishNode = choice.get("finish_reason");
                                        if (finishNode != null && !"null".equals(finishNode.asText())) {
                                            // 流结束，拼装修好的工具调用参数
                                            for (Map.Entry<Integer, ToolCall> entry : toolCallIdBuffers.entrySet()) {
                                                ToolCall tc = entry.getValue();
                                                String args = toolCallJsonBuffers
                                                        .getOrDefault(entry.getKey(), new StringBuilder())
                                                        .toString();
                                                tc.setFunction(new ToolCall.FunctionCall(
                                                        tc.getFunction() != null ? tc.getFunction().getName() : "", args));
                                                onToolCallDelta.accept(tc);
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    // 跳过格式错误的 SSE chunk
                                }
                            }

                            onDone.run();
                        }
                    } catch (IOException e) {
                        onError.accept("读取流时出错: " + e.getMessage());
                    }
                }
            });

        } catch (Exception e) {
            onError.accept("请求序列化失败: " + e.getMessage());
        }
    }

    /**
     * 非流式同步调用，用于简单的健康检查。
     */
    public String simpleChat(String model, String userMessage) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", userMessage)));

        String json = mapper.writeValueAsString(body);
        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .post(RequestBody.create(json, JSON))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API 错误 " + response.code());
            }
            try (ResponseBody rb = response.body()) {
                JsonNode root = mapper.readTree(rb.string());
                return root.at("/choices/0/message/content").asText();
            }
        }
    }
}
