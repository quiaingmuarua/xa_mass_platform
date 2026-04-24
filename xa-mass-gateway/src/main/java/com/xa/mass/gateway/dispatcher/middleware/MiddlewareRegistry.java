package com.xa.mass.gateway.dispatcher.middleware;

import com.google.gson.JsonObject;
import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;
import com.xa.mass.base.exception.ValidationException;
import com.xa.mass.gateway.dispatcher.GatewayFrameKind;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.transport.model.TaskResultReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MiddlewareRegistry {
    private static final Logger logger = LoggerFactory.getLogger(MiddlewareRegistry.class);
    private final MessageInboundMiddleware inputMiddleware = processEnvelopeMiddleware();
    private final MessageOutboundMiddleware outputMiddleware = sendEnvelopeMiddleware();
    private final CopyOnWriteArrayList<ExceptionMiddleware> exceptionMiddlewareList = new CopyOnWriteArrayList<>();

    public MiddlewareRegistry() {
        resetExceptionMiddlewares();
    }

    public static MessageInboundMiddleware processEnvelopeMiddleware() {
        return (rawJson, context) -> {
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
                    case PING_HEARTBEAT -> {
                        context.getMessageTransporter().sendOutput(new OutboundDelivery(
                                workerId,
                                connRole,
                                context.getFrameRouter().handlePing(frame),
                                traceId
                        ));
                        yield true;
                    }
                    case PONG_HEARTBEAT -> {
                        context.getFrameRouter().handlePong(frame);
                        yield true;
                    }
                    case TASK_STEP -> {
                        if (context.getTaskStepFrameBridge() == null) {
                            context.getMessageTransporter().sendOutput(new OutboundDelivery(
                                    workerId,
                                    connRole,
                                    context.getMessageCodec().encodeTaskAck(frame, 503, "task step bridge unavailable"),
                                    traceId
                            ));
                            yield true;
                        }
                        try {
                            TaskResultReport report = context.getMessageCodec().decodeTaskResult(frame);
                            boolean handled = context.getTaskStepFrameBridge().handleTaskStep(report);
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
                        yield true;
                    }
                    case CONTROL_EVENT_REQUEST -> {
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
                        yield true;
                    }
                    case CONTROL_EVENT_RESPONSE -> {
                        if (context.getControlEventResponseFrameSink() != null) {
                            context.getControlEventResponseFrameSink().handleControlEventResponse(
                                    rawJson,
                                    workerId,
                                    context.getMessageCodec().extractProject(frame),
                                    traceId,
                                    context.getMessageCodec().extractPayload(frame)
                            );
                        }
                        yield true;
                    }
                    case UNKNOWN -> {
                        logger.warn("No adapter route found for inbound compatibility frame");
                        yield true;
                    }
                };
            } catch (Exception e) {
                logger.error("Error in processEnvelopeMiddleware", e);
                return false;
            }
        };
    }

    public static MessageOutboundMiddleware sendEnvelopeMiddleware() {
        return (delivery, context) -> {
            try {
                boolean sent = context.getSessionManager().sendMessage(
                        delivery.getWorkerId(),
                        delivery.getConnRole(),
                        delivery.getRawJson()
                );
                if (sent) {
                    return true;
                }
                String detail = "endpoint unavailable for workerId="
                        + delivery.getWorkerId() + ", role=" + delivery.getConnRole();
                WorkerDebugMessageStore.markFailed(delivery.getTraceId(), detail);
                logger.warn("sendEnvelopeMiddleware skipped because endpoint is unavailable: workerId={}, role={}, traceId={}",
                        delivery.getWorkerId(), delivery.getConnRole(), delivery.getTraceId());
                return false;
            } catch (Exception e) {
                WorkerDebugMessageStore.markFailed(delivery != null ? delivery.getTraceId() : null, e.getMessage());
                logger.error("Error in sendEnvelopeMiddleware", e);
                return false;
            }
        };
    }

    public List<MessageInboundMiddleware> getInputMiddlewares() {
        return List.of(inputMiddleware);
    }

    public List<MessageOutboundMiddleware> getOutputMiddlewares() {
        return List.of(outputMiddleware);
    }

    public void registerExceptionMiddleware(ExceptionMiddleware mw) {
        if (mw != null) {
            exceptionMiddlewareList.add(mw);
        }
    }

    public List<ExceptionMiddleware> getExceptionMiddlewareList() {
        return List.copyOf(exceptionMiddlewareList);
    }

    private void resetExceptionMiddlewares() {
        exceptionMiddlewareList.clear();
        exceptionMiddlewareList.add(defaultExceptionMiddleware());
    }

    private ExceptionMiddleware defaultExceptionMiddleware() {
        return (rawJson, delivery, context, ex) -> {
            if (ex instanceof ValidationException) {
                logger.warn("[ExceptionMiddleware] Validation failed: {}", ex.getMessage());
                return false;
            } else if (ex instanceof CommandException ce) {
                ErrorCode code = ce.getErrorCode();
                logger.warn("[CommandException] code={}, msg={}", code.code, ce.getMessage());
                return false;
            } else {
                logger.error("[ExceptionMiddleware] System error", ex);
                return false;
            }
        };
    }
}
