package com.ysh.studiotool.mcp;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;

/**
 * MCP Scanner 共享的符号定位与坐标/符号名换算工具（FindUsagesScanner 与 GotoDefinitionScanner 共用）。
 */
public final class SymbolLocator {

    private SymbolLocator() {
    }

    /**
     * 引用处：findReferenceAt 解析目标；声明处：无引用时取命名元素。
     */
    public static PsiElement resolveTarget(PsiFile psiFile, int offset) {
        PsiReference reference = psiFile.findReferenceAt(offset);
        if (reference != null) {
            PsiElement resolved = reference.resolve();
            if (resolved != null) {
                return resolved;
            }
        }
        return namedElementAt(psiFile, offset);
    }

    /**
     * 声明处定位：光标处 leaf 对应的命名元素。
     */
    public static PsiElement namedElementAt(PsiFile psiFile, int offset) {
        PsiElement leaf = psiFile.findElementAt(offset);
        return leaf == null ? null : TargetElementUtil.getNamedElement(leaf);
    }

    public static int toOffset(Document document, int line, int column) {
        int lineIndex = Math.min(Math.max(line, 1), document.getLineCount()) - 1;
        int start = document.getLineStartOffset(lineIndex);
        int end = document.getLineEndOffset(lineIndex);
        return Math.min(start + Math.max(column, 1) - 1, end);
    }

    public static String symbolOf(PsiElement target) {
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

    public static String qualifiedName(PsiClass aClass) {
        if (aClass == null) {
            return "";
        }
        String qualified = aClass.getQualifiedName();
        return qualified != null ? qualified : String.valueOf(aClass.getName());
    }
}
