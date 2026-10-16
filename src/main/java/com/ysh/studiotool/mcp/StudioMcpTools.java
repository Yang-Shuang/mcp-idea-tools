package com.ysh.studiotool.mcp;

import com.ysh.studiotool.config.McpToolConfig;
import com.ysh.studiotool.config.ToolConfig;
import com.ysh.studiotool.executor.BaseResult;
import com.ysh.studiotool.executor.GradleExecutor;
import com.ysh.studiotool.executor.McpToolExecutor;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
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

    private StudioMcpTools() {
    }

    public static List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
        return List.of(
                tool("studio_run_default", DESC_RUN_DEFAULT, emptySchema(), StudioMcpTools::runDefault),
                tool("studio_build", DESC_BUILD, emptySchema(), StudioMcpTools::build),
                tool("studio_sync", DESC_SYNC, emptySchema(), StudioMcpTools::sync),
                tool("studio_get_state", DESC_GET_STATE, emptySchema(), StudioMcpTools::getGradleState),
                tool("studio_select_config", DESC_SELECT_CONFIG, selectConfigSchema(), StudioMcpTools::selectConfig),
                tool("studio_execute_tasks", DESC_EXECUTE_TASKS, executeTasksSchema(), StudioMcpTools::executeTasks));
    }

    private static McpServerFeatures.SyncToolSpecification tool(String name, String description,
                                                                Map<String, Object> inputSchema,
                                                                BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder(name, inputSchema)
                        .description(description)
                        .build())
                .callHandler(handler)
                .build();
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
}
