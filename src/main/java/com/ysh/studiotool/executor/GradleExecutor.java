package com.ysh.studiotool.executor;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.externalSystem.importing.ImportSpec;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.externalSystem.model.task.TaskData;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Key;
import com.intellij.util.messages.MessageBusConnection;
import com.ysh.studiotool.notification.NotificationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType;
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration;
import org.jetbrains.plugins.gradle.settings.DistributionType;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class GradleExecutor {


    private static final List<String> NORMAL_TASKS = List.of("clean",
            "build",
            "lint",
            "compileJava",
            "processResources",
            "classes",
            "compileTestJava",
            "test",
            "jar",
            "assemble",
            "assembleDebug",
            "assembleRelease",
            "bundleDebug",
            "installDebug",
            "uninstallAll",
            "connectedAndroidTest");

    private static Project getProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        if (projects.length == 0) {
            return null;
        }
        return projects[0];
    }

    public static GradleInfoResult getGradleInfo() {

        Project project = getProject();
        if (project == null) {
            return new GradleInfoResult(BaseResult.FAILED, null, "No open project found");
        }
        GradleInfoResult result = new GradleInfoResult(BaseResult.SUCCESS, null, null);

        String path = project.getBasePath();
        if (path == null || path.isEmpty()) {
            return new GradleInfoResult(BaseResult.FAILED, null, "project path is null");
        }
//        // 同时查找正斜杠和反斜杠，取最后出现的那一个
//        int index = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
//
//        if (index != -1) {
//            result.setProjectName(path.substring(index + 1));
//            NotificationManager.logInfo("成功截取项目名 : " + result.getProjectName());
//        } else {
//            result.setProjectName(path);
//            NotificationManager.logInfo("路径中无分隔符 : " + path);
//        }

        String projectInfo = ProjectInfoTools.getProjectInfoJson(project);
        result.setProjectInfo(projectInfo);

        // ✅ 获取所有运行配置（原始对象）
        RunManager runManager = RunManager.getInstance(project);

        // 🎯 关键点：使用 getAllSettings() 而不是 getAllConfigurationsList()
        // 它直接返回包装对象列表，每个包装对象都带有名称
        List<RunnerAndConfigurationSettings> allSettings = runManager.getAllSettings();

        List<String> allConfigs = allSettings.stream()
                .map(RunnerAndConfigurationSettings::getName)
                .collect(Collectors.toList());
        result.setRunConfigs(allConfigs);

        result.setSelectedRunConfig(runManager.getSelectedConfiguration().getName());

        GradleSettings gradleSettings = GradleSettings.getInstance(project);
        GradleProjectSettings projectSettings = gradleSettings.getLinkedProjectSettings(path);

        if (projectSettings == null) {
            return new GradleInfoResult(BaseResult.FAILED, null, "GradleProjectSettings is null");
        }

        DistributionType type = projectSettings.getDistributionType();

        if (type == DistributionType.LOCAL) {
            result.setDistributionType("LOCAL");
        } else if (type == DistributionType.WRAPPED) {
            result.setDistributionType("WRAPPED");
        } else if (type == DistributionType.DEFAULT_WRAPPED) {
            result.setDistributionType("DEFAULT_WRAPPED");
        } else if (type == DistributionType.BUNDLED) {
            result.setDistributionType("BUNDLED");
        }
        result.setGradleHome(projectSettings.getGradleHome());
        result.setGradleJvm(projectSettings.getGradleJvm());


        String gradleUserHome = gradleSettings.getServiceDirectoryPath();
        if (gradleUserHome == null) {
            // 如果为 null，说明用户使用的是系统默认的 ~/.gradle
            gradleUserHome = System.getProperty("user.home") + "/.gradle";
        }
        result.setGradleUserHome(gradleUserHome);

        HashMap<String, TreeSet<String>> taskNames = new HashMap<String, TreeSet<String>>();

        Collection<TaskData> tasks = ExternalSystemApiUtil.findProjectTasks(project, GradleConstants.SYSTEM_ID, path);

        for (TaskData task : tasks) {
            NotificationManager.logInfo("TaskData : " + task.getGroup() + " - " + task.getName() + " - " + task.getType() + " - " + task.getDescription());
            TreeSet<String> taskList = taskNames.get(task.getGroup());
            if (taskList == null) {
                taskList = new TreeSet<>();
                taskNames.put(task.getGroup(), taskList);
            }
            if (NORMAL_TASKS.contains(task.getName())) {
                taskList.add(task.getName());
            }
        }

        result.setTasks(taskNames);
        return result;
    }

    public static BaseResult checkGradleTasks(@NotNull String taskName) {
        Project project = getProject();
        if (project == null) {
            return BaseResult.failed(null, "No open project found");
        }

        HashMap<String, TreeSet<String>> taskNames = new HashMap<String, TreeSet<String>>();

        Collection<TaskData> tasks = ExternalSystemApiUtil.findProjectTasks(project, GradleConstants.SYSTEM_ID, project.getBasePath());

        boolean hasTask = false;

        for (TaskData task : tasks) {
            if (taskName.equals(task.getName())) {
                hasTask = true;
                break;
            }
        }

        if (hasTask) {
            return BaseResult.success("YES, This project gradle has the task : " + taskName, null);
        } else {
            return BaseResult.failed(null, "No, This project gradle tasks do not contain the task : " + taskName);
        }
    }


    public static GradleInfoResult runTasks(List<String> tasks) {

        Project project = getProject();
        if (project == null) {
            return new GradleInfoResult(BaseResult.FAILED, null, "No open project found");
        }

        try {
            // 1. 发起异步调用，拿到 Future 凭证
            CompletableFuture<GradleInfoResult> future = runTasks(project, project.getBasePath(), tasks);

            // 2. 🎯 阻塞当前 MCP 线程，直到后台的 Gradle 跑完（拿到完整日志）
            // 注意：MCP 的 Action 线程不是 IDEA 的 UI 线程（EDT），所以这里放心阻塞，绝对不会卡死界面！
            GradleInfoResult result = future.get();

            // 3. 🎯 全量日志对 Agent 基本无用（大项目动辄 6 万+ 字节），统一精简后再返回
            result.setMessage(GradleLogFilter.filter(result.getMessage()));

            return result;
        } catch (Exception e) {
            return new GradleInfoResult(BaseResult.FAILED, null, e.getMessage());
        }
    }

    /**
     * 执行指定路径下的 Gradle 任务
     *
     * @param project    当前 IDEA 项目实例
     * @param linkedPath Gradle 项目根路径（settings.gradle 所在路径）
     * @param taskNames  要执行的任务列表，如 List.of("clean", "build")
     * @return 返回一个可等待的 Future 对象
     */
    private static CompletableFuture<GradleInfoResult> runTasks(@NotNull Project project, @NotNull String linkedPath, @NotNull List<String> taskNames) {
        CompletableFuture<GradleInfoResult> future = new CompletableFuture<>();
        StringBuilder logAccumulator = new StringBuilder();

        // 1. 构建执行基础配置
        ExternalSystemTaskExecutionSettings execSettings = new ExternalSystemTaskExecutionSettings();
        execSettings.setExternalProjectPath(linkedPath);
        execSettings.setExternalSystemIdString(GradleConstants.SYSTEM_ID.getId());
        execSettings.setTaskNames(taskNames);

        // 2. 利用你挖出来的全新 Builder 模式组装规格
        TaskExecutionSpec spec = TaskExecutionSpec.create()
                .withProject(project)
                .withSystemId(GradleConstants.SYSTEM_ID)
                .withExecutorId(DefaultRunExecutor.EXECUTOR_ID)
                .withSettings(execSettings)
                .withProgressExecutionMode(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                .withListener(new ExternalSystemTaskNotificationListener() {

                    // 🎯 核心：实时捕获控制台输出（这里根据 2025.3+ 签名做适配）
                    @Override
                    public void onTaskOutput(@NotNull ExternalSystemTaskId id, @NotNull String text, boolean stdOut) {
                        // 有些版本是 boolean stdOut，有些是 ProcessOutputType type
                        // 如果编译提示签名不对，直接点一下 Alt+Enter 让 IDEA 自动重写该方法即可
                        logAccumulator.append(text);
                    }

                    // 兼容部分旧版本或变体签名
                    public void onTaskOutput(ExternalSystemTaskId id, String text, ProcessOutputType type) {
//                        if (type != null && type.isStderr()) {
//                            logAccumulator.append("[ERROR] ").append(text);
//                        } else {
//                            logAccumulator.append(text);
//                        }
                        logAccumulator.append(text);
                    }

                    // 🎯 核心：任务成功回调
                    @Override
                    public void onSuccess(@NotNull String projectPath, @NotNull ExternalSystemTaskId id) {
                        GradleInfoResult result = new GradleInfoResult(BaseResult.SUCCESS, logAccumulator.toString(), null);
                        future.complete(result); // 唤醒等待的 MCP 线程
                    }

                    // 🎯 核心：任务失败回调
                    @Override
                    public void onFailure(@NotNull String projectPath, @NotNull ExternalSystemTaskId id, @NotNull Exception e) {
                        String errMsg = e.getMessage() != null ? e.getMessage() : "Gradle task execution failed.";
                        GradleInfoResult result = new GradleInfoResult(BaseResult.FAILED, logAccumulator.toString(), errMsg);
                        future.complete(result); // 即使失败也要唤醒，并把报错日志带回去
                    }

                    // --- 以下为接口必须实现的其他生命周期空方法，保持原样即可 ---
                    @Override
                    public void onStart(@NotNull ExternalSystemTaskId id, String projectPath) {
                    }

                    @Override
                    public void onEnd(@NotNull ExternalSystemTaskId id) {
                    }

                    @Override
                    public void onCancel(@NotNull ExternalSystemTaskId id) {
                        future.complete(new GradleInfoResult(BaseResult.FAILED, logAccumulator.toString(), "Task cancelled by user."));
                    }
                }).build();

        // 3. 把它扔进 IDEA 的异步执行引擎里转起来
        ExternalSystemUtil.runTask(spec);

        return future;
    }


    /**
     * MCP 链路调用的公共入口：同步指定的 Gradle 项目
     *
     * @return 组装好的 GradleInfoResult 结果
     */
    public static GradleInfoResult syncProject() {
        // 1. 获取当前激活的 Project 实例（复用你现有的 getProject() 方法）
        Project project = getProject();
        if (project == null) {
            return new GradleInfoResult(BaseResult.FAILED, null, "No open project found");
        }

        // 2. 容错处理：如果 Agent 没传路径，默认取当前项目根目录
        String targetPath = project.getBasePath();
        if (targetPath == null) {
            return new GradleInfoResult(BaseResult.FAILED, null, "Project base path is null");
        }

        try {
            // 3. 发起异步 Sync 调用，拿到 Future 凭证
            CompletableFuture<GradleInfoResult> future = syncProjectAsync(project, targetPath);

            // 4. 🎯 阻塞当前 MCP HTTP 线程，直到后台的 Gradle Sync 彻底跑完
            // 依据 2025.3+ 规范，此处位于 NanoHTTPD 的线程池中，放心阻塞，绝不卡死 UI EDT 界面
            GradleInfoResult result = future.get();

            return result;
        } catch (Exception e) {
            return new GradleInfoResult(BaseResult.FAILED, null, "Sync execution exception: " + e.getMessage());
        }
    }

    /**
     * 内部异步执行引擎：利用全新的 ImportSpecBuilder 适配 2025.3+ 平台规范
     */
    private static CompletableFuture<GradleInfoResult> syncProjectAsync(@NotNull Project project, @NotNull String projectPath) {
        CompletableFuture<GradleInfoResult> future = new CompletableFuture<>();

        // 1. 组装 2025.3+ 推荐的 Import 规格对象
        ImportSpec spec = new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                // 💡 优化点：改为 BACKGROUND 异步，既能让底层开线程跑，又不会弹出 MODAL 弹窗打扰坐在大屏幕前的人类
                .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                .withCallback(new ExternalProjectRefreshCallback() {

                    // 🎯 核心：Sync 成功回调
                    @Override
                    public void onSuccess(@Nullable DataNode<ProjectData> externalProject) {
                        String logInfo = "Gradle project structure resolved successfully.";
                        if (externalProject != null && externalProject.getData() != null) {
                            logInfo += " Linked project name: " + externalProject.getData().getExternalName();
                        }

                        GradleInfoResult result = new GradleInfoResult(BaseResult.SUCCESS, logInfo, null);
                        future.complete(result); // 🟢 唤醒等待的 MCP 线程
                    }

                    // 🎯 核心：Sync 失败回调
                    @Override
                    public void onFailure(@NotNull String errorMessage, @Nullable String errorDetails) {
                        StringBuilder errorBuilder = new StringBuilder();
                        errorBuilder.append("Gradle Sync Failed: ").append(errorMessage);
                        if (errorDetails != null) {
                            errorBuilder.append("\nDetails: ").append(errorDetails);
                        }

                        GradleInfoResult result = new GradleInfoResult(BaseResult.FAILED, null, errorBuilder.toString());
                        future.complete(result); // 🔴 即使失败也要唤醒，并把报错信息带回给 Agent
                    }
                })
                .build();

        // 2. 将刷新任务丢进 IDEA 的外部系统管理中心（安全地从非 EDT 线程拉起）
        // 2025.3+ 的 refreshProject 内部会自动处理线程上下文切换，支持直接异步调用
        ExternalSystemUtil.refreshProject(projectPath, spec);

        return future;
    }

    public static GradleInfoResult executeGradleTaskWithLogs(List<String> tasks) {

        Project project = getProject();
        if (project == null) {
            return new GradleInfoResult(BaseResult.FAILED, null, "No open project found");
        }

        // 1. 🎯 程序化创建一个临时的 Gradle 运行配置（相当于在 UI 上动态配一个 Gradle Run）
        GradleExternalTaskConfigurationType configType = GradleExternalTaskConfigurationType.getInstance();
        RunnerAndConfigurationSettings settings = RunManager.getInstance(project)
                .createConfiguration("Agent-Gradle-Task", configType.getFactory());

        GradleRunConfiguration runConfiguration = (GradleRunConfiguration) settings.getConfiguration();
        runConfiguration.getSettings().setExternalProjectPath(project.getBasePath()); // 设置项目根目录
        runConfiguration.getSettings().setTaskNames(tasks); // 设置要执行的 Task，如 "build"

        // 2. 准备并发容器和日志收集器
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder allLogsBuffer = new StringBuilder(); // 🎯 收集全量日志送给 Agent
        AtomicInteger exitCodeRef = new AtomicInteger(-1);

        MessageBusConnection connection = project.getMessageBus().connect();
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, new com.intellij.execution.ExecutionListener() {
            @Override
            public void processStarted(@NotNull String executorId, @NotNull com.intellij.execution.runners.ExecutionEnvironment env, @NotNull ProcessHandler handler) {
                // 精准匹配：只拦截我们刚才创建的这个临时配置
                if (env.getRunnerAndConfigurationSettings() == settings) {

                    // 3. 🎯 核心：挂载进程监听器，把 Gradle 的输出吃进来
                    handler.addProcessListener(new ProcessListener() {
                        @Override
                        public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                            // 无论是标准输出(STDOUT)还是错误输出(STDERR)，全都要！
                            if (ProcessOutputTypes.STDOUT.equals(outputType) || ProcessOutputTypes.STDERR.equals(outputType)) {
                                allLogsBuffer.append(event.getText());
                            }
                        }
                    });
                }
            }

            @Override
            public void processTerminated(@NotNull String executorId, @NotNull com.intellij.execution.runners.ExecutionEnvironment env, @NotNull ProcessHandler handler, int exitCode) {
                if (env.getRunnerAndConfigurationSettings() == settings) {
                    exitCodeRef.set(exitCode);
                    latch.countDown();
                }
            }
        });

        try {
            // 4. 在 UI 线程触发这个 Gradle 任务
            ApplicationManager.getApplication().invokeAndWait(() -> {
                ExecutionUtil.runConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance());
            });

            // 5. 等待 Gradle 跑完（编译和下载依赖可能会很久，建议 timeoutMs 给大一点，比如 5-10 分钟）
            boolean completed = latch.await(50_000, TimeUnit.MILLISECONDS);

            String totalLogs = allLogsBuffer.toString();

            if (!completed) {
                return new GradleInfoResult(BaseResult.FAILED, null, "Gradle 任务超时");
            }

            int exitCode = exitCodeRef.get();
            if (exitCode == 0) {
                // 成功了，把全量日志塞回去
                return new GradleInfoResult(BaseResult.SUCCESS, totalLogs, null);
            } else {
                // 失败了（比如测试没通过、代码编译错），同样把报错日志全部打包带走
                return new GradleInfoResult(BaseResult.FAILED, totalLogs, "Gradle 执行失败，退出码：" + exitCode);
            }

        } catch (ProcessCanceledException e) {
            throw e; // 🟢 极其重要：直接放行，交给 IDEA 核心框架
        } catch (Exception e) {
            return new GradleInfoResult(BaseResult.FAILED, null, "由于异常导致未成功触发: " + e.getMessage());
        } finally {
            connection.disconnect(); // 别忘了拔掉眼线
        }
    }


}
