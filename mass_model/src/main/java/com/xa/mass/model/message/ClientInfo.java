package com.xa.mass.model.message;

import lombok.Data;
import lombok.Builder;

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