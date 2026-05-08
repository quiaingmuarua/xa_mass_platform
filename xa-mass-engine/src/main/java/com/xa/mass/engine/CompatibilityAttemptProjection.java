package com.xa.mass.engine;

import com.xa.mass.base.annotation.CompatibilityProjectionOnly;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.storage.api.TaskDetailStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-local residue shape for concrete attempt compatibility projection.
 */
@CompatibilityProjectionOnly
record CompatibilityAttemptProjection(String attemptId,
                                      String taskId,
                                      String messageId,
                                      int attemptNo,
                                      String workerId,
                                      String workerContextId,
                                      String batchId,
                                      TaskMsgAttemptStatus status,
                                      TaskMsgAttemptFinalReason finalReason,
                                      String errorMessage,
                                      String errorCode,
                                      Map<String, Object> output) {

    CompatibilityAttemptProjection {
        output = copyMap(output);
        attemptNo = Math.max(0, attemptNo);
    }

    static CompatibilityAttemptProjection fromStorage(TaskDetailStore.TaskMessageAttemptProjection projection) {
        if (projection == null) {
            return null;
        }
        return new CompatibilityAttemptProjection(
                projection.attemptId(),
                projection.taskId(),
                projection.messageId(),
                projection.attemptNo(),
                projection.workerId(),
                projection.workerContextId(),
                projection.batchId(),
                projection.status(),
                projection.finalReason(),
                projection.errorMessage(),
                projection.errorCode(),
                projection.output()
        );
    }

    TaskDetailStore.TaskMessageAttemptProjection toStorageProjection() {
        return new TaskDetailStore.TaskMessageAttemptProjection(
                attemptId,
                taskId,
                messageId,
                attemptNo,
                workerId,
                workerContextId,
                batchId,
                status,
                finalReason,
                errorMessage,
                errorCode,
                output
        );
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
