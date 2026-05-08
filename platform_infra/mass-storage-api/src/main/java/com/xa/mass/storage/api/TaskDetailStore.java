package com.xa.mass.storage.api;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage seam for task-message compatibility projection and attempt detail.
 *
 * <p>Separated from {@link TaskStorage} so that the high-frequency
 * message/attempt write path can be routed to a different sink (e.g. a
 * trace/audit module) without touching the control-plane task storage or the
 * engine orchestration code. The current in-memory and JDBC implementations
 * implement both interfaces; future trace sinks implement only this one.
 *
 * <p>Callers should treat this seam in two tiers:
 * runtime-essential compatibility projection helpers used by result repair and
 * bounded convergence, and shell/debug reads that must not grow into
 * pagination, analytics, or durable-history contracts.
 *
 * <p>This seam exposes only neutral storage-edge projection records. Any
 * compatibility materialization belongs to engine-internal residue owners, not
 * to this storage contract.</p>
 */
public interface TaskDetailStore {

    /**
     * Engine-facing bounded projection upsert.
     *
     * <p>Mainline engine code should prefer this helper over open-coding
     * add/update CRUD flow so the residue seam stays a projection sink rather
     * than a message-CRUD owner.</p>
     */
    @CompatibilityProjectionOnly
    boolean upsertTaskMessageProjection(String taskId, TaskMessageProjection projection);

    /**
     * Engine-facing bounded projection snapshot read.
     *
     * <p>Mainline engine code should prefer this neutral snapshot view over
     * materializing compatibility residue directly.</p>
     */
    @CompatibilityProjectionOnly
    Optional<TaskMessageProjection> getTaskMessageProjection(String taskId, String messageId);

    @CompatibilityProjectionOnly
    List<TaskMessageProjection> getTaskMessageProjections(String taskId, int limit);

    /**
     * Engine-facing bounded latest-attempt projection upsert.
     *
     * <p>This helper exists so dispatch/result/expiry convergence can treat
     * attempt residue as one bounded write step instead of a CRUD lifecycle.</p>
     */
    @CompatibilityProjectionOnly
    boolean upsertTaskMessageAttemptProjection(String taskId,
                                               String messageId,
                                               TaskMessageAttemptProjection projection);

    @CompatibilityProjectionOnly
    List<TaskMessageAttemptProjection> getTaskMessageAttemptProjections(String taskId, String messageId);

    @CompatibilityProjectionOnly
    Optional<TaskMessageAttemptProjection> getLatestTaskMessageAttemptProjection(String taskId,
                                                                                 String messageId);

    @CompatibilityProjectionOnly
    default Optional<TaskMessageAttemptProjection> getLatestActiveTaskMessageAttemptProjection(String taskId,
                                                                                               String messageId) {
        List<TaskMessageAttemptProjection> projections = getTaskMessageAttemptProjections(taskId, messageId);
        for (int i = projections.size() - 1; i >= 0; i--) {
            TaskMessageAttemptProjection projection = projections.get(i);
            if (projection != null && projection.status() != null && !projection.status().isFinal()) {
                return Optional.of(projection);
            }
        }
        return Optional.empty();
    }

    /** Aggregate diagnostics only; these stats do not promote the residue rows to runtime truth. */
    TaskMessageStats getTaskMessageStats(String taskId);

    TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId);

    TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId);

    @CompatibilityProjectionOnly
    record TaskMessageProjection(String messageId,
                                 String taskId,
                                 Map<String, Object> input,
                                 String payloadRef,
                                 TaskMessageProjectionStatus status,
                                 LocalDateTime assignedTime,
                                 LocalDateTime createTime,
                                 LocalDateTime updateTime,
                                 LocalDateTime startTime,
                                 LocalDateTime completeTime,
                                 int retryCount,
                                 int maxRetryCount,
                                 String errorMessage,
                                 String errorCode,
                                 TaskMessageProjectionFinalReason finalReason,
                                 Map<String, Object> output,
                                 String latestAttemptId,
                                 String latestAttemptWorkerId,
                                 String latestAttemptWorkerContextId,
                                 String latestAttemptBatchId) {

        public TaskMessageProjection {
            input = copyMap(input);
            output = copyMap(output);
            retryCount = Math.max(0, retryCount);
            maxRetryCount = Math.max(0, maxRetryCount);
        }
    }

    @CompatibilityProjectionOnly
    record TaskMessageAttemptProjection(String attemptId,
                                        String taskId,
                                        String messageId,
                                        int attemptNo,
                                        String workerId,
                                        String workerContextId,
                                        String batchId,
                                        TaskMessageAttemptProjectionStatus status,
                                        TaskMessageAttemptProjectionFinalReason finalReason,
                                        String errorMessage,
                                        String errorCode,
                                        Map<String, Object> output) {

        public TaskMessageAttemptProjection {
            output = copyMap(output);
            attemptNo = Math.max(0, attemptNo);
        }
    }

    class TaskMessageStats {
        private final long total;
        private final long success;
        private final long failed;
        private final long expired;
        private final long processing;

        public TaskMessageStats(long total, long success, long failed, long expired, long processing) {
            this.total = total;
            this.success = success;
            this.failed = failed;
            this.expired = expired;
            this.processing = processing;
        }

        public long getTotal() { return total; }
        public long getSuccess() { return success; }
        public long getFailed() { return failed; }
        public long getExpired() { return expired; }
        public long getProcessing() { return processing; }

        public double getSuccessRate() {
            return total > 0 ? (double) success / total * 100 : 0.0;
        }

        public double getFailureRate() {
            return total > 0 ? (double) (failed + expired) / total * 100 : 0.0;
        }
    }

    class TaskMessageAttemptStats {
        private final long totalAttempts;
        private final long activeAttempts;
        private final long runningAttempts;
        private final long failedAttempts;
        private final long expiredAttempts;

        public TaskMessageAttemptStats(long totalAttempts,
                                       long activeAttempts,
                                       long runningAttempts,
                                       long failedAttempts,
                                       long expiredAttempts) {
            this.totalAttempts = totalAttempts;
            this.activeAttempts = activeAttempts;
            this.runningAttempts = runningAttempts;
            this.failedAttempts = failedAttempts;
            this.expiredAttempts = expiredAttempts;
        }

        public long getTotalAttempts() { return totalAttempts; }
        public long getActiveAttempts() { return activeAttempts; }
        public long getRunningAttempts() { return runningAttempts; }
        public long getFailedAttempts() { return failedAttempts; }
        public long getExpiredAttempts() { return expiredAttempts; }
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                throw new NullPointerException("map key");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
