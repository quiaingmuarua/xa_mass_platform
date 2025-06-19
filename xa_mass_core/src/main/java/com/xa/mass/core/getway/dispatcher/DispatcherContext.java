package com.xa.mass.core.getway.dispatcher;

import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.session.ServerSessionManager;
import com.google.gson.Gson;
import com.xa.mass.core.getway.middleware.MiddlewareRegistry;
import com.xa.mass.core.model.message.enums.MessageType;
import java.util.HashMap;
import java.util.Map;

public class DispatcherContext {
    public enum MiddlewareDirection { INPUT, OUTPUT }

    private final MessageQueue<Envelope> inputQueue;
    private final MessageQueue<Envelope> outputQueue;
    private final ServerSessionManager sessionManager;
    private final Gson gson;
    private final MiddlewareRegistry middlewareRegistry = new MiddlewareRegistry();
    private final Map<String, Map<MessageType, MessageHandler>> handlerMap = new HashMap<>();
    // ... 可扩展其它只读配置

    private MiddlewareDirection direction;

    public DispatcherContext(
            MessageQueue<Envelope> inputQueue,
            MessageQueue<Envelope> outputQueue,
            ServerSessionManager sessionManager,
            Gson gson
    ) {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
        this.sessionManager = sessionManager;
        this.gson = gson;
    }

    public MessageQueue<Envelope> getInputQueue() { return inputQueue; }
    public MessageQueue<Envelope> getOutputQueue() { return outputQueue; }
    public ServerSessionManager getSessionManager() { return sessionManager; }
    public Gson getGson() { return gson; }

    public MiddlewareDirection getDirection() { return direction; }
    public void setDirection(MiddlewareDirection direction) { this.direction = direction; }

    public MiddlewareRegistry getMiddlewareRegistry() { return middlewareRegistry; }
    public Map<String, Map<MessageType, MessageHandler>> getHandlerMap() { return handlerMap; }
    // ... 其它 getter
} 