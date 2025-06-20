package com.xa.mass.core.getway.dispatcher.handler;

import com.xa.mass.core.model.message.enums.MessageType;

public class MessageRouterKeys {
    public static String of(MessageType msgType, String subMsgType) {
        return msgType.name() + "::" + (subMsgType != null ? subMsgType : "");
    }
}
