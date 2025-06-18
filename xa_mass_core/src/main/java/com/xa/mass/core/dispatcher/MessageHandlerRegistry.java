package com.xa.mass.core.dispatcher;


import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.enums.MessageType;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class MessageHandlerRegistry {
    private static final Map<String, MessageHandler> handlerMap = new ConcurrentHashMap<>();

    public static void register(MessageType type, String subMsgType, MessageHandler handler) {
        String key = type.name() + "::" + subMsgType;
        handlerMap.put(key, handler);
    }

    public static Optional<MessageHandler> resolve(MassMessage<?> msg) {
        String key = msg.getMsgType().name() + "::" + msg.getSubMsgType();
        return Optional.ofNullable(handlerMap.get(key));
    }
}
