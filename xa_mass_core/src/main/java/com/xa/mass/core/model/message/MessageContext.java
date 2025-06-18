package com.xa.mass.core.model.message;

import lombok.Data;

@Data
public class MessageContext {
    private String deviceId;     // 物理设备 ID
    private String connRole;     // 连接角色（如 "app", "controller", "docker"）
    private String sessionId;    // 当前连接唯一标识（用于重连判断）
    private String tid;
    private Integer retryCount;
    private String lastAckMsgId;
    private String curStepId;
}
