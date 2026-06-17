package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkerResultSubmissionRequest extends AbstractUnknownFieldRequest {

    private String resultCorrelationRef;
    private boolean success;
    private String resultCode;
    private String result;

    public String getResultCorrelationRef() {
        return resultCorrelationRef;
    }

    public void setResultCorrelationRef(String resultCorrelationRef) {
        this.resultCorrelationRef = resultCorrelationRef;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getResultCode() {
        return resultCode;
    }

    public void setResultCode(String resultCode) {
        this.resultCode = resultCode;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }
}
