package com.xa.mass.core.getway.queue;


import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

public class MessageParser {

    public static final MessageParser INSTANCE = new MessageParser();
    private static final Logger logger = LoggerFactory.getLogger(MessageParser.class);
    private final Gson gson = new Gson();

    public MassMessage tryDecode(String rawJson) {
        try {
            return gson.fromJson(rawJson, MassMessage.class);
        } catch (JsonSyntaxException e) {
            logger.warn("Invalid JSON format: {}", rawJson, e);
            return null;
        }
    }

    public Envelope toStoredMessage(String rawJson, MassMessage msg) {
        MessageContext ctx = msg.getContext();
        return Envelope.builder().rawJson(rawJson).deviceId(ctx.getDeviceId())
                .connRole(ctx.getConnRole()).traceId(msg.getMsgId()).receivedAt(System.currentTimeMillis()).build();
    }

    public boolean isValid(MassMessage msg) {
        if (msg == null) return false;
        MessageContext ctx = msg.getContext();
        return ctx != null &&
                ctx.getDeviceId() != null &&
                ctx.getConnRole() != null;
    }
}
