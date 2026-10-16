package com.ysh.studiotool.mcp;

import com.ysh.studiotool.notification.NotificationManager;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

public final class McpServerManager {

    public static final String MCP_ENDPOINT = "/mcp";
    public static final String BIND_HOST = "127.0.0.1";

    private static final String SERVER_NAME = "idea-mcp-tools";
    private static final String SERVER_VERSION = "1.0.0";

    public static final String INSTRUCTIONS = "控制 IntelliJ IDEA / Android Studio 的 MCP 工具集，适用于大型 Android 项目（Java + Gradle）。\n 【推荐工作流】\n " +
            "1. 先调用 studio_get_state 了解项目状态：项目名、Gradle 配置、运行配置列表（runConfigs）、可用 Gradle 任务（tasks）。\n" +
            "2. 运行 App：studio_select_config 选择配置（名称取自 runConfigs）-> studio_run_default 运行。\n" +
            "3. 修改了 build.gradle / settings.gradle / gradle.properties / 依赖后：先 studio_sync，再执行构建或运行。\n" +
            "4. 编译验证代码改动：studio_build。\n" +
            "5. 执行特定 Gradle 任务：studio_execute_tasks（任务名取自 studio_get_state 返回的 tasks）。";
    // 显式注入，绕开 SDK 的 ServiceLoader（其依赖 TCCL，在 IDE 插件的类加载器隔离下找不到 SPI 实现）
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final McpJsonMapper MCP_JSON_MAPPER = new JacksonMcpJsonMapper(JSON_MAPPER);
    private static final JsonSchemaValidator JSON_SCHEMA_VALIDATOR = new DefaultJsonSchemaValidator(JSON_MAPPER);

    private Server jettyServer;
    private McpSyncServer mcpServer;
    private HttpServletStreamableServerTransportProvider transportProvider;
    private int boundPort = -1;

    public synchronized void start(int port) throws Exception {
        if (jettyServer != null) {
            stop();
        }

        ClassLoader pluginClassLoader = McpServerManager.class.getClassLoader();
        ClassLoader previousTcl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(pluginClassLoader);
        try {
            transportProvider = HttpServletStreamableServerTransportProvider.builder()
                    .mcpEndpoint(MCP_ENDPOINT)
                    .jsonMapper(MCP_JSON_MAPPER)
                    .build();

            mcpServer = McpServer.sync(transportProvider)
                    .serverInfo(SERVER_NAME, SERVER_VERSION)
                    .instructions(INSTRUCTIONS)
                    .jsonMapper(MCP_JSON_MAPPER)
                    .jsonSchemaValidator(JSON_SCHEMA_VALIDATOR)
                    .tools(StudioMcpTools.toolSpecifications())
                    .build();

            QueuedThreadPool threadPool = new QueuedThreadPool(20);
            threadPool.setName("mcp-jetty");

            jettyServer = new Server(threadPool);

            ServerConnector connector = new ServerConnector(jettyServer);
            connector.setHost(BIND_HOST);
            connector.setPort(port);
            connector.setIdleTimeout(0L);
            jettyServer.addConnector(connector);

            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            context.setClassLoader(pluginClassLoader);
            ServletHolder holder = new ServletHolder("mcp", transportProvider);
            holder.setAsyncSupported(true);
            context.addServlet(holder, MCP_ENDPOINT);

            // 记录完整 JSON-RPC 请求报文（含 jsonrpc/id/method/params），便于排查 Agent 调用；
            // 请求体缓存后交给下游，不影响 SDK 正常解析
            FilterHolder rawLogFilter = new FilterHolder((request, response, chain) -> {
                if (request instanceof HttpServletRequest httpRequest) {
                    byte[] body = httpRequest.getInputStream().readAllBytes();
                    if (body.length > 0) {
                        NotificationManager.logInfo("MCP raw request: " + new String(body, StandardCharsets.UTF_8));
                    }
                    chain.doFilter(new CachedBodyRequest(httpRequest, body), response);
                    return;
                }
                chain.doFilter(request, response);
            });
            // Streamable 传输依赖异步，Filter 链必须声明支持 async，否则 Jetty 会拒绝
            rawLogFilter.setAsyncSupported(true);
            context.addFilter(rawLogFilter, MCP_ENDPOINT, EnumSet.of(DispatcherType.REQUEST));

            jettyServer.setHandler(context);

            jettyServer.start();
            boundPort = port;
            NotificationManager.logInfo("MCP server started at " + getEndpoint());
        } catch (Exception e) {
            cleanup();
            throw e;
        } finally {
            Thread.currentThread().setContextClassLoader(previousTcl);
        }
    }

    public synchronized void stop() {
        if (jettyServer == null && mcpServer == null && transportProvider == null) {
            return;
        }
        ClassLoader previousTcl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(McpServerManager.class.getClassLoader());
        try {
            cleanup();
            NotificationManager.logInfo("MCP server stopped");
        } finally {
            Thread.currentThread().setContextClassLoader(previousTcl);
        }
    }

    public synchronized boolean isRunning() {
        return jettyServer != null && jettyServer.isStarted();
    }

    public synchronized int getPort() {
        return boundPort;
    }

    public synchronized String getEndpoint() {
        return boundPort > 0 ? "http://" + BIND_HOST + ":" + boundPort + MCP_ENDPOINT : null;
    }

    private void cleanup() {
        Server server = jettyServer;
        McpSyncServer syncServer = mcpServer;
        jettyServer = null;
        mcpServer = null;
        transportProvider = null;
        boundPort = -1;

        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                NotificationManager.logInfo("Error stopping Jetty: " + e.getMessage());
            }
            try {
                server.destroy();
            } catch (Exception e) {
                NotificationManager.logInfo("Error destroying Jetty: " + e.getMessage());
            }
        }
        if (syncServer != null) {
            try {
                syncServer.close();
            } catch (Exception e) {
                NotificationManager.logInfo("Error closing MCP server: " + e.getMessage());
            }
        }
    }

    /**
     * 缓存请求体的包装：Filter 中已消费原始流，下游通过包装类重复读取缓存的字节
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                }

                @Override
                public int read() {
                    return buffer.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
