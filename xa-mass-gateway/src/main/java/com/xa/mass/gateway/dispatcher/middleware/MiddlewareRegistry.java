package com.xa.mass.gateway.dispatcher.middleware;

import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;
import com.xa.mass.base.exception.ValidationException;
import com.xa.mass.gateway.dispatcher.FrameRouteResolution;
import com.xa.mass.gateway.dispatcher.handler.MassMessageEventCodeResolver;
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

                FrameRouteResolution result = context.getFrameRouter().route(msg);

                if (result.isMatched()) {
                    logger.debug("Found handler for message: {}", result);
                    String responseEventCode = resolveResponseEventCode(envelope, result, msg);
                    List<MassMessage> responses = result.getHandler().handle(msg);
                    if (responses != null) {
                        for (MassMessage resp : responses) {
                            String json = context.getMessageCodec().encode(resp);
                            context.getMessageTransporter().sendOutput(Envelope.builder()
                                    .workerId(ctx.getWorkerId())
                                    .connRole(ctx.getConnRole())
                                    .eventCode(responseEventCode)
                                    .rawJson(json)
                                    .build());
                        }
                    }
                } else {
                    logger.warn("No handler found for message: {}", result);
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

    private static String resolveResponseEventCode(Envelope envelope, FrameRouteResolution result, MassMessage msg) {
        if (envelope != null && envelope.getEventCode() != null && !envelope.getEventCode().isBlank()) {
            return envelope.getEventCode();
        }
        if (result == null || result.getHandler() == null) {
            return null;
        }
        if (result.getHandler() instanceof MassMessageEventCodeResolver resolver) {
            return resolver.resolveEventCode(msg);
        }
        return null;
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
