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
 * Fixed inbound processor for the current WebSocket compatibility frames.
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
            String connRole = context.getMessageCodec().extractConnRole(frame);
            String traceId = context.getMessageCodec().extractMessageId(frame);
            GatewayFrameKind frameKind = context.getFrameRouter().route(frame);
            return switch (frameKind) {
                case PING_HEARTBEAT -> processHeartbeatPing(frame, workerId, connRole, traceId);
                case PONG_HEARTBEAT -> processHeartbeatPong(frame);
                case TASK_STEP -> processTaskStep(frame, workerId, connRole, traceId);
                case CONTROL_EVENT_REQUEST -> processControlEventRequest(frame, workerId, connRole, traceId);
                case CONTROL_EVENT_RESPONSE -> processControlEventResponse(rawJson, frame);
                case UNKNOWN -> processUnknownFrame();
            };
        } catch (Exception ex) {
            logProcessingException(ex);
            return false;
        }
    }

    private boolean processHeartbeatPing(JsonObject frame, String workerId, String connRole, String traceId) {
        context.getMessageTransporter().sendOutput(new OutboundDelivery(
                workerId,
                connRole,
                context.getFrameRouter().handlePing(frame),
                traceId
        ));
        return true;
    }

    private boolean processHeartbeatPong(JsonObject frame) {
        context.getFrameRouter().handlePong(frame);
        return true;
    }

    private boolean processTaskStep(JsonObject frame, String workerId, String connRole, String traceId) {
        if (context.getTaskResultIngestChannel() == null) {
            context.getMessageTransporter().sendOutput(new OutboundDelivery(
                    workerId,
                    connRole,
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
                    connRole,
                    context.getMessageCodec().encodeTaskAck(frame, code, message),
                    traceId
            ));
        } catch (IllegalArgumentException ex) {
            context.getMessageTransporter().sendOutput(new OutboundDelivery(
                    workerId,
                    connRole,
                    context.getMessageCodec().encodeTaskAck(frame, 400, ex.getMessage()),
                    traceId
            ));
        }
        return true;
    }

    private boolean processControlEventRequest(JsonObject frame, String workerId, String connRole, String traceId) {
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
                connRole,
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
                    context.getMessageCodec().extractPayload(frame)
            );
        }
        return true;
    }

    private boolean processUnknownFrame() {
        logger.warn("No adapter route found for inbound compatibility frame");
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
