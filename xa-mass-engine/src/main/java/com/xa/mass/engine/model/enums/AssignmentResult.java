package com.xa.mass.engine.model.enums;

/**
 * 分配结果枚举
 */
public enum AssignmentResult {
    SUCCESS("成功"),
    FAILED("失败"),
    CONFLICT("冲突"),
    SKIPPED("跳过"),
    RULE_NOT_MATCH("规则不匹配"),
    RESOURCE_UNAVAILABLE("资源不可用"),
    QUOTA_EXCEEDED("配额超限");

    private final String description;

    AssignmentResult(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
} 