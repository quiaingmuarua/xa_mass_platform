package com.xa.mass.core.getway.dispatcher;

import com.google.gson.Gson;
import com.xa.mass.core.model.message.MassMessage;
import com.xa.mass.core.model.message.enums.MessageType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SingleAppMessageHandlerRegistry {

    private static final Gson gson = new Gson();
    private static final Map<String, MassMessageHandler> handlerMap = new ConcurrentHashMap<>();

    private final Map<String, Map<MessageType, MassMessageHandler>> appHandlerMap = new HashMap<>();

    public static void registerWithApp(MessageType type, String subMsgType, MassMessageHandler handler) {
        String key = type.name() + "::" + subMsgType;
        handlerMap.put(key, handler);
    }

    public static Optional<MassMessageHandler> resolveWithApp(MassMessage msg) {
        String key = msg.getMsgType().name() + "::" + msg.getSubMsgType();
        return Optional.ofNullable(handlerMap.get(key));
    }

}
