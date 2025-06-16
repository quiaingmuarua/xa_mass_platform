package com.xa.mass.server.queue;

import com.xa.mass.model.message.MessageContext;
// import io.netty.channel.ChannelHandlerContext; //不再需要
// import io.netty.handler.codec.http.websocketx.TextWebSocketFrame; //不再需要

public class WebSocketMessage {
    private final String message;
    private final MessageContext messageContext; // 保留 MessageContext 以便查找 ChannelHandlerContext


    public WebSocketMessage(String message, MessageContext messageContext) {
        this.message = message;
        this.messageContext = messageContext;
    }

    public String getMessage() {
        return message;
    }

    public MessageContext getMessageContext() { // 方法名修改为 getMessageContext
        return messageContext;
    }
}