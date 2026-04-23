package com.xa.mass.gateway.dispatcher.middleware;

import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.base.exception.CommandException;
import com.xa.mass.base.exception.ErrorCode;
import com.xa.mass.base.exception.ValidationException;
import com.xa.mass.gateway.dispatcher.handler.ResolutionResult;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.queue.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class MiddlewareRegistry {
    public static final MiddlewareRegistry instance = new MiddlewareRegistry();
    private static final Logger logger = LoggerFactory.getLogger(MiddlewareRegistry.class);
    private final NavigableMap<Integer, EnvelopeMiddleware> inputMiddlewareMap = new TreeMap<>();
    private final NavigableMap<Integer, EnvelopeMiddleware> outputMiddlewareMap = new TreeMap<>();
    private final Map<Integer, Boolean> inputEnabledMap = new HashMap<>();
    private final Map<Integer, Boolean> outputEnabledMap = new HashMap<>();
    private final List<ExceptionMiddleware> exceptionMiddlewareList = new ArrayList<>();

    private MiddlewareRegistry() {
    }

    public static void autoRegister() {
        // Register the default mainline middlewares at the highest priority so they wrap the full chain.
        MiddlewareRegistry.instance.registerInput(Integer.MAX_VALUE, processEnvelopeMiddleware());
        MiddlewareRegistry.instance.registerOutput(Integer.MAX_VALUE, sendEnvelopeMiddleware());

        MiddlewareRegistry.instance.registerExceptionMiddleware((envelope, context, ex) -> {
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
        });
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

                ResolutionResult result = context.getMessageHandlerRegistry().resolve(msg);

                if (result.isFound()) {
                    logger.debug("Found handler for message: {}", result);
                    List<MassMessage> responses = result.getHandler().handle(msg);
                    if (responses != null) {
                        for (MassMessage resp : responses) {
                            String json = context.getMessageCodec().encode(resp);
                            context.getMessageTransporter().sendOutput(Envelope.builder()
                                    .workerId(ctx.getWorkerId())
                                    .connRole(ctx.getConnRole())
                                    .eventCode(envelope.getEventCode())
                                    .rawJson(json)
                                    .build());
                        }
                    }
                } else if (result.isFallback()) {
                    logger.warn("Using fallback handler for message: msgType={}, subType={}, payload={}",
                            msg.getMsgType(), msg.getSubMsgType(), msg.getPayload());
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

    public void registerInput(int priority, EnvelopeMiddleware mw) {
        inputMiddlewareMap.put(priority, mw);
        inputEnabledMap.put(priority, true);
    }

    public void unregisterInput(int priority) {
        inputMiddlewareMap.remove(priority);
        inputEnabledMap.remove(priority);
    }

    public void setInputEnabled(int priority, boolean enabled) {
        if (inputMiddlewareMap.containsKey(priority)) {
            inputEnabledMap.put(priority, enabled);
        }
    }

    public List<EnvelopeMiddleware> getActiveInputMiddlewares() {
        List<EnvelopeMiddleware> list = new ArrayList<>();
        for (Map.Entry<Integer, EnvelopeMiddleware> entry : inputMiddlewareMap.entrySet()) {
            if (Boolean.TRUE.equals(inputEnabledMap.get(entry.getKey()))) {
                list.add(entry.getValue());
            }
        }
        return list;
    }

    public void registerOutput(int priority, EnvelopeMiddleware mw) {
        outputMiddlewareMap.put(priority, mw);
        outputEnabledMap.put(priority, true);
    }

    public void unregisterOutput(int priority) {
        outputMiddlewareMap.remove(priority);
        outputEnabledMap.remove(priority);
    }

    public void setOutputEnabled(int priority, boolean enabled) {
        if (outputMiddlewareMap.containsKey(priority)) {
            outputEnabledMap.put(priority, enabled);
        }
    }

    public List<EnvelopeMiddleware> getActiveOutputMiddlewares() {
        List<EnvelopeMiddleware> list = new ArrayList<>();
        for (Map.Entry<Integer, EnvelopeMiddleware> entry : outputMiddlewareMap.entrySet()) {
            if (Boolean.TRUE.equals(outputEnabledMap.get(entry.getKey()))) {
                list.add(entry.getValue());
            }
        }
        logger.debug("getActiveOutputMiddlewares {}", list);
        return list;
    }

    public NavigableMap<Integer, EnvelopeMiddleware> getInputMiddlewareMap() {
        return inputMiddlewareMap;
    }

    public NavigableMap<Integer, EnvelopeMiddleware> getOutputMiddlewareMap() {
        return outputMiddlewareMap;
    }

    public Map<Integer, Boolean> getInputEnabledMap() {
        return inputEnabledMap;
    }

    public Map<Integer, Boolean> getOutputEnabledMap() {
        return outputEnabledMap;
    }

    public void registerExceptionMiddleware(ExceptionMiddleware mw) {
        exceptionMiddlewareList.add(mw);
    }

    public List<ExceptionMiddleware> getExceptionMiddlewareList() {
        return exceptionMiddlewareList;
    }
}
