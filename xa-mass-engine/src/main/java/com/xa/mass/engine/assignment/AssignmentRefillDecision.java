package com.xa.mass.engine.assignment;

public record AssignmentRefillDecision(
        AssignmentRefillOutcome outcome,
        String reason
) {
    public boolean shouldRequestDispatch() {
        return outcome == AssignmentRefillOutcome.REQUEST_DISPATCH;
    }

    public static AssignmentRefillDecision requestDispatch(String reason) {
        return new AssignmentRefillDecision(AssignmentRefillOutcome.REQUEST_DISPATCH, reason);
    }

    public static AssignmentRefillDecision skip(String reason) {
        return new AssignmentRefillDecision(AssignmentRefillOutcome.SKIP, reason);
    }
}
