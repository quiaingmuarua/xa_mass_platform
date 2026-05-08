package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-owned bounded compatibility audit view for one concrete attempt.
 */
@CompatibilityProjectionOnly
public record CompatibilityTaskMessageAttemptView(String attemptId,
                                                  String taskId,
                                                  String messageId,
                                                  int attemptNo,
                                                  String workerId,
                                                  String workerContextId,
                                                  String batchId,
                                                  String status,
                                                  LocalDateTime leaseExpireTime,
                                                  LocalDateTime dispatchTime,
                                                  LocalDateTime ackTime,
                                                  LocalDateTime startTime,
                                                  LocalDateTime finishTime,
                                                  String finalReason,
                                                  String errorMessage,
                                                  String errorCode,
                                                  Map<String, Object> output,
                                                  LocalDateTime createTime,
                                                  LocalDateTime updateTime) {

    public CompatibilityTaskMessageAttemptView {
        output = copyMap(output);
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            return Map.of();
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
