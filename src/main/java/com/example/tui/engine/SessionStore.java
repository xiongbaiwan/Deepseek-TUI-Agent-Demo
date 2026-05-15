package com.example.tui.engine;

import com.example.tui.model.Message;
import com.example.tui.tools.SharedMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.*;

import static com.example.tui.tools.SharedMapper.INSTANCE;

/**
 * 会话持久化：将对话历史保存为 JSON 文件，支持保存/加载/列出/删除。
 * 存储路径: .tui/sessions/<sessionId>.json
 */
public class SessionStore {

    private final Path sessionsDir;

    public SessionStore(Path workspaceRoot) {
        this.sessionsDir = workspaceRoot.resolve(".tui").resolve("sessions");
    }

    public SessionStore() {
        this(Paths.get(System.getProperty("user.dir")));
    }

    /** 保存会话 */
    public void save(String sessionId, List<Message> messages, int turnCount) {
        try {
            Files.createDirectories(sessionsDir);
            SessionData data = new SessionData();
            data.sessionId = sessionId;
            data.messages = messages;
            data.turnCount = turnCount;
            data.savedAt = Instant.now();

            // 提取首条用户消息预览
            if (!messages.isEmpty()) {
                Message firstUser = messages.stream()
                        .filter(m -> "user".equals(m.getRole()))
                        .findFirst().orElse(null);
                data.firstMessagePreview = firstUser != null ? firstUser.getContent() : "";
            }

            String json = INSTANCE.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(data);
            Files.writeString(sessionsDir.resolve(sessionId + ".json"), json);
        } catch (IOException e) {
            System.err.println("警告: 保存会话失败: " + e.getMessage());
        }
    }

    /** 加载会话 */
    public SessionData load(String sessionId) {
        try {
            Path file = sessionsDir.resolve(sessionId + ".json");
            if (!Files.exists(file)) return null;
            String json = Files.readString(file);
            return INSTANCE.readValue(json, SessionData.class);
        } catch (IOException e) {
            return null;
        }
    }

    /** 列出所有会话 */
    public List<SessionInfo> list() {
        try {
            if (!Files.exists(sessionsDir)) return Collections.emptyList();
            try (Stream<Path> stream = Files.list(sessionsDir)) {
                return stream
                        .filter(p -> p.toString().endsWith(".json"))
                        .sorted((a, b) -> {
                            try {
                                long ta = Files.getLastModifiedTime(a).toMillis();
                                long tb = Files.getLastModifiedTime(b).toMillis();
                                return Long.compare(tb, ta);
                            } catch (IOException e) { return 0; }
                        })
                        .map(p -> {
                            try {
                                String json = Files.readString(p);
                                SessionData data = INSTANCE.readValue(json, SessionData.class);
                                SessionInfo info = new SessionInfo();
                                info.id = data.sessionId;
                                info.turnCount = data.turnCount;
                                info.savedAt = data.savedAt;
                                info.firstMessagePreview = data.firstMessagePreview;
                                return info;
                            } catch (IOException e) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /** 删除会话 */
    public boolean delete(String sessionId) {
        try {
            return Files.deleteIfExists(sessionsDir.resolve(sessionId + ".json"));
        } catch (IOException e) {
            return false;
        }
    }

    /** 会话数据（内部存储格式） */
    public static class SessionData {
        public String sessionId;
        public List<Message> messages = new ArrayList<>();
        public int turnCount;
        public Instant savedAt;
        public String firstMessagePreview;
    }

    /** 会话列表展示信息 */
    public static class SessionInfo {
        public String id;
        public int turnCount;
        public Instant savedAt;
        public String firstMessagePreview;

        @Override
        public String toString() {
            String preview = firstMessagePreview != null && firstMessagePreview.length() > 40
                    ? firstMessagePreview.substring(0, 40) + "..."
                    : firstMessagePreview;
            return String.format("%s [%d turns] %s — %s",
                    id.substring(0, Math.min(8, id.length())), turnCount, savedAt, preview);
        }
    }
}
