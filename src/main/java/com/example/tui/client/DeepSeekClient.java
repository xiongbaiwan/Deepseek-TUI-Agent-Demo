package com.example.tui.client;

import com.example.tui.model.*;
import com.example.tui.tools.SharedMapper;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DeepSeek Chat Completions API 客户端，支持 SSE 流式响应、重试、取消和结构化错误。
 * 对应 Rust 项目中的 client.rs。
 */
public class DeepSeekClient implements AutoCloseable {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long BASE_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 30_000;

    private static final Logger LOG = Logger.getLogger(DeepSeekClient.class.getName());

    private final OkHttpClient httpClient;
    private final HttpUrl chatUrl;
    private final String apiKey;
    private final int maxRetries;
    private final Random random = new Random();

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public DeepSeekClient(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, 30, 300);
    }

    public DeepSeekClient(String apiKey, String baseUrl, int connectTimeoutSeconds, int readTimeoutSeconds) {
        this(apiKey, baseUrl, connectTimeoutSeconds, readTimeoutSeconds, DEFAULT_MAX_RETRIES);
    }

    public DeepSeekClient(String apiKey, String baseUrl, int connectTimeoutSeconds, int readTimeoutSeconds, int maxRetries) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        this.apiKey = apiKey;
        this.maxRetries = Math.max(0, maxRetries);

        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null || (!parsed.scheme().equals("http") && !parsed.scheme().equals("https"))) {
            throw new IllegalArgumentException("baseUrl 格式无效: " + baseUrl);
        }
        this.chatUrl = parsed.newBuilder()
                .addPathSegment("chat")
                .addPathSegment("completions")
                .build();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 流式调用对话。返回 Cancellable 对象，调用方可通过它取消请求。
     */
    public Cancellable streamChat(ChatRequest request,
                                  Consumer<String> onTextDelta,
                                  Consumer<ToolCall> onToolCallDelta,
                                  Runnable onDone,
                                  Consumer<String> onUsage,
                                  Consumer<String> onError) {
        String requestId = UUID.randomUUID().toString();
        SseStream stream = new SseStream(requestId, request, onTextDelta, onToolCallDelta, onDone, onUsage, onError);
        stream.start();
        return stream;
    }

    /**
     * 非流式同步调用，返回结构化响应对象。
     */
    public ChatResponse chat(ChatRequest request) throws IOException, InterruptedException, TimeoutException {
        ChatRequest nonStreamRequest = new ChatRequest(
                request.getModel(), request.getMessages(), request.getTools());
        nonStreamRequest.setStream(false);
        nonStreamRequest.setStreamOptions(null);
        nonStreamRequest.setTemperature(request.getTemperature());
        nonStreamRequest.setMaxTokens(request.getMaxTokens());

        String body = SharedMapper.INSTANCE.writeValueAsString(nonStreamRequest);
        Request httpRequest = new Request.Builder()
                .url(chatUrl)
                .post(RequestBody.create(body, JSON))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        ChatResponse[] resultHolder = new ChatResponse[1];
        Exception[] errorHolder = new Exception[1];

        httpClient.newCall(httpRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                errorHolder[0] = e;
                latch.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        try (ResponseBody rb = response.body()) {
                            String errBody = rb != null ? rb.string() : "";
                            throw new IOException("API 错误 " + response.code() + ": " + errBody);
                        }
                    }
                    try (ResponseBody rb = response.body()) {
                        if (rb == null) throw new IOException("响应体为空");
                        resultHolder[0] = ChatResponse.fromJson(rb.string());
                    }
                } catch (IOException e) {
                    errorHolder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        if (!latch.await(300, TimeUnit.SECONDS)) {
            throw new TimeoutException("非流式请求超时");
        }
        if (errorHolder[0] != null) {
            throw new IOException("API 调用失败", errorHolder[0]);
        }
        return resultHolder[0];
    }

    /**
     * 关闭客户端底层资源（连接池、分发器）。关闭后实例不能再使用。
     */
    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
        LOG.info("DeepSeekClient 已关闭");
    }

    /** 格式化 token 数，>=1M 显示 M，否则显示千位分隔 */
    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) {
            return String.format("%.1fM", tokens / 1_000_000.0);
        }
        return String.format("%,d", tokens);
    }

    /** 可取消的操作接口 */
    @FunctionalInterface
    public interface Cancellable {
        void cancel();
    }

    /**
     * 管理单次流式请求的完整生命周期，包括内部重试。
     * 重试在独立线程执行，不阻塞 OkHttp 分发器。
     */
    private class SseStream implements Cancellable {

        private final String requestId;
        private final ChatRequest request;
        private final Consumer<String> onTextDelta;
        private final Consumer<ToolCall> onToolCallDelta;
        private final Runnable onDone;
        private final Consumer<String> onUsage;
        private final Consumer<String> onError;

        private volatile EventSource eventSource;
        private volatile boolean cancelled = false;

        SseStream(String requestId, ChatRequest request,
                  Consumer<String> onTextDelta, Consumer<ToolCall> onToolCallDelta,
                  Runnable onDone, Consumer<String> onUsage, Consumer<String> onError) {
            this.requestId = requestId;
            this.request = request;
            this.onTextDelta = onTextDelta;
            this.onToolCallDelta = onToolCallDelta;
            this.onDone = onDone;
            this.onUsage = onUsage;
            this.onError = onError;
        }

        void start() {
            new Thread(() -> executeWithRetry(), "deepseek-stream-" + requestId).start();
        }

        @Override
        public void cancel() {
            cancelled = true;
            EventSource es = eventSource;
            if (es != null) {
                es.cancel();
            }
        }

        private void executeWithRetry() {
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                if (cancelled) {
                    onDone.run();
                    return;
                }

                final int currentAttempt = attempt;
                String body;
                try {
                    body = SharedMapper.INSTANCE.writeValueAsString(request);
                } catch (Exception e) {
                    onError.accept("请求序列化失败: " + e.getMessage());
                    onDone.run();
                    return;
                }

                Request httpRequest = new Request.Builder()
                        .url(chatUrl)
                        .post(RequestBody.create(body, JSON))
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-Client-Request-Id", requestId)
                        .build();

                long startTime = System.nanoTime();
                CountDownLatch latch = new CountDownLatch(1);
                AtomicBoolean shouldRetry = new AtomicBoolean(false);

                SseCallback callback = new SseCallback(latch, shouldRetry, startTime);
                eventSource = EventSources.createFactory(httpClient).newEventSource(httpRequest, callback);

                // 等待流结束
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    onDone.run();
                    return;
                }

                if (cancelled) {
                    onDone.run();
                    return;
                }

                // 判断是否需要重试
                if (shouldRetry.get() && currentAttempt < maxRetries) {
                    long delay = computeRetryDelay(currentAttempt);
                    LOG.fine(() -> "[" + requestId + "] " + (delay / 1000)
                            + "s 后重试 (attempt " + (currentAttempt + 1) + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        onDone.run();
                        return;
                    }
                    continue;
                }

                onDone.run();
                return;
            }
            onDone.run();
        }

        private long computeRetryDelay(int attempt) {
            long baseDelay = Math.min(BASE_RETRY_DELAY_MS * (1L << attempt), MAX_RETRY_DELAY_MS);
            long jitter = (long) (random.nextDouble() * baseDelay * 0.5);
            return baseDelay + jitter;
        }

        private class SseCallback extends EventSourceListener {
            private final CountDownLatch latch;
            private final AtomicBoolean shouldRetry;
            private final long startTime;

            private final Map<Integer, StringBuilder> toolCallJsonBuffers = new HashMap<>();
            private final Map<Integer, ToolCall> toolCallIdBuffers = new HashMap<>();

            SseCallback(CountDownLatch latch, AtomicBoolean shouldRetry, long startTime) {
                this.latch = latch;
                this.shouldRetry = shouldRetry;
                this.startTime = startTime;
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                try {
                    if ("[DONE]".equals(data)) return;

                    var node = SharedMapper.INSTANCE.readTree(data);
                    var choices = node.get("choices");
                    var usage = node.get("usage");

                    // 解析 usage
                    if (usage != null && !usage.isNull() && onUsage != null) {
                        int prompt = usage.path("prompt_tokens").asInt(0);
                        int completion = usage.path("completion_tokens").asInt(0);
                        int total = usage.path("total_tokens").asInt(0);
                        onUsage.accept(String.format("输入 %s | 输出 %s | 总计 %s",
                                formatTokens(prompt),
                                formatTokens(completion),
                                formatTokens(total)));
                    }

                    if (choices == null || !choices.isArray()) return;

                    for (var choice : choices) {
                        var delta = choice.get("delta");
                        if (delta == null) continue;

                        // 文本增量
                        var contentNode = delta.get("content");
                        if (contentNode != null && contentNode.isTextual() && onTextDelta != null) {
                            onTextDelta.accept(contentNode.asText());
                        }

                        // 工具调用增量
                        var toolCallsNode = delta.get("tool_calls");
                        if (toolCallsNode != null && toolCallsNode.isArray() && onToolCallDelta != null) {
                            for (var tc : toolCallsNode) {
                                int index = tc.path("index").asInt(-1);
                                if (index < 0) continue;

                                var existing = toolCallIdBuffers.computeIfAbsent(
                                        index, k -> new ToolCall(null, null, ""));

                                var idNode = tc.get("id");
                                if (idNode != null) {
                                    existing.setId(idNode.asText());
                                }

                                var funcNode = tc.get("function");
                                if (funcNode != null) {
                                    var nameNode = funcNode.get("name");
                                    if (nameNode != null && existing.getFunction() == null) {
                                        existing.setFunction(new ToolCall.FunctionCall(nameNode.asText(), ""));
                                    }
                                    var argsNode = funcNode.get("arguments");
                                    if (argsNode != null) {
                                        toolCallJsonBuffers
                                                .computeIfAbsent(index, k -> new StringBuilder())
                                                .append(argsNode.asText());
                                    }
                                }
                            }
                        }

                        // 检查结束原因
                        var finishNode = choice.get("finish_reason");
                        if (finishNode != null && !"null".equals(finishNode.asText())) {
                            String reason = finishNode.asText();
                            if ("tool_calls".equals(reason) && onToolCallDelta != null) {
                                emitAccumulatedToolCalls();
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "[" + requestId + "] SSE 解析跳过: " + e.getMessage(), e);
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                long elapsed = System.nanoTime() - startTime;
                LOG.fine(() -> "[" + requestId + "] 流结束，耗时 " + TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms");
                latch.countDown();
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                if (cancelled) {
                    LOG.fine(() -> "[" + requestId + "] 请求已取消");
                    latch.countDown();
                    return;
                }

                // 5xx 或网络错误标记为需要重试
                if (response != null && response.code() >= 500) {
                    LOG.fine(() -> "[" + requestId + "] 服务端错误 " + response.code());
                    shouldRetry.set(true);
                    latch.countDown();
                    return;
                }
                if (t != null) {
                    LOG.fine(() -> "[" + requestId + "] 网络错误: " + t.getMessage());
                    shouldRetry.set(true);
                    latch.countDown();
                    return;
                }

                // 4xx 客户端错误不重试
                if (response != null) {
                    int code = response.code();
                    String errBody = null;
                    try (ResponseBody rb = response.body()) {
                        if (rb != null) errBody = rb.string();
                    } catch (IOException ignored) {}
                    ApiError apiError = ApiError.parse(errBody);
                    LOG.log(Level.WARNING, "[" + requestId + "] API 错误 " + code + ": " + apiError.description());
                    onError.accept("API 错误 " + code + ": " + apiError.description());
                }
                latch.countDown();
            }

            @Override
            public void onOpen(EventSource eventSource, Response response) {
                long elapsed = System.nanoTime() - startTime;
                LOG.fine(() -> "[" + requestId + "] 连接建立，耗时 " + TimeUnit.NANOSECONDS.toMillis(elapsed) + "ms");
            }

            private void emitAccumulatedToolCalls() {
                for (var entry : toolCallIdBuffers.entrySet()) {
                    ToolCall tc = entry.getValue();
                    String args = toolCallJsonBuffers.getOrDefault(entry.getKey(), new StringBuilder()).toString();
                    String funcName = tc.getFunction() != null ? tc.getFunction().getName() : "";
                    tc.setFunction(new ToolCall.FunctionCall(funcName, args));
                    onToolCallDelta.accept(tc);
                }
            }
        }
    }
}
