package com.xa.mass.testing.chaos.support;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.net.ServerSocket;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class ChaosSupport {

    private static final Gson GSON = new GsonBuilder().create();

    private ChaosSupport() {
    }

    public static void waitForCondition(BooleanSupplier condition,
                                        int timeoutSeconds,
                                        String failureMessage) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50L);
        }
        require(condition.getAsBoolean(), failureMessage);
    }

    public static void maybeSleep(int processingDelayMillis) {
        if (processingDelayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(processingDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static JsonObject parseFrame(String message) {
        try {
            return GSON.fromJson(message, JsonObject.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean isTaskDispatchFrame(JsonObject frame) {
        return frame != null
                && readString(frame, "taskId") != null
                && readString(frame, "messageId") != null
                && !hasBoolean(frame, "success")
                && !isResponseFrame(frame);
    }

    public static boolean isResponseFrame(JsonObject frame) {
        return frame != null
                && frame.has("response")
                && !frame.get("response").isJsonNull()
                && frame.get("response").getAsBoolean();
    }

    public static String buildTaskResult(JsonObject taskFrame,
                                         boolean success,
                                         String detail,
                                         Map<String, Object> output) {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", readString(taskFrame, "messageId"));
        frame.addProperty("workerId", readString(taskFrame, "workerId"));
        frame.addProperty("taskId", readString(taskFrame, "taskId"));
        frame.addProperty("project", readString(taskFrame, "project"));
        frame.addProperty("success", success);
        frame.addProperty("detail", detail);
        frame.add("output", GSON.toJsonTree(output != null ? output : Map.of()));
        return GSON.toJson(frame);
    }

    public static String readString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static URI appendWorkerId(URI serverUri, String workerId) {
        String existingQuery = serverUri.getRawQuery();
        String workerQuery = "workerId=" + workerId.trim();
        String mergedQuery = (existingQuery == null || existingQuery.isBlank())
                ? workerQuery
                : existingQuery + "&" + workerQuery;
        try {
            return new URI(
                    serverUri.getScheme(),
                    serverUri.getRawAuthority(),
                    serverUri.getRawPath(),
                    mergedQuery,
                    serverUri.getRawFragment()
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to append workerId to serverUri", ex);
        }
    }

    public static int intProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }

    public static long longProperty(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(raw.trim());
    }

    public static boolean booleanProperty(String key, boolean defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    public static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    public static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to allocate a free transport port", e);
        }
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    public static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    public static String timestampSuffix() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT));
    }

    private static boolean hasBoolean(JsonObject frame, String field) {
        if (frame == null || !frame.has(field) || frame.get(field).isJsonNull()) {
            return false;
        }
        try {
            frame.get(field).getAsBoolean();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
