package com.ysh.studiotool.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "McpToolConfig", storages = @Storage("McpToolConfig.xml"))
@Service(Service.Level.APP)
public final class McpToolConfig implements PersistentStateComponent<ToolConfig> {

    private ToolConfig toolConfig = new ToolConfig();

    public McpToolConfig() {
    }

    @Override
    public @Nullable ToolConfig getState() {
        return toolConfig;
    }

    @Override
    public void loadState(@NotNull ToolConfig state) {
        this.toolConfig = state;
    }

    // 方便其他地方获取该服务实例的静态方法
    public static McpToolConfig getInstance() {
        return ApplicationManager.getApplication().getService(McpToolConfig.class);
    }
}
