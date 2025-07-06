package com.xa.mass.base.jsondsl.processor;

import java.util.HashMap;
import java.util.Map;

/**
 * DSL 处理上下文
 * <p>
 * 用于在处理器之间传递上下文信息，支持参数传递和状态共享
 * </p>
 */
public class ProcessingContext {
    
    private final Map<String, Object> parameters = new HashMap<>();
    private final Map<String, Object> variables = new HashMap<>();
    private boolean debug = false;
    private String scopeName;
    
    public ProcessingContext() {}
    
    public ProcessingContext(String scopeName) {
        this.scopeName = scopeName;
    }
    
    /**
     * 设置参数
     */
    public void setParameter(String key, Object value) {
        parameters.put(key, value);
    }
    
    /**
     * 获取参数
     */
    public Object getParameter(String key) {
        return parameters.get(key);
    }
    
    /**
     * 获取参数，支持默认值
     */
    public Object getParameter(String key, Object defaultValue) {
        return parameters.getOrDefault(key, defaultValue);
    }
    
    /**
     * 设置变量
     */
    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }
    
    /**
     * 获取变量
     */
    public Object getVariable(String key) {
        return variables.get(key);
    }
    
    /**
     * 获取变量，支持默认值
     */
    public Object getVariable(String key, Object defaultValue) {
        return variables.getOrDefault(key, defaultValue);
    }
    
    /**
     * 检查是否包含参数
     */
    public boolean hasParameter(String key) {
        return parameters.containsKey(key);
    }
    
    /**
     * 检查是否包含变量
     */
    public boolean hasVariable(String key) {
        return variables.containsKey(key);
    }
    
    /**
     * 获取所有参数
     */
    public Map<String, Object> getParameters() {
        return new HashMap<>(parameters);
    }
    
    /**
     * 获取所有变量
     */
    public Map<String, Object> getVariables() {
        return new HashMap<>(variables);
    }
    
    /**
     * 设置调试模式
     */
    public void setDebug(boolean debug) {
        this.debug = debug;
    }
    
    /**
     * 是否开启调试模式
     */
    public boolean isDebug() {
        return debug;
    }
    
    /**
     * 设置作用域名称
     */
    public void setScopeName(String scopeName) {
        this.scopeName = scopeName;
    }
    
    /**
     * 获取作用域名称
     */
    public String getScopeName() {
        return scopeName;
    }
    
    /**
     * 合并另一个上下文
     */
    public void merge(ProcessingContext other) {
        if (other != null) {
            this.parameters.putAll(other.parameters);
            this.variables.putAll(other.variables);
            this.scopeName = other.scopeName;
            this.debug = other.debug;
        }
    }
    
    /**
     * 创建子上下文
     */
    public ProcessingContext createChild(String childScopeName) {
        ProcessingContext child = new ProcessingContext(childScopeName);
        child.parameters.putAll(this.parameters);
        child.variables.putAll(this.variables);
        child.debug = this.debug;
        return child;
    }
} 