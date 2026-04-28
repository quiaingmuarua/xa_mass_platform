package com.xa.mass.server.testutil;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;

public final class WsFrameTestSupport {

    private static final Gson GSON = new Gson();

    private WsFrameTestSupport() {
    }

    public static JsonObject parse(String rawJson) {
        return GSON.fromJson(rawJson, JsonObject.class);
    }

    public static String buildTaskDispatch(String messageId,
                                           String project,
                                           String workerId,
                                           String taskId,
                                           JsonObject input) {
        return buildTaskDispatch(messageId, project, workerId, taskId, "mock.task.dispatch", input);
    }

    public static String buildTaskDispatch(String messageId,
                                           String project,
                                           String workerId,
                                           String taskId,
                                           String eventCode,
                                           JsonObject input) {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", messageId);
        frame.addProperty("workerId", workerId);
        frame.addProperty("project", project);
        frame.addProperty("eventCode", eventCode);
        frame.addProperty("taskId", taskId);
        frame.addProperty("taskName", "mock-task");
        frame.addProperty("retryCount", 0);
        frame.add("input", input != null ? input : new JsonObject());
        frame.add("sharedConfig", new JsonObject());
        return GSON.toJson(frame);
    }

    public static String buildTaskResult(String messageId,
                                         String project,
                                         String workerId,
                                         String taskId,
                                         String status,
                                         String detail) {
        return buildTaskResult(messageId, project, workerId, taskId, status, detail, null);
    }

    public static String buildTaskResult(String messageId,
                                         String project,
                                         String workerId,
                                         String taskId,
                                         String status,
                                         String detail,
                                         String errorCode) {
        JsonObject output = new JsonObject();
        output.addProperty("status", status);
        output.addProperty("mockData", detail);
        if (errorCode != null) {
            output.addProperty("errorCode", errorCode);
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", messageId);
        frame.addProperty("workerId", workerId);
        frame.addProperty("taskId", taskId);
        frame.addProperty("project", project);
        frame.addProperty("success", "SUCCESS".equalsIgnoreCase(status));
        frame.addProperty("detail", detail);
        if (errorCode != null) {
            frame.addProperty("errorCode", errorCode);
        }
        frame.add("output", output);
        return GSON.toJson(frame);
    }

    public static boolean isTask(JsonObject frame) {
        return frame != null
                && readString(frame, "taskId") != null
                && !isResponse(frame)
                && !hasBoolean(frame, "success");
    }

    public static boolean isResponse(JsonObject frame) {
        return frame != null
                && frame.has("response")
                && !frame.get("response").isJsonNull()
                && frame.get("response").getAsBoolean();
    }

    public static String messageId(JsonObject frame) {
        return readString(frame, "messageId");
    }

    public static String project(JsonObject frame) {
        return readString(frame, "project");
    }

    public static JsonObject payload(JsonObject frame) {
        if (frame != null && frame.has("input") && frame.get("input").isJsonObject()) {
            return frame.getAsJsonObject("input");
        }
        if (frame != null && frame.has("output") && frame.get("output").isJsonObject()) {
            return frame.getAsJsonObject("output");
        }
        return new JsonObject();
    }

    public static String workerId(JsonObject frame) {
        return readString(frame, "workerId");
    }

    public static String taskId(JsonObject frame) {
        return readString(frame, "taskId");
    }

    public static JsonObject payloadFromMap(Map<String, ?> map) {
        return GSON.toJsonTree(map).getAsJsonObject();
    }

    private static String readString(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasBoolean(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return false;
        }
        try {
            object.get(field).getAsBoolean();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
