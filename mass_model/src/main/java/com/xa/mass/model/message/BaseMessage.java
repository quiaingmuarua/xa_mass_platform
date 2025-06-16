package com.xa.mass.model.message;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class BaseMessage {
    private String msgId;
    private MessageType msgType;
    private String subMsgType;
    private MessageDirection from;
    private MessageContext context;
    private Object payload;
    private MessageResult result;
} 