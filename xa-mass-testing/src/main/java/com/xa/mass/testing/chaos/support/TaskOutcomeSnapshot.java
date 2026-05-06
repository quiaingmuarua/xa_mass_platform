package com.xa.mass.testing.chaos.support;

import com.xa.mass.base.model.TaskMsgAttempt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TaskOutcomeSnapshot(String taskId,
                                  String status,
                                  String terminalReason,
                                  List<MessageOutcomeSnapshot> messages) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("status", status);
        map.put("terminalReason", terminalReason);
        map.put("messages", messages.stream().map(MessageOutcomeSnapshot::toMap).toList());
        return Map.copyOf(map);
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
            return Map.copyOf(map);
        }
    }

    public record AttemptOutcomeSnapshot(int attemptNo,
                                         String attemptId,
                                         String workerId,
                                         String workerContextId,
                                         String batchId,
                                         String status,
                                         String finalReason,
                                         String leaseExpireTime) {
        public static AttemptOutcomeSnapshot fromAttempt(TaskMsgAttempt attempt) {
            return new AttemptOutcomeSnapshot(
                    attempt.getAttemptNo(),
                    attempt.getAttemptId(),
                    attempt.getWorkerId(),
                    attempt.getWorkerContextId(),
                    attempt.getBatchId(),
                    ChaosSupport.enumName(attempt.getStatus()),
                    ChaosSupport.enumName(attempt.getFinalReason()),
                    attempt.getLeaseExpireTime() != null ? String.valueOf(attempt.getLeaseExpireTime()) : null
            );
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("attemptNo", attemptNo);
            map.put("attemptId", attemptId);
            map.put("workerId", workerId);
            map.put("workerContextId", workerContextId);
            map.put("batchId", batchId);
            map.put("status", status);
            map.put("finalReason", finalReason);
            map.put("leaseExpireTime", leaseExpireTime);
            return Map.copyOf(map);
        }
    }
}
