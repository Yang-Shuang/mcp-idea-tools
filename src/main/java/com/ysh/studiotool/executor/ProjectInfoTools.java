package com.ysh.studiotool.executor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.util.*;

/**
 * 项目信息收集工具类，自动过滤空壳模块，为 AI Agent 提供精简、高价值的上下文。
 */
public class ProjectInfoTools {

    private static final Gson GSON = new GsonBuilder().create();

    public static String getProjectInfoJson(Project project) {
        if (project == null || project.isDisposed()) {
            return "{\"error\": \"Project is null or disposed\"}";
        }

        return ReadAction.compute(() -> {
            Map<String, Object> info = new LinkedHashMap<>();

            // 1. 基本信息
            info.put("projectName", project.getName());
            info.put("basePath", project.getBasePath());

            var projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
            info.put("projectSdk", projectSdk != null ? projectSdk.getName() : "Unknown");

            // 2. 特征与类型深度识别
            String basePath = project.getBasePath();
            List<String> rootConfigFiles = new ArrayList<>();
            boolean isGradle = false;
            boolean isAndroid = false;
            boolean isIntelliJPlugin = false;

            if (basePath != null) {
                File baseDir = new File(basePath);
                File[] files = baseDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile()) {
                            String name = f.getName();
                            if (name.equals("build.gradle") || name.equals("build.gradle.kts") ||
                                    name.equals("settings.gradle") || name.equals("settings.gradle.kts") ||
                                    name.equals("libs.versions.toml") || name.equals("pom.xml")) {
                                rootConfigFiles.add(name);
                                isGradle = name.contains("gradle");
                            }
                        }
                    }
                }

                // 检查是否为 Android 项目
                File appManifest = new File(basePath, "app/src/main/AndroidManifest.xml");
                File rootManifest = new File(basePath, "src/main/AndroidManifest.xml");
                if (appManifest.exists() || rootManifest.exists()) {
                    isAndroid = true;
                }

                // 检查是否为 IntelliJ 插件项目
                File pluginXml = new File(basePath, "src/main/resources/META-INF/plugin.xml");
                if (pluginXml.exists()) {
                    isIntelliJPlugin = true;
                }
            }

            info.put("isGradleProject", isGradle);
            info.put("isAndroidProject", isAndroid);
            info.put("isIntelliJPluginProject", isIntelliJPlugin);
            info.put("keyRootFiles", rootConfigFiles);

            // 3. 模块信息与智能化过滤
            ModuleManager moduleManager = ModuleManager.getInstance(project);
            Module[] modules = moduleManager.getModules();
            List<Map<String, Object>> moduleList = new ArrayList<>();

            for (Module module : modules) {
                ModuleRootManager rootManager = ModuleRootManager.getInstance(module);

                List<String> productionSources = new ArrayList<>();
                List<String> testSources = new ArrayList<>();
                List<String> resourceRoots = new ArrayList<>();

                for (VirtualFile vf : rootManager.getSourceRoots()) {
                    String path = vf.getPath();
                    // 兼容 Windows (\) 和 Linux/Mac (/) 的路径判断
                    if (path.contains("/src/test/") || path.contains("\\src\\test\\")) {
                        testSources.add(path);
                    } else if (path.contains("/src/main/resources") || path.contains("\\src\\main\\resources")) {
                        resourceRoots.add(path);
                    } else {
                        productionSources.add(path);
                    }
                }

                // 【核心优化点】如果该模块既没有生产源码、也没有测试源码、更没有资源目录，
                // 说明它是 Gradle/IDEA 自动生成的空壳容器模块（如根项目或空的 test 模块），直接跳过！
                if (productionSources.isEmpty() && testSources.isEmpty() && resourceRoots.isEmpty()) {
                    continue;
                }

                Map<String, Object> modInfo = new LinkedHashMap<>();
                modInfo.put("name", module.getName());
                modInfo.put("productionSources", productionSources);
                modInfo.put("testSources", testSources);
                modInfo.put("resourceRoots", resourceRoots);

//                // 收集第三方库摘要
//                Set<String> libraryNames = new TreeSet<>();
//                rootManager.orderEntries().forEachLibrary(library -> {
//                    String libName = library.getName();
//                    if (libName != null && !libName.isEmpty()) {
//                        libraryNames.add(libName);
//                    }
//                    return true;
//                });
//                modInfo.put("librariesSummary", libraryNames);

                // 模块间依赖关系
                Module[] dependencies = rootManager.getDependencies();
                List<String> depNames = new ArrayList<>();
                for (Module dep : dependencies) {
                    depNames.add(dep.getName());
                }
                modInfo.put("moduleDependencies", depNames);

                moduleList.add(modInfo);
            }

            info.put("modules", moduleList);

            return GSON.toJson(info);
        });
    }
}