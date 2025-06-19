package com.xa.mass.core.getway.middleware;

import com.xa.mass.core.getway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageContext;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
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

    static {
        // 自动注册主流程 middleware（优先级最大，保证在链尾）
        MiddlewareRegistry.instance.registerInput(Integer.MAX_VALUE, processEnvelopeMiddleware());
        MiddlewareRegistry.instance.registerOutput(Integer.MAX_VALUE, sendEnvelopeMiddleware());
    }

    List<ExceptionMiddleware> exceptionMiddlewareList = new ArrayList<>();

    private MiddlewareRegistry() {

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
        return list;
    }

    // 可作为 input/output middleware 链的最后一环
    public static EnvelopeMiddleware processEnvelopeMiddleware() {
        return (envelope, context) -> {
            try {
                MassMessage msg = context.getGson().fromJson(envelope.getRawJson(), MassMessage.class);
                if (msg == null || msg.getContext() == null) return true;
                MessageContext ctx = msg.getContext();
                Optional<com.xa.mass.core.getway.dispatcher.MessageHandler> handler = MessageHandlerRegistry.resolve(msg);
                if (handler.isPresent()) {
                    List<MassMessage> responses = handler.get().handle(msg);
                    if (responses != null) {
                        for (MassMessage resp : responses) {
                            String json = context.getGson().toJson(resp);
                            context.getOutputQueue().offer(Envelope.builder()
                                    .deviceId(ctx.getDeviceId())
                                    .connRole(ctx.getConnRole())
                                    .rawJson(json)
                                    .build());
                        }
                    }
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
                ChannelHandlerContext ctx = context.getSessionManager().getChannelContext(envelope.getDeviceId(), envelope.getConnRole());
                if (ctx != null && ctx.channel().isActive()) {
                    ctx.writeAndFlush(new TextWebSocketFrame(envelope.getRawJson()));
                }
            } catch (Exception e) {
                logger.error("Error in sendEnvelopeMiddleware", e);
                return false;
            }
            return true;
        };
    }


    public NavigableMap<Integer, EnvelopeMiddleware> getInputMiddlewareMap() { return inputMiddlewareMap; }
    public NavigableMap<Integer, EnvelopeMiddleware> getOutputMiddlewareMap() { return outputMiddlewareMap; }
    public Map<Integer, Boolean> getInputEnabledMap() { return inputEnabledMap; }
    public Map<Integer, Boolean> getOutputEnabledMap() { return outputEnabledMap; }

    public void registerExceptionMiddleware(ExceptionMiddleware mw) {
        exceptionMiddlewareList.add(mw);
    }

    public List<ExceptionMiddleware> getExceptionMiddlewareList() {
        return exceptionMiddlewareList;
    }

} 