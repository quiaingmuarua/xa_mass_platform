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
 * Inbound processor for current WebSocket transport shells plus event-first
 * control frames.
 */
public final class GatewayInputProcessor {
    private static final Logger logger = LoggerFactory.getLogger(GatewayInputProcessor.class);

    private final DispatchRuntimeContext context;

    public GatewayInputProcessor(DispatchRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public boolean process(String rawJson) {
        try {
            JsonObject frame = context.getMessageCodec().parseObject(rawJson);
            if (frame == null) {
                return true;
            }
            String workerId = context.getMessageCodec().extractWorkerId(frame);
            String traceId = context.getMessageCodec().extractMessageId(frame);
            if (context.getMessageCodec().isEventFirstControlResponse(frame)) {
                return processControlEventResponse(rawJson, frame);
            }
            if (context.getMessageCodec().isEventFirstControlRequest(frame)) {
                return processControlEventRequest(frame, workerId, traceId);
            }
            if (context.getMessageCodec().isHeartbeatPing(frame)) {
                return processHeartbeatPing(frame, workerId, traceId);
            }
            if (context.getMessageCodec().isHeartbeatPong(frame)) {
                return processHeartbeatPong(frame);
            }
            if (context.getMessageCodec().isTaskStep(frame)) {
                return processTaskStep(frame, workerId, traceId);
            }
            return processUnknownFrame();
        } catch (Exception ex) {
            logProcessingException(ex);
            return false;
        }
    }

    private boolean processHeartbeatPing(JsonObject frame, String workerId, String traceId) {
        if (context.getSystemEventChannel() != null) {
            context.getSystemEventChannel().publishWorkerHeartbeat(workerId, "heartbeat",
                    context.getMessageCodec().extractMessageId(frame));
        }
        context.getMessageTransporter().sendOutput(new OutboundDelivery(
                workerId,
                context.getMessageCodec().encodeHeartbeatPong(frame),
                traceId
        ));
        return true;
    }

    private boolean processHeartbeatPong(JsonObject frame) {
        logger.debug("Received pong from {}", context.getMessageCodec().extractWorkerId(frame));
        return true;
    }

    private boolean processTaskStep(JsonObject frame, String workerId, String traceId) {
        if (context.getTaskResultIngestChannel() == null) {
            context.getMessageTransporter().sendOutput(new OutboundDelivery(
                    workerId,
                    context.getMessageCodec().encodeTaskAck(frame, 503, "task step bridge unavailable"),
                    traceId
            ));
            return true;
        }
        try {
            TaskResultReport report = context.getMessageCodec().decodeTaskResult(frame);
            boolean handled = context.getTaskResultIngestChannel().ingest(report);
            int code = handled ? 200 : 404;
            String message = handled ? "task result processed" : "task result ignored";
            context.getMessageTransporter().sendOutput(new OutboundDelivery(
                    workerId,
                    context.getMessageCodec().encodeTaskAck(frame, code, message),
                    traceId
            ));
        } catch (IllegalArgumentException ex) {
            context.getMessageTransporter().sendOutput(new OutboundDelivery(
                    workerId,
                    context.getMessageCodec().encodeTaskAck(frame, 400, ex.getMessage()),
                    traceId
            ));
        }
        return true;
    }

    private boolean processControlEventRequest(JsonObject frame, String workerId, String traceId) {
        EventRequest request = context.getMessageCodec().decodeControlEventRequest(frame);
        EventResponse response;
        if (context.getControlEventRequestFrameBridge() == null) {
            response = EventResponse.failure(
                    "CONTROL_EVENT_UNAVAILABLE",
                    "control event bridge unavailable",
                    request.getRequestId()
            );
        } else {
            EventPrincipal principal = context.getMessageCodec().decodeControlEventPrincipal(frame);
            response = context.getControlEventRequestFrameBridge()
                    .handleControlEventRequest(request, principal);
        }
        context.getMessageTransporter().sendOutput(new OutboundDelivery(
                workerId,
                context.getMessageCodec().encodeControlEventResponse(frame, response),
                traceId
        ));
        return true;
    }

    private boolean processControlEventResponse(String rawJson, JsonObject frame) {
        if (context.getControlEventResponseFrameSink() != null) {
            context.getControlEventResponseFrameSink().handleControlEventResponse(
                    rawJson,
                    context.getMessageCodec().extractWorkerId(frame),
                    context.getMessageCodec().extractProject(frame),
                    context.getMessageCodec().extractMessageId(frame),
                    context.getMessageCodec().extractControlResponseData(frame)
            );
        }
        return true;
    }

    private boolean processUnknownFrame() {
        logger.warn("No task-shell or heartbeat handler found for inbound adapter frame");
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
