package com.xa.mass.server.worker.observation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        String observationEventName,
        Map<String, Projection> projectionsByGroup
) {
    public record Projection(String messageEventName,
            BiFunction<Map<String, Object>, List<Long>, Map<String, Object>> project) {
        public Projection {
            if (messageEventName == null || messageEventName.isBlank()) throw new IllegalArgumentException("Projection event must be present");
            Objects.requireNonNull(project, "project");
        }
    }
    public WorkerPropertyProjection {
        Objects.requireNonNull(observationEventName, "observationEventName");
        var copy = new LinkedHashMap<String, Projection>();
        Objects.requireNonNull(projectionsByGroup, "projectionsByGroup").forEach((group, projection) -> {
            if (group == null || group.isBlank()) throw new IllegalArgumentException("Projection Group must be present");
            copy.put(group, Objects.requireNonNull(projection, "projection"));
        });
        projectionsByGroup = Collections.unmodifiableMap(copy);
    }
}
