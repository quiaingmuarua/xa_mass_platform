package com.xa.mass.core.engine.model.device;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ClientInfo {
    private String osVersion;
    private String deviceModel;
    private String deviceName;
    private String networkType;
    private String ipAddress;
    private String macAddress;
    private String appVersion;
    private String sdkVersion;
} 
