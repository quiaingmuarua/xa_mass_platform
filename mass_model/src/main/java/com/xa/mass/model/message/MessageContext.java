package com.xa.mass.model.message;

import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class MessageContext {
    private String deviceId;
    private String connRole;
    private String taskId;
    private Integer retryCount;
    private ResponseLevel responseLevel;
    private String lastAckMsgId;
    private String curStepId;
} 