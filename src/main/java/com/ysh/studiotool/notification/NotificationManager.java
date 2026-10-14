package com.ysh.studiotool.notification;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.mcp.tool.executor.config.McpToolConfig;
import com.mcp.tool.executor.config.ToolConfig;

public final class NotificationManager {

    private static final String GROUP_ID = "MCP-Executor";
    private static final Logger mLogger = Logger.getInstance("MCP-Executor");

    private NotificationManager() {
    }

    public static void info(String message) {
        notify(NotificationType.INFORMATION, message);
        logInfo(message);
    }

    public static void error(String message) {
        notify(NotificationType.ERROR, message);
        logError(message);
    }

    public static void logInfo(String info) {
        mLogger.info(info);
    }

    public static void logError(String error) {
        mLogger.info(error);
    }

    private static void notify(NotificationType type, String message) {
        try {
            ToolConfig config = McpToolConfig.getInstance().getState();
            if (config == null || !config.showNotifications) return;

            NotificationGroupManager groupManager = NotificationGroupManager.getInstance();
            if (groupManager == null) {
                return;
            }

            NotificationGroup group = groupManager.getNotificationGroup(GROUP_ID);
            if (group == null) {
                return;
            }

            Notification notification = group.createNotification("MCP-Executor", message, type);

            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            Project targetProject = openProjects.length > 0 ? openProjects[0] : null;

            if (targetProject != null && !targetProject.isDisposed()) {
                notification.notify(targetProject);
            } else {
                Project defaultProject = ProjectManager.getInstance().getDefaultProject();
                if (defaultProject != null && !defaultProject.isDisposed()) {
                    notification.notify(defaultProject);
                }
            }
        } catch (Exception e) {
            mLogger.info(e.getMessage());
        }
    }
}
