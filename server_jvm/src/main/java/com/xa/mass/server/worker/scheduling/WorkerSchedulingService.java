package com.xa.mass.server.worker.scheduling;

import com.fasterxml.jackson.annotation.JsonValue;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class WorkerSchedulingService {

    private static final String PAUSE_OPERATION =
            "workerScheduling.pause";
    private static final String RESUME_OPERATION =
            "workerScheduling.resume";
    private static final String OBSERVE_OPERATION =
            "workerScheduling.observe";
    private static final int MAX_OBSERVE_WORKERS = 100;

    private final WorkerScoreCore workerScores;

    public WorkerSchedulingService(WorkerScoreCore workerScores) {
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
    }

    public PauseStatus pause(
            String workerGroupId,
            String workerId
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(workerId, "workerId");
        try {
            WorkerScoreTransitionResult result = requireResult(
                    workerScores.rewriteCurrentScores(
                            workerGroupId,
                            List.of(workerId),
                            WorkerScoreCore.PAUSE_TIME_MILLIS,
                            null
                    ),
                    workerId
            );
            return switch (result.status()) {
                case TRANSITIONED -> PauseStatus.PAUSED;
                case NOOP -> PauseStatus.ALREADY_PAUSED;
                case STALE -> {
                    if (result.score() != null) {
                        yield PauseStatus.ALREADY_PAUSED;
                    }
                    throw failure(
                            ServerErrorCode.WORKER_RESOURCE_NOT_FOUND,
                            PAUSE_OPERATION
                    );
                }
                case INVALID -> throw failure(
                        ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT,
                        PAUSE_OPERATION
                );
            };
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(PAUSE_OPERATION, error);
        }
    }

    public ResumeStatus resume(
            String workerGroupId,
            String workerId
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(workerId, "workerId");
        try {
            WorkerScoreCore.WorkerScoreState state = workerScores
                    .getScoreStates(workerGroupId, List.of(workerId))
                    .get(workerId);
            if (state == null) {
                throw failure(
                        ServerErrorCode.WORKER_RESOURCE_NOT_FOUND,
                        RESUME_OPERATION
                );
            }
            if (state.timeMillis()
                    != WorkerScoreCore.PAUSE_TIME_MILLIS) {
                return ResumeStatus.ALREADY_RESUMED;
            }
            WorkerScoreTransitionResult result = requireResult(
                    workerScores.releaseScoreHolds(
                            workerGroupId,
                            Map.of(workerId, state.score()),
                            System.currentTimeMillis()
                    ),
                    workerId
            );
            return switch (result.status()) {
                case TRANSITIONED -> ResumeStatus.RESUMED;
                case NOOP -> ResumeStatus.ALREADY_RESUMED;
                case STALE, INVALID -> throw failure(
                        ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT,
                        RESUME_OPERATION
                );
            };
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(RESUME_OPERATION, error);
        }
    }

    public WorkerSchedulingObservation observe(
            String workerGroupId,
            List<String> workerIds
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireWorkerIds(workerIds);
        try {
            Map<String, WorkerScoreCore.WorkerScoreState> states =
                    workerScores.getScoreStates(workerGroupId, workerIds);
            if (states == null
                    || !new LinkedHashSet<>(states.keySet()).equals(
                    new LinkedHashSet<>(workerIds)
            )) {
                throw new IllegalStateException(
                        "Worker score observation is incomplete"
                );
            }

            long readAtMillis = System.currentTimeMillis();
            long currentSlotMillis = readAtMillis
                    / WorkerScoreCore.SLOT_MILLIS
                    * WorkerScoreCore.SLOT_MILLIS;
            var projected = new LinkedHashMap<String, SchedulingState>();
            for (String workerId : workerIds) {
                WorkerScoreCore.WorkerScoreState state =
                        states.get(workerId);
                if (state != null && !workerId.equals(state.workerId())) {
                    throw new IllegalStateException(
                            "Worker score identity does not match"
                    );
                }
                projected.put(
                        workerId,
                        classify(state, currentSlotMillis)
                );
            }
            return new WorkerSchedulingObservation(
                    readAtMillis,
                    projected
            );
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(OBSERVE_OPERATION, error);
        }
    }

    private static SchedulingState classify(
            WorkerScoreCore.WorkerScoreState state,
            long currentSlotMillis
    ) {
        if (state == null) {
            return SchedulingState.MISSING;
        }
        if (state.timeMillis() == WorkerScoreCore.PAUSE_TIME_MILLIS) {
            return SchedulingState.PAUSED;
        }
        if (state.polarity()
                == WorkerScoreCore.WorkerScorePolarity.RECOVERY_RECHECK) {
            long coldTimeMillis = (WorkerScoreCore.MIN_TIME_SLOT + 1)
                    * WorkerScoreCore.SLOT_MILLIS;
            return state.timeMillis() <= coldTimeMillis
                    ? SchedulingState.COLD
                    : SchedulingState.RECOVERY;
        }
        return state.timeMillis() >= currentSlotMillis
                ? SchedulingState.HELD_HOT
                : SchedulingState.HOT_SCORE_OVERDUE;
    }

    private static WorkerScoreTransitionResult requireResult(
            Map<String, WorkerScoreTransitionResult> results,
            String workerId
    ) {
        if (results == null || results.get(workerId) == null) {
            throw new IllegalStateException(
                    "Worker score transition result is missing"
            );
        }
        return results.get(workerId);
    }

    private static ServerException unavailable(
            String operation,
            RuntimeException cause
    ) {
        return new ServerException(
                ServerErrorCode.WORKER_SCHEDULING_UNAVAILABLE,
                operation,
                "Worker scheduling score operation failed",
                cause
        );
    }

    private static ServerException failure(
            ServerErrorCode code,
            String operation
    ) {
        return new ServerException(code, operation, null, null);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
    }

    private static void requireWorkerIds(List<String> workerIds) {
        if (workerIds == null
                || workerIds.isEmpty()
                || workerIds.size() > MAX_OBSERVE_WORKERS
                || new LinkedHashSet<>(workerIds).size()
                != workerIds.size()) {
            throw new IllegalArgumentException(
                    "workerIds must contain 1..100 unique values"
            );
        }
        workerIds.forEach(workerId -> requireNonBlank(
                workerId,
                "workerId"
        ));
    }

    public enum SchedulingState {
        HOT_SCORE_OVERDUE("hot-score-overdue"),
        HELD_HOT("held-hot"),
        PAUSED("paused"),
        RECOVERY("recovery"),
        COLD("cold"),
        MISSING("missing");

        private final String wireValue;

        SchedulingState(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }

    public enum PauseStatus {
        PAUSED("paused"),
        ALREADY_PAUSED("already_paused");

        private final String wireValue;

        PauseStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }

    public enum ResumeStatus {
        RESUMED("resumed"),
        ALREADY_RESUMED("already_resumed");

        private final String wireValue;

        ResumeStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }

    public record WorkerSchedulingObservation(
            long readAtMillis,
            Map<String, SchedulingState> statesByWorkerId
    ) {
        public WorkerSchedulingObservation {
            statesByWorkerId = Collections.unmodifiableMap(
                    new LinkedHashMap<>(statesByWorkerId)
            );
        }
    }
}
