package com.xa.mass.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.MessageFinalityOutcome;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import org.junit.jupiter.api.Test;

class TaskRuntimeResultDecisionMapperTest {

    @Test
    void logicalFinalBecomesAcceptedProgressAndTerminalCandidate() {
        var decision = TaskRuntimeResultDecisionMapper.toEngineDecision(
                MessageFinalityOutcome.logicalFinal("task-1", "message-1", 1, 86_400_000L));

        assertThat(decision.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.progressDirty()).isTrue();
        assertThat(decision.terminalCandidate()).isTrue();
        assertThat(decision.retryScheduled()).isFalse();
    }

    @Test
    void retryScheduledBecomesProgressDirtyRetryWakeupWithoutTerminalCandidate() {
        var decision = TaskRuntimeResultDecisionMapper.toEngineDecision(
                MessageFinalityOutcome.retryScheduled("task-1", "message-1", 1, 500L, "failed"));

        assertThat(decision.status()).isEqualTo(MessageFinalityStatus.RETRY_SCHEDULED);
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.progressDirty()).isTrue();
        assertThat(decision.terminalCandidate()).isFalse();
        assertThat(decision.retryScheduled()).isTrue();
        assertThat(decision.retryAtMillis()).isEqualTo(500L);
    }

    @Test
    void duplicateOrLateIsAcceptedNoop() {
        var decision = TaskRuntimeResultDecisionMapper.toEngineDecision(
                MessageFinalityOutcome.duplicateOrLate("task-1", "message-1", 1, "already final"));

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.progressDirty()).isFalse();
        assertThat(decision.terminalCandidate()).isFalse();
        assertThat(decision.retryScheduled()).isFalse();
    }

    @Test
    void missingOutcomeIsRejected() {
        var decision = TaskRuntimeResultDecisionMapper.toEngineDecision(null);

        assertThat(decision.status()).isEqualTo(MessageFinalityStatus.REJECTED);
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.progressDirty()).isFalse();
    }
}
