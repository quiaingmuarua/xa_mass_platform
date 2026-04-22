package com.xa.mass.api.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractUnknownFieldRequest {

    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    @JsonAnySetter
    protected void captureUnknownField(String key, Object value) {
        unknownFields.put(key, value);
    }

    @JsonIgnore
    public List<String> getUnknownFieldNames() {
        return unknownFields.keySet().stream().sorted().toList();
    }

    @JsonIgnore
    public boolean hasUnknownFields() {
        return !unknownFields.isEmpty();
    }
}
