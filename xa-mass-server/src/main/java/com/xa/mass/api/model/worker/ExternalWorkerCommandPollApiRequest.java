package com.xa.mass.api.model.worker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ExternalWorkerCommandPollApiRequest extends AbstractUnknownFieldRequest {

    private Integer maxCommands;

    public Integer getMaxCommands() {
        return maxCommands;
    }

    public void setMaxCommands(Integer maxCommands) {
        this.maxCommands = maxCommands;
    }
}
