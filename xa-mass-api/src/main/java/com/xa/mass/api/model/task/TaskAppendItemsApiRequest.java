package com.xa.mass.api.model.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public class TaskAppendItemsApiRequest extends AbstractUnknownFieldRequest {

    private List<Map<String, Object>> inputs;

    public List<Map<String, Object>> getInputs() {
        return inputs;
    }

    public void setInputs(List<Map<String, Object>> inputs) {
        this.inputs = inputs;
    }
}
