package com.example.tui.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 全局共享 ObjectMapper 实例，避免每次工具调用都重建（初始化开销大）。 */
public final class SharedMapper {
    public static final ObjectMapper INSTANCE = new ObjectMapper();
}
