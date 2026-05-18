package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkerCommandSubmitApiRequest extends AbstractUnknownFieldRequest {

    private String workerId;
    private String commandId;
    private String commandType;
    private String requester;
    private String reason;
    private String idempotencyKey;
    private Long deadlineEpochMillis;
    private Map<String, Object> payload;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public String getCommandType() {
        return commandType;
    }

    public void setCommandType(String commandType) {
        this.commandType = commandType;
    }

    public String getRequester() {
        return requester;
    }

    public void setRequester(String requester) {
        this.requester = requester;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getDeadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    public void setDeadlineEpochMillis(Long deadlineEpochMillis) {
        this.deadlineEpochMillis = deadlineEpochMillis;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }
}
