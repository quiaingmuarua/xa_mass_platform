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
import com.xa.mass.transport.WorkerEndpointRoles;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gson-based WebSocket compatibility codec.
 */
public class GsonMessageCodec implements MessageCodec {

    private static final Logger logger = LoggerFactory.getLogger(GsonMessageCodec.class);
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final Gson gson;

    public GsonMessageCodec() {
        this(new GsonBuilder().setPrettyPrinting().create());
    }

    public GsonMessageCodec(Gson gson) {
        this.gson = gson;
    }

    @Override
    public JsonObject parseObject(String json) {
        try {
            JsonElement element = gson.fromJson(json, JsonElement.class);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonSyntaxException ex) {
            logger.warn("Failed to parse WebSocket JSON frame", ex);
            return null;
        }
    }

    @Override
    public boolean isValid(String json) {
        return parseObject(json) != null;
    }

    @Override
    public String extractWorkerId(JsonObject frame) {
        return readNestedString(frame, "context", "workerId");
    }

    @Override
    public String extractConnRole(JsonObject frame) {
        String connRole = readNestedString(frame, "context", "connRole");
        if (connRole == null || connRole.isBlank()) {
            return WorkerEndpointRoles.TASK_DISPATCH;
        }
        return connRole.trim();
    }

    @Override
    public String extractProject(JsonObject frame) {
        return readString(frame, "project");
    }

    @Override
    public String extractMessageId(JsonObject frame) {
        return readString(frame, "msgId");
    }

    @Override
    public String extractEventCode(JsonObject frame) {
        JsonObject payload = extractPayload(frame);
        String eventCode = readString(payload, "eventCode");
        if (eventCode != null) {
            return eventCode;
        }
        return readString(payload, WorkerControlEventProtocol.EVENT_FIELD);
    }

    @Override
    public JsonObject extractPayload(JsonObject frame) {
        if (frame == null || !frame.has("payload") || frame.get("payload").isJsonNull() || !frame.get("payload").isJsonObject()) {
            return new JsonObject();
        }
        return frame.getAsJsonObject("payload");
    }

    @Override
    public String encodeHeartbeatPong(JsonObject requestFrame) {
        JsonObject response = baseResponseFrame(requestFrame, "PONG", readString(requestFrame, "subMsgType"));
        JsonObject payload = new JsonObject();
        payload.addProperty("code", 200);
        payload.addProperty("message", "pong");
        response.add("payload", payload);
        return gson.toJson(response);
    }

    @Override
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
        context.addProperty("connRole", WorkerEndpointRoles.TASK_DISPATCH);
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
        payload.addProperty("eventCode", item.getEventCode());
        frame.add("payload", payload);
        return gson.toJson(frame);
    }

    @Override
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

    @Override
    public String encodeTaskAck(JsonObject requestFrame, int code, String message) {
        JsonObject response = baseResponseFrame(requestFrame, "TASK", readString(requestFrame, "subMsgType"));
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        payload.addProperty("message", message);
        response.add("payload", payload);
        return gson.toJson(response);
    }

    @Override
    public EventRequest decodeControlEventRequest(JsonObject frame) {
        JsonObject payloadObject = extractPayload(frame);
        JsonObject headersObject = payloadObject.has(WorkerControlEventProtocol.HEADERS_FIELD)
                && payloadObject.get(WorkerControlEventProtocol.HEADERS_FIELD).isJsonObject()
                ? payloadObject.getAsJsonObject(WorkerControlEventProtocol.HEADERS_FIELD)
                : new JsonObject();
        JsonObject requestPayload = payloadObject.has(WorkerControlEventProtocol.PAYLOAD_FIELD)
                && payloadObject.get(WorkerControlEventProtocol.PAYLOAD_FIELD).isJsonObject()
                ? payloadObject.getAsJsonObject(WorkerControlEventProtocol.PAYLOAD_FIELD)
                : payloadObject;

        return EventRequest.builder()
                .event(readString(payloadObject, WorkerControlEventProtocol.EVENT_FIELD))
                .project(extractProject(frame))
                .requestId(firstNonBlank(
                        readString(payloadObject, WorkerControlEventProtocol.REQUEST_ID_FIELD),
                        extractMessageId(frame)))
                .headers(gson.fromJson(headersObject, new TypeToken<Map<String, String>>() {
                }.getType()))
                .payload(gson.fromJson(requestPayload, MAP_TYPE))
                .build();
    }

    @Override
    public EventPrincipal decodeControlEventPrincipal(JsonObject frame) {
        JsonObject payloadObject = extractPayload(frame);
        JsonObject principalObject = payloadObject.has(WorkerControlEventProtocol.PRINCIPAL_FIELD)
                && payloadObject.get(WorkerControlEventProtocol.PRINCIPAL_FIELD).isJsonObject()
                ? payloadObject.getAsJsonObject(WorkerControlEventProtocol.PRINCIPAL_FIELD)
                : new JsonObject();
        return EventPrincipal.builder()
                .clientId(readString(principalObject, WorkerControlEventProtocol.CLIENT_ID_FIELD))
                .userId(readString(principalObject, WorkerControlEventProtocol.USER_ID_FIELD))
                .build();
    }

    @Override
    public String encodeControlEventResponse(JsonObject requestFrame, EventResponse response) {
        JsonObject reply = baseResponseFrame(requestFrame, "CONTROL", readString(requestFrame, "subMsgType"));
        JsonObject payload = new JsonObject();
        payload.addProperty("success", response.isSuccess());
        if (response.getCode() != null) {
            payload.addProperty("code", response.getCode());
        }
        if (response.getMessage() != null) {
            payload.addProperty("message", response.getMessage());
        }
        payload.add(WorkerControlEventProtocol.REQUEST_ID_FIELD, gson.toJsonTree(response.getRequestId()));
        payload.add("data", gson.toJsonTree(response.getData()));
        reply.add("payload", payload);
        return gson.toJson(reply);
    }

    public Gson getGson() {
        return gson;
    }

    private JsonObject baseResponseFrame(JsonObject requestFrame, String msgType, String subMsgType) {
        JsonObject response = new JsonObject();
        response.addProperty("msgId", extractMessageId(requestFrame));
        response.addProperty("response", true);
        response.addProperty("msgType", msgType);
        if (subMsgType != null) {
            response.addProperty("subMsgType", subMsgType);
        }
        response.addProperty("from", "SERVER");
        String project = extractProject(requestFrame);
        if (project != null) {
            response.addProperty("project", project);
        }
        JsonObject context = requestFrame != null && requestFrame.has("context") && requestFrame.get("context").isJsonObject()
                ? requestFrame.getAsJsonObject("context").deepCopy()
                : new JsonObject();
        response.add("context", context);
        return response;
    }

    private JsonObject toJsonObject(Object payloadObj) {
        if (payloadObj == null) {
            return new JsonObject();
        }
        JsonElement payloadJson = gson.toJsonTree(payloadObj);
        return payloadJson != null && payloadJson.isJsonObject() ? payloadJson.getAsJsonObject() : new JsonObject();
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
