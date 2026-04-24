package com.xa.mass.gateway.queue;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transport-frame decoder and metadata extractor for the current gateway
 * adapter.
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

    public JsonObject tryDecode(String rawJson) {
        try {
            return messageCodec.parseObject(rawJson);
        } catch (Exception e) {
            logger.warn("Invalid message format: {}", rawJson, e);
            return null;
        }
    }

    public boolean isValid(JsonObject frame) {
        return frame != null && messageCodec.extractWorkerId(frame) != null;
    }

    public String extractWorkerId(JsonObject frame) {
        return messageCodec.extractWorkerId(frame);
    }

    public String extractConnRole(JsonObject frame) {
        return messageCodec.extractConnRole(frame);
    }

    public String extractMessageId(JsonObject frame) {
        return messageCodec.extractMessageId(frame);
    }

    public String extractProject(JsonObject frame) {
        return messageCodec.extractProject(frame);
    }

    public String extractEventCode(JsonObject frame) {
        return messageCodec.extractEventCode(frame);
    }

    public MessageCodec getMessageCodec() {
        return messageCodec;
    }
}
