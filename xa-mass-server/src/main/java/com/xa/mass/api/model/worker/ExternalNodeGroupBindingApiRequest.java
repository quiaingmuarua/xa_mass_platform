package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalNodeGroupBindingApiRequest extends AbstractUnknownFieldRequest {

    private String adapterNodeId;
    private String workerGroupId;
    private String pluginVersion;
    private String deploymentVersion;
    private Boolean enabled;
    private Boolean draining;
    private Map<String, String> attributes;

    public String getAdapterNodeId() {
        return adapterNodeId;
    }

    public void setAdapterNodeId(String adapterNodeId) {
        this.adapterNodeId = adapterNodeId;
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    public void setWorkerGroupId(String workerGroupId) {
        this.workerGroupId = workerGroupId;
    }

    public String getPluginVersion() {
        return pluginVersion;
    }

    public void setPluginVersion(String pluginVersion) {
        this.pluginVersion = pluginVersion;
    }

    public String getDeploymentVersion() {
        return deploymentVersion;
    }

    public void setDeploymentVersion(String deploymentVersion) {
        this.deploymentVersion = deploymentVersion;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getDraining() {
        return draining;
    }

    public void setDraining(Boolean draining) {
        this.draining = draining;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }
}
