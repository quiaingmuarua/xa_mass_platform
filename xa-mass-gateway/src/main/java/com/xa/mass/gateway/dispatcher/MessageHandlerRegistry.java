package com.xa.mass.gateway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.dispatcher.handler.MessageRouterKeys;
import com.xa.mass.gateway.dispatcher.handler.ResolutionResult;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageHandlerRegistry {
    private static final Logger log = LoggerFactory.getLogger(MessageHandlerRegistry.class);
    private static final String GLOBAL = "GLOBAL";
    private final Gson gson = new Gson();
    // project -> (key -> handler)
    private final Map<String, Map<String, MassMessageHandler>> handlerMap = new ConcurrentHashMap<>();

    // 是否启用 fallback 机制
    private boolean enableFallback = true;

    public void autoRegister() {
        // 注册全局 handler
        this.register(GLOBAL, MessageType.PING, "heartbeat", this::handlePing);
        this.register(GLOBAL, MessageType.PONG, "heartbeat", this::handlePong);
        this.register(GLOBAL, MessageType.TASK, "", this::handleTask);
    }

    public void register(String project, MessageType type, String subMsgType, MassMessageHandler handler) {
        String key = MessageRouterKeys.of(type, subMsgType);
        String proj = (project == null || project.trim().isEmpty()) ? GLOBAL : project;
        handlerMap.computeIfAbsent(proj, k -> new ConcurrentHashMap<>()).put(key, handler);
        log.debug("Register handler for project={}, key={}, type={}, subMsgType={}, handler={}", proj, key, type, subMsgType, handler.getClass().getName());
    }

    public ResolutionResult resolve(String project, MessageType type, String subMsgType) {
        String key = MessageRouterKeys.of(type, subMsgType);
        String proj = (project == null || project.trim().isEmpty()) ? GLOBAL : project;

        // 先查 project 级
        MassMessageHandler handler = handlerMap.getOrDefault(proj, Collections.emptyMap()).get(key);
        if (handler != null) {
            log.info("Resolved handler for project '{}', key '{}': {}", proj, key, handler.getClass().getName());
            return ResolutionResult.found(handler, proj, type.name(), subMsgType, "project");
        }

        // 再查全局
        if (!GLOBAL.equals(proj)) {
            handler = handlerMap.getOrDefault(GLOBAL, Collections.emptyMap()).get(key);
            if (handler != null) {
                log.info("Resolved global handler for key '{}': {}", key, handler.getClass().getName());
                return ResolutionResult.found(handler, proj, type.name(), subMsgType, "global");
            }
        }

        // 如果启用 fallback，返回 fallback handler
        if (enableFallback) {
            log.warn("No message handler found for project '{}', key '{}', using fallback", proj, key);
            return ResolutionResult.fallback(proj, type.name(), subMsgType);
        }

        // 否则返回未找到
        log.warn("No message handler found for project '{}', key '{}'", proj, key);
        return ResolutionResult.notFound(proj, type.name(), subMsgType);
    }

    public ResolutionResult resolve(MassMessage msg) {
        return resolve(msg.getProject(), msg.getMsgType(), msg.getSubMsgType());
    }

    /**
     * 检查是否启用 fallback 机制
     */
    public boolean isEnableFallback() {
        return enableFallback;
    }

    /**
     * 设置是否启用 fallback 机制
     */
    public void setEnableFallback(boolean enableFallback) {
        this.enableFallback = enableFallback;
    }

    private List<MassMessage> handlePing(MassMessage msg) {
        log.info("Received Ping from {}/{}", msg.getContext().getWorkerId(), msg.getContext().getConnRole());
        MassMessage pong = new MassMessage();
        pong.setMsgId(msg.getMsgId());
        pong.setResponse(true);
        pong.setMsgType(MessageType.PONG);
        pong.setSubMsgType("");
        pong.setFrom(MessageDirection.SERVER);
        pong.setContext(msg.getContext());
        pong.setPayload(gson.toJsonTree(new MessageResult(200, "pong")));
        return Collections.singletonList(pong);
    }

    private List<MassMessage> handlePong(MassMessage msg) {
        log.info("Received PONG from {}/{}", msg.getContext().getWorkerId(), msg.getContext().getConnRole());
        return Collections.emptyList();
    }

    private List<MassMessage> handleTask(MassMessage msg) {
        MassMessage ack = new MassMessage();
        ack.setMsgId(msg.getMsgId());
        ack.setResponse(true);
        ack.setMsgType(MessageType.TASK);
        ack.setSubMsgType("");
        ack.setFrom(MessageDirection.SERVER);
        ack.setContext(msg.getContext());
        ack.setPayload(gson.toJsonTree(new MessageResult(200, "task received")));
        return Collections.singletonList(ack);
    }
}
