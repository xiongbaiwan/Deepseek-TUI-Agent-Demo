package com.example.tui.tools;

import java.nio.file.Path;

/**
 * 路径安全校验：确保文件操作不超出工作区根目录。
 * 对应 Rust 项目中 workspace trust / sandbox 的路径校验逻辑。
 */
public final class WorkspaceGuard {

    private WorkspaceGuard() {}

    /**
     * 将相对路径解析到 workspaceRoot 下，并校验解析后的绝对路径仍在 workspaceRoot 范围内。
     * @return 规范化后的绝对 Path
     * @throws SecurityException 如果路径逃逸了 workspaceRoot
     */
    public static Path resolveWithin(Path workspaceRoot, String rawPath) {
        Path target = workspaceRoot.resolve(rawPath).normalize().toAbsolutePath();
        Path root = workspaceRoot.normalize().toAbsolutePath();
        if (!target.startsWith(root)) {
            throw new SecurityException("路径超出工作区范围: " + rawPath);
        }
        return target;
    }
}
