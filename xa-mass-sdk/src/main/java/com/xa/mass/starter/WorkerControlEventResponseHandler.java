package com.xa.mass.starter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerControlMessageProtocol;
import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;

/**
 * Records inbound worker control-event replies from workers.
 */
public class WorkerControlEventResponseHandler implements ControlEventResponseFrameSink {
    private final Gson gson = new Gson();

    @Override
    public void handleControlEventResponse(String rawJson,
                                           String workerId,
                                           String project,
                                           String messageId,
                                           JsonObject payload) {
        String replyToMessageId = extractReplyToMessageId(payload);
        String eventCode = extractCanonicalEventCode(payload);
        String detail = extractDetail(payload);
        String payloadJson = payload != null ? gson.toJson(payload) : "{}";
        WorkerDebugMessageStore.recordInbound(
                workerId,
                project,
                eventCode,
                "CONTROL",
                WorkerControlEventProtocol.SUB_MSG_TYPE,
                messageId,
                replyToMessageId,
                payloadJson,
                rawJson,
                detail
        );
    }

    private String extractCanonicalEventCode(JsonObject payloadObj) {
        String eventCode = readString(payloadObj, WorkerControlEventProtocol.EVENT_CODE_FIELD);
        if (eventCode != null) {
            return eventCode;
        }
        return readString(payloadObj, WorkerControlEventProtocol.EVENT_FIELD);
    }

    private String extractReplyToMessageId(JsonObject payloadObj) {
        if (payloadObj == null || !payloadObj.has("replyToMessageId") || payloadObj.get("replyToMessageId").isJsonNull()) {
            return null;
        }
        try {
            return payloadObj.get("replyToMessageId").getAsString();
        } catch (Exception ex) {
            return payloadObj.get("replyToMessageId").toString();
        }
    }

    private String extractDetail(JsonObject payloadObj) {
        if (payloadObj == null) {
            return "worker control response received";
        }
        if (payloadObj.has(WorkerControlMessageProtocol.ACK_STATUS_FIELD)
                && !payloadObj.get(WorkerControlMessageProtocol.ACK_STATUS_FIELD).isJsonNull()) {
            try {
                return payloadObj.get(WorkerControlMessageProtocol.ACK_STATUS_FIELD).getAsString();
            } catch (Exception ex) {
                return payloadObj.get(WorkerControlMessageProtocol.ACK_STATUS_FIELD).toString();
            }
        }
        if (payloadObj.has("message") && !payloadObj.get("message").isJsonNull()) {
            try {
                return payloadObj.get("message").getAsString();
            } catch (Exception ex) {
                return payloadObj.get("message").toString();
            }
        }
        return "worker control response received";
    }

    private String readString(JsonObject payloadObj, String field) {
        if (payloadObj == null || field == null || !payloadObj.has(field) || payloadObj.get(field).isJsonNull()) {
            return null;
        }
        try {
            return payloadObj.get(field).getAsString();
        } catch (Exception ex) {
            return payloadObj.get(field).toString();
        }
    }
}
