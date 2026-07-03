package com.xa.mass.task.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TaskScoreV1ContractTest {

    @Test
    void scoreRangesDefineDispatchVisibility() {
        long nowMillis = TaskScoreV1.TIME_SCORE_FLOOR + 1_000L;

        var terminal = TaskScoreV1.terminalClosed();
        var created = TaskScoreV1.createdNonSchedulable();
        var due = TaskScoreV1.dueAt(nowMillis);
        var future = TaskScoreV1.futureAt(nowMillis + 1_000L);
        var hold = TaskScoreV1.schedulerHold();

        assertThat(terminal.isTerminalBand()).isTrue();
        assertThat(terminal.isDueAt(nowMillis)).isFalse();

        assertThat(created.isNonSchedulableBand()).isTrue();
        assertThat(created.isDueAt(nowMillis)).isFalse();

        assertThat(due.isSchedulableTimeBand()).isTrue();
        assertThat(due.isDueAt(nowMillis)).isTrue();

        assertThat(future.isFutureAt(nowMillis)).isTrue();
        assertThat(future.isDueAt(nowMillis)).isFalse();

        assertThat(hold.isSchedulerHold()).isTrue();
        assertThat(hold.isDueAt(nowMillis)).isFalse();
    }

    @Test
    void dueTimeStaysBelowSchedulerHoldFloor() {
        var score = TaskScoreV1.dueAt(Long.MAX_VALUE);

        assertThat(score.score()).isEqualTo(TaskScoreV1.SCHEDULER_HOLD_FLOOR - 1L);
        assertThat(score.isSchedulerHold()).isFalse();
    }

    @Test
    void nonSchedulableAndTerminalCodesStayInSeparateRanges() {
        assertThatThrownBy(() -> TaskScoreV1.nonSchedulable(-1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskScoreV1.nonSchedulable(TaskScoreV1.TIME_SCORE_FLOOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskScoreV1.terminal(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
