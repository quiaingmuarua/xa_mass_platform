package com.xa.mass.engine.rules;

public class RuleDefinition {
    private String id;
    private String name;
    private String description;
    private RuleType type;
    private String content; // 规则体（脚本字符串或Json）
    private String expression; // 规则表达式（与content相同，用于模板显示）
    private String desc;
    private int priority = 1;
    private boolean enabled = true;
    // 可扩展优先级/生效范围/适用对象等
    // ... getters/setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public RuleType getType() {
        return type;
    }

    public void setType(RuleType type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
        // 同时设置expression，保持一致性
        this.expression = content;
    }

    public String getExpression() {
        return expression != null ? expression : content;
    }

    public void setExpression(String expression) {
        this.expression = expression;
        // 同时设置content，保持一致性
        this.content = expression;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}