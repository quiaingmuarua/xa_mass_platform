package com.xa.mass.testing.chaos.support;

import com.xa.mass.sdk.SdkTaskMessageAttemptView;

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
        public static AttemptOutcomeSnapshot fromAttempt(SdkTaskMessageAttemptView attempt) {
            return new AttemptOutcomeSnapshot(
                    attempt.attemptNo(),
                    attempt.attemptId(),
                    attempt.workerId(),
                    attempt.workerContextId(),
                    attempt.batchId(),
                    attempt.status(),
                    attempt.finalReason(),
                    attempt.leaseExpireTime() != null ? String.valueOf(attempt.leaseExpireTime()) : null
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
