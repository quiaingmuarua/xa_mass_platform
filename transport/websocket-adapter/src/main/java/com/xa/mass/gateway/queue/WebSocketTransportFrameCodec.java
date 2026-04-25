package com.xa.mass.gateway.queue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.gateway.util.GatewayStringValues;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Current WebSocket adapter frame codec.
 *
 * <p>This type owns only the remaining WebSocket task-frame shell for the
 * current gateway runtime. It is adapter-local and must not be treated as a
 * platform capability contract.
 */
public final class WebSocketTransportFrameCodec {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketTransportFrameCodec.class);
    private static final String MESSAGE_ID_FIELD = "messageId";
    private static final String WORKER_ID_FIELD = "workerId";
    private static final String PROJECT_FIELD = "project";
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

    public String extractProject(JsonObject frame) {
        return readString(frame, PROJECT_FIELD);
    }

    public String extractMessageId(JsonObject frame) {
        return readString(frame, MESSAGE_ID_FIELD);
    }

    public String extractEventCode(JsonObject frame) {
        return readString(frame, EVENT_CODE_FIELD);
    }

    public boolean isCanonicalTaskResult(JsonObject frame) {
        return frame != null
                && readString(frame, EVENT_CODE_FIELD) == null
                && readString(frame, "taskId") != null
                && extractMessageId(frame) != null
                && hasBoolean(frame, "success");
    }

    public boolean isCanonicalTaskDispatch(JsonObject frame) {
        return frame != null
                && !isResponse(frame)
                && readString(frame, "taskId") != null
                && extractMessageId(frame) != null
                && !hasBoolean(frame, "success");
    }

    public String encodeCanonicalTaskDispatch(TaskDispatchItem item) {
        JsonObject frame = new JsonObject();
        frame.addProperty(MESSAGE_ID_FIELD, item.getMessageId());
        frame.addProperty(WORKER_ID_FIELD, item.getWorkerId());
        if (item.getProject() != null) {
            frame.addProperty(PROJECT_FIELD, item.getProject());
        }
        if (item.getEventCode() != null) {
            frame.addProperty(EVENT_CODE_FIELD, item.getEventCode());
        }
        frame.addProperty("taskId", item.getTaskId());
        if (item.getTaskName() != null) {
            frame.addProperty("taskName", item.getTaskName());
        }
        if (item.getUserId() != null) {
            frame.addProperty("userId", item.getUserId());
        }
        frame.addProperty("retryCount", item.getRetryCount());
        if (item.getWorkerContextId() != null) {
            frame.addProperty("workerContextId", item.getWorkerContextId());
        }
        if (item.getBatchId() != null) {
            frame.addProperty("batchId", item.getBatchId());
        }
        frame.add("input", gson.toJsonTree(item.getInput() != null ? item.getInput() : Map.of()));
        frame.add("sharedConfig", gson.toJsonTree(item.getSharedConfig() != null ? item.getSharedConfig() : Map.of()));
        return gson.toJson(frame);
    }

    public TaskResultReport decodeCanonicalTaskResult(JsonObject frame) {
        String taskId = readString(frame, "taskId");
        String messageId = extractMessageId(frame);
        if (taskId == null || messageId == null) {
            throw new IllegalArgumentException("taskId/messageId are required");
        }

        Boolean success = readBoolean(frame, "success");
        if (success == null) {
            throw new IllegalArgumentException("success is required");
        }
        String detail = GatewayStringValues.firstNonBlank(
                readString(frame, "detail"),
                readString(frame, "message")
        );
        String errorCode = readString(frame, "errorCode");
        JsonObject outputObject = readJsonObject(frame, "output");
        return new TaskResultReport(
                taskId,
                messageId,
                success,
                detail,
                errorCode,
                gson.fromJson(outputObject, MAP_TYPE)
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
}
