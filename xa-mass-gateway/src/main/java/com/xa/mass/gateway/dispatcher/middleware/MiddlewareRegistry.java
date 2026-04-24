package com.xa.mass.gateway.dispatcher.middleware;

import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;
import com.xa.mass.base.exception.ValidationException;
import com.xa.mass.gateway.dispatcher.GatewayFrameKind;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.queue.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MiddlewareRegistry {
    private static final Logger logger = LoggerFactory.getLogger(MiddlewareRegistry.class);
    private final EnvelopeMiddleware inputMiddleware = processEnvelopeMiddleware();
    private final EnvelopeMiddleware outputMiddleware = sendEnvelopeMiddleware();
    private final CopyOnWriteArrayList<ExceptionMiddleware> exceptionMiddlewareList = new CopyOnWriteArrayList<>();

    public MiddlewareRegistry() {
        resetExceptionMiddlewares();
    }

    // Final middleware for the input/output chain that decodes, routes, and emits downstream responses.
    public static EnvelopeMiddleware processEnvelopeMiddleware() {
        return (envelope, context) -> {
            try {
                MassMessage msg = context.getMessageCodec().decode(envelope.getRawJson());
                if (msg == null || msg.getContext() == null) {
                    return true;
                }
                MessageContext ctx = msg.getContext();
                GatewayFrameKind frameKind = context.getFrameRouter().route(msg);
                List<MassMessage> responses = dispatchInboundFrame(frameKind, msg, context);
                if (responses == null || responses.isEmpty()) {
                    return true;
                }
                for (MassMessage resp : responses) {
                    String json = context.getMessageCodec().encode(resp);
                    context.getMessageTransporter().sendOutput(Envelope.builder()
                            .workerId(ctx.getWorkerId())
                            .connRole(ctx.getConnRole())
                            .eventCode(envelope != null ? envelope.getEventCode() : null)
                            .rawJson(json)
                            .build());
                }
            } catch (Exception e) {
                logger.error("Error in processEnvelopeMiddleware", e);
                return false;
            }
            return true;
        };
    }

    public static EnvelopeMiddleware sendEnvelopeMiddleware() {
        return (envelope, context) -> {
            try {
                logger.debug("sendEnvelopeMiddleware {}", envelope);
                boolean sent = context.getSessionManager()
                        .sendMessage(envelope.getWorkerId(), envelope.getConnRole(), envelope.getRawJson());
                if (sent) {
                    return true;
                }
                String detail = "endpoint unavailable for workerId="
                        + envelope.getWorkerId() + ", role=" + envelope.getConnRole();
                WorkerDebugMessageStore.markFailed(envelope.getTraceId(), detail);
                logger.warn("sendEnvelopeMiddleware skipped because endpoint is unavailable: workerId={}, role={}, eventCode={}, traceId={}",
                        envelope.getWorkerId(), envelope.getConnRole(), envelope.getEventCode(), envelope.getTraceId());
                return false;
            } catch (Exception e) {
                WorkerDebugMessageStore.markFailed(envelope != null ? envelope.getTraceId() : null, e.getMessage());
                logger.error("Error in sendEnvelopeMiddleware", e);
                return false;
            }
        };
    }

    private static List<MassMessage> dispatchInboundFrame(GatewayFrameKind frameKind,
                                                          MassMessage msg,
                                                          com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext context) {
        if (frameKind == null) {
            logger.warn("No frame kind resolved for message");
            return List.of();
        }
        return switch (frameKind) {
            case PING_HEARTBEAT -> context.getFrameRouter().handlePing(msg);
            case PONG_HEARTBEAT -> context.getFrameRouter().handlePong(msg);
            case TASK_STEP -> {
                if (context.getTaskStepFrameBridge() == null) {
                    logger.warn("No task-step bridge configured for inbound TASK/step frame");
                    yield List.of();
                }
                yield context.getTaskStepFrameBridge().handleTaskStep(msg);
            }
            case CONTROL_EVENT_REQUEST -> {
                if (context.getControlEventRequestFrameBridge() == null) {
                    logger.warn("No control-event request bridge configured for inbound CONTROL/event frame");
                    yield List.of();
                }
                yield context.getControlEventRequestFrameBridge().handleControlEventRequest(msg);
            }
            case CONTROL_EVENT_RESPONSE -> {
                if (context.getControlEventResponseFrameSink() == null) {
                    logger.warn("No control-event response sink configured for inbound CONTROL/event response");
                    yield List.of();
                }
                context.getControlEventResponseFrameSink().handleControlEventResponse(msg);
                yield List.of();
            }
            case UNKNOWN -> {
                logger.warn("No adapter route found for inbound compatibility frame");
                yield List.of();
            }
        };
    }

    public List<EnvelopeMiddleware> getInputMiddlewares() {
        return List.of(inputMiddleware);
    }

    public List<EnvelopeMiddleware> getOutputMiddlewares() {
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
        return (envelope, context, ex) -> {
            if (ex instanceof ValidationException) {
                logger.warn("[ExceptionMiddleware] Validation failed: {}", ex.getMessage());
                return false;
            } else if (ex instanceof CommandException) {
                CommandException ce = (CommandException) ex;
                ErrorCode code = ce.getErrorCode();
                logger.warn("[CommandException] code={}, msg={}", code.code, ce.getMessage());
                return false;
            } else {
                logger.error("[ExceptionMiddleware] System error: ", ex);
                return false;
            }
        };
    }
}
