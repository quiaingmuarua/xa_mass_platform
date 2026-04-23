package com.xa.mass.gateway.dispatcher.handler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.dispatcher.event.EventEnvelope;
import com.xa.mass.gateway.dispatcher.event.EventGatewayBridge;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventResponse;

import java.lang.reflect.Type;
import java.util.*;

/**
 * Compatibility bridge from worker control CONTROL frames into the event runtime.
 */
public class WorkerControlEventBridgeHandler implements MassMessageHandler {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final EventGatewayBridge bridge;

    public WorkerControlEventBridgeHandler(EventGatewayBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public List<MassMessage> handle(MassMessage msg) {
        JsonObject payloadObject = msg.getPayload() != null && msg.getPayload().isJsonObject()
                ? msg.getPayload().getAsJsonObject()
                : new JsonObject();
        String event = readString(payloadObject, WorkerControlEventProtocol.EVENT_FIELD);
        JsonObject headersObject = payloadObject.has(WorkerControlEventProtocol.HEADERS_FIELD)
                && payloadObject.get(WorkerControlEventProtocol.HEADERS_FIELD).isJsonObject()
                ? payloadObject.getAsJsonObject(WorkerControlEventProtocol.HEADERS_FIELD)
                : new JsonObject();
        JsonObject requestPayload = payloadObject.has(WorkerControlEventProtocol.PAYLOAD_FIELD)
                && payloadObject.get(WorkerControlEventProtocol.PAYLOAD_FIELD).isJsonObject()
                ? payloadObject.getAsJsonObject(WorkerControlEventProtocol.PAYLOAD_FIELD)
                : payloadObject;

        EventEnvelope envelope = EventEnvelope.builder()
                .event(event)
                .project(msg.getProject())
                .requestId(firstNonBlank(
                        readString(payloadObject, WorkerControlEventProtocol.REQUEST_ID_FIELD),
                        msg.getMsgId()))
                .headers(toStringMap(headersObject))
                .payload(toObjectMap(requestPayload))
                .principal(resolvePrincipal(payloadObject))
                .build();

        EventResponse response = bridge.handle(envelope);
        return Collections.singletonList(toMassMessageResponse(msg, response));
    }

    private EventPrincipal resolvePrincipal(JsonObject payloadObject) {
        JsonObject principalObject = payloadObject.has(WorkerControlEventProtocol.PRINCIPAL_FIELD)
                && payloadObject.get(WorkerControlEventProtocol.PRINCIPAL_FIELD).isJsonObject()
                ? payloadObject.getAsJsonObject(WorkerControlEventProtocol.PRINCIPAL_FIELD)
                : new JsonObject();
        return EventPrincipal.builder()
                .clientId(readString(principalObject, WorkerControlEventProtocol.CLIENT_ID_FIELD))
                .userId(readString(principalObject, WorkerControlEventProtocol.USER_ID_FIELD))
                .build();
    }

    private MassMessage toMassMessageResponse(MassMessage request, EventResponse response) {
        MassMessage reply = new MassMessage();
        reply.setMsgId(request.getMsgId());
        reply.setResponse(true);
        reply.setMsgType(MessageType.CONTROL);
        reply.setSubMsgType(request.getSubMsgType());
        reply.setFrom(MessageDirection.SERVER);
        reply.setProject(request.getProject());

        MessageContext originalContext = request.getContext();
        MessageContext replyContext = new MessageContext();
        if (originalContext != null) {
            replyContext.setWorkerId(originalContext.getWorkerId());
            replyContext.setConnRole(originalContext.getConnRole());
            replyContext.setTid(originalContext.getTid());
            replyContext.setRetryCount(originalContext.getRetryCount());
        }
        reply.setContext(replyContext);
        Map<String, Object> responsePayload = new LinkedHashMap<>();
        responsePayload.put("success", response.isSuccess());
        responsePayload.put("code", response.getCode());
        responsePayload.put("message", response.getMessage());
        responsePayload.put("data", response.getData());
        responsePayload.put(WorkerControlEventProtocol.REQUEST_ID_FIELD, response.getRequestId());
        reply.setPayload(GSON.toJsonTree(responsePayload));
        return reply;
    }

    private Map<String, String> toStringMap(JsonObject jsonObject) {
        if (jsonObject == null || jsonObject.entrySet().isEmpty()) {
            return Collections.emptyMap();
        }
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        return GSON.fromJson(jsonObject, type);
    }

    private Map<String, Object> toObjectMap(JsonObject jsonObject) {
        if (jsonObject == null || jsonObject.entrySet().isEmpty()) {
            return Collections.emptyMap();
        }
        return GSON.fromJson(jsonObject, MAP_TYPE);
    }

    private String readString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return value.getAsString();
    }

    private String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        return right;
    }
}
