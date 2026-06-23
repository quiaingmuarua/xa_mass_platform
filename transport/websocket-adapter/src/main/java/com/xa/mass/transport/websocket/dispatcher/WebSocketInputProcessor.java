package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.transport.channel.ResultIngressEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Inbound processor for canonical WebSocket task-result frames.
 */
public final class WebSocketInputProcessor {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketInputProcessor.class);

    private final WebSocketDispatcherContext context;

    public WebSocketInputProcessor(WebSocketDispatcherContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public boolean process(String rawJson) {
        return process(context.getFrameParser().parseObject(rawJson));
    }

    public boolean process(JsonObject frame) {
        try {
            if (frame == null) {
                return true;
            }
            if (context.getResultFrameReader().isResultFrame(frame)) {
                return processCanonicalTaskResult(frame);
            }
            return processUnknownFrame();
        } catch (Exception ex) {
            logProcessingException(ex);
            return false;
        }
    }

    private boolean processCanonicalTaskResult(JsonObject frame) {
        if (context.getResultIngressSink() == null) {
            logger.warn("Canonical task result ignored because task result ingest channel is unavailable");
            return true;
        }
        try {
            ResultIngressEntry entry = context.getResultFrameReader().toEntry(frame);
            boolean accepted = context.getResultIngressSink().ingest(entry);
            if (!accepted) {
                throw new IllegalStateException("task result ingest channel rejected inbound canonical task result");
            }
        } catch (IllegalArgumentException ex) {
            logger.warn("Canonical task result rejected: {}", ex.getMessage());
        }
        return true;
    }

    private boolean processUnknownFrame() {
        logger.warn("No canonical task-result handler found for inbound adapter frame");
        return true;
    }

    private void logProcessingException(Exception ex) {
        if (ex instanceof IllegalArgumentException) {
            logger.warn("WebSocket input frame rejected: {}", ex.getMessage());
            return;
        }
        logger.error("WebSocket input processing failed", ex);
    }
}

