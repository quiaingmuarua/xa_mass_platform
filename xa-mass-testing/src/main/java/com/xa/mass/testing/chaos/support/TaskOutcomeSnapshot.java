package com.xa.mass.testing.chaos.support;

import com.xa.mass.sdk.model.TaskActiveLeaseSnapshot;
import com.xa.mass.sdk.model.TaskWorkStatsSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TaskOutcomeSnapshot(String taskId,
                                  String status,
                                  String terminalReason,
                                  RuntimeWorkOutcomeSnapshot runtime,
                                  List<MessageOutcomeSnapshot> messages) {

    public TaskOutcomeSnapshot(String taskId,
                               String status,
                               String terminalReason,
                               List<MessageOutcomeSnapshot> messages) {
        this(taskId, status, terminalReason, RuntimeWorkOutcomeSnapshot.empty(), messages);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("status", status);
        map.put("terminalReason", terminalReason);
        map.put("runtime", runtime.toMap());
        map.put("reviewMessages", messages.stream().map(MessageOutcomeSnapshot::toMap).toList());
        return map;
    }

    public record RuntimeWorkOutcomeSnapshot(long totalCount,
                                             long readyCount,
                                             long inflightCount,
                                             long delayedCount,
                                             long successCount,
                                             long failedCount,
                                             long expiredCount,
                                             long finalCount,
                                             long activeLeaseCount,
                                             List<ActiveLeaseOutcomeSnapshot> activeLeases) {
        private static RuntimeWorkOutcomeSnapshot empty() {
            return from(TaskWorkStatsSnapshot.EMPTY, List.of());
        }

        public static RuntimeWorkOutcomeSnapshot from(TaskWorkStatsSnapshot stats,
                                                       List<TaskActiveLeaseSnapshot> activeLeases) {
            TaskWorkStatsSnapshot effectiveStats = stats != null ? stats : TaskWorkStatsSnapshot.EMPTY;
            List<TaskActiveLeaseSnapshot> effectiveLeases = activeLeases != null ? activeLeases : List.of();
            return new RuntimeWorkOutcomeSnapshot(
                    effectiveStats.totalCount(),
                    effectiveStats.readyCount(),
                    effectiveStats.inflightCount(),
                    effectiveStats.delayedCount(),
                    effectiveStats.successCount(),
                    effectiveStats.failedCount(),
                    effectiveStats.expiredCount(),
                    effectiveStats.finalCount(),
                    effectiveLeases.size(),
                    effectiveLeases.stream().map(ActiveLeaseOutcomeSnapshot::fromLease).toList()
            );
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalCount", totalCount);
            map.put("readyCount", readyCount);
            map.put("inflightCount", inflightCount);
            map.put("delayedCount", delayedCount);
            map.put("successCount", successCount);
            map.put("failedCount", failedCount);
            map.put("expiredCount", expiredCount);
            map.put("finalCount", finalCount);
            map.put("activeLeaseCount", activeLeaseCount);
            map.put("activeLeases", activeLeases.stream().map(ActiveLeaseOutcomeSnapshot::toMap).toList());
            return map;
        }
    }

    public record ActiveLeaseOutcomeSnapshot(String messageId,
                                             String workerId,
                                             String batchId,
                                             int retryCount,
                                             String leaseExpireAt) {
        public static ActiveLeaseOutcomeSnapshot fromLease(TaskActiveLeaseSnapshot lease) {
            return new ActiveLeaseOutcomeSnapshot(
                    lease.messageId(),
                    lease.workerId(),
                    lease.batchId(),
                    lease.retryCount(),
                    lease.leaseExpireAt() != null ? String.valueOf(lease.leaseExpireAt()) : null
            );
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", messageId);
            map.put("workerId", workerId);
            map.put("batchId", batchId);
            map.put("retryCount", retryCount);
            map.put("leaseExpireAt", leaseExpireAt);
            return map;
        }
    }

    public record MessageOutcomeSnapshot(String messageId,
                                         String status,
                                         String finalReason,
                                         int retryCount,
                                         String latestAttemptWorkerId,
                                         List<AttemptOutcomeSnapshot> attempts) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", messageId);
            map.put("status", status);
            map.put("finalReason", finalReason);
            map.put("retryCount", retryCount);
            map.put("latestAttemptWorkerId", latestAttemptWorkerId);
            map.put("attempts", attempts.stream().map(AttemptOutcomeSnapshot::toMap).toList());
            return map;
        }
    }

    public record AttemptOutcomeSnapshot(int attemptNo,
                                         String attemptId,
                                         String workerId,
                                         String batchId,
                                         String status,
                                         String finalReason,
                                         String leaseExpireTime) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("attemptNo", attemptNo);
            map.put("attemptId", attemptId);
            map.put("workerId", workerId);
            map.put("batchId", batchId);
            map.put("status", status);
            map.put("finalReason", finalReason);
            map.put("leaseExpireTime", leaseExpireTime);
            return map;
        }
    }
}
