package com.ysh.studiotool.mcp;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.ysh.studiotool.executor.BaseResult;
import com.ysh.studiotool.notification.NotificationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ErrorScanner {

    private static final long ANALYSIS_WAIT_TIMEOUT_MS = 3000L;
    private static final long ANALYSIS_POLL_INTERVAL_MS = 100L;
    private static final String RESTART_REASON = "studio_get_errors";

    private ErrorScanner() {
    }

    public static Map<String, Object> scan(List<String> paths) {
        Map<String, Object> result = new LinkedHashMap<>();
        Project project = getProject();
        if (project == null) {
            result.put("state", BaseResult.FAILED);
            result.put("error", "No open project found");
            return result;
        }

        boolean dumbMode = DumbService.getInstance(project).isDumb();
        List<Map<String, Object>> fileResults = new ArrayList<>();
        List<VirtualFile> analyzedFiles = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();
        int totalErrors = 0;
        int totalWarnings = 0;
        int totalWeakWarnings = 0;

        for (String path : paths) {
            VirtualFile vFile = findVirtualFile(project, path);
            analyzedFiles.add(vFile);
            Map<String, Object> fileResult = analyzeFile(project, vFile, path, true, true);
            fileResults.add(fileResult);
            totalErrors += intOf(fileResult.get("errorCount"));
            totalWarnings += intOf(fileResult.get("warningCount"));
            totalWeakWarnings += intOf(fileResult.get("weakWarningCount"));
        }

        // 附加：当前打开且 daemon 已分析完成的文件（快照模式，零成本、不触碰 UI），
        // 让 agent 在定向分析的同时拿到 IDE 其余打开文件的已知错误状态
        for (VirtualFile vFile : FileEditorManager.getInstance(project).getOpenFiles()) {
            if (analyzedFiles.contains(vFile)) {
                continue;
            }
            Map<String, Object> fileResult = analyzeFile(project, vFile, vFile.getPath(), false, false);
            if ("not_analyzed".equals(fileResult.get("status"))) {
                skippedFiles.add(vFile.getPath());
                continue;
            }
            fileResults.add(fileResult);
            totalErrors += intOf(fileResult.get("errorCount"));
            totalWarnings += intOf(fileResult.get("warningCount"));
            totalWeakWarnings += intOf(fileResult.get("weakWarningCount"));
        }

        result.put("state", BaseResult.SUCCESS);
        result.put("dumbMode", dumbMode);
        if (dumbMode) {
            result.put("hint", "IDE is in dumb mode (indexing), results may be inaccurate, retry after indexing completes");
        }
        if (!skippedFiles.isEmpty()) {
            result.put("skippedFiles", skippedFiles);
            result.put("hint", "Some open files were not analyzed yet (not the selected editor); pass their paths explicitly to analyze them");
        }
        result.put("errorCount", totalErrors);
        result.put("warningCount", totalWarnings);
        result.put("weakWarningCount", totalWeakWarnings);
        result.put("files", fileResults);
        return result;
    }

    /**
     * 不指定文件时：检查当前所有打开编辑器文件
     */
    public static Map<String, Object> scanOpenEditors() {
        Map<String, Object> result = new LinkedHashMap<>();
        Project project = getProject();
        if (project == null) {
            result.put("state", BaseResult.FAILED);
            result.put("error", "No open project found");
            return result;
        }

        boolean dumbMode = DumbService.getInstance(project).isDumb();
        List<Map<String, Object>> fileResults = new ArrayList<>();
        int totalErrors = 0;
        int totalWarnings = 0;
        int totalWeakWarnings = 0;
        int openFileCount = 0;

        VirtualFile[] openFiles = FileEditorManager.getInstance(project).getOpenFiles();
        NotificationManager.logInfo("scanOpenEditors: " + openFiles.length + " open files");
        List<String> skippedFiles = new ArrayList<>();
        for (VirtualFile vFile : openFiles) {
            NotificationManager.logInfo("scanOpenEditors: open file = " + vFile.getPath());
            openFileCount++;
            Map<String, Object> fileResult = analyzeFile(project, vFile, vFile.getPath(), false, false);
            NotificationManager.logInfo("analyze result: " + fileResult.get("file")
                    + ", status=" + fileResult.get("status")
                    + ", errors=" + fileResult.get("errorCount")
                    + ", warnings=" + fileResult.get("warningCount")
                    + ", weakWarnings=" + fileResult.get("weakWarningCount"));
            if ("not_analyzed".equals(fileResult.get("status"))) {
                skippedFiles.add(vFile.getPath());
                continue;
            }
            if (intOf(fileResult.get("errorCount")) > 0
                    || intOf(fileResult.get("warningCount")) > 0
                    || intOf(fileResult.get("weakWarningCount")) > 0) {
                fileResults.add(fileResult);
                totalErrors += intOf(fileResult.get("errorCount"));
                totalWarnings += intOf(fileResult.get("warningCount"));
                totalWeakWarnings += intOf(fileResult.get("weakWarningCount"));
            }
        }

        result.put("state", BaseResult.SUCCESS);
        result.put("dumbMode", dumbMode);
        if (dumbMode) {
            result.put("hint", "IDE is in dumb mode (indexing), results may be inaccurate, retry after indexing completes");
        }
        if (!skippedFiles.isEmpty()) {
            result.put("skippedFiles", skippedFiles);
            result.put("hint", "Some open files were not analyzed yet (not the selected editor); pass their paths explicitly to analyze them");
        }
        result.put("openFileCount", openFileCount);
        result.put("errorCount", totalErrors);
        result.put("warningCount", totalWarnings);
        result.put("weakWarningCount", totalWeakWarnings);
        result.put("files", fileResults);
        return result;
    }

    /**
     * 分析单个文件。
     * daemon 只分析当前选中的编辑器，因此：
     * - 分析已完成 → 直接读取现成高亮（零成本，不触碰 UI）
     * - 快照模式（allowWaiting=false）→ 未完成的分析文件标记 not_analyzed 返回
     * - 等待模式（allowWaiting=true，用于显式指定的文件）→ 打开/激活该文件 → restart 催促 daemon →
     *   轮询等待完成 → 读取结果 → 恢复原来的选中 tab
     */
    private static Map<String, Object> analyzeFile(Project project, VirtualFile vFile, String displayName,
                                                   boolean refreshContent, boolean allowWaiting) {
        Map<String, Object> fileResult = new LinkedHashMap<>();
        fileResult.put("file", displayName);
        if (vFile == null) {
            putEmptyResult(fileResult, "not_found");
            return fileResult;
        }
        if (vFile.isDirectory()) {
            putEmptyResult(fileResult, "not_a_file");
            return fileResult;
        }

        FileDocumentManager docManager = FileDocumentManager.getInstance();
        NotificationManager.logInfo("analyzeFile start: " + displayName + ", refreshContent=" + refreshContent);

        Resolved resolved = ReadAction.compute(() -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
            Document document = psiFile == null ? null : docManager.getDocument(vFile);
            boolean unsaved = document != null && docManager.isDocumentUnsaved(document);
            return new Resolved(psiFile, document, unsaved);
        });
        NotificationManager.logInfo("analyzeFile resolved: " + displayName
                + ", psi=" + (resolved.psiFile != null)
                + ", doc=" + (resolved.document != null)
                + ", unsaved=" + resolved.unsaved);

        if (resolved.psiFile == null) {
            putEmptyResult(fileResult, "unsupported");
            return fileResult;
        }
        if (resolved.document == null) {
            putEmptyResult(fileResult, "no_document");
            return fileResult;
        }

        // reloadFromDisk 在 2025.3 要求 EDT，经 invokeAndWait 执行；
        // 编辑器有未保存修改时以编辑器内容为准，否则重载确保与磁盘内容一致
        if (refreshContent && !resolved.unsaved) {
            ApplicationManager.getApplication().invokeAndWait(
                    () -> docManager.reloadFromDisk(resolved.document),
                    ModalityState.any());
        }

        List<HighlightInfo> infos = currentHighlights(project, resolved.document);
        NotificationManager.logInfo("current highlights: " + displayName + " -> " + infos.size());

        boolean finished = isAnalysisFinished(project, resolved.psiFile);
        NotificationManager.logInfo("analysis finished: " + displayName + " -> " + finished);

        if (!allowWaiting) {
            // 快照模式：有现成高亮就返回（即 IDE 当前展示的状态）；
            // finished=false（如定向分析切换 tab 时 daemon 弄脏了其他文件的状态）时高亮数据仍在，应照常返回并标注可能非最新；
            // 仅"无高亮且未完成"才视为未分析（如重启后从未激活的 tab）
            if (infos.isEmpty() && !finished) {
                NotificationManager.logInfo("snapshot mode, not analyzed yet: " + displayName);
                putEmptyResult(fileResult, "not_analyzed");
                return fileResult;
            }
            fileResult.put("analysisFinished", finished);
            collectProblems(fileResult, resolved.document, infos);
            return fileResult;
        }

        // 等待模式：显式指定的文件需要最新分析
        fileResult.put("analysisFinished", finished);
        if (finished) {
            collectProblems(fileResult, resolved.document, infos);
            return fileResult;
        }
        if (DumbService.getInstance(project).isDumb()) {
            NotificationManager.logInfo("dumb mode, return current highlights only: " + displayName);
            collectProblems(fileResult, resolved.document, infos);
            return fileResult;
        }

        FileEditorManager editorManager = FileEditorManager.getInstance(project);
        // getSelectedFiles/isFileOpen 同样要求 EDT，与 open/close 一样经 invokeAndWait 执行；
        // invokeAndWait 没有 Computable+ModalityState 重载，用 Runnable+容器带回结果
        NotificationManager.logInfo("collecting editor state on EDT: " + displayName);
        EditorContext[] holder = new EditorContext[1];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            VirtualFile[] selectedBefore = editorManager.getSelectedFiles();
            VirtualFile previousSelected = selectedBefore.length > 0 ? selectedBefore[0] : null;
            holder[0] = new EditorContext(previousSelected, editorManager.isFileOpen(vFile));
        }, ModalityState.any());
        VirtualFile previousSelected = holder[0].previousSelected();
        boolean wasOpen = holder[0].wasOpen();
        NotificationManager.logInfo("editor state: " + displayName
                + ", wasOpen=" + wasOpen
                + ", previousSelected=" + (previousSelected != null ? previousSelected.getName() : "null"));
        try {
            // 无论是否已打开都调用 openFile：对已打开的 tab 它是幂等的“激活为选中编辑器”操作，
            // 而 daemon 只分析选中的编辑器，未激活的 tab 不会被调度分析
            ApplicationManager.getApplication().invokeAndWait(
                    () -> editorManager.openFile(vFile, false),
                    ModalityState.any());
            ReadAction.compute(() -> {
                DaemonCodeAnalyzer.getInstance(project).restart(resolved.psiFile, RESTART_REASON);
                return true;
            });
            NotificationManager.logInfo("daemon restarted, waiting for analysis: " + displayName);
            boolean waitFinished = waitForAnalysis(project, resolved.psiFile);
            infos = currentHighlights(project, resolved.document);
            NotificationManager.logInfo("highlights after wait: " + displayName + " -> " + infos.size());
            fileResult.put("analysisFinished", waitFinished);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Exception e) {
            NotificationManager.logInfo("analysis wait failed: " + displayName + " -> " + e);
        } finally {
            try {
                if (!wasOpen) {
                    ApplicationManager.getApplication().invokeAndWait(
                            () -> editorManager.closeFile(vFile),
                            ModalityState.any());
                }
                if (previousSelected != null && !previousSelected.equals(vFile)) {
                    ApplicationManager.getApplication().invokeAndWait(
                            () -> editorManager.openFile(previousSelected, false),
                            ModalityState.any());
                }
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (Exception e) {
                NotificationManager.logInfo("failed to restore editor state: " + displayName + " -> " + e);
            }
        }

        collectProblems(fileResult, resolved.document, infos);
        return fileResult;
    }

    private static void putEmptyResult(Map<String, Object> fileResult, String status) {
        fileResult.put("status", status);
        fileResult.put("errorCount", 0);
        fileResult.put("warningCount", 0);
        fileResult.put("weakWarningCount", 0);
        fileResult.put("problems", List.of());
    }

    private static boolean isAnalysisFinished(Project project, PsiFile psiFile) {
        Boolean finished = ReadAction.compute(() -> {
            DaemonCodeAnalyzerEx analyzer = (DaemonCodeAnalyzerEx) DaemonCodeAnalyzer.getInstance(project);
            return analyzer.isErrorAnalyzingFinished(psiFile);
        });
        return Boolean.TRUE.equals(finished);
    }

    private static boolean waitForAnalysis(Project project, PsiFile psiFile) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ANALYSIS_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isAnalysisFinished(project, psiFile)) {
                return true;
            }
            Thread.sleep(ANALYSIS_POLL_INTERVAL_MS);
        }
        NotificationManager.logInfo("analysis wait timeout: " + psiFile.getVirtualFile());
        return false;
    }

    private static List<HighlightInfo> currentHighlights(Project project, Document document) {
        return ReadAction.compute(() -> {
            List<HighlightInfo> infos = new ArrayList<>();
            DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.WEAK_WARNING,
                    0, document.getTextLength(), info -> {
                        HighlightSeverity severity = info.getSeverity();
                        if (severity != null && severity.compareTo(HighlightSeverity.WEAK_WARNING) >= 0) {
                            infos.add(info);
                        }
                        return true;
                    });
            return infos;
        });
    }

    private static void collectProblems(Map<String, Object> fileResult, Document document, List<HighlightInfo> infos) {
        ReadAction.compute(() -> {
            List<Map<String, Object>> problems = new ArrayList<>();
            int errors = 0;
            int warnings = 0;
            int weakWarnings = 0;

            for (HighlightInfo info : infos) {
                HighlightSeverity severity = info.getSeverity();
                if (severity == null) {
                    continue;
                }
                boolean isError = severity.compareTo(HighlightSeverity.ERROR) >= 0;
                boolean isWarning = !isError && severity.compareTo(HighlightSeverity.WARNING) >= 0;
                boolean isWeakWarning = !isError && !isWarning && severity.compareTo(HighlightSeverity.WEAK_WARNING) >= 0;
                if (!isError && !isWarning && !isWeakWarning) {
                    continue;
                }
                if (isError) {
                    errors++;
                } else if (isWarning) {
                    warnings++;
                } else {
                    weakWarnings++;
                }

                int offset = Math.min(info.getStartOffset(), document.getTextLength());
                int lineIndex = document.getLineNumber(offset);
                int line = lineIndex + 1;
                int column = offset - document.getLineStartOffset(lineIndex) + 1;
                String codeLine = document.getText(new TextRange(
                        document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))).trim();

                Map<String, Object> problem = new LinkedHashMap<>();
                problem.put("line", line);
                problem.put("column", column);
                problem.put("severity", isError ? "ERROR" : isWarning ? "WARNING" : "WEAK_WARNING");
                problem.put("codeLine", codeLine);
                problem.put("message", messageOf(info));
                problems.add(problem);
            }

            fileResult.put("status", "ok");
            fileResult.put("errorCount", errors);
            fileResult.put("warningCount", warnings);
            fileResult.put("weakWarningCount", weakWarnings);
            fileResult.put("problems", problems);
            return true;
        });
    }

    private static VirtualFile findVirtualFile(Project project, String path) {
        File ioFile = new File(path);
        if (!ioFile.isAbsolute()) {
            String basePath = project.getBasePath();
            if (basePath != null) {
                ioFile = new File(basePath, path);
            }
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
    }

    private static String messageOf(HighlightInfo info) {
        String message = info.getDescription();
        if (message == null || message.isEmpty()) {
            message = info.getToolTip();
        }
        if (message == null) {
            return "Unknown issue";
        }
        return message.replaceAll("<[^>]*>", "").trim();
    }

    private static int intOf(Object value) {
        return value instanceof Integer integer ? integer : 0;
    }

    private static Project getProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        if (projects.length == 0) {
            return null;
        }
        return projects[0];
    }

    private record Resolved(PsiFile psiFile, Document document, boolean unsaved) {
    }

    private record EditorContext(VirtualFile previousSelected, boolean wasOpen) {
    }
}
