package com.xa.mass.server.testutil;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.UUID;

public final class WsFrameTestSupport {

    private static final Gson GSON = new Gson();

    private WsFrameTestSupport() {
    }

    public static JsonObject parse(String rawJson) {
        return GSON.fromJson(rawJson, JsonObject.class);
    }

    public static String buildTaskDispatch(String resultCorrelationRef, JsonObject input) {
        return buildTaskDispatch(resultCorrelationRef, "mock.task.dispatch", input);
    }

    public static String buildTaskDispatch(String resultCorrelationRef, String eventCode, JsonObject input) {
        JsonObject body = new JsonObject();
        body.addProperty("replyRef", resultCorrelationRef);
        body.addProperty("eventCode", eventCode);
        body.addProperty("body", GSON.toJson(input != null ? input : new JsonObject()));
        body.add("sharedConfig", new JsonObject());
        return workerChannelFrame("ACTION", body);
    }

    public static String buildTaskResult(String resultCorrelationRef, String status, String detail) {
        return buildTaskResult(resultCorrelationRef, status, detail, null);
    }

    public static String buildTaskResult(String resultCorrelationRef,
                                         String status,
                                         String detail,
                                         String errorCode) {
        JsonObject output = new JsonObject();
        output.addProperty("status", status);
        output.addProperty("mockData", detail);
        if (errorCode != null) {
            output.addProperty("errorCode", errorCode);
        }
        JsonObject body = new JsonObject();
        body.addProperty("replyRef", resultCorrelationRef);
        body.addProperty("success", "SUCCESS".equalsIgnoreCase(status));
        if (errorCode != null) {
            body.addProperty("code", errorCode);
        } else if (!"SUCCESS".equalsIgnoreCase(status)) {
            body.addProperty("code", "MOCK_TASK_FAILED");
        }
        body.addProperty("body", GSON.toJson(output));
        return workerChannelFrame("ACTION_REPLY", body);
    }

    public static boolean isTask(JsonObject frame) {
        if (frame == null || isResponse(frame)) {
            return false;
        }
        JsonObject action = actionBody(frame);
        if (action != null) {
            return readString(action, "replyRef") != null
                    && readString(action, "eventCode") != null;
        }
        return readString(frame, "resultCorrelationRef") != null
                && !hasBoolean(frame, "success");
    }

    public static boolean isResponse(JsonObject frame) {
        return isWorkerChannelKind(frame, "ACTION_REPLY")
                || frame != null
                && frame.has("response")
                && !frame.get("response").isJsonNull()
                && frame.get("response").getAsBoolean();
    }

    public static String resultCorrelationRef(JsonObject frame) {
        String legacy = readString(frame, "resultCorrelationRef");
        if (legacy != null) {
            return legacy;
        }
        JsonObject action = actionBody(frame);
        if (action != null) {
            return readString(action, "replyRef");
        }
        JsonObject reply = actionReplyBody(frame);
        return reply == null ? null : readString(reply, "replyRef");
    }

    public static String project(JsonObject frame) {
        return readString(frame, "project");
    }

    public static JsonObject payload(JsonObject frame) {
        JsonObject action = actionBody(frame);
        if (action != null) {
            JsonObject body = readJsonStringObject(action, "body");
            if (!body.entrySet().isEmpty()) {
                return body;
            }
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
        return readString(frame, "workerId");
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

    private static String workerChannelFrame(String kind, JsonObject body) {
        JsonObject frame = new JsonObject();
        frame.addProperty("frameId", UUID.randomUUID().toString());
        frame.addProperty("kind", kind);
        frame.addProperty("body", GSON.toJson(body == null ? new JsonObject() : body));
        return GSON.toJson(frame);
    }

    private static boolean isWorkerChannelKind(JsonObject frame, String kind) {
        return frame != null && kind.equals(readString(frame, "kind"));
    }

    private static JsonObject actionBody(JsonObject frame) {
        if (!isWorkerChannelKind(frame, "ACTION")) {
            return null;
        }
        return readJsonStringObject(frame, "body");
    }

    private static JsonObject actionReplyBody(JsonObject frame) {
        if (!isWorkerChannelKind(frame, "ACTION_REPLY")) {
            return null;
        }
        return readJsonStringObject(frame, "body");
    }

    private static JsonObject readJsonStringObject(JsonObject object, String field) {
        String rawJson = readString(object, field);
        if (rawJson == null) {
            return new JsonObject();
        }
        try {
            JsonElement element = GSON.fromJson(rawJson, JsonElement.class);
            return element != null && element.isJsonObject()
                    ? element.getAsJsonObject()
                    : new JsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }
}
