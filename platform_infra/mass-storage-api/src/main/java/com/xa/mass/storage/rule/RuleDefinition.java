package com.xa.mass.storage.rule;

/**
 * Worker-matching rule definition.
 *
 * <p>{@code content} is the canonical expression consumed by the rule
 * evaluator. {@code expression} and {@code desc} are legacy aliases kept for
 * mock-data compatibility; do not treat them as independent truth.
 */
public class RuleDefinition {
    private String id;
    private String name;
    private String description;
    private RuleType type;
    private String content;
    private String expression;
    private String desc;
    private int priority = 1;
    private boolean enabled = true;

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
        return description != null ? description : desc;
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
        return content != null ? content : expression;
    }

    public void setContent(String content) {
        this.content = content;
        this.expression = content;
    }

    /**
     * @deprecated Use {@link #getContent()}.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    public String getExpression() {
        return getContent();
    }

    /**
     * @deprecated Use {@link #setContent(String)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    public void setExpression(String expression) {
        this.expression = expression;
        this.content = expression;
    }

    /**
     * @deprecated Use {@link #getDescription()}.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    public String getDesc() {
        return getDescription();
    }

    /**
     * @deprecated Use {@link #setDescription(String)}.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    public void setDesc(String desc) {
        this.desc = desc;
        this.description = desc;
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
