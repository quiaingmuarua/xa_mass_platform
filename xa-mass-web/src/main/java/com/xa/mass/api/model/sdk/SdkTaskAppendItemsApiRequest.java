package com.xa.mass.api.model.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public class SdkTaskAppendItemsApiRequest extends AbstractUnknownFieldRequest {

    private List<Object> inputs;

    public List<Object> getInputs() {
        return inputs;
    }

    public void setInputs(List<Object> inputs) {
        this.inputs = inputs;
    }
}
