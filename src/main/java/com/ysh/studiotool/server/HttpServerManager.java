package com.ysh.studiotool.server;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.ysh.studiotool.notification.NotificationManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Service(Service.Level.APP)
public final class HttpServerManager implements Disposable {

    private volatile McpHttpHandler handler;
    private volatile boolean serviceEnabled = false;
    private String currentPort = null;
    private volatile long currentTimeoutMs = 600000L;
    private volatile boolean isOperationRunning = false;

    public long getCurrentTimeoutMs() {
        return currentTimeoutMs;
    }

    public boolean isServiceEnabled() {
        return serviceEnabled && this.handler != null;
    }

    public String getPort() {
        return this.currentPort != null ? this.currentPort : "-";
    }

    public boolean isOperationRunning() {
        return isOperationRunning;
    }

    public void setOperationRunning(boolean running) {
        this.isOperationRunning = running;
    }

    public void start(int port, long timeoutMs) {
//        stop();
//        killProcessOccupyingPort(port);
//        try {
//            this.currentPort = String.valueOf(port);
//            this.currentTimeoutMs = timeoutMs;
//
//            handler = new McpHttpHandler(port, this);
//            handler.start();
//
//            serviceEnabled = true;
//            NotificationManager.info("Server started on port " + port);
//
//        } catch (java.io.IOException e) {
//            serviceEnabled = false;
//            currentPort = null;
//            NotificationManager.error("Failed to bind HTTP server: port " + port + " in use");
//            NotificationManager.logError(e.getMessage());
//        } catch (Exception e) {
//            serviceEnabled = false;
//            currentPort = null;
//            NotificationManager.error("Failed to start server: " + e.getMessage());
//        }
    }

    public void stop() {
//        if (this.handler != null) {
//            try {
//                this.handler.stop();
//            } catch (Exception e) {
//                NotificationManager.error("Error stopping server: " + e.getMessage());
//            } finally {
//                this.handler = null;
//                serviceEnabled = false;
//                currentPort = null;
//            }
//            NotificationManager.info("Server stopped");
//        } else {
//            serviceEnabled = false;
//        }
    }

    public void restart(int port, long timeoutMs) {
        stop();
        start(port, timeoutMs);
    }

    public void updateTimeout(long newTimeoutMs) {
        this.currentTimeoutMs = newTimeoutMs;
        NotificationManager.info("Timeout updated to " + newTimeoutMs + "ms");
    }

    /**
     * 暴力清理端口的工具方法（兼容 Windows 和 Mac）
     */
    private void killProcessOccupyingPort(int port) {
        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder processBuilder = null;

        // 根据系统构建命令
        if (os.contains("win")) {
            // Windows 下查找并强杀的命令
            String cmd = "for /f \"tokens=5\" %a in ('netstat -aon ^| findstr :" + port + "') do taskkill /F /PID %a";
            NotificationManager.logInfo("cmd : " + cmd);
            processBuilder = new ProcessBuilder("cmd", "/c", cmd);
        } else {
            // Mac/Linux 下查找并强杀的命令
            String cmd = "lsof -t -i:" + port + " | xargs kill -9";
            NotificationManager.logInfo("cmd : " + cmd);
            processBuilder = new ProcessBuilder("/bin/sh", "-c", cmd);
        }

        try {
            // 1. 启动进程
            Process process = processBuilder.start();

            // 2. 读取标准输出 (Standard Output)
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // 3. 读取错误输出 (Error Output)
            StringBuilder errorOutput = new StringBuilder();
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                }
            }

            // 4. 等待命令执行完毕并获取退出码 (Exit Code)
            int exitCode = process.waitFor();

            // 5. 分析执行结果
            NotificationManager.logInfo("(Exit Code): " + exitCode);

            if (output.length() > 0) {
                NotificationManager.logInfo("output:" + output.toString().trim());
            }

            if (errorOutput.length() > 0) {
                NotificationManager.logInfo("errorOutput:" + errorOutput.toString().trim());
            }

        } catch (Exception e) {
            NotificationManager.logError("killProcessOccupyingPort" + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        stop();
    }
}
