package com.xa.mass.gateway.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息解析器
 * 使用 MessageCodec 进行消息的解析和验证
 */
public class MessageParser {

    public static final MessageParser INSTANCE = new MessageParser();
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
                .traceId(msg.getMsgId())
                .receivedAt(System.currentTimeMillis())
                .build();
    }

    public boolean isValid(MassMessage msg) {
        if (msg == null) return false;
        MessageContext ctx = msg.getContext();
        return ctx != null &&
                ctx.getWorkerId() != null &&
                ctx.getConnRole() != null;
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

        String controlEvent = readString(payloadObject, WorkerControlEventProtocol.EVENT_FIELD);
        if (controlEvent != null) {
            return controlEvent;
        }

        JsonArray steps = payloadObject.has("steps") && payloadObject.get("steps").isJsonArray()
                ? payloadObject.getAsJsonArray("steps")
                : null;
        if (steps == null || steps.size() == 0 || !steps.get(0).isJsonObject()) {
            return null;
        }
        JsonObject firstStep = steps.get(0).getAsJsonObject();
        JsonObject params = firstStep.has("params") && firstStep.get("params").isJsonObject()
                ? firstStep.getAsJsonObject("params")
                : null;
        if (params == null || !params.has("_sdk") || !params.get("_sdk").isJsonObject()) {
            return null;
        }
        JsonObject sdkMetadata = params.getAsJsonObject("_sdk");
        return readString(sdkMetadata, "eventCode");
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
