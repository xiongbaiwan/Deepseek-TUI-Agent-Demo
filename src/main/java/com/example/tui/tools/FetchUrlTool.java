package com.example.tui.tools;

import com.example.tui.model.ChatRequest.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.*;
import java.util.*;

import okhttp3.*;

public class FetchUrlTool implements Tool {

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .readTimeout(java.time.Duration.ofSeconds(30))
        .followRedirects(true)
        .build();

    @Override
    public ToolDefinition definition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", Map.of(
            "type", "string",
            "description", "要获取的 URL"
        ));
        properties.put("strip_html", Map.of(
            "type", "boolean",
            "description", "是否从 HTML 中剥离标签仅保留文本，默认 true"
        ));
        params.put("properties", properties);
        params.put("required", List.of("url"));

        return new ToolDefinition(new ToolDefinition.ToolFunction(
            "fetch_url",
            "直接 HTTP GET 请求获取已知 URL 的内容",
            params
        ));
    }

    @Override
    public boolean isParallelSafe() { return true; }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode node = SharedMapper.INSTANCE.readTree(argumentsJson);
            String url = node.get("url").asText();
            boolean stripHtml = !node.has("strip_html") || node.get("strip_html").asBoolean();

            // 仅允许 http/https，拒绝 file://、本地回环、内网地址
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException e) {
                return "URL 格式无效: " + e.getMessage();
            }
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return "仅支持 http/https 协议，拒绝: " + url;
            }
            String host = uri.getHost();
            if (host == null || host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("0.0.0.0") || host.equals("[::1]")) {
                return "拒绝访问本地地址: " + url;
            }

            Request request = new Request.Builder().url(url).build();

            try (Response response = HTTP.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return "HTTP 请求失败: " + response.code() + " " + response.message();
                }

                String body = response.body() != null ? response.body().string() : "";
                String contentType = response.header("Content-Type", "");

                if (stripHtml && contentType.contains("text/html")) {
                    body = body.replaceAll("(?is)<script[^>]*>.*?</script>", "");
                    body = body.replaceAll("(?is)<style[^>]*>.*?</style>", "");
                    body = body.replaceAll("<[^>]+>", "");
                    body = body.replaceAll("&nbsp;", " ");
                    body = body.replaceAll("&amp;", "&");
                    body = body.replaceAll("&lt;", "<");
                    body = body.replaceAll("&gt;", ">");
                    body = body.replaceAll("&quot;", "\"");
                    body = body.replaceAll("&#39;", "'");
                }

                int maxLen = 20000;
                if (body.length() > maxLen) {
                    return body.substring(0, maxLen) + "\n... (内容过长，已截断)";
                }
                return body;
            }
        } catch (Exception e) {
            return "获取 URL 失败: " + e.getMessage();
        }
    }
}
