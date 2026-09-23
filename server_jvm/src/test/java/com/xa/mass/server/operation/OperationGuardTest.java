package com.xa.mass.server.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OperationGuardTest {

    private final OperationGuard guard = new OperationGuard();

    @Test
    void rejectsOnlyTheSameRunningOperationAndReleasesAfterCompletion() {
        String result = guard.execute("export", "task-1", () -> {
            assertThatThrownBy(() -> guard.execute(
                    "export",
                    "task-1",
                    () -> "duplicate"
            )).isInstanceOfSatisfying(
                    OperationAlreadyRunningException.class,
                    error -> {
                        assertThat(error.namespace()).isEqualTo("export");
                        assertThat(error.resourceId()).isEqualTo("task-1");
                    }
            );
            assertThat(guard.execute(
                    "export",
                    "task-2",
                    () -> "other-task"
            )).isEqualTo("other-task");
            assertThat(guard.execute(
                    "prepare",
                    "task-1",
                    () -> "other-operation"
            )).isEqualTo("other-operation");
            return "completed";
        });

        assertThat(result).isEqualTo("completed");
        assertThat(guard.execute(
                "export",
                "task-1",
                () -> "retried"
        )).isEqualTo("retried");
    }

    @Test
    void releasesOperationAfterActionFailure() {
        assertThatThrownBy(() -> guard.execute(
                "export",
                "task-1",
                () -> {
                    throw new IllegalStateException("failed");
                }
        )).isInstanceOf(IllegalStateException.class);

        assertThat(guard.execute(
                "export",
                "task-1",
                () -> "retried"
        )).isEqualTo("retried");
    }
}
