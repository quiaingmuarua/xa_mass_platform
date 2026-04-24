package com.xa.mass.mock.testutil;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;

import java.util.Map;

public final class WsFrameTestSupport {

    private static final Gson GSON = new Gson();

    private WsFrameTestSupport() {
    }

    public static JsonObject parse(String rawJson) {
        return GSON.fromJson(rawJson, JsonObject.class);
    }

    public static String buildTaskDispatch(String msgId,
                                           String project,
                                           String workerId,
                                           String taskId,
                                           JsonObject input) {
        JsonObject frame = new JsonObject();
        frame.addProperty(WorkerControlEventProtocol.MESSAGE_ID_FIELD, msgId);
        frame.addProperty(WorkerControlEventProtocol.WORKER_ID_FIELD, workerId);
        frame.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, project);
        frame.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, "mock.task.dispatch");
        frame.addProperty("taskId", taskId);
        frame.addProperty("taskName", "mock-task");
        frame.addProperty("retryCount", 0);
        frame.add("input", input != null ? input : new JsonObject());
        frame.add("sharedConfig", new JsonObject());
        return GSON.toJson(frame);
    }

    public static String buildTaskResult(String msgId,
                                         String project,
                                         String workerId,
                                         String taskId,
                                         String status,
                                         String detail) {
        return buildTaskResult(msgId, project, workerId, taskId, status, detail, null);
    }

    public static String buildTaskResult(String msgId,
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
        frame.addProperty("messageId", msgId);
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

    public static String buildControlEventRequest(String msgId,
                                                  String project,
                                                  String workerId,
                                                  String eventCode,
                                                  String requestId,
                                                  JsonObject eventPayload) {
        JsonObject frame = new JsonObject();
        frame.addProperty(WorkerControlEventProtocol.MESSAGE_ID_FIELD, msgId);
        frame.addProperty(WorkerControlEventProtocol.RESPONSE_FIELD, false);
        frame.addProperty(WorkerControlEventProtocol.WORKER_ID_FIELD, workerId);
        frame.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, project);
        frame.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode);
        if (requestId != null) {
            frame.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, requestId);
        }
        frame.add(WorkerControlEventProtocol.HEADERS_FIELD, new JsonObject());
        frame.add(WorkerControlEventProtocol.PAYLOAD_FIELD, eventPayload != null ? eventPayload : new JsonObject());
        frame.add(WorkerControlEventProtocol.PRINCIPAL_FIELD, new JsonObject());
        return GSON.toJson(frame);
    }

    public static boolean isTask(JsonObject frame) {
        return frame != null
                && readString(frame, "taskId") != null
                && !isResponse(frame)
                && !hasBoolean(frame, "success");
    }

    public static boolean isControl(JsonObject frame) {
        return frame != null
                && readString(frame, WorkerControlEventProtocol.EVENT_CODE_FIELD) != null
                && readString(frame, "taskId") == null;
    }

    public static boolean isResponse(JsonObject frame) {
        return frame != null
                && frame.has("response")
                && !frame.get("response").isJsonNull()
                && frame.get("response").getAsBoolean();
    }

    public static String msgId(JsonObject frame) {
        String messageId = readString(frame, WorkerControlEventProtocol.MESSAGE_ID_FIELD);
        return messageId != null ? messageId : readString(frame, "msgId");
    }

    public static String project(JsonObject frame) {
        return readString(frame, "project");
    }

    public static JsonObject payload(JsonObject frame) {
        if (frame != null && frame.has(WorkerControlEventProtocol.PAYLOAD_FIELD) && frame.get(WorkerControlEventProtocol.PAYLOAD_FIELD).isJsonObject()) {
            return frame.getAsJsonObject(WorkerControlEventProtocol.PAYLOAD_FIELD);
        }
        if (frame != null && frame.has("input") && frame.get("input").isJsonObject()) {
            return frame.getAsJsonObject("input");
        }
        if (frame != null && frame.has("output") && frame.get("output").isJsonObject()) {
            return frame.getAsJsonObject("output");
        }
        return new JsonObject();
    }

    public static String workerId(JsonObject frame) {
        String workerId = readString(frame, WorkerControlEventProtocol.WORKER_ID_FIELD);
        return workerId != null ? workerId : readNestedString(frame, "context", "workerId");
    }

    public static String taskId(JsonObject frame) {
        String rootTaskId = readString(frame, "taskId");
        return rootTaskId != null ? rootTaskId : readNestedString(frame, "context", "taskId");
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

    private static String readNestedString(JsonObject object, String nestedField, String field) {
        if (object == null || nestedField == null || !object.has(nestedField) || !object.get(nestedField).isJsonObject()) {
            return null;
        }
        return readString(object.getAsJsonObject(nestedField), field);
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
