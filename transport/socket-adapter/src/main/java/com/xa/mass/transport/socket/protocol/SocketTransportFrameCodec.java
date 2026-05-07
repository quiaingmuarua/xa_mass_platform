package com.xa.mass.transport.socket.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.packet.TransportPacket;
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
                && readString(frame, TransportPacket.PAYLOAD_WORKER_ID) != null;
    }

    public boolean isHeartbeatFrame(JsonObject frame) {
        return "heartbeat".equalsIgnoreCase(readString(frame, "type"));
    }

    public String extractWorkerId(JsonObject frame) {
        return readString(frame, TransportPacket.PAYLOAD_WORKER_ID);
    }

    public String extractRouteKey(JsonObject frame) {
        return readString(frame, "routeKey");
    }

    public String extractTraceId(JsonObject frame) {
        return readString(frame, "traceId");
    }

    public boolean isCanonicalTaskResult(JsonObject frame) {
        return frame != null
                && readString(frame, "eventCode") == null
                && readString(frame, "taskId") != null
                && readString(frame, "messageId") != null
                && hasBoolean(frame, TransportPacket.PAYLOAD_SUCCESS);
    }

    public String encodeCanonicalTaskDispatch(TransportPacket packet) {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", packet.messageId());
        put(frame, TransportPacket.PAYLOAD_WORKER_ID, packet.payloadString(TransportPacket.PAYLOAD_WORKER_ID));
        put(frame, TransportPacket.PAYLOAD_PROJECT, packet.payloadString(TransportPacket.PAYLOAD_PROJECT));
        if (packet.eventCode() != null) {
            frame.addProperty("eventCode", packet.eventCode());
        }
        frame.addProperty("taskId", packet.taskId());
        put(frame, TransportPacket.PAYLOAD_TASK_NAME, packet.payloadString(TransportPacket.PAYLOAD_TASK_NAME));
        put(frame, TransportPacket.PAYLOAD_USER_ID, packet.payloadString(TransportPacket.PAYLOAD_USER_ID));
        frame.addProperty(TransportPacket.PAYLOAD_RETRY_COUNT, packet.payloadInt(TransportPacket.PAYLOAD_RETRY_COUNT));
        put(frame, TransportPacket.PAYLOAD_WORKER_CONTEXT_ID, packet.payloadString(TransportPacket.PAYLOAD_WORKER_CONTEXT_ID));
        put(frame, TransportPacket.PAYLOAD_BATCH_ID, packet.payloadString(TransportPacket.PAYLOAD_BATCH_ID));
        frame.add(TransportPacket.PAYLOAD_INPUT, gson.toJsonTree(packet.payloadObject(TransportPacket.PAYLOAD_INPUT)));
        frame.add(TransportPacket.PAYLOAD_SHARED_CONFIG, gson.toJsonTree(packet.payloadObject(TransportPacket.PAYLOAD_SHARED_CONFIG)));
        return gson.toJson(frame);
    }

    public TaskResultReport decodeCanonicalTaskResult(JsonObject frame) {
        String taskId = readString(frame, "taskId");
        String messageId = readString(frame, "messageId");
        if (taskId == null || messageId == null) {
            throw new IllegalArgumentException("taskId/messageId are required");
        }
        Boolean success = readBoolean(frame, TransportPacket.PAYLOAD_SUCCESS);
        if (success == null) {
            throw new IllegalArgumentException(TransportPacket.PAYLOAD_SUCCESS + " is required");
        }
        String detail = firstNonBlank(
                readString(frame, TransportPacket.PAYLOAD_DETAIL),
                readString(frame, "message")
        );
        Map<String, Object> output = gson.fromJson(readJsonObject(frame, TransportPacket.PAYLOAD_OUTPUT), MAP_TYPE);
        return TaskResultReport.fromDecodedTransportPayload(
                taskId,
                messageId,
                success,
                detail,
                readString(frame, TransportPacket.PAYLOAD_ERROR_CODE),
                output
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

    private static void put(JsonObject frame, String field, String value) {
        if (value != null) {
            frame.addProperty(field, value);
        }
    }
}
