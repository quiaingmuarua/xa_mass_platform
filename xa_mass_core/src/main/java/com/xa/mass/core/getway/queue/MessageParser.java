package com.xa.mass.core.getway.queue;

import com.xa.mass.core.getway.model.massMessage.MassMessage;
import com.xa.mass.core.getway.model.massMessage.MessageContext;
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

    /**
     * 获取底层的消息编解码器
     * @return 消息编解码器
     */
    public MessageCodec getMessageCodec() {
        return messageCodec;
    }
}
