package com.xa.mass.core.model.message;

import lombok.Data;

@Data
public class MessageContext {
    private String deviceId;
    private String connRole;
    private String taskId;
    private Integer retryCount;
    private ResponseLevel responseLevel;
    private String lastAckMsgId;
    private String curStepId;
} 
