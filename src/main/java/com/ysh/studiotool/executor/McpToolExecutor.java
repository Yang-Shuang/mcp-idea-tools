package com.ysh.studiotool.executor;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Key;
import com.intellij.pom.Navigatable;
import com.intellij.util.messages.MessageBusConnection;
import com.ysh.studiotool.notification.NotificationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.settings.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class McpToolExecutor {

    private McpToolExecutor() {
    }

    public static ExecutionResult executeRun(long timeoutMs) {
        long startTime = System.currentTimeMillis();

        Project project = getProject();
        if (project == null) {
            return ExecutionResult.error("No open project found", 10);
        }

        RunnerAndConfigurationSettings settings = RunManager.getInstance(project).getSelectedConfiguration();
        if (settings == null) {
            return ExecutionResult.error("No run configuration selected", 10);
        }

        NotificationManager.info("Running: " + settings.getName());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ProcessHandler> handlerRef = new AtomicReference<>();
        AtomicInteger exitCodeRef = new AtomicInteger(-1);
        AtomicReference<String> errorRef = new AtomicReference<>();

        // 💡 新增：用来实时收集控制台报错和正常输出的容器
        StringBuilder stdErrBuffer = new StringBuilder();
        StringBuilder stdOutBuffer = new StringBuilder();

        MessageBusConnection connection = project.getMessageBus().connect();
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override
            public void processStarted(String executorId, ExecutionEnvironment env, ProcessHandler handler) {
                // 安全检查：确保是我们要的那个运行配置
                if (env.getRunnerAndConfigurationSettings() == settings) {
                    handlerRef.set(handler);

                    // 💡 核心新增：直接监听这个进程的控制台输出流
                    handler.addProcessListener(new ProcessListener() {
                        @Override
                        public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                            String text = event.getText(); // 拿到当前这一行/这段文本

                            // 判断输出类型
                            if (ProcessOutputTypes.STDERR.equals(outputType)) {
                                stdErrBuffer.append(text); // 收集标准错误流（报错、异常堆栈）
                            } else if (ProcessOutputTypes.STDOUT.equals(outputType)) {
                                stdOutBuffer.append(text); // 收集标准输出流（普通日志）
                            }
                        }
                    });
                }
            }

            @Override
            public void processTerminated(String executorId, ExecutionEnvironment env, ProcessHandler handler, int exitCode) {
                if (handlerRef.get() == handler) {
                    exitCodeRef.set(exitCode);
                    latch.countDown();
                }
            }

            @Override
            public void processNotStarted(String executorId, ExecutionEnvironment env, Throwable cause) {
                if (env.getRunnerAndConfigurationSettings() == settings) {
                    errorRef.set(cause != null ? cause.getMessage() : "Process not started");
                    latch.countDown();
                }
            }
        });

        try {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                ExecutionUtil.runConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance());
            });

            boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);

            // 1. 处理超时
            if (!completed) {
                ProcessHandler handler = handlerRef.get();
                if (handler != null) {
                    handler.destroyProcess();
                }
                long durationMs = System.currentTimeMillis() - startTime;
                return ExecutionResult.error("Run timed out after " + timeoutMs + "ms", durationMs);
            }

            // 2. 处理启动失败
            String error = errorRef.get();
            if (error != null) {
                long durationMs = System.currentTimeMillis() - startTime;
                return ExecutionResult.error("Run failed to start: " + error, durationMs);
            }

            int exitCode = exitCodeRef.get();
            long durationMs = System.currentTimeMillis() - startTime;

            // 3. 处理正常结束与运行中崩溃
            if (exitCode == 0) {
                NotificationManager.info("Run completed successfully.");
                return ExecutionResult.success(stdOutBuffer.toString(), durationMs, exitCode);
            } else {
                // 💡 亮点：当退出码不为0时，直接把刚才收集到的 STDERR（错误堆栈）返回回去！
                String specificError = stdErrBuffer.toString().trim();
                if (specificError.isEmpty()) {
                    // 如果错误流是空的，尝试拿最后几行普通输出（有些程序喜欢把错误打印到 stdout）
                    specificError = "Process exited with code " + exitCode + ". Console output:\n" + stdOutBuffer.toString();
                }

                NotificationManager.error("Run failed with exit code " + exitCode);
                return ExecutionResult.error(specificError, durationMs);
            }

        } catch (ProcessCanceledException e) {
            throw e; // 🟢 极其重要：直接放行，交给 IDEA 核心框架
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            return ExecutionResult.error("Run error: " + e.getMessage(), durationMs);
        } finally {
            connection.disconnect();
        }
    }

    public static ExecutionResult executeBuild(long timeoutMs) {
        long startTime = System.currentTimeMillis();

        Project project = getProject();
        if (project == null) {
            return ExecutionResult.error("No open project found", 0);
        }

        // 🎯 检查：如果当前线程已经是 UI 线程，绝对不能用 latch.await() 阻塞它！
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return ExecutionResult.error("Cannot run executeBuild synchronously on the Event Dispatch Thread (EDT).", 0);
        }

        NotificationManager.info("Building project...");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicReference<Boolean> abortedRef = new AtomicReference<>(false);

        // 🎯 新增：用来收集具体编译错误信息的容器
        List<String> errorMessages = new ArrayList<>();

        try {
            // 触发编译必须在 UI 线程
            ApplicationManager.getApplication().invokeAndWait(() -> {
                CompilerManager.getInstance(project).make((aborted, errors, warnings, compileContext) -> {
                    abortedRef.set(aborted);
                    errorCount.set(errors);

                    // 🎯 核心改动：从编译上下文中捞出具体的错误文本（如语法错误、找不到符号等）
                    if (errors > 0) {
                        CompilerMessage[] messages = compileContext.getMessages(CompilerMessageCategory.ERROR);

                        for (CompilerMessage msg : messages) {
                            // 1. 安全获取文件名（防止全局编译错误导致 NPE）
                            String fileName = (msg.getVirtualFile() != null) ? msg.getVirtualFile().getName() : "Global";

                            int line = -1;
                            // 2. 🎯 通过 Navigatable 转换为 OpenFileDescriptor 来获取行号
                            Navigatable navigatable = msg.getNavigatable();
                            if (navigatable instanceof OpenFileDescriptor) {
                                // 注意：OpenFileDescriptor 内部的行号是从 0 开始算的（0-indexed）
                                // 我们展示给人类看的时候，习惯性把它 +1 变成正常的行号
                                line = ((OpenFileDescriptor) navigatable).getLine() + 1;
                            }

                            // 3. 完美拼接
                            String lineInfo = (line != -1) ? ":" + line : "";
                            errorMessages.add("[" + fileName + lineInfo + "] " + msg.getMessage());
                        }
                    }

                    latch.countDown(); // 编译彻底完事，释放锁
                });
            });

            // 此时当前线程是非 UI 线程，可以安全地限时等待
            boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            long durationMs = System.currentTimeMillis() - startTime;

            if (!completed) {
                NotificationManager.error("Build timed out after " + timeoutMs + "ms");
                return ExecutionResult.error("Build timed out after " + timeoutMs + "ms", durationMs);
            }

            if (abortedRef.get()) {
                NotificationManager.error("Build aborted");
                return ExecutionResult.error("Build aborted", durationMs);
            }

            if (errorCount.get() > 0) {
                // 🎯 将具体报错拼成一段大文本返回
                String detailedError = String.join("\n", errorMessages);
                NotificationManager.error("Build failed:\n" + detailedError);
                return ExecutionResult.error("Build failed:\n" + detailedError, durationMs);
            }

            NotificationManager.info("Build completed (" + durationMs + "ms)");
            return ExecutionResult.success("Build completed successfully", durationMs, 0);

        } catch (ProcessCanceledException e) {
            throw e; // 🟢 极其重要：直接放行，交给 IDEA 核心框架
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            String errorMessage = "Build error: " + e.getMessage();
            NotificationManager.error(errorMessage);
            return ExecutionResult.error(errorMessage, durationMs);
        }
    }

    /**
     * MCP 接口：根据名称切换当前的运行配置
     *
     * @param configName 目标运行配置的名称（例如 "Main"）
     * @return 切换结果
     */
    public static BaseResult switchRunConfiguration(@NotNull String configName) {
        Project project = getProject();
        if (project == null) {
            return BaseResult.failed(null, "No open project found");
        }

        RunManager runManager = RunManager.getInstance(project);

        // 🎯 核心 API：直接通过名字寻找其对应的「包装对象」
        RunnerAndConfigurationSettings targetSettings = runManager.findConfigurationByName(configName);

        if (targetSettings == null) {
            return BaseResult.failed(null, "Configuration not found: " + configName);
        }

        try {
            // ⚠️ 2025.3+ 严格限制：修改 RunManager 的选中项会直接引发 IDE 顶栏 UI 变更。
            // 必须将切换操作投递到 EDT (UI 线程) 中执行，否则平台会抛出线程安全异常。
            ApplicationManager.getApplication().invokeLater(() -> {
                runManager.setSelectedConfiguration(targetSettings);
            });

            return BaseResult.success("Successfully switched to configuration: " + configName, null);
        } catch (Exception e) {
            return BaseResult.failed(null, "Failed to switch configuration: " + e.getMessage());
        }
    }

    private static Project getProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        if (projects.length == 0) {
            return null;
        }
        return projects[0];
    }
}
