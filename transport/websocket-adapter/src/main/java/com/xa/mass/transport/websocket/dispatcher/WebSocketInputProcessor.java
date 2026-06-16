package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;
import com.xa.mass.base.exception.ValidationException;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import com.xa.mass.transport.websocket.util.WebSocketStringValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
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
        return process(WebSocketInboundMessage.raw(rawJson));
    }

    public boolean process(WebSocketInboundMessage inboundMessage) {
        try {
            if (inboundMessage == null) {
                return true;
            }
            JsonObject frame = inboundMessage.getParsedFrame();
            if (frame == null) {
                frame = context.getFrameCodec().parseObject(inboundMessage.getRawJson());
            }
            if (frame == null) {
                return true;
            }
            if (context.getFrameCodec().isCanonicalTaskResult(frame)) {
                return processCanonicalTaskResult(frame, inboundMessage);
            }
            return processUnknownFrame();
        } catch (Exception ex) {
            logProcessingException(ex);
            return false;
        }
    }

    private boolean processCanonicalTaskResult(JsonObject frame, WebSocketInboundMessage inboundMessage) {
        if (context.getResultIngressChannel() == null) {
            logger.warn("Canonical task result ignored because task result ingest channel is unavailable");
            return true;
        }
        try {
            String payload = context.getFrameCodec().encodeCanonicalTaskResultPayload(frame);
            String routeKey = WebSocketStringValues.firstNonBlank(
                    context.getFrameCodec().extractRouteKey(frame),
                    inboundMessage.getRouteKey()
            );
            boolean accepted = context.getResultIngressChannel().ingest(TransportResultIngressEnvelope.received(
                    payload,
                    null,
                    context.getFrameCodec().extractMessageId(frame),
                    diagnostics(routeKey, WebSocketStringValues.firstNonBlank(
                            context.getFrameCodec().extractTraceId(frame),
                            context.getFrameCodec().extractMessageId(frame)
                    ))
            ));
            if (!accepted) {
                throw new IllegalStateException("task result ingest channel rejected inbound canonical task result");
            }
        } catch (IllegalArgumentException ex) {
            logger.warn("Canonical task result rejected: {}", ex.getMessage());
        }
        return true;
    }

    private Map<String, String> diagnostics(String routeKey, String traceId) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        values.put("adapterId", context.getAdapterId());
        if (routeKey != null && !routeKey.isBlank()) {
            values.put("routeKey", routeKey);
        }
        if (traceId != null && !traceId.isBlank()) {
            values.put("traceId", traceId);
        }
        return Map.copyOf(values);
    }

    private boolean processUnknownFrame() {
        logger.warn("No canonical task-result handler found for inbound adapter frame");
        return true;
    }

    private void logProcessingException(Exception ex) {
        if (ex instanceof ValidationException) {
            logger.warn("WebSocket input validation failed: {}", ex.getMessage());
            return;
        }
        if (ex instanceof CommandException commandException) {
            ErrorCode code = commandException.getErrorCode();
            logger.warn("WebSocket input command failed: code={}, message={}",
                    code != null ? code.code : "UNKNOWN",
                    commandException.getMessage());
            return;
        }
        logger.error("WebSocket input processing failed", ex);
    }
}

