package com.xa.mass.core.getway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageResult;
import com.xa.mass.core.model.message.enums.MessageDirection;
import com.xa.mass.core.model.message.enums.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MessageHandlerRegistry {
    private static final Logger log = LoggerFactory.getLogger(MessageHandlerRegistry.class);
    private static final String GLOBAL = "GLOBAL";
    private final Gson gson = new Gson();
    // project -> (key -> handler)
    private final Map<String, Map<String, MassMessageHandler>> handlerMap = new ConcurrentHashMap<>();

    public void autoRegister() {
        // 注册全局 handler
        this.register(null, MessageType.PING, "", this::handlePing);
        this.register(null, MessageType.PONG, "", this::handlePong);
        this.register(null, MessageType.TASK, "", this::handleTask);
    }

    public void register(String project, MessageType type, String subMsgType, MassMessageHandler handler) {
        String key = MessageRouterKeys.of(type, subMsgType);
        String proj = (project == null || project.trim().isEmpty()) ? GLOBAL : project;
        handlerMap.computeIfAbsent(proj, k -> new ConcurrentHashMap<>()).put(key, handler);
        log.debug("Register handler for project={}, key={}, type={}, subMsgType={}, handler={}", proj, key, type, subMsgType, handler.getClass().getName());
    }

    public Optional<MassMessageHandler> resolve(String project, MessageType type, String subMsgType) {
        String key = MessageRouterKeys.of(type, subMsgType);
        String proj = (project == null || project.trim().isEmpty()) ? GLOBAL : project;
        // 先查 project 级
        MassMessageHandler handler = handlerMap.getOrDefault(proj, Collections.emptyMap()).get(key);
        if (handler != null) {
            log.info("Resolved handler for project '{}', key '{}': {}", proj, key, handler.getClass().getName());
            return Optional.of(handler);
        }
        // 再查全局
        if (!GLOBAL.equals(proj)) {
            handler = handlerMap.getOrDefault(GLOBAL, Collections.emptyMap()).get(key);
            if (handler != null) {
                log.info("Resolved global handler for key '{}': {}", key, handler.getClass().getName());
                return Optional.of(handler);
            }
        }
        log.warn("No message handler found for project '{}', key '{}'", proj, key);
        return Optional.empty();
    }

    public Optional<MassMessageHandler> resolve(MassMessage msg) {
        return resolve(msg.getProject(), msg.getMsgType(), msg.getSubMsgType());
    }

    private List<MassMessage> handlePing(MassMessage msg) {
        log.info("Received Ping from {}/{}", msg.getContext().getDeviceId(), msg.getContext().getConnRole());
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
        log.info("Received PONG from {}/{}", msg.getContext().getDeviceId(), msg.getContext().getConnRole());
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
