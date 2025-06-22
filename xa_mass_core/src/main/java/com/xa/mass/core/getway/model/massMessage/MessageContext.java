package com.xa.mass.core.getway.model.massMessage;

public class MessageContext {
    private String deviceId;     // 物理设备 ID
    private String connRole;     // 连接角色（如 "app", "controller", "docker"）
    private String sessionId;    // 当前连接唯一标识（用于重连判断）
    private String tid;
    private Integer retryCount;
    private String lastAckMsgId;
    private String curStepId;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getConnRole() {
        return connRole;
    }

    public void setConnRole(String connRole) {
        this.connRole = connRole;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTid() {
        return tid;
    }

    public void setTid(String tid) {
        this.tid = tid;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getLastAckMsgId() {
        return lastAckMsgId;
    }

    public void setLastAckMsgId(String lastAckMsgId) {
        this.lastAckMsgId = lastAckMsgId;
    }

    public String getCurStepId() {
        return curStepId;
    }

    public void setCurStepId(String curStepId) {
        this.curStepId = curStepId;
    }

    @Override
    public String toString() {
        return "MessageContext{" +
                "deviceId='" + deviceId + '\'' +
                ", connRole='" + connRole + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", tid='" + tid + '\'' +
                ", retryCount=" + retryCount +
                ", lastAckMsgId='" + lastAckMsgId + '\'' +
                ", curStepId='" + curStepId + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageContext that = (MessageContext) o;
        return (deviceId != null ? deviceId.equals(that.deviceId) : that.deviceId == null) &&
                (connRole != null ? connRole.equals(that.connRole) : that.connRole == null) &&
                (sessionId != null ? sessionId.equals(that.sessionId) : that.sessionId == null) &&
                (tid != null ? tid.equals(that.tid) : that.tid == null) &&
                (retryCount != null ? retryCount.equals(that.retryCount) : that.retryCount == null) &&
                (lastAckMsgId != null ? lastAckMsgId.equals(that.lastAckMsgId) : that.lastAckMsgId == null) &&
                (curStepId != null ? curStepId.equals(that.curStepId) : that.curStepId == null);
    }

    @Override
    public int hashCode() {
        int result = deviceId != null ? deviceId.hashCode() : 0;
        result = 31 * result + (connRole != null ? connRole.hashCode() : 0);
        result = 31 * result + (sessionId != null ? sessionId.hashCode() : 0);
        result = 31 * result + (tid != null ? tid.hashCode() : 0);
        result = 31 * result + (retryCount != null ? retryCount.hashCode() : 0);
        result = 31 * result + (lastAckMsgId != null ? lastAckMsgId.hashCode() : 0);
        result = 31 * result + (curStepId != null ? curStepId.hashCode() : 0);
        return result;
    }
}
