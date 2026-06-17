package com.xa.mass.workerpack.testutil;

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

    public static String buildTaskDispatch(String resultCorrelationRef, JsonObject input) {
        return buildTaskDispatch(resultCorrelationRef, "mock.task.dispatch", input);
    }

    public static String buildTaskDispatch(String resultCorrelationRef, String eventCode, JsonObject input) {
        JsonObject frame = new JsonObject();
        frame.addProperty("resultCorrelationRef", resultCorrelationRef);
        frame.addProperty("eventCode", eventCode);
        frame.add("input", input != null ? input : new JsonObject());
        frame.add("sharedConfig", new JsonObject());
        return GSON.toJson(frame);
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
        JsonObject frame = new JsonObject();
        frame.addProperty("resultCorrelationRef", resultCorrelationRef);
        frame.addProperty("success", "SUCCESS".equalsIgnoreCase(status));
        output.addProperty("detail", detail);
        if (errorCode != null) {
            frame.addProperty("resultCode", errorCode);
        }
        frame.addProperty("result", GSON.toJson(output));
        return GSON.toJson(frame);
    }

    public static boolean isTask(JsonObject frame) {
        return frame != null
                && readString(frame, "resultCorrelationRef") != null
                && !isResponse(frame)
                && !hasBoolean(frame, "success");
    }

    public static boolean isResponse(JsonObject frame) {
        return frame != null
                && frame.has("response")
                && !frame.get("response").isJsonNull()
                && frame.get("response").getAsBoolean();
    }

    public static String resultCorrelationRef(JsonObject frame) {
        return readString(frame, "resultCorrelationRef");
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
        if (frame != null && frame.has("result") && !frame.get("result").isJsonNull()) {
            try {
                return GSON.fromJson(frame.get("result").getAsString(), JsonObject.class);
            } catch (Exception ignored) {
                return new JsonObject();
            }
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
}
