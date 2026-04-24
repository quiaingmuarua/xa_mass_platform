package com.xa.mass.gateway.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;
import com.xa.mass.base.exception.ValidationException;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.transport.model.TaskResultReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Inbound processor for canonical WebSocket task/control frames.
 */
public final class GatewayInputProcessor {
    private static final Logger logger = LoggerFactory.getLogger(GatewayInputProcessor.class);

    private final DispatchRuntimeContext context;

    public GatewayInputProcessor(DispatchRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public boolean process(String rawJson) {
        try {
            JsonObject frame = context.getFrameCodec().parseObject(rawJson);
            if (frame == null) {
                return true;
            }
            String workerId = context.getFrameCodec().extractWorkerId(frame);
            String traceId = context.getFrameCodec().extractMessageId(frame);
            if (context.getFrameCodec().isEventFirstControlResponse(frame)) {
                return processControlEventResponse(rawJson, frame);
            }
            if (context.getFrameCodec().isEventFirstControlRequest(frame)) {
                return processControlEventRequest(frame, workerId, traceId);
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

    private boolean processControlEventRequest(JsonObject frame, String workerId, String traceId) {
        EventRequest request = context.getFrameCodec().decodeControlEventRequest(frame);
        EventResponse response;
        if (context.getControlEventRequestHandler() == null) {
            response = EventResponse.failure(
                    "CONTROL_EVENT_UNAVAILABLE",
                    "control event handler unavailable",
                    request.getRequestId()
            );
        } else {
            EventPrincipal principal = context.getFrameCodec().decodeControlEventPrincipal(frame);
            response = context.getControlEventRequestHandler()
                    .handleControlEventRequest(request, principal);
        }
        context.getMessageTransporter().sendOutput(new OutboundDelivery(
                workerId,
                context.getFrameCodec().encodeControlEventResponse(frame, response),
                traceId
        ));
        return true;
    }

    private boolean processControlEventResponse(String rawJson, JsonObject frame) {
        if (context.getControlEventResponseFrameSink() != null) {
            context.getControlEventResponseFrameSink().handleControlEventResponse(
                    rawJson,
                    context.getFrameCodec().extractWorkerId(frame),
                    context.getFrameCodec().extractProject(frame),
                    context.getFrameCodec().extractMessageId(frame),
                    context.getFrameCodec().extractControlResponseData(frame)
            );
        }
        return true;
    }

    private boolean processUnknownFrame() {
        logger.warn("No canonical task/control handler found for inbound adapter frame");
        return true;
    }

    private void logProcessingException(Exception ex) {
        if (ex instanceof ValidationException) {
            logger.warn("Gateway input validation failed: {}", ex.getMessage());
            return;
        }
        if (ex instanceof CommandException commandException) {
            ErrorCode code = commandException.getErrorCode();
            logger.warn("Gateway input command failed: code={}, message={}",
                    code != null ? code.code : "UNKNOWN",
                    commandException.getMessage());
            return;
        }
        logger.error("Gateway input processing failed", ex);
    }
}
