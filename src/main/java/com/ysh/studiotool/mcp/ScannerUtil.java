package com.ysh.studiotool.mcp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;

public final class ScannerUtil {

    private ScannerUtil() {
    }

    public static Project getProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        if (projects.length == 0) {
            return null;
        }
        return projects[0];
    }

    public static VirtualFile findVirtualFile(Project project, String path) {
        File ioFile = new File(path);
        if (!ioFile.isAbsolute()) {
            String basePath = project.getBasePath();
            if (basePath != null) {
                ioFile = new File(basePath, path);
            }
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
    }
}
