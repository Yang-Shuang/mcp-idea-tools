package com.ysh.studiotool.mcp;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.injected.editor.VirtualFileWindow;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.ysh.studiotool.executor.BaseResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * studio_goto_definition 实现：定位符号 -> multiResolve 全候选 -> 定义坐标。
 * 只读工具，全程 ReadAction，不参与 OPERATION_RUNNING 互斥锁。
 */
public final class GotoDefinitionScanner {

    private GotoDefinitionScanner() {
    }

    public static Map<String, Object> gotoDefinition(String path, int line, int column) {
        Project project = ScannerUtil.getProject();
        if (project == null) {
            return failed("No open project found");
        }
        VirtualFile vFile = ScannerUtil.findVirtualFile(project, path);
        if (vFile == null || vFile.isDirectory()) {
            return failed("File not found: " + path);
        }
        boolean dumbMode = DumbService.getInstance(project).isDumb();
        Map<String, Object> result = ReadAction.compute(
                () -> doGotoDefinition(project, vFile, path, line, column));
        result.put("dumbMode", dumbMode);
        if (dumbMode) {
            result.put("hint", "IDE is in dumb mode (indexing), results may be incomplete, retry after indexing completes");
        }
        return result;
    }

    private static Map<String, Object> doGotoDefinition(Project project, VirtualFile vFile, String path,
                                                        int line, int column) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
        if (psiFile == null) {
            return failed("Unsupported file type: " + path);
        }
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) {
            return failed("No document for file: " + path);
        }

        int offset = TargetElementUtil.adjustOffset(psiFile, document, SymbolLocator.toOffset(document, line, column));
        List<PsiElement> candidates = collectCandidates(psiFile, offset);
        if (candidates.isEmpty()) {
            return failed("No symbol found at line " + line + ", column " + column);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", BaseResult.SUCCESS);
        result.put("symbol", SymbolLocator.symbolOf(candidates.get(0)));

        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> definition = definitionOf(project, candidates.get(i), i == 0, seen);
            if (definition != null) {
                definitions.add(definition);
            }
        }

        result.put("totalCount", definitions.size());
        result.put("definitions", definitions);
        return result;
    }

    /**
     * 引用处：primary = resolve() 排首，multiResolve(true) 有效结果补齐；声明处：返回自身。
     */
    private static List<PsiElement> collectCandidates(PsiFile psiFile, int offset) {
        PsiReference reference = psiFile.findReferenceAt(offset);
        if (reference != null) {
            List<PsiElement> candidates = new ArrayList<>();
            if (reference instanceof PsiPolyVariantReference poly) {
                for (ResolveResult resolveResult : poly.multiResolve(true)) {
                    if (resolveResult.isValidResult() && resolveResult.getElement() != null) {
                        candidates.add(resolveResult.getElement());
                    }
                }
            }
            PsiElement primary = reference.resolve();
            if (primary != null) {
                candidates.remove(primary);
                candidates.add(0, primary);
            }
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }
        PsiElement named = SymbolLocator.namedElementAt(psiFile, offset);
        return named == null ? List.of() : List.of(named);
    }

    private static Map<String, Object> definitionOf(Project project, PsiElement target, boolean primary,
                                                    Set<String> seen) {
        PsiFile defFile = target.getContainingFile();
        if (defFile == null || defFile.getVirtualFile() == null) {
            return null;
        }
        VirtualFile vFile = defFile.getVirtualFile();
        Document document = PsiDocumentManager.getInstance(project).getDocument(defFile);
        if (document == null) {
            return null;
        }
        int offset = target.getTextOffset();
        // 语言注入（如 Markdown 中的 Java 代码块）：换算到宿主文件坐标
        if (document instanceof DocumentWindow window) {
            offset = window.injectedToHost(offset);
            vFile = ((VirtualFileWindow) vFile).getDelegate();
            PsiFile hostFile = PsiManager.getInstance(project).findFile(vFile);
            Document hostDocument = hostFile == null
                    ? null
                    : PsiDocumentManager.getInstance(project).getDocument(hostFile);
            if (hostDocument == null) {
                return null;
            }
            document = hostDocument;
        }
        offset = Math.min(Math.max(offset, 0), document.getTextLength() - 1);
        String key = vFile.getPath() + ":" + offset;
        if (!seen.add(key)) {
            return null;
        }

        Map<String, Object> definition = new LinkedHashMap<>();
        int lineIndex = document.getLineNumber(offset);
        definition.put("file", vFile.getPath());
        definition.put("line", lineIndex + 1);
        definition.put("column", offset - document.getLineStartOffset(lineIndex) + 1);
        definition.put("lineText", document.getText(new TextRange(
                document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))).trim());
        definition.put("primary", primary);
        return definition;
    }

    private static Map<String, Object> failed(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", BaseResult.FAILED);
        result.put("error", error);
        return result;
    }
}
