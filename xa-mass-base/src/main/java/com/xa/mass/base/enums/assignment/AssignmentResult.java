package com.xa.mass.base.enums.assignment;

/**
 * Outcome of a worker or message assignment attempt.
 */
public enum AssignmentResult {
    SUCCESS("Success"),
    FAILED("Failed"),
    CONFLICT("Conflict"),
    SKIPPED("Skipped"),
    RULE_NOT_MATCH("Rule not matched"),
    RESOURCE_UNAVAILABLE("Resource unavailable"),
    QUOTA_EXCEEDED("Quota exceeded");

    private final String description;

    AssignmentResult(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
