package com.xa.mass.base.jsondsl.model;

import java.util.Map;
import java.util.Objects;

/**
 * JSON-DSL 上下文配置
 * <p>
 * 包含 MODEL、COUNT 等核心参数，用于对象生成和处理的上下文信息
 * </p>
 */
public class JsonDslContext {
    
    /**
     * 模型类名或注册的别名
     */
    private String model;
    
    /**
     * 生成对象数量，默认为 1
     */
    private Integer count = 1;
    
    /**
     * 集合类型，支持 LIST、SET 等
     */
    private String type;
    
    /**
     * 作用域名称，用于变量查找
     */
    private String scopeName;
    
    /**
     * 父作用域引用
     */
    private String parentScope;
    
    /**
     * 额外的上下文参数
     */
    private Map<String, Object> parameters;
    
    /**
     * 是否启用调试模式
     */
    private Boolean debug = false;
    
    /**
     * 是否启用严格模式（更严格的类型检查）
     */
    private Boolean strict = false;
    
    // ==================== 构造函数 ====================
    
    public JsonDslContext() {}
    
    public JsonDslContext(String model) {
        this.model = model;
    }
    
    public JsonDslContext(String model, Integer count) {
        this.model = model;
        this.count = count;
    }
    
    // ==================== 验证方法 ====================
    
    /**
     * 验证上下文配置的有效性
     */
    public void validate() {
        if (count != null && count < 0) {
            throw new IllegalArgumentException("count 不能为负数");
        }
        
        if (type != null && !isValidType(type)) {
            throw new IllegalArgumentException("不支持的集合类型: " + type);
        }
    }
    
    private boolean isValidType(String type) {
        return "LIST".equals(type) || "SET".equals(type) || "MAP".equals(type);
    }
    
    // ==================== 便捷方法 ====================
    
    /**
     * 获取参数值
     */
    public Object getParameter(String key) {
        return parameters != null ? parameters.get(key) : null;
    }
    
    /**
     * 设置参数值
     */
    public void setParameter(String key, Object value) {
        if (parameters == null) {
            parameters = new java.util.HashMap<>();
        }
        parameters.put(key, value);
    }
    
    /**
     * 检查是否包含指定参数
     */
    public boolean hasParameter(String key) {
        return parameters != null && parameters.containsKey(key);
    }
    
    /**
     * 获取完整的作用域路径
     */
    public String getFullScopePath() {
        if (parentScope != null) {
            return parentScope + "." + scopeName;
        }
        return scopeName;
    }
    
    // ==================== Getter/Setter ====================
    
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
        this.type = type;
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
    
    // ==================== equals/hashCode/toString ====================
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JsonDslContext that = (JsonDslContext) o;
        return Objects.equals(model, that.model) &&
               Objects.equals(count, that.count) &&
               Objects.equals(type, that.type) &&
               Objects.equals(scopeName, that.scopeName);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(model, count, type, scopeName);
    }
    
    @Override
    public String toString() {
        return "JsonDslContext{" +
                "model='" + model + '\'' +
                ", count=" + count +
                ", type='" + type + '\'' +
                ", scopeName='" + scopeName + '\'' +
                ", debug=" + debug +
                '}';
    }
} 