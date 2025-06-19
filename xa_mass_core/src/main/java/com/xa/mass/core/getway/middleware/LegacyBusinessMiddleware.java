package com.xa.mass.core.getway.middleware;

import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.dispatcher.DispatcherContext;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageContext;
import com.xa.mass.core.getway.queue.MessageDecoder;
import com.xa.mass.core.getway.queue.MessageContextValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xa.mass.core.getway.exception.ValidationException;

public class LegacyBusinessMiddleware implements EnvelopeMiddleware {
    private static final Logger logger = LoggerFactory.getLogger(LegacyBusinessMiddleware.class);
    private final MessageDecoder messageDecoder;
    private final MessageContextValidator contextValidator;

    public LegacyBusinessMiddleware(MessageDecoder messageDecoder, MessageContextValidator contextValidator) {
        this.messageDecoder = messageDecoder;
        this.contextValidator = contextValidator;
    }

    @Override
    public boolean handle(Envelope envelope, DispatcherContext context) {
        String raw = envelope.getRawJson();
        if (raw == null || !raw.trim().startsWith("{")) {
            throw new ValidationException("Dropped invalid message: " + raw);
        }
        MassMessage parsed = messageDecoder.tryDecode(raw);
        if (!contextValidator.isValid(parsed)) {
            throw new ValidationException("Message missing context info: " + raw);
        }
        // 可选：校验通过后将解析结果放入 context，供后续中间件/handler 用
        // context.setParsedMessage(parsed); // 如 context 支持此方法
        logger.info("Decoded and validated message: {}", parsed);
        return true;
    }
} 