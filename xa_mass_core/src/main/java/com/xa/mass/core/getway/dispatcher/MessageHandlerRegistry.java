package com.xa.mass.core.getway.dispatcher;


import com.google.gson.Gson;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.MessageResult;
import com.xa.mass.core.model.message.enums.MessageDirection;
import com.xa.mass.core.model.message.enums.MessageType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MessageHandlerRegistry {

    private static final Gson gson = new Gson();
    private static final Map<String, MessageHandler> handlerMap = new ConcurrentHashMap<>();

    static {
        MessageHandlerRegistry.register(MessageType.PING, "", MessageHandlerRegistry::handlePing);
        MessageHandlerRegistry.register(MessageType.PONG, "", MessageHandlerRegistry::handlePong);
        MessageHandlerRegistry.register(MessageType.TASK, "", MessageHandlerRegistry::handleTask);
    }

    private final Map<String, Map<MessageType, MessageHandler>> appHandlerMap = new HashMap<>();

    public static void register(MessageType type, String subMsgType, MessageHandler handler) {
        String key = MessageRouterKeys.of(type, subMsgType);
        handlerMap.put(key, handler);
    }

    public static Optional<MessageHandler> resolve(MassMessage msg) {
        String key = MessageRouterKeys.of(msg.getMsgType(), msg.getSubMsgType());
        return Optional.ofNullable(handlerMap.get(key));
    }

    private static List<MassMessage> handlePing(MassMessage msg) {
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

    private static List<MassMessage> handlePong(MassMessage msg) {
        System.out.println("Received PONG from " +
                msg.getContext().getDeviceId() + "/" + msg.getContext().getConnRole());
        return Collections.emptyList();
    }

    private static List<MassMessage> handleTask(MassMessage msg) {
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
