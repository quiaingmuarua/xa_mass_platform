package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerControlMessageProtocol;
import com.xa.mass.command.model.CommandResponse;
import com.xa.mass.gateway.session.SessionRoles;
import com.xa.mass.mock.command.runtime.MockCommandRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Adapter-local helper that translates inbound WebSocket control-event frames
 * into mock command execution plus a control ack frame.
 */
final class MockWorkerControlFrameHandler {

    private static final Logger logger = LoggerFactory.getLogger(MockWorkerControlFrameHandler.class);
    private static final Gson GSON = new Gson();

    ControlResponsePlan prepareResponse(JsonObject controlMessage, String workerId) {
        if (controlMessage == null) {
            return null;
        }
        logger.info("[{}] Received control message. msgId={}, subMsgType={}",
                workerId, readString(controlMessage, "msgId"), readString(controlMessage, "subMsgType"));

        JsonObject eventEnvelope = extractEventEnvelope(controlMessage);
        JsonObject commandRequest = extractCommandRequest(controlMessage, eventEnvelope, workerId);
        CommandResponse<?> commandResult = commandRequest != null ? MockCommandRuntime.dispatch(commandRequest) : null;

        JsonObject response = new JsonObject();
        response.addProperty("msgId", readString(controlMessage, "msgId"));
        response.addProperty("response", true);
        response.addProperty("msgType", "CONTROL");
        response.addProperty("subMsgType", WorkerControlEventProtocol.SUB_MSG_TYPE);
        response.addProperty("from", "CLIENT");
        String project = readString(controlMessage, "project");
        if (project != null) {
            response.addProperty("project", project);
        }

        JsonObject responseContext = new JsonObject();
        JsonObject originalContext = getContext(controlMessage);
        responseContext.addProperty("connRole", firstNonBlank(readString(originalContext, "connRole"), SessionRoles.TASK_MESSAGES));
        responseContext.addProperty("workerId", workerId);
        response.add("context", responseContext);

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put(WorkerControlMessageProtocol.MESSAGE_KIND_FIELD, WorkerControlMessageProtocol.MESSAGE_KIND_ACK);
        payloadMap.put(WorkerControlMessageProtocol.REPLY_TO_MESSAGE_ID_FIELD, readString(controlMessage, "msgId"));
        payloadMap.put(WorkerControlMessageProtocol.ACK_STATUS_FIELD, WorkerControlMessageProtocol.ACK_STATUS_RECEIVED);
        payloadMap.put("message", resolveAckMessage(commandRequest, commandResult, eventEnvelope));
        payloadMap.put(WorkerControlMessageProtocol.WORKER_ID_FIELD, workerId);
        payloadMap.put(WorkerControlMessageProtocol.RECEIVED_AT_FIELD, System.currentTimeMillis());
        payloadMap.put(WorkerControlMessageProtocol.ECHO_PAYLOAD_FIELD, getPayload(controlMessage));
        payloadMap.put(WorkerControlMessageProtocol.ECHO_SUB_MSG_TYPE_FIELD, readString(controlMessage, "subMsgType"));
        if (eventEnvelope != null && eventEnvelope.has(WorkerControlEventProtocol.REQUEST_ID_FIELD)
                && !eventEnvelope.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).isJsonNull()) {
            payloadMap.put(WorkerControlEventProtocol.REQUEST_ID_FIELD,
                    eventEnvelope.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).getAsString());
        }
        payloadMap.put(WorkerControlMessageProtocol.EVENT_HANDLED_FIELD, commandResult != null);
        if (commandResult != null) {
            payloadMap.put(WorkerControlMessageProtocol.EVENT_RESULT_FIELD, commandResult);
        }
        String resolvedEventCode = resolveInboundEventCode(controlMessage, commandRequest, eventEnvelope);
        putCanonicalEventCode(payloadMap, resolvedEventCode);
        response.add("payload", GSON.toJsonTree(payloadMap));
        return new ControlResponsePlan(GSON.toJson(response), commandResult);
    }

    private JsonObject extractCommandRequest(JsonObject controlMessage,
                                             JsonObject eventEnvelope,
                                             String workerId) {
        JsonObject payloadObject = getPayload(controlMessage);
        JsonObject commandRequest = null;
        if (!payloadObject.entrySet().isEmpty()) {
            if (eventEnvelope != null) {
                commandRequest = buildCommandRequestFromEventEnvelope(eventEnvelope);
            } else if (readCanonicalEventCode(payloadObject) != null) {
                commandRequest = payloadObject.deepCopy();
            } else if (payloadObject.has("command") && payloadObject.get("command").isJsonObject()) {
                commandRequest = payloadObject.getAsJsonObject("command").deepCopy();
            } else if (payloadObject.has(WorkerControlMessageProtocol.TEXT_FIELD)
                    && payloadObject.get(WorkerControlMessageProtocol.TEXT_FIELD).isJsonPrimitive()) {
                commandRequest = parseCommandText(workerId, payloadObject.get(WorkerControlMessageProtocol.TEXT_FIELD).getAsString());
            }
        }

        commandRequest = normalizeCanonicalEventCode(commandRequest);

        if (commandRequest == null
                || !commandRequest.has(WorkerControlEventProtocol.EVENT_FIELD)
                || commandRequest.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
            return null;
        }
        if (!commandRequest.has("workerId")) {
            commandRequest.addProperty("workerId", workerId);
        }
        if (!commandRequest.has("requestMsgId") && readString(controlMessage, "msgId") != null) {
            commandRequest.addProperty("requestMsgId", readString(controlMessage, "msgId"));
        }
        if (!commandRequest.has("project") && readString(controlMessage, "project") != null) {
            commandRequest.addProperty("project", readString(controlMessage, "project"));
        }
        return commandRequest;
    }

    private JsonObject extractEventEnvelope(JsonObject controlMessage) {
        JsonObject payloadObject = getPayload(controlMessage);
        if (!WorkerControlEventProtocol.SUB_MSG_TYPE.equals(readString(controlMessage, "subMsgType"))) {
            return null;
        }
        if (readCanonicalEventCode(payloadObject) == null) {
            return null;
        }
        return normalizeCanonicalEventCode(payloadObject.deepCopy());
    }

    private JsonObject buildCommandRequestFromEventEnvelope(JsonObject eventEnvelope) {
        JsonObject commandRequest = new JsonObject();
        putCanonicalEventCode(commandRequest, readCanonicalEventCode(eventEnvelope));
        if (eventEnvelope.has(WorkerControlEventProtocol.PAYLOAD_FIELD)
                && eventEnvelope.get(WorkerControlEventProtocol.PAYLOAD_FIELD).isJsonObject()) {
            JsonObject payloadObject = eventEnvelope.getAsJsonObject(WorkerControlEventProtocol.PAYLOAD_FIELD);
            for (Map.Entry<String, JsonElement> entry : payloadObject.entrySet()) {
                if (WorkerControlEventProtocol.EVENT_FIELD.equals(entry.getKey())
                        || WorkerControlEventProtocol.EVENT_CODE_FIELD.equals(entry.getKey())) {
                    continue;
                }
                commandRequest.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        copyIfPresent(eventEnvelope, commandRequest, WorkerControlEventProtocol.REQUEST_ID_FIELD);
        if (eventEnvelope.has(WorkerControlEventProtocol.PRINCIPAL_FIELD)
                && eventEnvelope.get(WorkerControlEventProtocol.PRINCIPAL_FIELD).isJsonObject()) {
            JsonObject principal = eventEnvelope.getAsJsonObject(WorkerControlEventProtocol.PRINCIPAL_FIELD);
            copyIfPresent(principal, commandRequest, WorkerControlEventProtocol.CLIENT_ID_FIELD);
            copyIfPresent(principal, commandRequest, WorkerControlEventProtocol.USER_ID_FIELD);
        }
        return commandRequest;
    }

    private String resolveAckMessage(JsonObject commandRequest,
                                     CommandResponse<?> commandResult,
                                     JsonObject eventEnvelope) {
        String commandEventCode = readCanonicalEventCode(commandRequest);
        String envelopeEventCode = readCanonicalEventCode(eventEnvelope);
        if (commandResult != null) {
            return "mock worker executed command: " + commandEventCode;
        }
        if (envelopeEventCode != null) {
            return "mock worker received event: " + envelopeEventCode;
        }
        return "mock worker received control message";
    }

    private String resolveInboundEventCode(JsonObject controlMessage,
                                           JsonObject commandRequest,
                                           JsonObject eventEnvelope) {
        String envelopeEventCode = readCanonicalEventCode(eventEnvelope);
        if (envelopeEventCode != null) {
            return envelopeEventCode;
        }
        String commandEventCode = readCanonicalEventCode(commandRequest);
        if (commandEventCode != null) {
            return commandEventCode;
        }
        return readString(controlMessage, "subMsgType");
    }

    private JsonObject parseCommandText(String workerId, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(trimmed);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            logger.warn("[{}] Ignoring invalid command JSON text: {}", workerId, e.getMessage());
            return null;
        }
    }

    private void copyIfPresent(JsonObject source, JsonObject target, String field) {
        if (source != null && source.has(field) && !source.get(field).isJsonNull()) {
            target.add(field, source.get(field).deepCopy());
        }
    }

    private JsonObject getContext(JsonObject message) {
        return message != null && message.has("context") && message.get("context").isJsonObject()
                ? message.getAsJsonObject("context")
                : new JsonObject();
    }

    private JsonObject getPayload(JsonObject message) {
        return message != null && message.has("payload") && message.get("payload").isJsonObject()
                ? message.getAsJsonObject("payload")
                : new JsonObject();
    }

    private String readString(JsonObject object, String field) {
        if (object == null || field == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstNonBlank(String left, String right) {
        return (left != null && !left.isBlank()) ? left : right;
    }

    private String readCanonicalEventCode(JsonObject object) {
        return firstNonBlank(
                readString(object, WorkerControlEventProtocol.EVENT_CODE_FIELD),
                readString(object, WorkerControlEventProtocol.EVENT_FIELD)
        );
    }

    private JsonObject normalizeCanonicalEventCode(JsonObject object) {
        String eventCode = readCanonicalEventCode(object);
        if (object == null || eventCode == null) {
            return object;
        }
        putCanonicalEventCode(object, eventCode);
        return object;
    }

    private void putCanonicalEventCode(JsonObject object, String eventCode) {
        if (object == null || eventCode == null || eventCode.isBlank()) {
            return;
        }
        object.addProperty(WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode);
        object.addProperty(WorkerControlEventProtocol.EVENT_FIELD, eventCode);
    }

    private void putCanonicalEventCode(Map<String, Object> object, String eventCode) {
        if (object == null || eventCode == null || eventCode.isBlank()) {
            return;
        }
        object.put(WorkerControlEventProtocol.EVENT_CODE_FIELD, eventCode);
        object.put(WorkerControlEventProtocol.EVENT_FIELD, eventCode);
    }

    record ControlResponsePlan(String responseJson, CommandResponse<?> commandResult) {
    }
}
