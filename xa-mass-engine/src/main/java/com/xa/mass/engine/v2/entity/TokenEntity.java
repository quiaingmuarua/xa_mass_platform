package com.xa.mass.engine.v2.entity;

public class  TokenEntity {
    private String tokenId;
    private String deviceId;
    private String project;
    private String country;
    private String platform;
    private String tokenStatus;      // 枚举用int或string都可
    private long lastUserTime;       //上次使用事件
    private long expireTime;         // ms
    private long createTime;         // ms
    private long updateTime;         // ms


    public String getDeviceId() {
        return deviceId;
    }

    public String getProject() {
        return project;
    }
}