package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;
import com.xa.mass.base.exception.ValidationException;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.transport.model.TaskResultReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Inbound processor for canonical WebSocket task-result frames.
 */
public final class WebSocketInputProcessor {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketInputProcessor.class);

    private final WebSocketDispatchRuntimeContext context;

    public WebSocketInputProcessor(WebSocketDispatchRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public boolean process(String rawJson) {
        try {
            JsonObject frame = context.getFrameCodec().parseObject(rawJson);
            if (frame == null) {
                return true;
            }
            if (context.getFrameCodec().isCanonicalTaskResult(frame)) {
                return processCanonicalTaskResult(frame);
            }
            return processUnknownFrame();
        } catch (Exception ex) {
            logProcessingException(ex);
            return false;
        }
    }

    private boolean processCanonicalTaskResult(JsonObject frame) {
        if (context.getTaskResultIngestChannel() == null) {
            logger.warn("Canonical task result ignored because task result ingest channel is unavailable");
            return true;
        }
        try {
            TaskResultReport report = context.getFrameCodec().decodeCanonicalTaskResult(frame);
            context.getTaskResultIngestChannel().ingest(report);
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
