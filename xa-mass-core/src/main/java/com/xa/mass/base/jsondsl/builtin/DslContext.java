package com.xa.mass.base.jsondsl.builtin;

import java.util.HashMap;
import java.util.Map;

/**
 * 递归 mock/DSL 生成时的作用域上下文，支持多级作用域链。
 *
 * 它提供更好的类型安全、验证和扩展性。新标准支持更丰富的上下文配置和元数据管理。
 */
public class DslContext {
    private final Map<String, Object> variables = new HashMap<>();
    private final DslContext parent;
    private int depth = 0;
    private String scopeName;
    private boolean strict;

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

    public String getScopeName() {
        return scopeName;
    }

    public void setScopeName(String scopeName) {
        this.scopeName = scopeName;
    }

    public DslContext getParent() {
        return parent;
    }

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }
}
