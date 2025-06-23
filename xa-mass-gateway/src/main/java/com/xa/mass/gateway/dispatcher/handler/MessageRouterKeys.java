package com.xa.mass.gateway.dispatcher.handler;

import com.xa.mass.gateway.model.enums.MessageType;

public class MessageRouterKeys {
    public static String of(MessageType msgType, String subMsgType) {
        return msgType.name() + "::" + (subMsgType != null ? subMsgType : "");
    }
}
