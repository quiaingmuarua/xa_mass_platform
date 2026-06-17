package com.xa.mass.transport.socket.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.transport.model.DeliveryCommand;
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
    private static final String TYPE_FIELD = "type";
    private static final String WORKER_GROUP_ID_FIELD = "workerGroupId";
    private static final String ROUTE_KEY_FIELD = "routeKey";
    private static final String TRACE_ID_FIELD = "traceId";
    private static final String TASK_ID_FIELD = "taskId";
    private static final String MESSAGE_ID_FIELD = "messageId";
    private static final String EVENT_CODE_FIELD = "eventCode";
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
        return "hello".equalsIgnoreCase(readString(frame, TYPE_FIELD))
                && readString(frame, TransportPacket.PAYLOAD_WORKER_ID) != null;
    }

    public boolean isHeartbeatFrame(JsonObject frame) {
        return "heartbeat".equalsIgnoreCase(readString(frame, TYPE_FIELD));
    }

    public String extractWorkerId(JsonObject frame) {
        return readString(frame, TransportPacket.PAYLOAD_WORKER_ID);
    }

    public String extractWorkerGroupId(JsonObject frame) {
        return readString(frame, WORKER_GROUP_ID_FIELD);
    }

    public String extractRouteKey(JsonObject frame) {
        return readString(frame, ROUTE_KEY_FIELD);
    }

    public String extractTraceId(JsonObject frame) {
        return readString(frame, TRACE_ID_FIELD);
    }

    public String extractMessageId(JsonObject frame) {
        return readString(frame, MESSAGE_ID_FIELD);
    }

    public String extractEventCode(JsonObject frame) {
        return readString(frame, EVENT_CODE_FIELD);
    }

    public boolean isCanonicalTaskResult(JsonObject frame) {
        return frame != null
                && extractEventCode(frame) == null
                && readString(frame, TASK_ID_FIELD) != null
                && extractMessageId(frame) != null
                && hasBoolean(frame, TransportPacket.PAYLOAD_SUCCESS);
    }

    public String encodeCanonicalTaskDispatch(DeliveryCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return command.getPayload();
    }

    public String encodeCanonicalTaskResultPayload(JsonObject frame) {
        String taskId = readString(frame, TASK_ID_FIELD);
        String messageId = extractMessageId(frame);
        if (taskId == null || messageId == null) {
            throw new IllegalArgumentException("taskId/messageId are required");
        }
        Boolean success = readBoolean(frame, TransportPacket.PAYLOAD_SUCCESS);
        if (success == null) {
            throw new IllegalArgumentException(TransportPacket.PAYLOAD_SUCCESS + " is required");
        }
        return gson.toJson(frame);
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
