package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalWorkerGroupDeclareApiRequest extends AbstractUnknownFieldRequest {

    private String groupId;
    private List<ExternalWorkerEventBindingApiRequest> eventBindings;
    private Map<String, String> defaultAttributes;
    private Integer defaultMaxConcurrentWork;

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public List<ExternalWorkerEventBindingApiRequest> getEventBindings() {
        return eventBindings;
    }

    public void setEventBindings(List<ExternalWorkerEventBindingApiRequest> eventBindings) {
        this.eventBindings = eventBindings;
    }

    public Map<String, String> getDefaultAttributes() {
        return defaultAttributes;
    }

    public void setDefaultAttributes(Map<String, String> defaultAttributes) {
        this.defaultAttributes = defaultAttributes;
    }

    public Integer getDefaultMaxConcurrentWork() {
        return defaultMaxConcurrentWork;
    }

    public void setDefaultMaxConcurrentWork(Integer defaultMaxConcurrentWork) {
        this.defaultMaxConcurrentWork = defaultMaxConcurrentWork;
    }
}
