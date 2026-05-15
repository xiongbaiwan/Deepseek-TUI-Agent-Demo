package com.example.tui.engine;

import com.fasterxml.jackson.annotation.*;
import java.time.Instant;
import java.util.*;

/**
 * 会话元数据，用于会话列表展示。
 */
public class Session {
    public String id;
    public Instant createdAt;
    public Instant lastMessageAt;
    public int turnCount;
    public String firstMessagePreview;

    public Session() {}

    public Session(String id, Instant createdAt, int turnCount, String firstMessagePreview) {
        this.id = id;
        this.createdAt = createdAt;
        this.lastMessageAt = createdAt;
        this.turnCount = turnCount;
        this.firstMessagePreview = firstMessagePreview != null && firstMessagePreview.length() > 50
                ? firstMessagePreview.substring(0, 50) + "..."
                : firstMessagePreview;
    }
}
