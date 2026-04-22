package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkerSendMessageRequest extends AbstractUnknownFieldRequest {

    private String workerId;
    private String project;
    private String msgType;
    private String subMsgType;
    private Object payload;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public String getSubMsgType() {
        return subMsgType;
    }

    public void setSubMsgType(String subMsgType) {
        this.subMsgType = subMsgType;
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }
}
