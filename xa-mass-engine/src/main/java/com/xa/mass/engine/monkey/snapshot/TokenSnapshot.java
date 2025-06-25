package com.xa.mass.engine.monkey.snapshot;

import java.time.LocalDateTime;

/**
 * Token属性快照
 */
public class TokenSnapshot {
    private String tokenId;
    private String deviceId;
    private String tokenStatus;
    private String channel;
    private String lastBindTaskId;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime lastUsedTime;
    private boolean isTokenAllocatable;
    private boolean isTokenAvailable;

    public TokenSnapshot() {
    }

    // Getters and Setters
    public String getTokenId() {
        return tokenId;
    }

    public void setTokenId(String tokenId) {
        this.tokenId = tokenId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getTokenStatus() {
        return tokenStatus;
    }

    public void setTokenStatus(String tokenStatus) {
        this.tokenStatus = tokenStatus;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getLastBindTaskId() {
        return lastBindTaskId;
    }

    public void setLastBindTaskId(String lastBindTaskId) {
        this.lastBindTaskId = lastBindTaskId;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public LocalDateTime getLastUsedTime() {
        return lastUsedTime;
    }

    public void setLastUsedTime(LocalDateTime lastUsedTime) {
        this.lastUsedTime = lastUsedTime;
    }

    public boolean isTokenAllocatable() {
        return isTokenAllocatable;
    }

    public void setTokenAllocatable(boolean tokenAllocatable) {
        isTokenAllocatable = tokenAllocatable;
    }

    public boolean isTokenAvailable() {
        return isTokenAvailable;
    }

    public void setTokenAvailable(boolean tokenAvailable) {
        isTokenAvailable = tokenAvailable;
    }
} 