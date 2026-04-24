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
        return "TASK".equals(readString(frame, "msgType"));
    }

    public static boolean isControl(JsonObject frame) {
        return readString(frame, WorkerControlEventProtocol.EVENT_CODE_FIELD) != null;
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
        return frame != null && frame.has(WorkerControlEventProtocol.PAYLOAD_FIELD) && frame.get(WorkerControlEventProtocol.PAYLOAD_FIELD).isJsonObject()
                ? frame.getAsJsonObject(WorkerControlEventProtocol.PAYLOAD_FIELD)
                : new JsonObject();
    }

    public static String workerId(JsonObject frame) {
        String workerId = readString(frame, WorkerControlEventProtocol.WORKER_ID_FIELD);
        return workerId != null ? workerId : readNestedString(frame, "context", "workerId");
    }

    public static String taskId(JsonObject frame) {
        return readNestedString(frame, "context", "taskId");
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
