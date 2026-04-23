package com.xa.mass.gateway.dispatcher.handler;

import com.xa.mass.gateway.model.enums.MessageType;

/**
 * Protocol-frame routing key builder for the gateway compatibility layer.
 *
 * <p>This key intentionally classifies transport frames only. It must not be
 * reused as a business-capability or control-plane identity key; those now
 * live on globally unique SDK event codes.
 */
public class MessageRouterKeys {

    public static String of(MessageType msgType, String subMsgType) {
        return msgType.name() + "::" + (subMsgType != null ? subMsgType : "");
    }
}
