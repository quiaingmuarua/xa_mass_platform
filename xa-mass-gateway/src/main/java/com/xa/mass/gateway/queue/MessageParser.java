package com.xa.mass.gateway.queue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transport-frame decoder and metadata extractor for the current gateway
 * adapter.
 *
 * <p>This utility should stay limited to wire-shape decoding plus minimal
 * routing/diagnostic metadata extraction. Business/control payload semantics
 * belong in downstream handlers, and task payload internals must not be used
 * as a fallback source of truth for event metadata.
 */
public class MessageParser {

    private static final Logger logger = LoggerFactory.getLogger(MessageParser.class);
    private final MessageCodec messageCodec;

    public MessageParser() {
        this.messageCodec = new GsonMessageCodec();
    }

    public MessageParser(MessageCodec messageCodec) {
        this.messageCodec = messageCodec;
    }

    public MassMessage tryDecode(String rawJson) {
        try {
            return messageCodec.decode(rawJson);
        } catch (Exception e) {
            logger.warn("Invalid message format: {}", rawJson, e);
            return null;
        }
    }

    public Envelope toStoredMessage(String rawJson, MassMessage msg) {
        MessageContext ctx = msg.getContext();
        return Envelope.builder().rawJson(rawJson).workerId(ctx.getWorkerId())
                .connRole(ctx.getConnRole())
                .eventCode(extractEventCode(msg))
                .project(msg.getProject())
                .traceId(msg.getMsgId())
                .receivedAt(System.currentTimeMillis())
                .build();
    }

    public boolean isValid(MassMessage msg) {
        if (msg == null) return false;
        MessageContext ctx = msg.getContext();
        return ctx != null &&
                ctx.getWorkerId() != null;
    }

    /**
     * 获取底层的消息编解码器
     * @return 消息编解码器
     */
    public MessageCodec getMessageCodec() {
        return messageCodec;
    }

    public static String extractEventCode(MassMessage msg) {
        if (msg == null) {
            return null;
        }
        JsonElement payload = msg.getPayload();
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonObject payloadObject = payload.getAsJsonObject();

        String explicitEventCode = readString(payloadObject, "eventCode");
        if (explicitEventCode != null) {
            return explicitEventCode;
        }

        String controlEvent = readString(payloadObject, WorkerControlEventProtocol.EVENT_FIELD);
        if (controlEvent != null) {
            return controlEvent;
        }
        return null;
    }

    private static String readString(JsonObject object, String field) {
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
