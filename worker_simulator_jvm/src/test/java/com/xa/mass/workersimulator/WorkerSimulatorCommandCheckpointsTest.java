package com.xa.mass.workersimulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.worker.error.WorkerException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class WorkerSimulatorCommandCheckpointsTest {

    private static final WorkerSimulatorCoordinate WORKER =
            new WorkerSimulatorCoordinate("group", "worker-1");

    @Test
    void enteredCheckpointBlocksUntilReleased() throws Exception {
        WorkerSimulatorCommandCheckpoints checkpoints =
                new WorkerSimulatorCommandCheckpoints();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            checkpoints.arm(WORKER, "token", 5_000L);
            Future<String> result = executor.submit(
                    () -> checkpoints.awaitIfArmed("token")
            );

            awaitEntered(checkpoints);
            assertThat(result).isNotDone();
            checkpoints.release(WORKER);

            assertThat(result.get()).isEqualTo("released");
            assertThatThrownBy(() -> checkpoints.snapshot(WORKER))
                    .isInstanceOf(WorkerSimulatorCommandCheckpoints
                            .UnknownCheckpointException.class);
        } finally {
            checkpoints.close();
            executor.shutdownNow();
        }
    }

    @Test
    void missingTokenBypassesAndDuplicateCoordinatesFail() {
        WorkerSimulatorCommandCheckpoints checkpoints =
                new WorkerSimulatorCommandCheckpoints();
        try {
            assertThat(checkpoints.awaitIfArmed("not-armed"))
                    .isEqualTo("bypassed");
            checkpoints.arm(WORKER, "token", 1_000L);
            assertThatThrownBy(() -> checkpoints.arm(
                    WORKER,
                    "other",
                    1_000L
            )).isInstanceOf(WorkerSimulatorCommandCheckpoints
                    .CheckpointConflictException.class);
            assertThatThrownBy(() -> checkpoints.arm(
                    new WorkerSimulatorCoordinate("group", "worker-2"),
                    "token",
                    1_000L
            )).isInstanceOf(WorkerSimulatorCommandCheckpoints
                    .CheckpointConflictException.class);
        } finally {
            checkpoints.close();
        }
    }

    @Test
    void timeoutIsBoundedAndCloseReleasesWaiter() throws Exception {
        WorkerSimulatorCommandCheckpoints timed =
                new WorkerSimulatorCommandCheckpoints();
        timed.arm(WORKER, "timeout", 1L);
        assertThatThrownBy(() -> timed.awaitIfArmed("timeout"))
                .isInstanceOf(WorkerException.class)
                .hasMessageContaining("timed out");
        assertThat(timed.snapshot(WORKER).state())
                .isEqualTo(WorkerSimulatorCommandCheckpoints.State.TIMED_OUT);
        timed.close();

        WorkerSimulatorCommandCheckpoints closing =
                new WorkerSimulatorCommandCheckpoints();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            closing.arm(WORKER, "close", 5_000L);
            Future<String> result = executor.submit(
                    () -> closing.awaitIfArmed("close")
            );
            awaitEntered(closing);

            closing.close();

            assertThat(result.get()).isEqualTo("released");
        } finally {
            closing.close();
            executor.shutdownNow();
        }
    }

    private static void awaitEntered(
            WorkerSimulatorCommandCheckpoints checkpoints
    ) throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (checkpoints.snapshot(WORKER).state()
                    == WorkerSimulatorCommandCheckpoints.State.ENTERED) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("checkpoint did not enter");
    }
}
