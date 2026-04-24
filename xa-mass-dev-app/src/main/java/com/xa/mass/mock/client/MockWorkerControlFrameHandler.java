package com.xa.mass.mock.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerControlMessageProtocol;
import com.xa.mass.command.model.CommandResponse;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
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

    ControlResponsePlan prepareResponse(MassMessage controlMessage, String workerId) {
        if (controlMessage == null) {
            return null;
        }
        logger.info("[{}] Received control message. msgId={}, subMsgType={}",
                workerId, controlMessage.getMsgId(), controlMessage.getSubMsgType());

        JsonObject eventEnvelope = extractEventEnvelope(controlMessage);
        JsonObject commandRequest = extractCommandRequest(controlMessage, eventEnvelope, workerId);
        CommandResponse<?> commandResult = commandRequest != null ? MockCommandRuntime.dispatch(commandRequest) : null;

        MassMessage response = new MassMessage();
        response.setMsgId(controlMessage.getMsgId());
        response.setResponse(true);
        response.setMsgType(MessageType.CONTROL);
        response.setSubMsgType(WorkerControlEventProtocol.SUB_MSG_TYPE);
        response.setFrom(MessageDirection.CLIENT);
        response.setProject(controlMessage.getProject());

        MessageContext originalContext = controlMessage.getContext();
        MessageContext responseContext = new MessageContext();
        responseContext.setConnRole(originalContext != null ? originalContext.getConnRole() : SessionRoles.TASK_MESSAGES);
        responseContext.setWorkerId(workerId);
        response.setContext(responseContext);

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put(WorkerControlMessageProtocol.MESSAGE_KIND_FIELD, WorkerControlMessageProtocol.MESSAGE_KIND_ACK);
        payloadMap.put(WorkerControlMessageProtocol.REPLY_TO_MESSAGE_ID_FIELD, controlMessage.getMsgId());
        payloadMap.put(WorkerControlMessageProtocol.ACK_STATUS_FIELD, WorkerControlMessageProtocol.ACK_STATUS_RECEIVED);
        payloadMap.put("message", resolveAckMessage(commandRequest, commandResult, eventEnvelope));
        payloadMap.put(WorkerControlMessageProtocol.WORKER_ID_FIELD, workerId);
        payloadMap.put(WorkerControlMessageProtocol.RECEIVED_AT_FIELD, System.currentTimeMillis());
        payloadMap.put(WorkerControlMessageProtocol.ECHO_PAYLOAD_FIELD, controlMessage.getPayload());
        payloadMap.put(WorkerControlMessageProtocol.ECHO_SUB_MSG_TYPE_FIELD, controlMessage.getSubMsgType());
        if (eventEnvelope != null
                && eventEnvelope.has(WorkerControlEventProtocol.REQUEST_ID_FIELD)
                && !eventEnvelope.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).isJsonNull()) {
            payloadMap.put(
                    WorkerControlEventProtocol.REQUEST_ID_FIELD,
                    eventEnvelope.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).getAsString()
            );
        }
        payloadMap.put(WorkerControlMessageProtocol.EVENT_HANDLED_FIELD, commandResult != null);
        if (commandResult != null) {
            payloadMap.put(WorkerControlMessageProtocol.EVENT_RESULT_FIELD, commandResult);
        }
        String resolvedEventCode = resolveInboundEventCode(controlMessage, commandRequest, eventEnvelope);
        if (resolvedEventCode != null) {
            payloadMap.put(WorkerControlEventProtocol.EVENT_FIELD, resolvedEventCode);
        }
        response.setPayload(GSON.toJsonTree(payloadMap));
        return new ControlResponsePlan(response, commandResult);
    }

    private JsonObject extractCommandRequest(MassMessage controlMessage,
                                             JsonObject eventEnvelope,
                                             String workerId) {
        JsonElement payload = controlMessage.getPayload();
        JsonObject commandRequest = null;
        if (payload == null || payload.isJsonNull()) {
            return null;
        }
        if (payload.isJsonObject()) {
            JsonObject payloadObject = payload.getAsJsonObject();
            if (eventEnvelope != null) {
                commandRequest = buildCommandRequestFromEventEnvelope(eventEnvelope);
            } else if (payloadObject.has(WorkerControlEventProtocol.EVENT_FIELD)
                    && !payloadObject.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
                commandRequest = payloadObject.deepCopy();
            } else if (payloadObject.has("command") && payloadObject.get("command").isJsonObject()) {
                commandRequest = payloadObject.getAsJsonObject("command").deepCopy();
            } else if (payloadObject.has(WorkerControlMessageProtocol.TEXT_FIELD)
                    && payloadObject.get(WorkerControlMessageProtocol.TEXT_FIELD).isJsonPrimitive()) {
                commandRequest = parseCommandText(workerId, payloadObject.get(WorkerControlMessageProtocol.TEXT_FIELD).getAsString());
            }
        } else if (payload.isJsonPrimitive() && payload.getAsJsonPrimitive().isString()) {
            commandRequest = parseCommandText(workerId, payload.getAsString());
        }

        if (commandRequest == null
                || !commandRequest.has(WorkerControlEventProtocol.EVENT_FIELD)
                || commandRequest.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
            return null;
        }
        if (!commandRequest.has("workerId")) {
            commandRequest.addProperty("workerId", workerId);
        }
        if (!commandRequest.has("requestMsgId") && controlMessage.getMsgId() != null) {
            commandRequest.addProperty("requestMsgId", controlMessage.getMsgId());
        }
        if (!commandRequest.has("project") && controlMessage.getProject() != null) {
            commandRequest.addProperty("project", controlMessage.getProject());
        }
        return commandRequest;
    }

    private JsonObject extractEventEnvelope(MassMessage controlMessage) {
        JsonElement payload = controlMessage.getPayload();
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonObject payloadObject = payload.getAsJsonObject();
        if (!WorkerControlEventProtocol.SUB_MSG_TYPE.equals(controlMessage.getSubMsgType())) {
            return null;
        }
        if (!payloadObject.has(WorkerControlEventProtocol.EVENT_FIELD)
                || payloadObject.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
            return null;
        }
        return payloadObject;
    }

    private JsonObject buildCommandRequestFromEventEnvelope(JsonObject eventEnvelope) {
        JsonObject commandRequest = new JsonObject();
        commandRequest.add(
                WorkerControlEventProtocol.EVENT_FIELD,
                eventEnvelope.get(WorkerControlEventProtocol.EVENT_FIELD).deepCopy()
        );
        if (eventEnvelope.has(WorkerControlEventProtocol.PAYLOAD_FIELD)
                && eventEnvelope.get(WorkerControlEventProtocol.PAYLOAD_FIELD).isJsonObject()) {
            JsonObject payloadObject = eventEnvelope.getAsJsonObject(WorkerControlEventProtocol.PAYLOAD_FIELD);
            for (Map.Entry<String, JsonElement> entry : payloadObject.entrySet()) {
                if (WorkerControlEventProtocol.EVENT_FIELD.equals(entry.getKey())) {
                    continue;
                }
                commandRequest.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        if (eventEnvelope.has(WorkerControlEventProtocol.REQUEST_ID_FIELD)
                && !eventEnvelope.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).isJsonNull()) {
            commandRequest.add(
                    WorkerControlEventProtocol.REQUEST_ID_FIELD,
                    eventEnvelope.get(WorkerControlEventProtocol.REQUEST_ID_FIELD).deepCopy()
            );
        }
        if (eventEnvelope.has(WorkerControlEventProtocol.PRINCIPAL_FIELD)
                && eventEnvelope.get(WorkerControlEventProtocol.PRINCIPAL_FIELD).isJsonObject()) {
            JsonObject principal = eventEnvelope.getAsJsonObject(WorkerControlEventProtocol.PRINCIPAL_FIELD);
            if (principal.has(WorkerControlEventProtocol.CLIENT_ID_FIELD)
                    && !principal.get(WorkerControlEventProtocol.CLIENT_ID_FIELD).isJsonNull()) {
                commandRequest.add(
                        WorkerControlEventProtocol.CLIENT_ID_FIELD,
                        principal.get(WorkerControlEventProtocol.CLIENT_ID_FIELD).deepCopy()
                );
            }
            if (principal.has(WorkerControlEventProtocol.USER_ID_FIELD)
                    && !principal.get(WorkerControlEventProtocol.USER_ID_FIELD).isJsonNull()) {
                commandRequest.add(
                        WorkerControlEventProtocol.USER_ID_FIELD,
                        principal.get(WorkerControlEventProtocol.USER_ID_FIELD).deepCopy()
                );
            }
        }
        return commandRequest;
    }

    private String resolveAckMessage(JsonObject commandRequest,
                                     CommandResponse<?> commandResult,
                                     JsonObject eventEnvelope) {
        if (commandResult != null) {
            return "mock worker executed command: "
                    + commandRequest.get(WorkerControlEventProtocol.EVENT_FIELD).getAsString();
        }
        if (eventEnvelope != null
                && eventEnvelope.has(WorkerControlEventProtocol.EVENT_FIELD)
                && !eventEnvelope.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
            return "mock worker received event: "
                    + eventEnvelope.get(WorkerControlEventProtocol.EVENT_FIELD).getAsString();
        }
        return "mock worker received control message";
    }

    private String resolveInboundEventCode(MassMessage controlMessage,
                                           JsonObject commandRequest,
                                           JsonObject eventEnvelope) {
        if (eventEnvelope != null
                && eventEnvelope.has(WorkerControlEventProtocol.EVENT_FIELD)
                && !eventEnvelope.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
            return eventEnvelope.get(WorkerControlEventProtocol.EVENT_FIELD).getAsString();
        }
        if (commandRequest != null
                && commandRequest.has(WorkerControlEventProtocol.EVENT_FIELD)
                && !commandRequest.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
            return commandRequest.get(WorkerControlEventProtocol.EVENT_FIELD).getAsString();
        }
        return controlMessage.getSubMsgType();
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

    record ControlResponsePlan(MassMessage response, CommandResponse<?> commandResult) {
    }
}
