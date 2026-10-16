package com.ysh.studiotool;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;
import com.ysh.studiotool.config.McpToolConfig;
import com.ysh.studiotool.config.ToolConfig;
import com.ysh.studiotool.mcp.McpServerManager;
import com.ysh.studiotool.notification.NotificationManager;
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
                McpServerManager manager = ApplicationManager.getApplication().getService(McpServerManager.class);
                if (!manager.isRunning()) {
                    try {
                        manager.start(config.port);
                        NotificationManager.info("MCP server started on port " + config.port);
                    } catch (Exception e) {
                        NotificationManager.error("Failed to start MCP server on port " + config.port + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            NotificationManager.error("Failed to initialize plugin: " + e.getMessage());
        }
    }

    @Override
    public void appWillBeClosed(boolean isRestart) {
        try {
            McpServerManager manager = ApplicationManager.getApplication().getService(McpServerManager.class);
            if (manager != null && manager.isRunning()) {
                manager.stop();
            }
        } catch (Exception e) {
            NotificationManager.error("Failed to stop server on shutdown: " + e.getMessage());
        }
    }
}
