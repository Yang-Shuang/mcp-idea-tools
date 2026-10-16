package com.ysh.studiotool.mcp;

public final class McpServerBootstrap {

    private McpServerBootstrap() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7844;
        McpServerManager manager = new McpServerManager();
        manager.start(port);
        System.out.println("MCP server listening on " + manager.getEndpoint());
        Thread.currentThread().join();
    }
}
