package com.xa.mass.base.jsondsl;

import java.util.HashMap;
import java.util.Map;

/**
 * 递归 mock/DSL 生成时的作用域上下文，支持多级作用域链。
 */
public class DslContext {
    private final Map<String, Object> variables = new HashMap<>();
    private final DslContext parent;
    private int depth = 0;
    private String scopeName;

    public DslContext() {
        this.parent = null;
    }
    public DslContext(DslContext parent) {
        this.parent = parent;
    }

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    public Object getVariable(String key) {
        if (variables.containsKey(key)) return variables.get(key);
        if (parent != null) return parent.getVariable(key);
        return null;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public void setScopeName(String scopeName) {
        this.scopeName = scopeName;
    }
    public String getScopeName() {
        return scopeName;
    }

    public DslContext getParent() {
        return parent;
    }
} 