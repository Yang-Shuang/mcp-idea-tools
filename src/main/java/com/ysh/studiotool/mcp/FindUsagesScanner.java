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
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.ysh.studiotool.executor.BaseResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * studio_find_usages 实现：定位符号 -> 扩展元素集（重载/继承）-> ReferencesSearch 全项目搜引用。
 * 只读工具，全程 ReadAction，不参与 OPERATION_RUNNING 互斥锁。
 */
public final class FindUsagesScanner {

    private static final int MAX_USAGES = 200;

    private FindUsagesScanner() {
    }

    public static Map<String, Object> findUsages(String path, int line, int column) {
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
                () -> doFindUsages(project, vFile, path, line, column));
        result.put("dumbMode", dumbMode);
        if (dumbMode) {
            result.put("hint", "IDE is in dumb mode (indexing), results may be incomplete, retry after indexing completes");
        }
        return result;
    }

    private static Map<String, Object> doFindUsages(Project project, VirtualFile vFile, String path,
                                                    int line, int column) {
        Map<String, Object> result = new LinkedHashMap<>();
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
        if (psiFile == null) {
            return failed("Unsupported file type: " + path);
        }
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) {
            return failed("No document for file: " + path);
        }

        int offset = TargetElementUtil.adjustOffset(psiFile, document, toOffset(document, line, column));
        PsiElement target = resolveTarget(psiFile, offset);
        if (target == null) {
            return failed("No symbol found at line " + line + ", column " + column);
        }

        result.put("state", BaseResult.SUCCESS);
        result.put("symbol", symbolOf(target));

        Collection<PsiElement> elements = expandElements(target);
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> usages = new ArrayList<>();
        int totalCount = 0;
        for (PsiElement element : elements) {
            for (PsiReference reference : ReferencesSearch.search(element, GlobalSearchScope.projectScope(project)).findAll()) {
                Map<String, Object> usage = usageOf(project, reference, seen);
                if (usage == null) {
                    continue;
                }
                totalCount++;
                if (usages.size() < MAX_USAGES) {
                    usages.add(usage);
                }
            }
        }

        usages.sort((a, b) -> {
            int byFile = String.valueOf(a.get("file")).compareTo(String.valueOf(b.get("file")));
            return byFile != 0 ? byFile : Integer.compare((int) a.get("line"), (int) b.get("line"));
        });

        result.put("totalCount", totalCount);
        result.put("truncated", totalCount > usages.size());
        result.put("usages", usages);
        if (usages.isEmpty() && !isSupportedType(target)) {
            result.put("hint", "Symbol type " + target.getClass().getSimpleName()
                    + " may not be fully supported for find usages");
        }
        return result;
    }

    /**
     * 引用处：findReferenceAt 解析目标；声明处：无引用时取命名元素。
     */
    private static PsiElement resolveTarget(PsiFile psiFile, int offset) {
        PsiReference reference = psiFile.findReferenceAt(offset);
        if (reference != null) {
            PsiElement resolved = reference.resolve();
            if (resolved != null) {
                return resolved;
            }
        }
        PsiElement leaf = psiFile.findElementAt(offset);
        if (leaf != null) {
            return TargetElementUtil.getNamedElement(leaf);
        }
        return null;
    }

    /**
     * 方法：包含类层级所有同名方法（重载 + 父类/接口）与子类覆写；其余符号：元素本身。
     */
    private static Collection<PsiElement> expandElements(PsiElement target) {
        if (target instanceof PsiMethod method) {
            Set<PsiElement> elements = new LinkedHashSet<>();
            PsiClass containingClass = method.getContainingClass();
            if (containingClass != null) {
                for (PsiMethod candidate : containingClass.findMethodsByName(method.getName(), true)) {
                    elements.add(candidate);
                }
            }
            for (PsiMethod overriding : OverridingMethodsSearch.search(method)) {
                elements.add(overriding);
            }
            if (elements.isEmpty()) {
                elements.add(method);
            }
            return elements;
        }
        return List.of(target);
    }

    private static Map<String, Object> usageOf(Project project, PsiReference reference, Set<String> seen) {
        PsiElement refElement = reference.getElement();
        PsiFile refFile = refElement.getContainingFile();
        if (refFile == null || refFile.getVirtualFile() == null) {
            return null;
        }
        VirtualFile vFile = refFile.getVirtualFile();
        Document document = PsiDocumentManager.getInstance(project).getDocument(refFile);
        if (document == null) {
            return null;
        }
        int absOffset = reference.getAbsoluteRange().getStartOffset();
        // 语言注入（如 Markdown 中的 Java 代码块）：换算到宿主文件坐标
        if (document instanceof DocumentWindow window) {
            absOffset = window.injectedToHost(absOffset);
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
        absOffset = Math.min(Math.max(absOffset, 0), document.getTextLength() - 1);
        String key = vFile.getPath() + ":" + absOffset;
        if (!seen.add(key)) {
            return null;
        }

        Map<String, Object> usage = new LinkedHashMap<>();
        int lineIndex = document.getLineNumber(absOffset);
        usage.put("file", vFile.getPath());
        usage.put("line", lineIndex + 1);
        usage.put("column", absOffset - document.getLineStartOffset(lineIndex) + 1);
        usage.put("lineText", document.getText(new TextRange(
                document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))).trim());
        usage.put("kind", kindOf(reference));
        return usage;
    }

    private static String kindOf(PsiReference reference) {
        PsiElement refElement = reference.getElement();
        if (PsiTreeUtil.getParentOfType(refElement, PsiImportStatementBase.class, false) != null) {
            return "import";
        }
        // 在 lambda/匿名类边界停下：跨过边界属于嵌套作用域，不能算外层方法调用的引用
        if (PsiTreeUtil.getParentOfType(refElement, PsiMethodCallExpression.class, false,
                PsiLambdaExpression.class, PsiAnonymousClass.class) != null) {
            return "call";
        }
        PsiExpression expression = PsiTreeUtil.getParentOfType(refElement, PsiExpression.class, false,
                PsiLambdaExpression.class, PsiAnonymousClass.class);
        if (expression != null && PsiUtil.isAccessedForWriting(expression)) {
            return "write";
        }
        return "read";
    }

    private static boolean isSupportedType(PsiElement target) {
        return target instanceof PsiMethod || target instanceof PsiVariable || target instanceof PsiClass
                || target instanceof PsiPackage;
    }

    private static int toOffset(Document document, int line, int column) {
        int lineIndex = Math.min(Math.max(line, 1), document.getLineCount()) - 1;
        int start = document.getLineStartOffset(lineIndex);
        int end = document.getLineEndOffset(lineIndex);
        return Math.min(start + Math.max(column, 1) - 1, end);
    }

    private static String symbolOf(PsiElement target) {
        if (target instanceof PsiMethod method) {
            return qualifiedName(method.getContainingClass()) + "#" + method.getName();
        }
        if (target instanceof PsiField field) {
            return qualifiedName(field.getContainingClass()) + "." + field.getName();
        }
        if (target instanceof PsiClass aClass) {
            return qualifiedName(aClass);
        }
        if (target instanceof PsiNamedElement named && named.getName() != null) {
            return named.getName();
        }
        return target.toString();
    }

    private static String qualifiedName(PsiClass aClass) {
        if (aClass == null) {
            return "";
        }
        String qualified = aClass.getQualifiedName();
        return qualified != null ? qualified : String.valueOf(aClass.getName());
    }

    private static Map<String, Object> failed(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", BaseResult.FAILED);
        result.put("error", error);
        return result;
    }
}
