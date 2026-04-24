package com.xa.mass.gateway.queue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Current WebSocket adapter frame codec.
 *
 * <p>This type owns the remaining WebSocket wire shapes for the current
 * gateway adapter: canonical task/control frames for the current WebSocket
 * runtime. It is adapter-local and must not be treated as a platform
 * capability contract.
 */
public final class WebSocketGatewayFrameCodec {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketGatewayFrameCodec.class);
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();
    private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private final Gson gson;

    public WebSocketGatewayFrameCodec() {
        this(new GsonBuilder().setPrettyPrinting().create());
    }

    public WebSocketGatewayFrameCodec(Gson gson) {
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
        return firstNonBlank(
                readString(frame, WorkerControlEventProtocol.WORKER_ID_FIELD),
                readNestedString(frame, "context", "workerId")
        );
    }

    public String extractProject(JsonObject frame) {
        return readString(frame, WorkerControlEventProtocol.PROJECT_FIELD);
    }

    public String extractMessageId(JsonObject frame) {
        return firstNonBlank(
                readString(frame, WorkerControlEventProtocol.MESSAGE_ID_FIELD),
                readString(frame, "msgId")
        );
    }

    public String extractEventCode(JsonObject frame) {
        if (frame == null) {
            return null;
        }
        String rootEventCode = readString(frame, WorkerControlEventProtocol.EVENT_CODE_FIELD);
        if (rootEventCode != null) {
            return rootEventCode;
        }
        JsonObject payload = extractPayload(frame);
        return readString(payload, WorkerControlEventProtocol.EVENT_CODE_FIELD);
    }

    public JsonObject extractPayload(JsonObject frame) {
        return readJsonObject(frame, WorkerControlEventProtocol.PAYLOAD_FIELD);
    }

    public JsonObject extractControlResponseData(JsonObject frame) {
        return readJsonObject(frame, WorkerControlEventProtocol.DATA_FIELD);
    }

    public boolean isEventFirstControlRequest(JsonObject frame) {
        return frame != null
                && !isResponse(frame)
                && readString(frame, WorkerControlEventProtocol.EVENT_CODE_FIELD) != null;
    }

    public boolean isEventFirstControlResponse(JsonObject frame) {
        return frame != null
                && isResponse(frame)
                && readString(frame, WorkerControlEventProtocol.EVENT_CODE_FIELD) != null;
    }

    public boolean isCanonicalTaskResult(JsonObject frame) {
        return frame != null
                && readString(frame, WorkerControlEventProtocol.EVENT_CODE_FIELD) == null
                && firstNonBlank(readString(frame, "taskId"), readNestedString(frame, "context", "taskId")) != null
                && extractMessageId(frame) != null
                && hasBoolean(frame, "success");
    }

    public boolean isCanonicalTaskDispatch(JsonObject frame) {
        return frame != null
                && !isResponse(frame)
                && firstNonBlank(readString(frame, "taskId"), readNestedString(frame, "context", "taskId")) != null
                && extractMessageId(frame) != null
                && !hasBoolean(frame, "success");
    }

    public String encodeCanonicalTaskDispatch(TaskDispatchItem item) {
        JsonObject frame = new JsonObject();
        frame.addProperty(WorkerControlEventProtocol.MESSAGE_ID_FIELD, item.getMsgId());
        frame.addProperty(WorkerControlEventProtocol.WORKER_ID_FIELD, item.getWorkerId());
        if (item.getProject() != null) {
            frame.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, item.getProject());
        }
        if (item.getEventCode() != null) {
            frame.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, item.getEventCode());
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
        String taskId = firstNonBlank(readString(frame, "taskId"), readNestedString(frame, "context", "taskId"));
        String msgId = extractMessageId(frame);
        if (taskId == null || msgId == null) {
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
        String errorCode = readString(frame, "errorCode");
        JsonObject outputObject = readJsonObject(frame, "output");
        return new TaskResultReport(
                taskId,
                msgId,
                success,
                detail,
                errorCode,
                gson.fromJson(outputObject, MAP_TYPE)
        );
    }

    public EventRequest decodeControlEventRequest(JsonObject frame) {
        JsonObject headersObject = readJsonObject(frame, WorkerControlEventProtocol.HEADERS_FIELD);
        JsonObject payloadObject = readJsonObject(frame, WorkerControlEventProtocol.PAYLOAD_FIELD);
        return EventRequest.builder()
                .event(readString(frame, WorkerControlEventProtocol.EVENT_CODE_FIELD))
                .project(extractProject(frame))
                .requestId(firstNonBlank(
                        readString(frame, WorkerControlEventProtocol.REQUEST_ID_FIELD),
                        extractMessageId(frame)
                ))
                .headers(gson.fromJson(headersObject, STRING_MAP_TYPE))
                .payload(gson.fromJson(payloadObject, MAP_TYPE))
                .build();
    }

    public EventPrincipal decodeControlEventPrincipal(JsonObject frame) {
        JsonObject principalObject = readJsonObject(frame, WorkerControlEventProtocol.PRINCIPAL_FIELD);
        return EventPrincipal.builder()
                .clientId(readString(principalObject, WorkerControlEventProtocol.CLIENT_ID_FIELD))
                .userId(readString(principalObject, WorkerControlEventProtocol.USER_ID_FIELD))
                .build();
    }

    public String encodeControlEventResponse(JsonObject requestFrame, EventResponse response) {
        JsonObject reply = new JsonObject();
        reply.addProperty(WorkerControlEventProtocol.MESSAGE_ID_FIELD, UUID.randomUUID().toString());
        reply.addProperty(WorkerControlEventProtocol.RESPONSE_FIELD, true);
        String workerId = extractWorkerId(requestFrame);
        if (workerId != null) {
            reply.addProperty(WorkerControlEventProtocol.WORKER_ID_FIELD, workerId);
        }
        String project = extractProject(requestFrame);
        if (project != null) {
            reply.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, project);
        }
        String eventCode = extractEventCode(requestFrame);
        if (eventCode != null) {
            reply.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode);
        }
        String requestId = firstNonBlank(
                response != null ? response.getRequestId() : null,
                readString(requestFrame, WorkerControlEventProtocol.REQUEST_ID_FIELD)
        );
        if (requestId != null) {
            reply.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, requestId);
        }
        reply.addProperty(WorkerControlEventProtocol.SUCCESS_FIELD, response != null && response.isSuccess());
        if (response != null && response.getCode() != null) {
            reply.addProperty(WorkerControlEventProtocol.CODE_FIELD, response.getCode());
        }
        if (response != null && response.getMessage() != null) {
            reply.addProperty(WorkerControlEventProtocol.MESSAGE_FIELD, response.getMessage());
        }
        reply.add(WorkerControlEventProtocol.DATA_FIELD, gson.toJsonTree(response != null ? response.getData() : null));
        return gson.toJson(reply);
    }

    public Gson getGson() {
        return gson;
    }

    private boolean isResponse(JsonObject frame) {
        return frame != null
                && frame.has(WorkerControlEventProtocol.RESPONSE_FIELD)
                && !frame.get(WorkerControlEventProtocol.RESPONSE_FIELD).isJsonNull()
                && frame.get(WorkerControlEventProtocol.RESPONSE_FIELD).getAsBoolean();
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

    private String readNestedString(JsonObject object, String nestedField, String field) {
        if (object == null || nestedField == null || !object.has(nestedField) || !object.get(nestedField).isJsonObject()) {
            return null;
        }
        return readString(object.getAsJsonObject(nestedField), field);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
