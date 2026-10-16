package com.ysh.studiotool.mcp;

import com.ysh.studiotool.config.McpToolConfig;
import com.ysh.studiotool.config.ToolConfig;
import com.ysh.studiotool.executor.BaseResult;
import com.ysh.studiotool.executor.GradleExecutor;
import com.ysh.studiotool.executor.McpToolExecutor;
import com.ysh.studiotool.notification.NotificationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import com.intellij.util.ExceptionUtil;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class StudioMcpTools {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicBoolean OPERATION_RUNNING = new AtomicBoolean(false);

    private static final String DESC_RUN_DEFAULT = "通过 IntelliJ IDEA / Android Studio 运行当前默认运行配置（相当于在 IDE 中点击 Run 按钮）。 运行的是 IntelliJ IDEA / Android Studio 当前选中的默认配置（可用 studio_select_config 切换）。";
    private static final String DESC_BUILD = "通过 IntelliJ IDEA / Android Studio 执行项目构建（Build Project，类似 gradle build）。用于编译验证代码改动。";
    private static final String DESC_SYNC = "通过 IntelliJ IDEA / Android Studio 执行 Gradle 项目同步（Sync Project，类似 gradle sync）。在修改 build.gradle、settings.gradle、gradle.properties 或依赖版本后，应先调用此工具完成同步，再执行构建或运行，否则配置变更不会生效。";
    private static final String DESC_GET_STATE = "获取 IntelliJ IDEA / Android Studio 当前状态信息。返回：当前项目名称、Gradle 使用方式（distributionType）、gradleJvm、gradleHome、gradleUserHome、当前默认运行配置（selectedRunConfig）、所有运行配置（runConfigs）、所有可用 Gradle task（tasks）。返回的 runConfigs 和 tasks 是 studio_select_config / studio_execute_tasks 参数的合法取值来源。";
    private static final String DESC_SELECT_CONFIG = "切换 IntelliJ IDEA / Android Studio 默认运行配置。Args: config: 运行配置名称，必须取自 studio_get_state 返回的 runConfigs 列表中已存在的名称，请勿自行猜测或拼造配置名。切换成功后可通过 studio_run_default 运行新配置。";
    private static final String DESC_EXECUTE_TASKS = "通过 IntelliJ IDEA / Android Studio 使用当前项目配置的 Gradle 执行指定 Gradle task。Args: tasks: 要执行的任务名，多个任务用英文逗号分隔，如 \"clean,build\"。任务名必须取自 studio_get_state 返回的 tasks 列表。";
    private static final String DESC_GET_ERRORS = "使用 IntelliJ IDEA / Android Studio 的分析引擎进行快速错误检查（无需编译，秒级返回），返回语法错误、引用解析失败、类型不匹配等问题所在的行号、列号、该行源码、严重级别（ERROR / WARNING / WEAK_WARNING）与描述。结果为 IDE 静态分析结论，个别场景可能与实际编译结果有偏差，最终以 studio_build / studio_execute_tasks 为准。Args: files: 可选，文件路径数组（绝对路径，或相对于项目根目录的路径）。传入时对这些文件做实时分析，通常传入本次修改过和新建的文件，以及受本次改动影响的关联文件（如被修改接口的实现类、调用方等）；不传时返回当前 IDE 已打开编辑器文件中已检测出的错误、警告与弱警告。";
    private static final String DESC_FIND_USAGES = "在 IntelliJ IDEA / Android Studio 中查找符号的所有引用（Find Usages）。Args: file: 符号所在文件路径（绝对路径，或相对于项目根目录的路径）；line: 行号（1 开始）；column: 列号（1 开始）。定位规则：按光标位置解析符号，支持声明处与引用处（引用处自动解析到目标符号）。方法符号自动包含所有同名重载及基类/子类覆写方法的引用。定位在 R.string.xxx 或 XML 资源定义（如 <string name=\"title\">）时，可同时返回 Java 中的 R 资源引用与 XML 中的 @string/title 引用（IDE 装有 Android 插件时生效，如在 Android Studio 中运行）。返回：state、symbol（实际解析到的符号，用于确认定位）、totalCount（真实总数）、truncated、usages[]（每条含 file/line/column/lineText/kind，kind ∈ call/read/write/import/text）。结果上限 200 条，超出时 truncated=true，totalCount 仍为真实数量。";
    private static final String DESC_GOTO_DEFINITION = "在 IntelliJ IDEA / Android Studio 中查找符号的定义位置（Go to Definition，通用符号：变量、常量/字段、类、接口、方法、参数等）。Args: file: 符号所在文件路径（绝对路径，或相对于项目根目录的路径）；line: 行号（1 开始）；column: 列号（1 开始）。定位规则：按光标位置解析符号，支持声明处与引用处（引用处自动解析到目标符号）。解析出多个定义候选时全部返回（如重载、多实现），definitions[0] 为首选定义（primary=true），其余候选 primary=false。仅返回定义坐标数据，不在 IDE 中打开/跳转。返回：state、symbol（实际解析到的符号，用于确认定位）、totalCount、definitions[]（每条含 file/line/column/lineText/primary）。";

    private StudioMcpTools() {
    }

    public static List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
        return List.of(
                tool("studio_run_default", DESC_RUN_DEFAULT, emptySchema(), StudioMcpTools::runDefault),
                tool("studio_build", DESC_BUILD, emptySchema(), StudioMcpTools::build),
                tool("studio_sync", DESC_SYNC, emptySchema(), StudioMcpTools::sync),
                tool("studio_get_state", DESC_GET_STATE, emptySchema(), StudioMcpTools::getGradleState),
                tool("studio_select_config", DESC_SELECT_CONFIG, selectConfigSchema(), StudioMcpTools::selectConfig),
                tool("studio_execute_tasks", DESC_EXECUTE_TASKS, executeTasksSchema(), StudioMcpTools::executeTasks),
                tool("studio_get_errors", DESC_GET_ERRORS, getErrorsSchema(), StudioMcpTools::getErrors),
                tool("studio_find_usages", DESC_FIND_USAGES, findUsagesSchema(), StudioMcpTools::findUsages),
                tool("studio_goto_definition", DESC_GOTO_DEFINITION, gotoDefinitionSchema(), StudioMcpTools::gotoDefinition));
    }

    private static McpServerFeatures.SyncToolSpecification tool(String name, String description,
                                                                Map<String, Object> inputSchema,
                                                                BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder(name, inputSchema)
                        .description(description)
                        .build())
                .callHandler((exchange, request) -> {
                    // 统一记录工具入参，便于排查 Agent 是否正确调用工具
                    NotificationManager.logInfo("MCP request [" + name + "]: " + requestJson(request));
                    return handler.apply(exchange, request);
                })
                .build();
    }

    private static String requestJson(McpSchema.CallToolRequest request) {
        try {
            return JSON.writeValueAsString(request.arguments());
        } catch (Exception e) {
            return String.valueOf(request.arguments());
        }
    }

    private static McpSchema.CallToolResult runDefault(McpSyncServerExchange exchange,
                                                       McpSchema.CallToolRequest request) {
        return executeWithLock("Failed to run default configuration",
                () -> McpToolExecutor.executeRun(readTimeoutMs()));
    }

    private static McpSchema.CallToolResult build(McpSyncServerExchange exchange,
                                                  McpSchema.CallToolRequest request) {
        return executeWithLock("Failed to build project",
                () -> McpToolExecutor.executeBuild(readTimeoutMs()));
    }

    private static McpSchema.CallToolResult sync(McpSyncServerExchange exchange,
                                                 McpSchema.CallToolRequest request) {
        return executeWithLock("Failed to sync project", GradleExecutor::syncProject);
    }

    private static McpSchema.CallToolResult selectConfig(McpSyncServerExchange exchange,
                                                         McpSchema.CallToolRequest request) {
        String config = stringArgument(request, "config");
        if (config == null || config.isEmpty()) {
            return errorResult("config must not be empty");
        }
        return executeWithLock("Failed to switch run configuration",
                () -> McpToolExecutor.switchRunConfiguration(config));
    }

    private static McpSchema.CallToolResult executeTasks(McpSyncServerExchange exchange,
                                                         McpSchema.CallToolRequest request) {
        String raw = stringArgument(request, "tasks");
        if (raw == null || raw.isBlank()) {
            return errorResult("tasks must not be empty");
        }
        List<String> tasks = Arrays.stream(raw.split("[\\s,]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (tasks.isEmpty()) {
            return errorResult("tasks must not be empty");
        }
        return executeWithLock("Failed to execute Gradle tasks",
                () -> GradleExecutor.runTasks(tasks));
    }

    private static McpSchema.CallToolResult getErrors(McpSyncServerExchange exchange,
                                                      McpSchema.CallToolRequest request) {
        List<String> files = listArgument(request, "files");
        try {
            Map<String, Object> result = files.isEmpty()
                    ? ErrorScanner.scanOpenEditors()
                    : ErrorScanner.scan(files);
            String json = JSON.writeValueAsString(result);
            NotificationManager.logInfo("Response Json : " + json);
            boolean failed = BaseResult.FAILED.equals(result.get("state"));
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(json)))
                    .isError(failed)
                    .build();
        } catch (Exception e) {
            NotificationManager.logInfo("Failed to analyze errors, stack:\n" + ExceptionUtil.getThrowableText(e));
            return errorResult("Failed to analyze errors: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult findUsages(McpSyncServerExchange exchange,
                                                       McpSchema.CallToolRequest request) {
        String file = stringArgument(request, "file");
        Integer line = numberArgument(request, "line");
        Integer column = numberArgument(request, "column");
        if (file == null || file.isBlank()) {
            return errorResult("file must not be empty");
        }
        if (line == null || line < 1 || column == null || column < 1) {
            return errorResult("line and column must be positive integers (1-based)");
        }
        try {
            Map<String, Object> result = FindUsagesScanner.findUsages(file, line, column);
            String json = JSON.writeValueAsString(result);
            NotificationManager.logInfo("Response Json : " + json);
            boolean failed = BaseResult.FAILED.equals(result.get("state"));
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(json)))
                    .isError(failed)
                    .build();
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Exception e) {
            NotificationManager.logInfo("Failed to find usages, stack:\n" + ExceptionUtil.getThrowableText(e));
            return errorResult("Failed to find usages: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult gotoDefinition(McpSyncServerExchange exchange,
                                                           McpSchema.CallToolRequest request) {
        String file = stringArgument(request, "file");
        Integer line = numberArgument(request, "line");
        Integer column = numberArgument(request, "column");
        if (file == null || file.isBlank()) {
            return errorResult("file must not be empty");
        }
        if (line == null || line < 1 || column == null || column < 1) {
            return errorResult("line and column must be positive integers (1-based)");
        }
        try {
            Map<String, Object> result = GotoDefinitionScanner.gotoDefinition(file, line, column);
            String json = JSON.writeValueAsString(result);
            NotificationManager.logInfo("Response Json : " + json);
            boolean failed = BaseResult.FAILED.equals(result.get("state"));
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(json)))
                    .isError(failed)
                    .build();
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Exception e) {
            NotificationManager.logInfo("Failed to goto definition, stack:\n" + ExceptionUtil.getThrowableText(e));
            return errorResult("Failed to goto definition: " + e.getMessage());
        }
    }

    private static Integer numberArgument(McpSchema.CallToolRequest request, String key) {
        Object value = request.arguments() == null ? null : request.arguments().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static McpSchema.CallToolResult getGradleState(McpSyncServerExchange exchange,
                                                           McpSchema.CallToolRequest request) {
        return executeWithLock("Failed to get project state", GradleExecutor::getGradleInfo);
    }

    /**
     * 沿用旧 REST 层语义：全局操作互斥 + JSON 序列化 + isError 跟随执行结果 + 异常转 isError 结果
     */
    private static McpSchema.CallToolResult executeWithLock(String errorMessagePrefix,
                                                            Supplier<? extends BaseResult> action) {
        if (!OPERATION_RUNNING.compareAndSet(false, true)) {
            return errorResult("Operation in progress");
        }
        try {
            BaseResult result = action.get();
            String json = JSON.writeValueAsString(result);
            NotificationManager.logInfo("Response Json : " + json);
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(json)))
                    .isError(!result.isSuccess())
                    .build();
        } catch (Exception e) {
            return errorResult(errorMessagePrefix + ": " + e.getMessage());
        } finally {
            OPERATION_RUNNING.set(false);
        }
    }

    private static List<String> listArgument(McpSchema.CallToolRequest request, String key) {
        Map<String, Object> args = request.arguments();
        if (args == null) {
            return List.of();
        }
        Object value = args.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }

    private static String stringArgument(McpSchema.CallToolRequest request, String key) {
        Map<String, Object> args = request.arguments();
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        return value instanceof String s ? s : null;
    }

    private static long readTimeoutMs() {
        ToolConfig config = McpToolConfig.getInstance().getState();
        return config != null ? config.timeoutMs : 600000L;
    }

    private static McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(message)))
                .isError(true)
                .build();
    }

    private static Map<String, Object> emptySchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of());
    }

    private static Map<String, Object> selectConfigSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "config", Map.of(
                                "title", "Config",
                                "type", "string")),
                "required", List.of("config"));
    }

    private static Map<String, Object> executeTasksSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "tasks", Map.of(
                                "title", "Tasks",
                                "type", "string")),
                "required", List.of("tasks"));
    }

    private static Map<String, Object> getErrorsSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "files", Map.of(
                                "title", "Files",
                                "type", "array",
                                "items", Map.of("type", "string"))),
                "required", List.of());
    }

    private static Map<String, Object> findUsagesSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file", Map.of("title", "File", "type", "string"),
                        "line", Map.of("title", "Line", "type", "number"),
                        "column", Map.of("title", "Column", "type", "number")),
                "required", List.of("file", "line", "column"));
    }

    private static Map<String, Object> gotoDefinitionSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file", Map.of("title", "File", "type", "string"),
                        "line", Map.of("title", "Line", "type", "number"),
                        "column", Map.of("title", "Column", "type", "number")),
                "required", List.of("file", "line", "column"));
    }
}
