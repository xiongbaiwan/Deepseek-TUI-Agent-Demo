# Java TUI Agent

基于 DeepSeek API 的终端交互式 AI Agent，支持工具调用（Tool Calling）能力。

## 功能

- 文件操作：读取、写入、编辑文件
- 目录浏览：列出目录内容
- 文件搜索：按文件名模糊搜索、正则内容搜索
- 网络请求：HTTP GET 获取网页内容
- Shell 执行：运行终端命令
- SSE 流式响应：实时逐字输出

## 技术栈

| 组件 | 依赖 |
|------|------|
| 终端 UI | JLine 3 |
| JSON 解析 | Jackson |
| HTTP 客户端 | OkHttp（支持流式 SSE） |
| 日志 | SLF4J |
| 构建 | Maven（Java 11+） |

## 快速开始

### 环境要求

- JDK 11 或更高版本
- Maven 3.6+

### 运行

```bash
# 使用环境变量配置 API Key
export DEEPSEEK_API_KEY=your-api-key
mvn exec:java

# 或命令行传参
mvn exec:java -Dexec.args="--api-key YOUR_KEY"

# 自定义 API 地址
mvn exec:java -Dexec.args="--api-key YOUR_KEY --base-url https://your-proxy"

# 演示模式（无真实 API 调用）
mvn exec:java -Dexec.args="--demo"
```

### 构建

```bash
mvn clean package
```

## 架构

```
App              — 入口，REPL 主循环
TerminalUI       — 终端交互界面
AgentEngine      — Agent 核心循环（用户输入 → LLM → 工具执行 → 循环）
DeepSeekClient   — DeepSeek Chat Completions API 客户端（SSE 流式）
Tool 接口         — 工具定义与执行
  ├─ ReadFileTool / WriteFileTool / EditFileTool
  ├─ ListDirTool / GrepFilesTool / FileSearchTool
  ├─ FetchUrlTool / ExecShellTool
```

## 运行时命令

在 REPL 中输入以下命令：

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助 |
| `/quit` `/q` | 退出程序 |
| `/clear` | 清除对话历史 |

## 项目结构

```
src/main/java/com/example/
├── cal/              — 工具类（BubbleSort）
└── tui/
    ├── App.java      — 程序入口
    ├── client/       — API 客户端
    ├── engine/       — Agent 引擎
    ├── model/        — 数据模型
    ├── tools/        — 工具实现
    └── ui/           — 终端界面
```
