package com.xa.mass.base.jsondsl.model;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Execution context for the standardized JSON DSL.
 */
public class JsonDslContext {

    private String model;
    private Integer count = 1;
    private String type;
    private String scopeName;
    private String parentScope;
    private Map<String, Object> parameters;
    private Boolean debug = false;
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

        if (type != null && !isValidCollectionType(type)) {
            throw new JsonDslException("Unsupported context.type: " + type);
        }
    }

    private boolean isValidCollectionType(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        return "LIST".equals(normalized) || "SET".equals(normalized) || "MAP".equals(normalized);
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

    public String getFullScopePath() {
        if (scopeName == null || scopeName.isBlank()) {
            return parentScope;
        }
        if (parentScope == null || parentScope.isBlank()) {
            return scopeName;
        }
        return parentScope + "." + scopeName;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type == null ? null : type.toUpperCase(Locale.ROOT);
    }

    public String getScopeName() {
        return scopeName;
    }

    public void setScopeName(String scopeName) {
        this.scopeName = scopeName;
    }

    public String getParentScope() {
        return parentScope;
    }

    public void setParentScope(String parentScope) {
        this.parentScope = parentScope;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public Boolean getDebug() {
        return debug;
    }

    public void setDebug(Boolean debug) {
        this.debug = debug;
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
                && Objects.equals(type, that.type)
                && Objects.equals(scopeName, that.scopeName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(model, count, type, scopeName);
    }

    @Override
    public String toString() {
        return "JsonDslContext{"
                + "model='" + model + '\''
                + ", count=" + count
                + ", type='" + type + '\''
                + ", scopeName='" + scopeName + '\''
                + ", debug=" + debug
                + '}';
    }
}
