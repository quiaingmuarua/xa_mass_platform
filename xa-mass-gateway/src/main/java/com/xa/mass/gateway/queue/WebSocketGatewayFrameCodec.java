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
 * gateway adapter: task transport shells plus root-level event-first control
 * frames. It is adapter-local and must not be treated as a platform
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

    public boolean isTaskStep(JsonObject frame) {
        return "TASK".equals(readString(frame, "msgType"))
                && "step".equals(normalizeSubType(readString(frame, "subMsgType")));
    }

    public boolean isCanonicalTaskResult(JsonObject frame) {
        return frame != null
                && readString(frame, "msgType") == null
                && readString(frame, WorkerControlEventProtocol.EVENT_CODE_FIELD) == null
                && firstNonBlank(readString(frame, "taskId"), readNestedString(frame, "context", "taskId")) != null
                && extractMessageId(frame) != null
                && hasBoolean(frame, "success");
    }

    public String encodeTaskDispatch(TaskDispatchItem item) {
        JsonObject frame = new JsonObject();
        frame.addProperty("msgId", item.getMsgId());
        frame.addProperty("response", false);
        frame.addProperty("msgType", "TASK");
        frame.addProperty("subMsgType", "step");
        frame.addProperty("from", "SERVER");
        if (item.getProject() != null) {
            frame.addProperty("project", item.getProject());
        }

        JsonObject context = new JsonObject();
        context.addProperty("workerId", item.getWorkerId());
        context.addProperty("taskId", item.getTaskId());
        context.addProperty("retryCount", item.getRetryCount());
        frame.add("context", context);

        JsonObject payload = new JsonObject();
        JsonArray steps = new JsonArray();
        JsonObject step = new JsonObject();
        step.addProperty("stepId", item.getBatchId() != null ? item.getBatchId() : item.getMsgId());
        step.addProperty("action", "task-dispatch");
        step.add("params", gson.toJsonTree(new LinkedHashMap<>(item.mergedPayload())));
        steps.add(step);
        payload.add("steps", steps);
        payload.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, item.getEventCode());
        frame.add("payload", payload);
        return gson.toJson(frame);
    }

    public TaskResultReport decodeTaskResult(JsonObject frame) {
        String taskId = readNestedString(frame, "context", "taskId");
        String msgId = extractMessageId(frame);
        if (taskId == null || msgId == null) {
            throw new IllegalArgumentException("taskId/msgId are required");
        }

        JsonObject payloadObj = extractPayload(frame);
        String status = readString(payloadObj, "status");
        String errorCode = readString(payloadObj, "errorCode");
        String detail = firstNonBlank(
                readString(payloadObj, "mockData"),
                readString(payloadObj, "message"),
                readString(payloadObj, "errorMessage"),
                payloadObj.toString()
        );

        boolean success = "SUCCESS".equalsIgnoreCase(status);
        if (!success && status == null && payloadObj.has("code") && payloadObj.get("code").isJsonPrimitive()) {
            try {
                int code = payloadObj.get("code").getAsInt();
                success = code >= 200 && code < 300;
            } catch (Exception ignored) {
            }
        }
        return new TaskResultReport(taskId, msgId, success, detail, errorCode, gson.fromJson(payloadObj, MAP_TYPE));
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

    public String encodeTaskAck(JsonObject requestFrame, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("msgId", extractMessageId(requestFrame));
        response.addProperty("response", true);
        response.addProperty("msgType", "TASK");
        response.addProperty("subMsgType", readString(requestFrame, "subMsgType"));
        response.addProperty("from", "SERVER");
        String project = extractProject(requestFrame);
        if (project != null) {
            response.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, project);
        }
        JsonObject context = readJsonObject(requestFrame, "context");
        if (!context.entrySet().isEmpty()) {
            response.add("context", context.deepCopy());
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("message", message);
        response.add("payload", payload);
        return gson.toJson(response);
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

    private String normalizeSubType(String subMsgType) {
        if (subMsgType == null || subMsgType.isBlank()) {
            return null;
        }
        return subMsgType.trim();
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
