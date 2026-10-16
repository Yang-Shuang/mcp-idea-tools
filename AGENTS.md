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
│   ├── StudioMcpTools    # 9 个工具的定义与 handler（统一互斥锁/序列化/错误处理）
│   ├── ErrorScanner      # studio_get_errors 实现（daemon 高亮读取）
│   ├── FindUsagesScanner # studio_find_usages 实现（ReferencesSearch + 重载/继承扩展）
│   ├── GotoDefinitionScanner # studio_goto_definition 实现（multiResolve 全候选定义）
│   ├── SymbolLocator     # 符号定位/坐标换算共享工具（Find/Goto 两 Scanner 共用）
│   ├── ScannerUtil       # getProject/findVirtualFile 共享工具
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

## 工具清单（9 个）

`studio_run_default`、`studio_build`、`studio_sync`、`studio_get_state`、`studio_select_config`、`studio_execute_tasks`、`studio_get_errors`、`studio_find_usages`、`studio_goto_definition`

工具描述为最终 Agent 可见的 prompt，修改时注意准确性。

不做 `studio_rename_symbol`（评估结论 2026-10-16）：精准改名场景已被 find_usages（全引用 + 精确坐标）+ Agent 直接编辑 + get_errors/build 验证覆盖；且它是唯一需要 IDE 写动作的工具（write-action/EDT/VFS 刷新），复杂度与风险不成比例，YAGNI。

## API 签名查询（javap）

验证 IntelliJ Platform 2025.3 API 是否存在/签名时，用 javap 对**编译依赖的 SDK**（Gradle 缓存里解压的发行版，build.txt = IU-253.28294.334，与 `intellijIdea("2025.3")` 完全一致）查询：

- javap：`E:/Java/jdk-21.0.8/bin/javap.exe`（必须 JDK 21+，JDK 17 读不了 253 的 class 版本）
- SDK 根目录（transform 哈希可能变化，失效时重新查找）：`E:/Gradle/Home/caches/8.14/transforms/f3b7f9948ff0990f354e88f1a0f7e1a2/transformed/idea-2025.3-win`
- 找不到时用：`find E:/Gradle/Home/caches -maxdepth 8 -type d -name "idea-2025.3-win"`
- 2025.3 把类拆散在多个 jar，一个 jar 找不到按下列顺序补齐 classpath（Windows 分隔符是 `;`）：
  - `lib/app.jar` + `lib/util-8.jar`（核心 PSI 如 `PsiElement`/`DocumentWindow` 在 util-8，不在 app.jar）
  - `plugins/java/lib/java-impl-frontend.jar`（`PsiClass`/`PsiUtil`/`OverridingMethodsSearch` 等 Java PSI 在这）
- 示例：`javap -cp "<SDK>/lib/util-8.jar;<SDK>/lib/app.jar;<SDK>/plugins/java/lib/java-impl-frontend.jar" com.intellij.psi.PsiClass`
- 备用：本机安装的 `E:/Application/IntelliJ IDEA 2025.3.6`（更新补丁版，结构相同；查稳定 API 够用，但非精确编译版本）

## 工作约定

- **禁止自动提交**：不要执行任何 git commit / push 操作，该提交时用户自己提交。
- 设计文档放在 `docs/superpowers/specs/`，同样不提交。

## 关键实现要点（防坑）

- **MCP SDK 的 ServiceLoader 依赖 TCCL**：IDE 插件类加载器隔离下找不到 SPI，`McpServerManager` 已显式注入 `JacksonMcpJsonMapper` / `DefaultJsonSchemaValidator` 绕开，勿删。
- **线程约束**：VFS/PSI/daemon 读取需 ReadAction；`reloadFromDisk`、`openFile/closeFile`、`getSelectedFiles` 等需 EDT（invokeAndWait）；`invokeAndWait` 无 Computable+ModalityState 重载，用 Runnable+容器带回值。
- **IDEA daemon 只分析选中的编辑器**：分析未激活文件需临时 openFile 激活 + restart + 轮询（见 ErrorScanner）。
- **全局操作互斥**：写操作（run/build/sync 等）共享 `OPERATION_RUNNING` 锁；get_errors 只读，不参与。
- 工具 handler 返回 `isError` 跟随 `BaseResult.isSuccess()`；异常统一转为 isError 结果。