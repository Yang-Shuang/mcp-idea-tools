# mcp-idea-tools

IntelliJ IDEA / Android Studio 插件，内置 MCP Server（Streamable HTTP），供 AI Agent 直接调用 IDE 能力。

- 端点：`http://127.0.0.1:7834/mcp`（端口可在插件设置中修改）
- 架构：Agent ──MCP 协议──> 插件内置 MCP Server（无 Python 中间层、无 NanoHTTPD）

## 技术栈

- Java 21，IntelliJ Platform 2025.3（build 253），gradle-intellij-platform 2.x
- MCP Java SDK `io.modelcontextprotocol.sdk:mcp:2.0.1`
- Jetty 12.0.40（ee10）承载 SDK 的 Servlet 传输
- 依赖的 bundled 插件：`com.intellij.java`、`org.jetbrains.plugins.gradle`

## 项目结构

```
src/main/java/com/ysh/studiotool/
├── mcp/                  # MCP Server 核心
│   ├── McpServerManager  # Jetty + SDK 装配、启停、生命周期
│   ├── StudioMcpTools    # 7 个工具的定义与 handler（统一互斥锁/序列化/错误处理）
│   ├── ErrorScanner      # studio_get_errors 实现（daemon 高亮读取）
│   └── McpServerBootstrap# main() 冒烟入口（IDE 外启动服务）
├── executor/             # 工具内部实现
│   ├── McpToolExecutor   # run/build/selectConfig
│   ├── GradleExecutor    # sync/executeTasks/getGradleInfo
│   └── *Result           # 结果模型（BaseResult 及子类）
├── config/               # 设置（McpToolConfig/ToolConfig 持久化 + Settings 面板）
├── notification/         # 通知与日志（NotificationManager.logInfo → idea.log）
├── server/               # 旧 REST 层残留，待删除
└── McpToolPlugin.java    # 入口：AppLifecycleListener 启动/停止服务
```

## 工具清单（7 个）

`studio_run_default`、`studio_build`、`studio_sync`、`studio_get_state`、`studio_select_config`、`studio_execute_tasks`、`studio_get_errors`

工具描述为最终 Agent 可见的 prompt，修改时注意准确性。

## 关键实现要点（防坑）

- **MCP SDK 的 ServiceLoader 依赖 TCCL**：IDE 插件类加载器隔离下找不到 SPI，`McpServerManager` 已显式注入 `JacksonMcpJsonMapper` / `DefaultJsonSchemaValidator` 绕开，勿删。
- **线程约束**：VFS/PSI/daemon 读取需 ReadAction；`reloadFromDisk`、`openFile/closeFile`、`getSelectedFiles` 等需 EDT（invokeAndWait）；`invokeAndWait` 无 Computable+ModalityState 重载，用 Runnable+容器带回值。
- **IDEA daemon 只分析选中的编辑器**：分析未激活文件需临时 openFile 激活 + restart + 轮询（见 ErrorScanner）。
- **全局操作互斥**：写操作（run/build/sync 等）共享 `OPERATION_RUNNING` 锁；get_errors 只读，不参与。
- 工具 handler 返回 `isError` 跟随 `BaseResult.isSuccess()`；异常统一转为 isError 结果。

## 未完成
- studio_find_usages：查引用，理解重载/继承/R 资源引用
- studio_goto_definition：跳转定义
- studio_rename_symbol：安全重命名（含 XML/资源引用联动），你当时说“有点用”