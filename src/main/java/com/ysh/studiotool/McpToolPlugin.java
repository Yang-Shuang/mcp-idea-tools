package com.ysh.studiotool;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;
import com.mcp.tool.executor.config.McpToolConfig;
import com.mcp.tool.executor.config.ToolConfig;
import com.mcp.tool.executor.notification.NotificationManager;
import com.mcp.tool.executor.server.HttpServerManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class McpToolPlugin implements AppLifecycleListener {

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        try {
            NotificationManager.logInfo("McpToolPlugin launch...");
            ToolConfig config = McpToolConfig.getInstance().getState();
            if (config == null) {
                return;
            }
            NotificationManager.logInfo("McpToolPlugin config.enabled : " + config.enabled);
            if (config.enabled) {
                HttpServerManager manager = ApplicationManager.getApplication().getService(HttpServerManager.class);
                if (!manager.isServiceEnabled()) {
                    manager.start(config.port, config.timeoutMs);
                }
            }
        } catch (Exception e) {
            NotificationManager.error("Failed to initialize plugin: " + e.getMessage());
        }
    }

    @Override
    public void appWillBeClosed(boolean isRestart) {
        try {
            HttpServerManager manager = ApplicationManager.getApplication().getService(HttpServerManager.class);
            if (manager != null && manager.isServiceEnabled()) {
                manager.stop();
            }
        } catch (Exception e) {
            NotificationManager.error("Failed to stop server on shutdown: " + e.getMessage());
        }
    }
}
