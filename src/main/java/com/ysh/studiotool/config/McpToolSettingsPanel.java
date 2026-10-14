package com.ysh.studiotool.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.mcp.tool.executor.notification.NotificationManager;
import com.mcp.tool.executor.server.HttpServerManager;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class McpToolSettingsPanel implements SearchableConfigurable {

    private JCheckBox enableServerCheckbox;
    private JTextField portTextField;
    private JTextField timeoutMsTextField;
    private JCheckBox showNotificationsCheckbox;

    @Override
    public String getId() {
        return "MCP_TOOL_EXECUTOR_SETTINGS";
    }

    @Override
    public String getHelpTopic() {
        return "reference.settings.mcp.tool.executor";
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "MCP Tool Executor";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.weightx = 1.0;

        JLabel enableLabel = new JLabel("Enable Server:");
        enableLabel.setFont(enableLabel.getFont().deriveFont(Font.BOLD));
        gbc.gridy = 0;
        mainPanel.add(enableLabel, gbc);

        JPanel enabledSubPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        enableServerCheckbox = new JCheckBox("", true);
        enabledSubPanel.add(enableServerCheckbox);
        JLabel enabledDesc = new JLabel("  Start HTTP server on IDE startup");
        enabledDesc.setForeground(Color.GRAY);
        enabledDesc.setFont(enabledDesc.getFont().deriveFont(Font.PLAIN, 10f));
        enabledSubPanel.add(enabledDesc);
        gbc.gridy = 1;
        mainPanel.add(enabledSubPanel, gbc);

        JLabel portLabel = new JLabel("Port:");
        portLabel.setFont(portLabel.getFont().deriveFont(Font.BOLD));
        gbc.gridy = 2;
        mainPanel.add(portLabel, gbc);

        JPanel portSubPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        portTextField = new JTextField("7834", 15);
        portSubPanel.add(portTextField);
        JLabel portDesc = new JLabel("  HTTP server port number (default: 7834)");
        portDesc.setForeground(Color.GRAY);
        portDesc.setFont(portDesc.getFont().deriveFont(Font.PLAIN, 10f));
        portSubPanel.add(portDesc);
        gbc.gridy = 3;
        mainPanel.add(portSubPanel, gbc);

        JLabel timeoutLabel = new JLabel("Timeout (ms):");
        timeoutLabel.setFont(timeoutLabel.getFont().deriveFont(Font.BOLD));
        gbc.gridy = 4;
        mainPanel.add(timeoutLabel, gbc);

        JPanel timeoutSubPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        timeoutMsTextField = new JTextField("600000", 15);
        timeoutSubPanel.add(timeoutMsTextField);
        JLabel timeoutDesc = new JLabel("  Execution timeout in milliseconds (default: 600000)");
        timeoutDesc.setForeground(Color.GRAY);
        timeoutDesc.setFont(timeoutDesc.getFont().deriveFont(Font.PLAIN, 10f));
        timeoutSubPanel.add(timeoutDesc);
        gbc.gridy = 5;
        mainPanel.add(timeoutSubPanel, gbc);

        JLabel notifyLabel = new JLabel("Show Notifications:");
        notifyLabel.setFont(notifyLabel.getFont().deriveFont(Font.BOLD));
        gbc.gridy = 6;
        mainPanel.add(notifyLabel, gbc);

        JPanel notifySubPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        showNotificationsCheckbox = new JCheckBox("", true);
        notifySubPanel.add(showNotificationsCheckbox);
        JLabel notifyDesc = new JLabel("  Display IDEA notifications for server events");
        notifyDesc.setForeground(Color.GRAY);
        notifyDesc.setFont(notifyDesc.getFont().deriveFont(Font.PLAIN, 10f));
        notifySubPanel.add(notifyDesc);
        gbc.gridy = 7;
        mainPanel.add(notifySubPanel, gbc);

        return mainPanel;
    }

    @Override
    public boolean isModified() {
        ToolConfig config = McpToolConfig.getInstance().getState();
        if (config == null) return false;

        boolean modified = enableServerCheckbox.isSelected() != config.enabled;

        try {
            int port = Integer.parseInt(portTextField.getText());
            modified |= port != config.port;
        } catch (NumberFormatException e) {
            modified = true;
        }

        try {
            long timeout = Long.parseLong(timeoutMsTextField.getText());
            modified |= timeout != config.timeoutMs;
        } catch (NumberFormatException e) {
            modified = true;
        }

        modified |= showNotificationsCheckbox.isSelected() != config.showNotifications;

        return modified;
    }

    @Override
    public void reset() {
        ToolConfig config = McpToolConfig.getInstance().getState();

        if (config == null) {
            enableServerCheckbox.setSelected(true);
            portTextField.setText("7834");
            timeoutMsTextField.setText("600000");
            showNotificationsCheckbox.setSelected(true);
        } else {
            enableServerCheckbox.setSelected(config.enabled);
            portTextField.setText(String.valueOf(config.port));
            timeoutMsTextField.setText(String.valueOf(config.timeoutMs));
            showNotificationsCheckbox.setSelected(config.showNotifications);
        }
    }

    @Override
    public void apply() throws ConfigurationException {
        ToolConfig config = McpToolConfig.getInstance().getState();
        if (config == null) {
            return;
        }

        config.enabled = enableServerCheckbox.isSelected();

        try {
            int port = Integer.parseInt(portTextField.getText());
            if (port < 1 || port > 65535) {
                throw new ConfigurationException("Port must be between 1 and 65535");
            }
            config.port = port;
        } catch (NumberFormatException e) {
            throw new ConfigurationException("Invalid port number: " + portTextField.getText());
        }

        try {
            long timeout = Long.parseLong(timeoutMsTextField.getText());
            if (timeout < 0) {
                throw new ConfigurationException("Timeout must be non-negative");
            }
            config.timeoutMs = timeout;
        } catch (NumberFormatException e) {
            throw new ConfigurationException("Invalid timeout value: " + timeoutMsTextField.getText());
        }

        config.showNotifications = showNotificationsCheckbox.isSelected();

        handleSettingsChange(config);
    }

    private void handleSettingsChange(ToolConfig config) {
        HttpServerManager manager = ApplicationManager.getApplication().getService(HttpServerManager.class);

        if (config.enabled && !manager.isServiceEnabled()) {
            manager.start(config.port, config.timeoutMs);
        } else if (!config.enabled && manager.isServiceEnabled()) {
            manager.stop();
        } else if (manager.isServiceEnabled() && !manager.getPort().equals(String.valueOf(config.port))) {
            manager.restart(config.port, config.timeoutMs);
        } else if (manager.isServiceEnabled()) {
            manager.updateTimeout(config.timeoutMs);
        } else {
            NotificationManager.info("Configuration saved");
        }
    }

    @Override
    public void disposeUIResources() {
    }
}
