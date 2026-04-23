package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkerSendMessageRequest extends AbstractWorkerRequest {
    private String msgType;
    private String subMsgType;
    private Object payload;

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
