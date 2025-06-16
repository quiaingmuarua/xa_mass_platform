package com.xa.mass.model.message;

import lombok.Data;

@Data
public class BaseMessage<T> {
    private String msgId;
    private MessageType msgType;
    private String subMsgType;
    private MessageDirection from;
    private MessageContext context;
    private T payload;                // 使用泛型代替 Object
    private MessageResult result;
}
