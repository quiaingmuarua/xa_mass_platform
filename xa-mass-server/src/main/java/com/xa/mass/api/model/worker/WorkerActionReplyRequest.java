package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkerActionReplyRequest extends AbstractUnknownFieldRequest {

    private String replyRef;
    private boolean success;
    private String code;
    private String body;

    public String getReplyRef() {
        return replyRef;
    }

    public void setReplyRef(String replyRef) {
        this.replyRef = replyRef;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
