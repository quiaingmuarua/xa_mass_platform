package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalWorkerResultSubmitApiRequest extends AbstractUnknownFieldRequest {

    private String resultCorrelationRef;
    private boolean success;
    private String detail;
    private String errorCode;
    private Map<String, Object> output;

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

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output;
    }
}
