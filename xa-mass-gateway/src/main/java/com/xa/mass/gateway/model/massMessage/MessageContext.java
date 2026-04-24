package com.xa.mass.gateway.model.massMessage;

import com.xa.mass.transport.WorkerEndpointRoles;

public class MessageContext {
    private String workerId;     // Worker ID
    private String connRole;     // transport lane name; defaults to task_messages on the current adapter
    private String sessionId;    // 当前连接唯一标识（用于重连判断）
    private String tid;
    private Integer retryCount;
    private String lastAckMsgId;
    private String curStepId;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getConnRole() {
        return normalizeConnRole(connRole);
    }

    public void setConnRole(String connRole) {
        this.connRole = normalizeConnRole(connRole);
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

    /**
     * Canonical accessor for the task binding carried over the transport
     * boundary. {@link #getTid()} remains as the legacy alias.
     */
    public String getTaskId() {
        return tid;
    }

    public void setTid(String tid) {
        this.tid = tid;
    }

    public void setTaskId(String taskId) {
        this.tid = taskId;
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

    public String getLastAckMessageId() {
        return lastAckMsgId;
    }

    public void setLastAckMsgId(String lastAckMsgId) {
        this.lastAckMsgId = lastAckMsgId;
    }

    public void setLastAckMessageId(String lastAckMessageId) {
        this.lastAckMsgId = lastAckMessageId;
    }

    public String getCurStepId() {
        return curStepId;
    }

    public String getStepId() {
        return curStepId;
    }

    public void setCurStepId(String curStepId) {
        this.curStepId = curStepId;
    }

    public void setStepId(String stepId) {
        this.curStepId = stepId;
    }

    @Override
    public String toString() {
        return "MessageContext{" +
                "workerId='" + workerId + '\'' +
                ", connRole='" + getConnRole() + '\'' +
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
        return (workerId != null ? workerId.equals(that.workerId) : that.workerId == null) &&
                getConnRole().equals(that.getConnRole()) &&
                (sessionId != null ? sessionId.equals(that.sessionId) : that.sessionId == null) &&
                (tid != null ? tid.equals(that.tid) : that.tid == null) &&
                (retryCount != null ? retryCount.equals(that.retryCount) : that.retryCount == null) &&
                (lastAckMsgId != null ? lastAckMsgId.equals(that.lastAckMsgId) : that.lastAckMsgId == null) &&
                (curStepId != null ? curStepId.equals(that.curStepId) : that.curStepId == null);
    }

    @Override
    public int hashCode() {
        int result = workerId != null ? workerId.hashCode() : 0;
        result = 31 * result + getConnRole().hashCode();
        result = 31 * result + (sessionId != null ? sessionId.hashCode() : 0);
        result = 31 * result + (tid != null ? tid.hashCode() : 0);
        result = 31 * result + (retryCount != null ? retryCount.hashCode() : 0);
        result = 31 * result + (lastAckMsgId != null ? lastAckMsgId.hashCode() : 0);
        result = 31 * result + (curStepId != null ? curStepId.hashCode() : 0);
        return result;
    }

    private static String normalizeConnRole(String connRole) {
        if (connRole == null || connRole.isBlank()) {
            return WorkerEndpointRoles.TASK_DISPATCH;
        }
        return connRole.trim();
    }
}
