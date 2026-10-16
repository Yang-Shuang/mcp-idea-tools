package com.ysh.studiotool.server;

import com.ysh.studiotool.executor.*;
import com.ysh.studiotool.notification.NotificationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class McpHttpHandler {

//    private final HttpServerManager serverManager;
//
//    private static final ObjectMapper mapper = new ObjectMapper();
//
//    static {
//        mapper.setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);
//    }
//
//    public McpHttpHandler(int port, HttpServerManager manager) {
//        super(port);
//        this.serverManager = manager;
//    }
//
//    @Override
//    public Response serve(IHTTPSession session) {
//        String method = session.getMethod().toString();
//        String uri = session.getUri();
//
//        NotificationManager.logInfo(method.toUpperCase() + " " + uri);
//
//        if ("/run".equals(uri)) {
//            return handleRunRequest();
//        } else if ("/build".equals(uri)) {
//            return handleBuildRequest();
//        } else if ("/sync".equals(uri)) {
//            return handleSyncRequest();
//        } else if ("/selectRunConfig".equals(uri)) {
//            return handleSelectRequest(session.getParameters());
//        } else if ("/state".equals(uri)) {
//            return handleGradleRequest();
//        }  else if ("/executeGradle".equals(uri)) {
//            return handleGradleTaskRequest(session.getParameters());
//        } else if ("/status".equals(uri)) {
//            return handleStatusRequest();
//        }
//
//        return newFixedLengthResponse(
//                Response.Status.NOT_FOUND,
//                MIME_PLAINTEXT,
//                toJson(BaseResult.failed(null, "Not found"))
//        );
//    }
//
//    private Response handleRunRequest() {
//        NotificationManager.info("Request : /run");
//        Response response = checkEnable();
//        if (response != null) {
//            return response;
//        }
//
//        try {
//            serverManager.setOperationRunning(true);
//
//            ExecutionResult result = McpToolExecutor.executeRun(serverManager.getCurrentTimeoutMs());
//            String json = toJson(result);
//
//            if (result.isSuccess()) {
//                return jsonResponse(Response.Status.OK, json);
//            } else {
//                return jsonResponse(Response.Status.INTERNAL_ERROR, json);
//            }
//        } catch (Exception e) {
//            return jsonResponse(Response.Status.INTERNAL_ERROR, toJson(BaseResult.failed(null, e.getMessage())));
//        } finally {
//            serverManager.setOperationRunning(false);
//        }
//    }
//
//    private Response handleBuildRequest() {
//        NotificationManager.info("Request : /build");
//        Response response = checkEnable();
//        if (response != null) {
//            return response;
//        }
//
//        try {
//            serverManager.setOperationRunning(true);
//
//            ExecutionResult result = McpToolExecutor.executeBuild(serverManager.getCurrentTimeoutMs());
//            String json = toJson(result);
//
//            if (result.isSuccess()) {
//                return jsonResponse(Response.Status.OK, json);
//            } else {
//                return jsonResponse(Response.Status.INTERNAL_ERROR, json);
//            }
//        } catch (Exception e) {
//            return jsonResponse(Response.Status.INTERNAL_ERROR, toJson(BaseResult.failed(null, e.getMessage())));
//        } finally {
//            serverManager.setOperationRunning(false);
//        }
//    }
//
//    private Response handleSyncRequest() {
//        NotificationManager.info("Request : /sync");
//        Response response = checkEnable();
//        if (response != null) {
//            return response;
//        }
//
//        try {
//            serverManager.setOperationRunning(true);
//
//            GradleInfoResult result = GradleExecutor.syncProject();
//            String json = toJson(result);
//
//            if (result.isSuccess()) {
//                return jsonResponse(Response.Status.OK, json);
//            } else {
//                return jsonResponse(Response.Status.INTERNAL_ERROR, json);
//            }
//        } catch (Exception e) {
//            return jsonResponse(Response.Status.INTERNAL_ERROR, toJson(BaseResult.failed(null, e.getMessage())));
//        } finally {
//            serverManager.setOperationRunning(false);
//        }
//    }
//
//    private Response handleGradleRequest() {
//        NotificationManager.info("Request : /state");
//        Response response = checkEnable();
//        if (response != null) {
//            return response;
//        }
//
//        try {
//            serverManager.setOperationRunning(true);
//            GradleInfoResult result = GradleExecutor.getGradleInfo();;
//            String json = toJson(result);
//
//            if (result.isSuccess()) {
//                return jsonResponse(Response.Status.OK, json);
//            } else {
//                return jsonResponse(Response.Status.INTERNAL_ERROR, json);
//            }
//        } catch (Exception e) {
//            return jsonResponse(Response.Status.INTERNAL_ERROR, toJson(BaseResult.failed(null, e.getMessage())));
//        } finally {
//            serverManager.setOperationRunning(false);
//        }
//    }
//
//    private Response handleSelectRequest(Map<String, List<String>> params) {
//        NotificationManager.info("Request : /selectRunConfig");
//        Response response = checkEnable();
//        if (response != null) {
//            return response;
//        }
//
//        List<String> configs = params.get("config");
//        if (configs == null || configs.isEmpty()) {
//            return jsonResponse(Response.Status.BAD_REQUEST, toJson(BaseResult.failed(null, "config not be null")));
//        }
//
//        try {
//            serverManager.setOperationRunning(true);
//            BaseResult result = McpToolExecutor.switchRunConfiguration(configs.get(0));;
//            String json = toJson(result);
//
//            if (result.isSuccess()) {
//                return jsonResponse(Response.Status.OK, json);
//            } else {
//                return jsonResponse(Response.Status.INTERNAL_ERROR, json);
//            }
//        } catch (Exception e) {
//            return jsonResponse(Response.Status.INTERNAL_ERROR, toJson(BaseResult.failed(null, e.getMessage())));
//        } finally {
//            serverManager.setOperationRunning(false);
//        }
//    }
//
//    private Response handleGradleTaskRequest(Map<String, List<String>> params) {
//        NotificationManager.info("Request : /executeGradle");
//        Response response = checkEnable();
//        if (response != null) {
//            return response;
//        }
//
//        List<String> tasks = params.get("tasks");
//        if (tasks == null || tasks.isEmpty()) {
//            return jsonResponse(Response.Status.BAD_REQUEST, toJson(BaseResult.failed(null, "tasks not be null")));
//        }
//        List<String> realTasks = new ArrayList<>();
//        for (String string : tasks) {
//            if (!TextUtils.isEmpty(string)) {
//                realTasks.add(string);
//            }
//        }
//
//        try {
//            serverManager.setOperationRunning(true);
//            GradleInfoResult result = GradleExecutor.runTasks(realTasks); // 新版api
////            GradleInfoResult result = GradleExecutor.executeGradleTaskWithLogs(realTasks);
//            String json = toJson(result);
//
//            if (result.isSuccess()) {
//                return jsonResponse(Response.Status.OK, json);
//            } else {
//                return jsonResponse(Response.Status.INTERNAL_ERROR, json);
//            }
//        } catch (Exception e) {
//            return jsonResponse(Response.Status.INTERNAL_ERROR, toJson(BaseResult.failed(null, e.getMessage())));
//        } finally {
//            serverManager.setOperationRunning(false);
//        }
//    }
//
//    private Response handleStatusRequest() {
//        String json = "{\"running\":" + (serverManager.isOperationRunning() ? "true" : "false")
//                + ",\"serviceEnabled\":" + (serverManager.isServiceEnabled() ? "true" : "false") + "}";
//
//        return jsonResponse(Response.Status.OK, json);
//    }
//
//    private Response checkEnable() {
//        if (!serverManager.isServiceEnabled()) {
//            return jsonResponse(Response.Status.SERVICE_UNAVAILABLE, toJson(BaseResult.failed(null, "Server not running")));
//        }
//
//        if (serverManager.isOperationRunning()) {
//            return jsonResponse(Response.Status.CONFLICT, toJson(BaseResult.failed(null, "Operation in progress")));
//        }
//        return null;
//    }
//
//    private Response jsonResponse(Response.Status status, String json) {
//        return newFixedLengthResponse(status, "application/json", json);
//    }
//
//    public static String toJson(Object obj) {
//        try {
//            return mapper.writeValueAsString(obj);
//        } catch (Exception e) {
//            return "{\"error\": \"序列化失败: " + e.getMessage() + "\"}";
//        }
//    }
}
