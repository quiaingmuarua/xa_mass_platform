package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-owned bounded compatibility view for one logical task message.
 *
 * <p>This keeps TaskMsg residue out of cross-module public reads while the
 * underlying compatibility projection still exists during migration.</p>
 */
@CompatibilityProjectionOnly
public record CompatibilityTaskMessageView(String messageId,
                                           String taskId,
                                           String status,
                                           String latestAttemptId,
                                           String latestAttemptWorkerId,
                                           String latestAttemptWorkerContextId,
                                           String latestAttemptBatchId,
                                           int retryCount,
                                           int maxRetryCount,
                                           String errorMessage,
                                           String errorCode,
                                           String finalReason,
                                           String payloadRef,
                                           Map<String, Object> input,
                                           Map<String, Object> output,
                                           LocalDateTime assignedTime,
                                           LocalDateTime createTime,
                                           LocalDateTime updateTime,
                                           LocalDateTime startTime,
                                           LocalDateTime completeTime) {

    public CompatibilityTaskMessageView {
        input = copyMap(input);
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
