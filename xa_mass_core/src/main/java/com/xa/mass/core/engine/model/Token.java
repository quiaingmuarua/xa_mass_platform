package com.xa.mass.core.engine.model;

import com.xa.mass.core.engine.model.enums.TokenStatus;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Token实体（号码/授权资源）
 * 负责授权与分配，是调度核心桥梁
 * 与 Device 关联，生命周期与任务执行强相关
 */
public class Token {
    /**
     * 唯一标识
     */
    private String tokenId;
    
    /**
     * 所属设备
     */
    private String deviceId;
    
    /**
     * 状态
     */
    private TokenStatus status;
    
    /**
     * 通道/归属地
     */
    private String channel;
    
    /**
     * 最近分配的任务ID
     */
    private String lastBindTaskId;
    
    /**
     * 有效期/锁定期
     */
    private LocalDateTime expireTime;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
    
    /**
     * 最后使用时间
     */
    private LocalDateTime lastUsedTime;
    
    public Token() {
        this.status = TokenStatus.LOGIN_READY;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }
    
    public Token(String tokenId, String deviceId, String channel) {
        this();
        this.tokenId = tokenId;
        this.deviceId = deviceId;
        this.channel = channel;
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
    
    public TokenStatus getStatus() {
        return status;
    }
    
    public void setStatus(TokenStatus status) {
        this.status = status;
        this.updateTime = LocalDateTime.now();
        if (status == TokenStatus.SENDING) {
            this.lastUsedTime = LocalDateTime.now();
        }
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
    
    /**
     * 检查Token是否可以分配
     */
    public boolean isAllocatable() {
        return status.isAllocatable() && !isExpired();
    }
    
    /**
     * 检查Token是否正在使用中
     */
    public boolean isInUse() {
        return status.isInUse();
    }
    
    /**
     * 检查Token是否已过期
     */
    public boolean isExpired() {
        return expireTime != null && expireTime.isBefore(LocalDateTime.now());
    }
    
    /**
     * 检查Token是否可用
     */
    public boolean isAvailable() {
        return status.isAvailable() && !isExpired();
    }
    
    /**
     * 绑定到任务
     */
    public boolean bindToTask(String taskId) {
        if (status.canTransitionTo(TokenStatus.BIND_READY)) {
            setStatus(TokenStatus.BIND_READY);
            setLastBindTaskId(taskId);
            return true;
        }
        return false;
    }
    
    /**
     * 开始发送
     */
    public boolean startSending() {
        if (status.canTransitionTo(TokenStatus.SENDING)) {
            setStatus(TokenStatus.SENDING);
            return true;
        }
        return false;
    }
    
    /**
     * 释放Token
     */
    public boolean release() {
        if (status.canTransitionTo(TokenStatus.LOGIN_READY)) {
            setStatus(TokenStatus.LOGIN_READY);
            setLastBindTaskId(null);
            return true;
        }
        return false;
    }
    
    /**
     * 锁定Token
     */
    public boolean block() {
        if (status.canTransitionTo(TokenStatus.BLOCKED)) {
            setStatus(TokenStatus.BLOCKED);
            return true;
        }
        return false;
    }
    
    /**
     * 解锁Token
     */
    public boolean unblock() {
        if (status.canTransitionTo(TokenStatus.LOGIN_READY)) {
            setStatus(TokenStatus.LOGIN_READY);
            return true;
        }
        return false;
    }
    
    /**
     * 标记为失效
     */
    public boolean invalidate() {
        if (status.canTransitionTo(TokenStatus.INVALID)) {
            setStatus(TokenStatus.INVALID);
            return true;
        }
        return false;
    }
    
    /**
     * 状态转换
     */
    public boolean transitionTo(TokenStatus targetStatus) {
        if (status.canTransitionTo(targetStatus)) {
            setStatus(targetStatus);
            return true;
        }
        return false;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Token token = (Token) o;
        return Objects.equals(tokenId, token.tokenId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(tokenId);
    }
    
    @Override
    public String toString() {
        return "Token{" +
                "tokenId='" + tokenId + '\'' +
                ", deviceId='" + deviceId + '\'' +
                ", status=" + status +
                ", channel='" + channel + '\'' +
                ", lastBindTaskId='" + lastBindTaskId + '\'' +
                ", isExpired=" + isExpired() +
                '}';
    }
} 