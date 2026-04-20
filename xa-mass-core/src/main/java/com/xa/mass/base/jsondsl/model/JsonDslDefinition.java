package com.xa.mass.base.jsondsl.model;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;

import java.util.Map;
import java.util.Objects;

/**
 * Canonical model for the standardized JSON DSL.
 */
public class JsonDslDefinition {

    private String uniqueId;
    private DslType type;
    private Integer priority = 1;
    private String description;
    private String version = "1.0";
    private Long createTime;
    private Long updateTime;
    private JsonDslContext context;
    private Map<String, Object> fieldDsl;
    private Map<String, Object> combineDsl;
    private Map<String, Object> extensions;
    private String[] tags;
    private String author;
    private Boolean enabled = true;
    private Boolean cacheable = false;
    private Integer cacheExpireSeconds = 300;

    public JsonDslDefinition() {
        this.createTime = System.currentTimeMillis();
        this.updateTime = this.createTime;
    }

    public JsonDslDefinition(String uniqueId, DslType type) {
        this();
        this.uniqueId = uniqueId;
        this.type = type;
    }

    public void validate() {
        if (uniqueId == null || uniqueId.trim().isEmpty()) {
            throw new JsonDslException("uniqueId cannot be blank");
        }
        if (type == null) {
            throw new JsonDslException("type cannot be null");
        }
        if (priority == null || priority < 0) {
            throw new JsonDslException("priority must be greater than or equal to 0");
        }

        switch (type) {
            case GENERATE -> validateGenerateDsl();
            case FILTER, TRANSFORM, VALIDATE -> validateFieldOrCombineDsl(type);
        }
    }

    private void validateGenerateDsl() {
        if (context == null) {
            throw new JsonDslException("GENERATE DSL must include context");
        }
        context.validate();
        if (context.getModel() == null || context.getModel().trim().isEmpty()) {
            throw new JsonDslException("GENERATE DSL must define context.model");
        }
    }

    private void validateFieldOrCombineDsl(DslType currentType) {
        if (!hasFieldOrCombineDsl()) {
            throw new JsonDslException(currentType.getCode() + " DSL must include fieldDsl or combineDsl");
        }
    }

    public boolean hasFieldOrCombineDsl() {
        return (fieldDsl != null && !fieldDsl.isEmpty())
                || (combineDsl != null && !combineDsl.isEmpty());
    }

    public void touch() {
        this.updateTime = System.currentTimeMillis();
    }

    public boolean isExpired() {
        if (!Boolean.TRUE.equals(cacheable) || cacheExpireSeconds == null || updateTime == null) {
            return false;
        }
        return System.currentTimeMillis() - updateTime > cacheExpireSeconds * 1000L;
    }

    public String getFullId() {
        return (type != null ? type.getCode() : "unknown") + ":" + uniqueId;
    }

    public String getUniqueId() {
        return uniqueId;
    }

    public void setUniqueId(String uniqueId) {
        this.uniqueId = uniqueId;
    }

    public DslType getType() {
        return type;
    }

    public void setType(DslType type) {
        this.type = type;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }

    public JsonDslContext getContext() {
        return context;
    }

    public void setContext(JsonDslContext context) {
        this.context = context;
    }

    public Map<String, Object> getFieldDsl() {
        return fieldDsl;
    }

    public void setFieldDsl(Map<String, Object> fieldDsl) {
        this.fieldDsl = fieldDsl;
    }

    public Map<String, Object> getCombineDsl() {
        return combineDsl;
    }

    public void setCombineDsl(Map<String, Object> combineDsl) {
        this.combineDsl = combineDsl;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }

    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }

    public String[] getTags() {
        return tags;
    }

    public void setTags(String[] tags) {
        this.tags = tags;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getCacheable() {
        return cacheable;
    }

    public void setCacheable(Boolean cacheable) {
        this.cacheable = cacheable;
    }

    public Integer getCacheExpireSeconds() {
        return cacheExpireSeconds;
    }

    public void setCacheExpireSeconds(Integer cacheExpireSeconds) {
        this.cacheExpireSeconds = cacheExpireSeconds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        JsonDslDefinition that = (JsonDslDefinition) o;
        return Objects.equals(uniqueId, that.uniqueId) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(uniqueId, type);
    }

    @Override
    public String toString() {
        return "JsonDslDefinition{"
                + "uniqueId='" + uniqueId + '\''
                + ", type=" + type
                + ", priority=" + priority
                + ", description='" + description + '\''
                + ", version='" + version + '\''
                + ", enabled=" + enabled
                + '}';
    }

    public enum DslType {
        GENERATE("generate", "Object generation"),
        FILTER("filter", "Object filtering"),
        TRANSFORM("transform", "Object transformation"),
        VALIDATE("validate", "Object validation");

        private final String code;
        private final String description;

        DslType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public static DslType fromCode(String code) {
            if (code == null || code.isBlank()) {
                throw new JsonDslException("DSL type cannot be blank");
            }
            for (DslType type : values()) {
                if (type.code.equalsIgnoreCase(code) || type.name().equalsIgnoreCase(code)) {
                    return type;
                }
            }
            throw new JsonDslException("Unsupported DSL type: " + code);
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }
}
