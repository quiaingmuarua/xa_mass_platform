package com.xa.mass.server.worker.scheduling;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerSchedulingObservation;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerSchedulingChangeStatus;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class WorkerSchedulingService {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerSchedulingService.class.getName()
    );

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

    /** Facts have already committed; invalidation is best-effort and does not undo them. */
    public void invalidateCandidates(String workerGroupId, List<String> workerIds) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireWorkerIds(workerIds);
        try {
            Map<String, WorkerScoreTransitionResult> results =
                    workerScores.sealCurrentScoreHolds(workerGroupId, workerIds);
            long invalid = workerIds.stream().filter(workerId -> {
                WorkerScoreTransitionResult result = results == null ? null : results.get(workerId);
                return result == null || result.status() == WorkerScoreCore.WorkerScoreTransitionStatus.INVALID;
            }).count();
            if (invalid > 0) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "workerScheduling.invalidateCandidates: group={0}, workers={1}, invalidResults={2}",
                        workerGroupId, workerIds.size(), invalid);
            }
        } catch (RuntimeException error) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "workerScheduling.invalidateCandidates: group=" + workerGroupId
                            + ", workers=" + workerIds.size(), error);
        }
    }

    public ActionOutcome pause(
            String workerGroupId,
            String workerId
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(workerId, "workerId");
        try {
            return actionOutcome(workerScores.pauseScheduling(workerGroupId, workerId), PAUSE_OPERATION);
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(PAUSE_OPERATION, error);
        }
    }

    public ActionOutcome resume(
            String workerGroupId,
            String workerId
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(workerId, "workerId");
        try {
            return actionOutcome(workerScores.resumeScheduling(workerGroupId, workerId), RESUME_OPERATION);
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
            WorkerSchedulingObservation observation = workerScores.observeSchedulingStates(workerGroupId, workerIds);
            if (observation == null
                    || !new LinkedHashSet<>(observation.statesByWorkerId().keySet())
                            .equals(new LinkedHashSet<>(workerIds))
                    || observation.statesByWorkerId().values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException("Worker scheduling observation is incomplete");
            }
            return observation;
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(OBSERVE_OPERATION, error);
        }
    }

    private static ActionOutcome actionOutcome(WorkerSchedulingChangeStatus status, String operation) {
        return switch (Objects.requireNonNull(status, "Worker scheduling change status")) {
            case APPLIED -> ActionOutcome.applied();
            case UNCHANGED -> ActionOutcome.unchanged();
            case MISSING -> throw failure(ServerErrorCode.WORKER_RESOURCE_NOT_FOUND, operation);
            case CONFLICT -> throw failure(ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT, operation);
        };
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

}
