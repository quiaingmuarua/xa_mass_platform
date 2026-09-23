package com.xa.mass.server.worker.observation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Fixed event selection and a pure, Worker-local Platform Properties patch.
 * One Server Properties handler combines all definitions. Within one drain, each Worker is
 * projected in selection-first-appearance order; a projection is not an independent queue consumer.
 * Each projection receives its times in notification receipt order, without sorting or deduplication,
 * and sees preceding local patches. Later patches overwrite overlapping fields before one write.
 * For example, A(10), B(20), A(30) invokes A([10,30]) then B([20]); projections must not depend on
 * replaying the original interleaving across selections.
 */
public record WorkerPropertyProjection(
        String workerGroupId,
        String messageEventName,
        String observationEventName,
        BiFunction<Map<String, Object>, List<Long>, Map<String, Object>> project
) {
    public WorkerPropertyProjection {
        Objects.requireNonNull(workerGroupId, "workerGroupId");
        Objects.requireNonNull(messageEventName, "messageEventName");
        Objects.requireNonNull(observationEventName, "observationEventName");
        Objects.requireNonNull(project, "project");
    }
}
