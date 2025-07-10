package com.xa.mass.engine.v2.entity;

import lombok.Data;

@Data
public class TokenEntity {
    private String tokenId;
    private String deviceId;
    private String project;
    private String country;
    private String platform;
    private String tokenStatus;      // ACTIVE INACTIVE EXPIRED BLOCKED
    private long lastUserTime;       //上次使用时间
    private long expireTime;         // ms
    private long createTime;         // ms
    private long updateTime;         // ms

    // 构造函数
    public TokenEntity() {}

    public TokenEntity(String tokenId, String deviceId, String project) {
        this.tokenId = tokenId;
        this.deviceId = deviceId;
        this.project = project;
        this.tokenStatus = "ACTIVE";
        this.createTime = System.currentTimeMillis();
        this.updateTime = System.currentTimeMillis();
        this.lastUserTime = System.currentTimeMillis();
    }

    // 业务方法
    public boolean isActive() {
        return "ACTIVE".equals(tokenStatus);
    }

    public boolean isInactive() {
        return "INACTIVE".equals(tokenStatus);
    }

    public boolean isExpired() {
        return "EXPIRED".equals(tokenStatus) || (expireTime > 0 && System.currentTimeMillis() > expireTime);
    }

    public boolean isBlocked() {
        return "BLOCKED".equals(tokenStatus);
    }

    public boolean isUsable() {
        return isActive() && !isExpired() && !isBlocked();
    }

    public void markAsActive() {
        this.tokenStatus = "ACTIVE";
        this.updateTime = System.currentTimeMillis();
    }

    public void markAsInactive() {
        this.tokenStatus = "INACTIVE";
        this.updateTime = System.currentTimeMillis();
    }

    public void markAsExpired() {
        this.tokenStatus = "EXPIRED";
        this.updateTime = System.currentTimeMillis();
    }

    public void markAsBlocked() {
        this.tokenStatus = "BLOCKED";
        this.updateTime = System.currentTimeMillis();
    }

    public void updateLastUseTime() {
        this.lastUserTime = System.currentTimeMillis();
        this.updateTime = System.currentTimeMillis();
    }

    public void setExpireTime(long expireTime) {
        this.expireTime = expireTime;
        this.updateTime = System.currentTimeMillis();
    }

    public long getTimeUntilExpiry() {
        if (expireTime <= 0) {
            return Long.MAX_VALUE; // 永不过期
        }
        return Math.max(0, expireTime - System.currentTimeMillis());
    }

    public boolean isExpiringSoon(long thresholdMs) {
        return getTimeUntilExpiry() <= thresholdMs;
    }

    public long getTimeSinceLastUse() {
        return System.currentTimeMillis() - lastUserTime;
    }

    public boolean isIdle(long idleThresholdMs) {
        return getTimeSinceLastUse() > idleThresholdMs;
    }
}