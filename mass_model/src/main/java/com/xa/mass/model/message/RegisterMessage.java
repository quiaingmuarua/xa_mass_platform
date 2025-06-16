package com.xa.mass.model.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class RegisterMessage extends BaseMessage {
    private String deviceId;
    private String connRole;
    private String lastAckMsgId;
    private String curStepId;
    private String platform;  // iOS/Docker etc.
    private String version;   // Client version
    private DeviceInfo deviceInfo;
} 