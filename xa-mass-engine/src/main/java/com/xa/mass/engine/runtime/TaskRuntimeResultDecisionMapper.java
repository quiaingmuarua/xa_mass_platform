package com.xa.mass.engine.runtime;

import com.xa.mass.task.runtime.MessageFinalityOutcome;
import com.xa.mass.task.runtime.MessageFinalityStatus;

public final class TaskRuntimeResultDecisionMapper {

    private TaskRuntimeResultDecisionMapper() {
    }

    public static TaskRuntimeResultDecision toEngineDecision(MessageFinalityOutcome outcome) {
        if (outcome == null) {
            return rejected("missing task-runtime outcome");
        }
        boolean retryScheduled = outcome.status() == MessageFinalityStatus.RETRY_SCHEDULED;
        boolean accepted = outcome.status() != MessageFinalityStatus.REJECTED;
        return new TaskRuntimeResultDecision(
                outcome.status(),
                accepted,
                accepted && outcome.progressDirty(),
                accepted && outcome.terminalCandidate(),
                retryScheduled,
                retryScheduled ? outcome.retryAtMillis() : 0L,
                outcome.reason());
    }

    private static TaskRuntimeResultDecision rejected(String reason) {
        return new TaskRuntimeResultDecision(
                MessageFinalityStatus.REJECTED,
                false,
                false,
                false,
                false,
                0L,
                reason);
    }
}
