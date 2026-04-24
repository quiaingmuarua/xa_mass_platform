package com.xa.mass.mock.testutil;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.gateway.session.SessionRoles;

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
                                           JsonObject payload) {
        JsonObject frame = baseFrame(msgId, "TASK", "step", false, "SERVER", project, workerId, taskId);
        frame.add("payload", payload != null ? payload : new JsonObject());
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
        JsonObject payload = new JsonObject();
        payload.addProperty("status", status);
        payload.addProperty("mockData", detail);
        if (errorCode != null) {
            payload.addProperty("errorCode", errorCode);
        }
        JsonObject frame = baseFrame(msgId, "TASK", "step", true, "CLIENT", project, workerId, taskId);
        frame.add("payload", payload);
        return GSON.toJson(frame);
    }

    public static String buildControlEventRequest(String msgId,
                                                  String project,
                                                  String workerId,
                                                  String eventCode,
                                                  String requestId,
                                                  JsonObject eventPayload) {
        JsonObject payload = new JsonObject();
        payload.addProperty("event", eventCode);
        if (requestId != null) {
            payload.addProperty("requestId", requestId);
        }
        payload.add("payload", eventPayload != null ? eventPayload : new JsonObject());
        JsonObject frame = baseFrame(msgId, "CONTROL", "event", false, "SERVER", project, workerId, null);
        frame.add("payload", payload);
        return GSON.toJson(frame);
    }

    public static boolean isTask(JsonObject frame) {
        return "TASK".equals(readString(frame, "msgType"));
    }

    public static boolean isControl(JsonObject frame) {
        return "CONTROL".equals(readString(frame, "msgType"));
    }

    public static boolean isResponse(JsonObject frame) {
        return frame != null
                && frame.has("response")
                && !frame.get("response").isJsonNull()
                && frame.get("response").getAsBoolean();
    }

    public static String msgId(JsonObject frame) {
        return readString(frame, "msgId");
    }

    public static String msgType(JsonObject frame) {
        return readString(frame, "msgType");
    }

    public static String subMsgType(JsonObject frame) {
        return readString(frame, "subMsgType");
    }

    public static String project(JsonObject frame) {
        return readString(frame, "project");
    }

    public static JsonObject context(JsonObject frame) {
        return frame != null && frame.has("context") && frame.get("context").isJsonObject()
                ? frame.getAsJsonObject("context")
                : new JsonObject();
    }

    public static JsonObject payload(JsonObject frame) {
        return frame != null && frame.has("payload") && frame.get("payload").isJsonObject()
                ? frame.getAsJsonObject("payload")
                : new JsonObject();
    }

    public static String workerId(JsonObject frame) {
        return readNestedString(frame, "context", "workerId");
    }

    public static String taskId(JsonObject frame) {
        return readNestedString(frame, "context", "taskId");
    }

    public static String connRole(JsonObject frame) {
        return readNestedString(frame, "context", "connRole");
    }

    public static int ackCode(JsonObject frame) {
        return payload(frame).get("code").getAsInt();
    }

    public static String ackMessage(JsonObject frame) {
        return readString(payload(frame), "message");
    }

    public static JsonObject payloadFromMap(Map<String, ?> map) {
        return GSON.toJsonTree(map).getAsJsonObject();
    }

    private static JsonObject baseFrame(String msgId,
                                        String msgType,
                                        String subMsgType,
                                        boolean response,
                                        String from,
                                        String project,
                                        String workerId,
                                        String taskId) {
        JsonObject frame = new JsonObject();
        frame.addProperty("msgId", msgId);
        frame.addProperty("response", response);
        frame.addProperty("msgType", msgType);
        frame.addProperty("subMsgType", subMsgType);
        frame.addProperty("from", from);
        if (project != null) {
            frame.addProperty("project", project);
        }
        JsonObject context = new JsonObject();
        context.addProperty("workerId", workerId);
        context.addProperty("connRole", SessionRoles.TASK_MESSAGES);
        if (taskId != null) {
            context.addProperty("taskId", taskId);
        }
        frame.add("context", context);
        return frame;
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
}
