package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalAdapterNodeRegisterApiRequest extends AbstractUnknownFieldRequest {

    private String adapterNodeId;
    private String adapterType;
    private String adapterVersion;
    private String endpointId;
    private Boolean enabled;
    private Boolean online;
    private Map<String, String> attributes;

    public String getAdapterNodeId() {
        return adapterNodeId;
    }

    public void setAdapterNodeId(String adapterNodeId) {
        this.adapterNodeId = adapterNodeId;
    }

    public String getAdapterType() {
        return adapterType;
    }

    public void setAdapterType(String adapterType) {
        this.adapterType = adapterType;
    }

    public String getAdapterVersion() {
        return adapterVersion;
    }

    public void setAdapterVersion(String adapterVersion) {
        this.adapterVersion = adapterVersion;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public void setEndpointId(String endpointId) {
        this.endpointId = endpointId;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }
}
