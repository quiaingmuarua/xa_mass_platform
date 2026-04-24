package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerControlMessageProtocol;
import com.xa.mass.command.model.CommandResponse;
import com.xa.mass.mock.command.runtime.MockCommandRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Adapter-local helper that turns inbound event-first control frames into mock
 * command execution plus an event-first acknowledgement frame.
 */
final class MockWorkerControlFrameHandler {

    private static final Logger logger = LoggerFactory.getLogger(MockWorkerControlFrameHandler.class);
    private static final Gson GSON = new Gson();

    ControlResponsePlan prepareResponse(JsonObject controlMessage, String workerId) {
        if (controlMessage == null) {
            return null;
        }
        String messageId = readString(controlMessage, WorkerControlEventProtocol.MESSAGE_ID_FIELD);
        String eventCode = readString(controlMessage, WorkerControlEventProtocol.EVENT_CODE_FIELD);
        logger.info("[{}] Received control event. messageId={}, eventCode={}", workerId, messageId, eventCode);

        JsonObject commandRequest = buildCommandRequest(controlMessage, workerId);
        CommandResponse<?> commandResult = commandRequest != null ? MockCommandRuntime.dispatch(commandRequest) : null;

        JsonObject response = new JsonObject();
        response.addProperty(WorkerControlEventProtocol.MESSAGE_ID_FIELD, UUID.randomUUID().toString());
        response.addProperty(WorkerControlEventProtocol.RESPONSE_FIELD, true);
        response.addProperty(WorkerControlEventProtocol.WORKER_ID_FIELD, workerId);

        String project = readString(controlMessage, WorkerControlEventProtocol.PROJECT_FIELD);
        if (project != null) {
            response.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, project);
        }
        if (eventCode != null) {
            response.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode);
        }
        String requestId = readString(controlMessage, WorkerControlEventProtocol.REQUEST_ID_FIELD);
        if (requestId != null) {
            response.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, requestId);
        }

        boolean handled = commandResult != null;
        response.addProperty(WorkerControlEventProtocol.SUCCESS_FIELD, handled && commandResult.isSuccess());
        response.addProperty(
                WorkerControlEventProtocol.CODE_FIELD,
                handled ? String.valueOf(commandResult.getCode()) : "MOCK_CONTROL_IGNORED"
        );
        response.addProperty(
                WorkerControlEventProtocol.MESSAGE_FIELD,
                resolveResponseMessage(eventCode, commandResult)
        );
        response.add(WorkerControlEventProtocol.DATA_FIELD, GSON.toJsonTree(buildResponseData(
                workerId,
                eventCode,
                messageId,
                controlMessage,
                commandResult
        )));
        return new ControlResponsePlan(GSON.toJson(response), commandResult);
    }

    private JsonObject buildCommandRequest(JsonObject controlMessage, String workerId) {
        String eventCode = readString(controlMessage, WorkerControlEventProtocol.EVENT_CODE_FIELD);
        if (eventCode == null) {
            return null;
        }
        JsonObject commandRequest = new JsonObject();
        commandRequest.addProperty("event", eventCode);
        commandRequest.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode);
        commandRequest.addProperty(WorkerControlMessageProtocol.WORKER_ID_FIELD, workerId);

        String requestMessageId = readString(controlMessage, WorkerControlEventProtocol.MESSAGE_ID_FIELD);
        if (requestMessageId != null) {
            commandRequest.addProperty("requestMsgId", requestMessageId);
        }
        String project = readString(controlMessage, WorkerControlEventProtocol.PROJECT_FIELD);
        if (project != null) {
            commandRequest.addProperty(WorkerControlEventProtocol.PROJECT_FIELD, project);
        }
        String requestId = readString(controlMessage, WorkerControlEventProtocol.REQUEST_ID_FIELD);
        if (requestId != null) {
            commandRequest.addProperty(WorkerControlEventProtocol.REQUEST_ID_FIELD, requestId);
        }

        JsonObject principal = readJsonObject(controlMessage, WorkerControlEventProtocol.PRINCIPAL_FIELD);
        copyIfPresent(principal, commandRequest, WorkerControlEventProtocol.CLIENT_ID_FIELD);
        copyIfPresent(principal, commandRequest, WorkerControlEventProtocol.USER_ID_FIELD);

        JsonObject payload = readJsonObject(controlMessage, WorkerControlEventProtocol.PAYLOAD_FIELD);
        for (Map.Entry<String, JsonElement> entry : payload.entrySet()) {
            commandRequest.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return commandRequest;
    }

    private Map<String, Object> buildResponseData(String workerId,
                                                  String eventCode,
                                                  String replyToMessageId,
                                                  JsonObject controlMessage,
                                                  CommandResponse<?> commandResult) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(WorkerControlMessageProtocol.MESSAGE_KIND_FIELD, WorkerControlMessageProtocol.MESSAGE_KIND_ACK);
        data.put(WorkerControlMessageProtocol.REPLY_TO_MESSAGE_ID_FIELD, replyToMessageId);
        data.put(WorkerControlMessageProtocol.ACK_STATUS_FIELD, WorkerControlMessageProtocol.ACK_STATUS_RECEIVED);
        data.put(WorkerControlMessageProtocol.WORKER_ID_FIELD, workerId);
        data.put(WorkerControlMessageProtocol.RECEIVED_AT_FIELD, System.currentTimeMillis());
        data.put(WorkerControlMessageProtocol.ECHO_PAYLOAD_FIELD, GSON.fromJson(
                readJsonObject(controlMessage, WorkerControlEventProtocol.PAYLOAD_FIELD),
                Object.class
        ));
        if (eventCode != null) {
            data.put(WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode);
        }
        String requestId = readString(controlMessage, WorkerControlEventProtocol.REQUEST_ID_FIELD);
        if (requestId != null) {
            data.put(WorkerControlEventProtocol.REQUEST_ID_FIELD, requestId);
        }
        data.put(WorkerControlMessageProtocol.EVENT_HANDLED_FIELD, commandResult != null);
        if (commandResult != null) {
            data.put(WorkerControlMessageProtocol.EVENT_RESULT_FIELD, commandResult);
        }
        return data;
    }

    private String resolveResponseMessage(String eventCode, CommandResponse<?> commandResult) {
        if (commandResult != null) {
            return commandResult.getMessage();
        }
        if (eventCode != null) {
            return "mock worker received event: " + eventCode;
        }
        return "mock worker received control message";
    }

    private JsonObject readJsonObject(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return new JsonObject();
        }
        JsonElement element = object.get(field);
        return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private void copyIfPresent(JsonObject source, JsonObject target, String field) {
        if (source != null && source.has(field) && !source.get(field).isJsonNull()) {
            target.add(field, source.get(field).deepCopy());
        }
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

    record ControlResponsePlan(String responseJson, CommandResponse<?> commandResult) {
    }
}
