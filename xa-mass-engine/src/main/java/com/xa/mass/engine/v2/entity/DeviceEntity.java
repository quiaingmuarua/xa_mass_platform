package com.xa.mass.engine.v2.entity;

import java.util.Map;

public class  DeviceEntity {
    private String deviceId;
    private String deviceStatus; //
    private String agentVersion;
    private String onlineStrategy;
    private String groupId;
    private Map<String,String> projectTokens; //key project, value tokenId
    private long lockExpireTime;
    private long lastHeartbeat;
    private long createTime;
    private long updateTime;


    public String getDeviceId() {
        return deviceId;
    }
}