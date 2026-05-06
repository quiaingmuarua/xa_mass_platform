package com.xa.mass.transport.socket.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.transport.model.TaskDispatchWireView;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.packet.TransportPacketViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Adapter-local line-delimited JSON codec for the socket adapter.
 */
public final class SocketTransportFrameCodec {

    private static final Logger logger = LoggerFactory.getLogger(SocketTransportFrameCodec.class);
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final Gson gson;

    public SocketTransportFrameCodec() {
        this(new GsonBuilder().create());
    }

    public SocketTransportFrameCodec(Gson gson) {
        this.gson = gson;
    }

    public JsonObject parseObject(String json) {
        try {
            JsonElement element = gson.fromJson(json, JsonElement.class);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonSyntaxException ex) {
            logger.warn("Failed to parse socket JSON frame", ex);
            return null;
        }
    }

    public boolean isHelloFrame(JsonObject frame) {
        return "hello".equalsIgnoreCase(readString(frame, "type"))
                && readString(frame, "workerId") != null;
    }

    public boolean isHeartbeatFrame(JsonObject frame) {
        return "heartbeat".equalsIgnoreCase(readString(frame, "type"));
    }

    public String extractWorkerId(JsonObject frame) {
        return readString(frame, "workerId");
    }

    public String extractTraceId(JsonObject frame) {
        return readString(frame, "traceId");
    }

    public boolean isCanonicalTaskResult(JsonObject frame) {
        return frame != null
                && readString(frame, "eventCode") == null
                && readString(frame, "taskId") != null
                && readString(frame, "messageId") != null
                && hasBoolean(frame, "success");
    }

    public String encodeCanonicalTaskDispatch(TransportPacket packet) {
        TaskDispatchWireView view = TransportPacketViews.dispatchWireView(packet);
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", view.messageId());
        frame.addProperty("workerId", view.workerId());
        if (view.project() != null) {
            frame.addProperty("project", view.project());
        }
        if (view.eventCode() != null) {
            frame.addProperty("eventCode", view.eventCode());
        }
        frame.addProperty("taskId", view.taskId());
        if (view.taskName() != null) {
            frame.addProperty("taskName", view.taskName());
        }
        if (view.userId() != null) {
            frame.addProperty("userId", view.userId());
        }
        frame.addProperty("retryCount", view.retryCount());
        if (view.workerContextId() != null) {
            frame.addProperty("workerContextId", view.workerContextId());
        }
        if (view.batchId() != null) {
            frame.addProperty("batchId", view.batchId());
        }
        frame.add("input", gson.toJsonTree(view.input() != null ? view.input() : Map.of()));
        frame.add("sharedConfig", gson.toJsonTree(view.sharedConfig() != null ? view.sharedConfig() : Map.of()));
        return gson.toJson(frame);
    }

    public TaskResultReport decodeCanonicalTaskResult(JsonObject frame) {
        String taskId = readString(frame, "taskId");
        String messageId = readString(frame, "messageId");
        if (taskId == null || messageId == null) {
            throw new IllegalArgumentException("taskId/messageId are required");
        }
        Boolean success = readBoolean(frame, "success");
        if (success == null) {
            throw new IllegalArgumentException("success is required");
        }
        String detail = firstNonBlank(
                readString(frame, "detail"),
                readString(frame, "message")
        );
        return new TaskResultReport(
                taskId,
                messageId,
                success,
                detail,
                readString(frame, "errorCode"),
                gson.fromJson(readJsonObject(frame, "output"), MAP_TYPE)
        );
    }

    private JsonObject readJsonObject(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return new JsonObject();
        }
        JsonElement element = object.get(field);
        return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private Boolean readBoolean(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsBoolean();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasBoolean(JsonObject object, String field) {
        return readBoolean(object, field) != null;
    }

    private String readString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            String value = object.get(field).getAsString();
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
