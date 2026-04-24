package com.xa.mass.gateway.model.massMessage;

import com.google.gson.annotations.SerializedName;
import com.xa.mass.transport.WorkerEndpointRoles;

public class MessageContext {
    private String workerId;     // Worker ID
    private String connRole;     // transport lane name; defaults to task_messages on the current adapter
    private String sessionId;    // current transport session identity
    @SerializedName(value = "taskId", alternate = {"tid"})
    private String taskId;
    private Integer retryCount;
    @SerializedName(value = "lastAckMessageId", alternate = {"lastAckMsgId"})
    private String lastAckMessageId;
    @SerializedName(value = "stepId", alternate = {"curStepId"})
    private String stepId;

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

    /**
     * Legacy alias kept only for inbound compatibility while transport
     * producers move to {@link #getTaskId()}.
     */
    @Deprecated
    public String getTid() {
        return taskId;
    }

    public String getTaskId() {
        return taskId;
    }

    /**
     * Legacy alias kept only for inbound compatibility while transport
     * producers move to {@link #setTaskId(String)}.
     */
    @Deprecated
    public void setTid(String tid) {
        this.taskId = tid;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    /**
     * Legacy alias kept only for inbound compatibility while transport
     * producers move to {@link #getLastAckMessageId()}.
     */
    @Deprecated
    public String getLastAckMsgId() {
        return lastAckMessageId;
    }

    public String getLastAckMessageId() {
        return lastAckMessageId;
    }

    /**
     * Legacy alias kept only for inbound compatibility while transport
     * producers move to {@link #setLastAckMessageId(String)}.
     */
    @Deprecated
    public void setLastAckMsgId(String lastAckMsgId) {
        this.lastAckMessageId = lastAckMsgId;
    }

    public void setLastAckMessageId(String lastAckMessageId) {
        this.lastAckMessageId = lastAckMessageId;
    }

    /**
     * Legacy alias kept only for inbound compatibility while transport
     * producers move to {@link #getStepId()}.
     */
    @Deprecated
    public String getCurStepId() {
        return stepId;
    }

    public String getStepId() {
        return stepId;
    }

    /**
     * Legacy alias kept only for inbound compatibility while transport
     * producers move to {@link #setStepId(String)}.
     */
    @Deprecated
    public void setCurStepId(String curStepId) {
        this.stepId = curStepId;
    }

    public void setStepId(String stepId) {
        this.stepId = stepId;
    }

    @Override
    public String toString() {
        return "MessageContext{"
                + "workerId='" + workerId + '\''
                + ", connRole='" + getConnRole() + '\''
                + ", sessionId='" + sessionId + '\''
                + ", taskId='" + taskId + '\''
                + ", retryCount=" + retryCount
                + ", lastAckMessageId='" + lastAckMessageId + '\''
                + ", stepId='" + stepId + '\''
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageContext that = (MessageContext) o;
        return (workerId != null ? workerId.equals(that.workerId) : that.workerId == null)
                && getConnRole().equals(that.getConnRole())
                && (sessionId != null ? sessionId.equals(that.sessionId) : that.sessionId == null)
                && (taskId != null ? taskId.equals(that.taskId) : that.taskId == null)
                && (retryCount != null ? retryCount.equals(that.retryCount) : that.retryCount == null)
                && (lastAckMessageId != null ? lastAckMessageId.equals(that.lastAckMessageId) : that.lastAckMessageId == null)
                && (stepId != null ? stepId.equals(that.stepId) : that.stepId == null);
    }

    @Override
    public int hashCode() {
        int result = workerId != null ? workerId.hashCode() : 0;
        result = 31 * result + getConnRole().hashCode();
        result = 31 * result + (sessionId != null ? sessionId.hashCode() : 0);
        result = 31 * result + (taskId != null ? taskId.hashCode() : 0);
        result = 31 * result + (retryCount != null ? retryCount.hashCode() : 0);
        result = 31 * result + (lastAckMessageId != null ? lastAckMessageId.hashCode() : 0);
        result = 31 * result + (stepId != null ? stepId.hashCode() : 0);
        return result;
    }

    private static String normalizeConnRole(String connRole) {
        if (connRole == null || connRole.isBlank()) {
            return WorkerEndpointRoles.TASK_DISPATCH;
        }
        return connRole.trim();
    }
}
