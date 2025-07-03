package com.xa.mass.base.jsondsl;

import java.util.HashMap;
import java.util.Map;

/**
 * 递归 mock 时传递索引、变量、递归深度等上下文信息。
 */
public class DslContext {
    private final Map<String, Object> variables = new HashMap<>();
    private int depth = 0;

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    public Object getVariable(String key) {
        return variables.get(key);
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
} 