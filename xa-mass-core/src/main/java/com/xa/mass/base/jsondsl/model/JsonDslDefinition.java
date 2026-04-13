package com.xa.mass.base.jsondsl.model;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;

import java.util.Map;
import java.util.Objects;

/**
 * 标准 JSON-DSL 定义结构体
 * <p>
 * 提供统一的 DSL 结构规范，支持生成、过滤等多种类型，
 * 便于扩展、调试和问题排查。
 * </p>
 */
public class JsonDslDefinition {

    /**
     * DSL 唯一标识符，用于调试、日志追踪和缓存
     */
    private String uniqueId;

    // ==================== 核心字段 ====================
    /**
     * DSL 类型：generate|filter|transform|validate
     */
    private DslType type;
    /**
     * 执行优先级，数字越小优先级越高，默认为 1
     */
    private Integer priority = 1;
    /**
     * DSL 描述信息，用于文档化和调试
     */
    private String description;
    /**
     * 版本号，用于 DSL 兼容性控制
     */
    private String version = "1.0";
    /**
     * 创建时间戳
     */
    private Long createTime;
    /**
     * 最后修改时间戳
     */
    private Long updateTime;
    /**
     * 上下文配置，包含 MODEL、COUNT 等核心参数
     */
    private JsonDslContext context;

    // ==================== 配置字段 ====================
    /**
     * 字段 DSL 配置，定义各字段的生成规则
     */
    private Map<String, Object> fieldDsl;
    /**
     * 组合规则配置，支持多字段联合判断
     */
    private Map<String, Object> combineDsl;
    /**
     * 扩展配置，用于未来功能扩展
     */
    private Map<String, Object> extensions;
    /**
     * 标签列表，用于分类和筛选
     */
    private String[] tags;

    // ==================== 元数据字段 ====================
    /**
     * 作者信息
     */
    private String author;
    /**
     * 是否启用，默认为 true
     */
    private Boolean enabled = true;
    /**
     * 是否缓存结果，默认为 false
     */
    private Boolean cacheable = false;
    /**
     * 缓存过期时间（秒），默认 300 秒
     */
    private Integer cacheExpireSeconds = 300;

    public JsonDslDefinition() {
        this.createTime = System.currentTimeMillis();
        this.updateTime = this.createTime;
    }

    // ==================== 构造函数 ====================

    public JsonDslDefinition(String uniqueId, DslType type) {
        this();
        this.uniqueId = uniqueId;
        this.type = type;
    }

    /**
     * 验证 DSL 定义的有效性
     */
    public void validate() {
        if (uniqueId == null || uniqueId.trim().isEmpty()) {
            throw new JsonDslException("uniqueId 不能为空");
        }

        if (type == null) {
            throw new JsonDslException("type 不能为空");
        }

        if (priority == null || priority < 0) {
            throw new JsonDslException("priority 必须大于等于 0");
        }

        // 根据类型进行特定验证
        switch (type) {
            case GENERATE:
                validateGenerateDsl();
                break;
            case FILTER:
                validateFilterDsl();
                break;
            case TRANSFORM:
                validateTransformDsl();
                break;
            case VALIDATE:
                validateValidateDsl();
                break;
        }
    }

    // ==================== 验证方法 ====================

    private void validateGenerateDsl() {
        if (context == null) {
            throw new JsonDslException("GENERATE 类型必须包含 context 配置");
        }
        if (context.getModel() == null || context.getModel().trim().isEmpty()) {
            throw new JsonDslException("GENERATE 类型必须指定 MODEL");
        }
    }

    private void validateFilterDsl() {
        if (fieldDsl == null || fieldDsl.isEmpty()) {
            throw new JsonDslException("FILTER 类型必须包含 fieldDsl 配置");
        }
    }

    private void validateTransformDsl() {
        if (fieldDsl == null || fieldDsl.isEmpty()) {
            throw new JsonDslException("TRANSFORM 类型必须包含 fieldDsl 配置");
        }
    }

    private void validateValidateDsl() {
        if (fieldDsl == null || fieldDsl.isEmpty()) {
            throw new JsonDslException("VALIDATE 类型必须包含 fieldDsl 配置");
        }
    }

    /**
     * 更新修改时间
     */
    public void touch() {
        this.updateTime = System.currentTimeMillis();
    }

    // ==================== 便捷方法 ====================

    /**
     * 检查是否过期（基于缓存时间）
     */
    public boolean isExpired() {
        if (!cacheable || cacheExpireSeconds == null) {
            return false;
        }
        return System.currentTimeMillis() - updateTime > cacheExpireSeconds * 1000L;
    }

    /**
     * 获取 DSL 的完整标识（包含类型）
     */
    public String getFullId() {
        return type.getCode() + ":" + uniqueId;
    }

    public String getUniqueId() {
        return uniqueId;
    }

    // ==================== Getter/Setter ====================

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
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JsonDslDefinition that = (JsonDslDefinition) o;
        return Objects.equals(uniqueId, that.uniqueId) && type == that.type;
    }

    // ==================== equals/hashCode/toString ====================

    @Override
    public int hashCode() {
        return Objects.hash(uniqueId, type);
    }

    @Override
    public String toString() {
        return "JsonDslDefinition{" +
                "uniqueId='" + uniqueId + '\'' +
                ", type=" + type +
                ", priority=" + priority +
                ", description='" + description + '\'' +
                ", version='" + version + '\'' +
                ", enabled=" + enabled +
                '}';
    }

    /**
     * DSL 类型枚举
     */
    public enum DslType {
        GENERATE("generate", "对象生成"),
        FILTER("filter", "对象过滤"),
        TRANSFORM("transform", "对象转换"),
        VALIDATE("validate", "对象验证");

        private final String code;
        private final String description;

        DslType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public static DslType fromCode(String code) {
            for (DslType type : values()) {
                if (type.code.equalsIgnoreCase(code)) {
                    return type;
                }
            }
            throw new JsonDslException("不支持的 DSL 类型: " + code);
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }
} 