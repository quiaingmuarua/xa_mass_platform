package com.xa.mass.core.model.message;

import com.xa.mass.core.model.message.enums.MessageDirection;
import com.xa.mass.core.model.message.enums.MessageType;
import lombok.Data;
import com.google.gson.JsonElement;

@Data
public class MassMessage {
    private String msgId;               // 全局唯一消息 ID
    private boolean response = false;   // 是否为响应消息（response消息可复用 MessageType）
    private MessageType msgType;        // 消息主类型，如 TASK、PING、RESPONSE 等
    private String subMsgType;          // 子类型，如 step、all、ack、configType 等
    private MessageDirection from;      // CLIENT / SERVER
    private MessageContext context;     // 通信上下文（设备ID、角色、连接信息等）
    private String appName="RCS";      // 所属应用名，如 WhatsApp、Telegram
    private JsonElement payload;                  // 实际消息体，统一为 JsonElement
}
