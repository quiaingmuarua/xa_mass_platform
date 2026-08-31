package com.xa.mass.scenarioworkers;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ScenarioWorkerCommandCheckpoints implements AutoCloseable {

    static final long MAX_HOLD_MILLIS = 120_000L;

    private final Map<ScenarioWorkerCoordinate, Gate> gatesByWorker =
            new LinkedHashMap<>();
    private final Map<String, Gate> gatesByToken = new LinkedHashMap<>();

    private boolean closed;

    synchronized void arm(
            ScenarioWorkerCoordinate worker,
            String checkpointToken,
            long maximumHoldMillis
    ) {
        ensureOpen();
        Objects.requireNonNull(worker, "worker");
        requireToken(checkpointToken);
        if (maximumHoldMillis < 1L
                || maximumHoldMillis > MAX_HOLD_MILLIS) {
            throw new IllegalArgumentException(
                    "maximumHoldMillis must be in 1.."
                            + MAX_HOLD_MILLIS
            );
        }
        if (gatesByWorker.containsKey(worker)) {
            throw new CheckpointConflictException(
                    "Worker already has an active command checkpoint"
            );
        }
        if (gatesByToken.containsKey(checkpointToken)) {
            throw new CheckpointConflictException(
                    "checkpointToken is already active"
            );
        }
        Gate gate = new Gate(worker, checkpointToken, maximumHoldMillis);
        gatesByWorker.put(worker, gate);
        gatesByToken.put(checkpointToken, gate);
    }

    synchronized Snapshot snapshot(ScenarioWorkerCoordinate worker) {
        Objects.requireNonNull(worker, "worker");
        Gate gate = gatesByWorker.get(worker);
        if (gate == null) {
            throw new UnknownCheckpointException(
                    "Worker has no active command checkpoint"
            );
        }
        return gate.snapshot();
    }

    void release(ScenarioWorkerCoordinate worker) {
        Gate gate;
        synchronized (this) {
            Objects.requireNonNull(worker, "worker");
            gate = gatesByWorker.remove(worker);
            if (gate == null) {
                throw new UnknownCheckpointException(
                        "Worker has no active command checkpoint"
                );
            }
            gatesByToken.remove(gate.checkpointToken(), gate);
        }
        gate.release();
    }

    String awaitIfArmed(String checkpointToken) {
        requireToken(checkpointToken);
        Gate gate;
        synchronized (this) {
            if (closed) {
                return "bypassed";
            }
            gate = gatesByToken.get(checkpointToken);
        }
        if (gate == null) {
            return "bypassed";
        }
        return gate.awaitRelease();
    }

    @Override
    public void close() {
        ArrayList<Gate> releasing;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            releasing = new ArrayList<>(gatesByWorker.values());
            gatesByWorker.clear();
            gatesByToken.clear();
        }
        releasing.forEach(Gate::release);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Scenario command checkpoints are closed"
            );
        }
    }

    private static String requireToken(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(
                    "checkpointToken must contain 1..256 characters"
            );
        }
        return value;
    }

    enum State {
        ARMED,
        ENTERED,
        TIMED_OUT
    }

    record Snapshot(
            ScenarioWorkerCoordinate worker,
            String checkpointToken,
            long maximumHoldMillis,
            State state,
            Long enteredAtEpochMillis
    ) {
    }

    static final class CheckpointConflictException
            extends IllegalStateException {

        private CheckpointConflictException(String message) {
            super(message);
        }
    }

    static final class UnknownCheckpointException
            extends IllegalArgumentException {

        private UnknownCheckpointException(String message) {
            super(message);
        }
    }

    private static final class Gate {

        private final ScenarioWorkerCoordinate worker;
        private final String checkpointToken;
        private final long maximumHoldMillis;
        private final CountDownLatch release = new CountDownLatch(1);

        private State state = State.ARMED;
        private Long enteredAtEpochMillis;

        private Gate(
                ScenarioWorkerCoordinate worker,
                String checkpointToken,
                long maximumHoldMillis
        ) {
            this.worker = worker;
            this.checkpointToken = checkpointToken;
            this.maximumHoldMillis = maximumHoldMillis;
        }

        private String checkpointToken() {
            return checkpointToken;
        }

        private synchronized Snapshot snapshot() {
            return new Snapshot(
                    worker,
                    checkpointToken,
                    maximumHoldMillis,
                    state,
                    enteredAtEpochMillis
            );
        }

        private String awaitRelease() {
            synchronized (this) {
                if (state == State.TIMED_OUT) {
                    throw timedOut();
                }
                if (state == State.ARMED) {
                    state = State.ENTERED;
                    enteredAtEpochMillis = System.currentTimeMillis();
                }
            }

            boolean released;
            try {
                released = release.await(
                        maximumHoldMillis,
                        TimeUnit.MILLISECONDS
                );
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new WorkerException(
                        WorkerErrorCode.EVENT_EXECUTION_FAILED,
                        "checkpoint.await",
                        "Scenario command checkpoint was interrupted",
                        interrupted
                );
            }
            if (released) {
                return "released";
            }
            synchronized (this) {
                state = State.TIMED_OUT;
            }
            throw timedOut();
        }

        private void release() {
            release.countDown();
        }

        private WorkerException timedOut() {
            return new WorkerException(
                    WorkerErrorCode.EVENT_EXECUTION_FAILED,
                    "checkpoint.await",
                    "Scenario command checkpoint timed out",
                    null
            );
        }
    }
}
