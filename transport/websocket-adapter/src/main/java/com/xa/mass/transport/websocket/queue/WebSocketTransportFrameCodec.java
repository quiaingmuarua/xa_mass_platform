package com.xa.mass.transport.websocket.queue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.transport.websocket.util.WebSocketStringValues;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.packet.TransportPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Current WebSocket adapter frame codec.
 *
 * <p>This type owns only the remaining WebSocket task-frame shell for the
 * current WebSocket adapter runtime. It is adapter-local and must not be treated as a
 * platform capability contract.
 */
public final class WebSocketTransportFrameCodec {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketTransportFrameCodec.class);
    private static final String MESSAGE_ID_FIELD = "messageId";
    private static final String WORKER_ID_FIELD = TransportPacket.PAYLOAD_WORKER_ID;
    private static final String ROUTE_KEY_FIELD = "routeKey";
    private static final String PROJECT_FIELD = TransportPacket.PAYLOAD_PROJECT;
    private static final String EVENT_CODE_FIELD = "eventCode";
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final Gson gson;

    public WebSocketTransportFrameCodec() {
        this(new GsonBuilder().setPrettyPrinting().create());
    }

    public WebSocketTransportFrameCodec(Gson gson) {
        this.gson = gson;
    }

    public JsonObject parseObject(String json) {
        try {
            JsonElement element = gson.fromJson(json, JsonElement.class);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonSyntaxException ex) {
            logger.warn("Failed to parse WebSocket JSON frame", ex);
            return null;
        }
    }

    public String extractWorkerId(JsonObject frame) {
        return readString(frame, WORKER_ID_FIELD);
    }

    public String extractRouteKey(JsonObject frame) {
        return readString(frame, ROUTE_KEY_FIELD);
    }

    public String extractProject(JsonObject frame) {
        return readString(frame, PROJECT_FIELD);
    }

    public String extractMessageId(JsonObject frame) {
        return readString(frame, MESSAGE_ID_FIELD);
    }

    public String extractEventCode(JsonObject frame) {
        return readString(frame, EVENT_CODE_FIELD);
    }

    public String extractTraceId(JsonObject frame) {
        return readString(frame, "traceId");
    }

    public boolean isCanonicalTaskResult(JsonObject frame) {
        return frame != null
                && readString(frame, EVENT_CODE_FIELD) == null
                && readString(frame, "taskId") != null
                && extractMessageId(frame) != null
                && hasBoolean(frame, TransportPacket.PAYLOAD_SUCCESS);
    }

    public boolean isCanonicalTaskDispatch(JsonObject frame) {
        return frame != null
                && !isResponse(frame)
                && readString(frame, "taskId") != null
                && extractMessageId(frame) != null
                && !hasBoolean(frame, TransportPacket.PAYLOAD_SUCCESS);
    }

    public String encodeCanonicalTaskDispatch(TransportPacket packet) {
        JsonObject frame = new JsonObject();
        frame.addProperty(MESSAGE_ID_FIELD, packet.messageId());
        put(frame, WORKER_ID_FIELD, packet.payloadString(WORKER_ID_FIELD));
        put(frame, PROJECT_FIELD, packet.payloadString(PROJECT_FIELD));
        if (packet.eventCode() != null) {
            frame.addProperty(EVENT_CODE_FIELD, packet.eventCode());
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
        String messageId = extractMessageId(frame);
        if (taskId == null || messageId == null) {
            throw new IllegalArgumentException("taskId/messageId are required");
        }

        Boolean success = readBoolean(frame, TransportPacket.PAYLOAD_SUCCESS);
        if (success == null) {
            throw new IllegalArgumentException(TransportPacket.PAYLOAD_SUCCESS + " is required");
        }
        String detail = WebSocketStringValues.firstNonBlank(
                readString(frame, TransportPacket.PAYLOAD_DETAIL),
                readString(frame, "message")
        );
        String errorCode = readString(frame, TransportPacket.PAYLOAD_ERROR_CODE);
        JsonObject outputObject = readJsonObject(frame, TransportPacket.PAYLOAD_OUTPUT);
        Map<String, Object> output = gson.fromJson(outputObject, MAP_TYPE);
        return TaskResultReport.fromDecodedTransportPayload(
                taskId,
                messageId,
                success,
                detail,
                errorCode,
                output
        );
    }

    public Gson getGson() {
        return gson;
    }

    private boolean isResponse(JsonObject frame) {
        Boolean response = readBoolean(frame, "response");
        return Boolean.TRUE.equals(response);
    }

    private Boolean readBoolean(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
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

    private JsonObject readJsonObject(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return new JsonObject();
        }
        JsonElement element = object.get(field);
        return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private String readString(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            String value = object.get(field).getAsString();
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void put(JsonObject frame, String field, String value) {
        if (value != null) {
            frame.addProperty(field, value);
        }
    }
}
