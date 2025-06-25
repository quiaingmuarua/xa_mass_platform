package com.xa.mass.engine.rules;

public class RuleDefinition {
    private String id;
    private RuleType type;
    private String content; // 规则体（脚本字符串或Json）
    private String desc;
    // 可扩展优先级/生效范围/适用对象等
    // ... getters/setters


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }
}