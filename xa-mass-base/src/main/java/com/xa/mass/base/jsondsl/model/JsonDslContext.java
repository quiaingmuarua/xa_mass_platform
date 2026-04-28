package com.xa.mass.base.jsondsl.model;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;

import java.util.Map;
import java.util.Objects;

/**
 * Execution context for the canonical typed JSON DSL.
 *
 * <p>Only fields consumed by the typed processor main path belong here.
 */
public class JsonDslContext {

    private String model;
    private Integer count = 1;
    private String scopeName;
    private Map<String, Object> parameters;
    private Boolean strict = false;

    public JsonDslContext() {
    }

    public JsonDslContext(String model) {
        this.model = model;
    }

    public JsonDslContext(String model, Integer count) {
        this.model = model;
        this.count = count;
    }

    public void validate() {
        if (count != null && count < 0) {
            throw new JsonDslException("context.count cannot be negative");
        }
    }

    public Object getParameter(String key) {
        return parameters != null ? parameters.get(key) : null;
    }

    public void setParameter(String key, Object value) {
        if (parameters == null) {
            parameters = new java.util.HashMap<>();
        }
        parameters.put(key, value);
    }

    public boolean hasParameter(String key) {
        return parameters != null && parameters.containsKey(key);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public String getScopeName() {
        return scopeName;
    }

    public void setScopeName(String scopeName) {
        this.scopeName = scopeName;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public Boolean getStrict() {
        return strict;
    }

    public void setStrict(Boolean strict) {
        this.strict = strict;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        JsonDslContext that = (JsonDslContext) o;
        return Objects.equals(model, that.model)
                && Objects.equals(count, that.count)
                && Objects.equals(scopeName, that.scopeName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(model, count, scopeName);
    }

    @Override
    public String toString() {
        return "JsonDslContext{"
                + "model='" + model + '\''
                + ", count=" + count
                + ", scopeName='" + scopeName + '\''
                + ", strict=" + strict
                + '}';
    }
}
