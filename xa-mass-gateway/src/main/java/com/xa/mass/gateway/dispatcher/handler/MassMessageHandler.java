package com.xa.mass.gateway.dispatcher.handler;


import com.xa.mass.gateway.model.massMessage.MassMessage;

import java.util.List;

@FunctionalInterface
public interface MassMessageHandler {
    /**
     * 处理指定消息类型，返回需要发送的响应消息（可为 null 或空）
     */
    List<MassMessage> handle(MassMessage msg);
}
