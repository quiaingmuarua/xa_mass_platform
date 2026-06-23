package com.xa.mass.workerpack.testutil;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;

public final class WsFrameTestSupport {

    private static final Gson GSON = new Gson();
    private static final String ACTION = "ACTION";
    private static final String ACTION_REPLY = "ACTION_REPLY";

    private WsFrameTestSupport() {
    }

    public static JsonObject parse(String rawJson) {
        return GSON.fromJson(rawJson, JsonObject.class);
    }

    public static String buildTaskDispatch(String replyRef, JsonObject input) {
        return buildTaskDispatch(replyRef, "mock.task.dispatch", input);
    }

    public static String buildTaskDispatch(String replyRef, String eventCode, JsonObject input) {
        JsonObject action = new JsonObject();
        action.addProperty("actionId", "action-" + replyRef);
        action.addProperty("replyRef", replyRef);
        action.addProperty("eventCode", eventCode);
        action.addProperty("body", GSON.toJson(input != null ? input : new JsonObject()));
        action.add("sharedConfig", new JsonObject());
        return channelFrame(ACTION, action);
    }

    public static String buildTaskResult(String replyRef, String status, String detail) {
        return buildTaskResult(replyRef, status, detail, null);
    }

    public static String buildTaskResult(String replyRef,
                                         String status,
                                         String detail,
                                         String errorCode) {
        JsonObject output = new JsonObject();
        output.addProperty("status", status);
        output.addProperty("mockData", detail);
        if (errorCode != null) {
            output.addProperty("errorCode", errorCode);
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("replyRef", replyRef);
        reply.addProperty("success", "SUCCESS".equalsIgnoreCase(status));
        output.addProperty("detail", detail);
        if (errorCode != null) {
            reply.addProperty("code", errorCode);
        }
        reply.addProperty("body", GSON.toJson(output));
        return channelFrame(ACTION_REPLY, reply);
    }

    public static boolean isTask(JsonObject frame) {
        JsonObject action = bodyObject(frame);
        return ACTION.equals(readString(frame, "kind"))
                && readString(action, "replyRef") != null
                && readString(action, "eventCode") != null;
    }

    public static boolean isResponse(JsonObject frame) {
        return frame != null
                && frame.has("response")
                && !frame.get("response").isJsonNull()
                && frame.get("response").getAsBoolean();
    }

    public static String replyRef(JsonObject frame) {
        return readString(bodyObject(frame), "replyRef");
    }

    public static String project(JsonObject frame) {
        return readString(frame, "project");
    }

    public static JsonObject payload(JsonObject frame) {
        JsonObject message = bodyObject(frame);
        if (message.has("body") && !message.get("body").isJsonNull()) {
            try {
                return GSON.fromJson(message.get("body").getAsString(), JsonObject.class);
            } catch (Exception ignored) {
                return new JsonObject();
            }
        }
        return new JsonObject();
    }

    public static String workerId(JsonObject frame) {
        return readString(frame, "workerId");
    }

    public static JsonObject message(JsonObject frame) {
        return bodyObject(frame);
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

    private static String channelFrame(String kind, JsonObject body) {
        JsonObject frame = new JsonObject();
        frame.addProperty("frameId", "frame-" + readString(body, "replyRef"));
        frame.addProperty("kind", kind);
        frame.addProperty("body", GSON.toJson(body));
        return GSON.toJson(frame);
    }

    private static JsonObject bodyObject(JsonObject frame) {
        if (frame == null || !frame.has("body") || frame.get("body").isJsonNull()) {
            return new JsonObject();
        }
        try {
            JsonObject body = GSON.fromJson(frame.get("body").getAsString(), JsonObject.class);
            return body == null ? new JsonObject() : body;
        } catch (Exception ignored) {
            return new JsonObject();
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
