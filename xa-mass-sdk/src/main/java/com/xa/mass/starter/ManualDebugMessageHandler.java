package com.xa.mass.starter;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.ManualDebugChatProtocol;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.model.massMessage.MassMessage;

import java.util.Collections;
import java.util.List;

/**
 * Records inbound manual debug message replies from workers.
 */
public class ManualDebugMessageHandler implements MassMessageHandler {
    private final Gson gson = new Gson();

    @Override
    public List<MassMessage> handle(MassMessage msg) {
        String workerId = msg.getContext() != null ? msg.getContext().getWorkerId() : null;
        String replyToMessageId = extractReplyToMessageId(msg.getPayload());
        String eventCode = extractEventCode(msg.getPayload());
        String detail = extractDetail(msg.getPayload());
        String payloadJson = msg.getPayload() != null ? gson.toJson(msg.getPayload()) : "{}";
        String rawJson = gson.toJson(msg);
        WorkerDebugMessageStore.recordInbound(
                workerId,
                msg.getProject(),
                eventCode,
                msg.getMsgType() != null ? msg.getMsgType().name() : "",
                msg.getSubMsgType(),
                msg.getMsgId(),
                replyToMessageId,
                payloadJson,
                rawJson,
                detail
        );
        return Collections.emptyList();
    }

    private String extractEventCode(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonObject payloadObj = payload.getAsJsonObject();
        if (payloadObj.has("commandEvent") && !payloadObj.get("commandEvent").isJsonNull()) {
            try {
                return payloadObj.get("commandEvent").getAsString();
            } catch (Exception ex) {
                return payloadObj.get("commandEvent").toString();
            }
        }
        if (payloadObj.has(WorkerControlEventProtocol.EVENT_FIELD)
                && !payloadObj.get(WorkerControlEventProtocol.EVENT_FIELD).isJsonNull()) {
            try {
                return payloadObj.get(WorkerControlEventProtocol.EVENT_FIELD).getAsString();
            } catch (Exception ex) {
                return payloadObj.get(WorkerControlEventProtocol.EVENT_FIELD).toString();
            }
        }
        return null;
    }

    private String extractReplyToMessageId(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonObject payloadObj = payload.getAsJsonObject();
        if (!payloadObj.has("replyToMessageId") || payloadObj.get("replyToMessageId").isJsonNull()) {
            return null;
        }
        try {
            return payloadObj.get("replyToMessageId").getAsString();
        } catch (Exception ex) {
            return payloadObj.get("replyToMessageId").toString();
        }
    }

    private String extractDetail(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) {
            return "manual debug message received";
        }
        JsonObject payloadObj = payload.getAsJsonObject();
        if (payloadObj.has(ManualDebugChatProtocol.ACK_STATUS_FIELD) && !payloadObj.get(ManualDebugChatProtocol.ACK_STATUS_FIELD).isJsonNull()) {
            try {
                return payloadObj.get(ManualDebugChatProtocol.ACK_STATUS_FIELD).getAsString();
            } catch (Exception ex) {
                return payloadObj.get(ManualDebugChatProtocol.ACK_STATUS_FIELD).toString();
            }
        }
        if (payloadObj.has("message") && !payloadObj.get("message").isJsonNull()) {
            try {
                return payloadObj.get("message").getAsString();
            } catch (Exception ex) {
                return payloadObj.get("message").toString();
            }
        }
        return "manual debug message received";
    }
}
