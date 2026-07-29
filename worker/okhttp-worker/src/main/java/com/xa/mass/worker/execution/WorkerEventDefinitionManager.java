package com.xa.mass.worker.execution;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class WorkerEventDefinitionManager<R> {

    private final Map<
            String,
            WorkerEventDefinition<?, ? extends R>
    > definitions;

    public WorkerEventDefinitionManager(
            Map<
                    String,
                    ? extends WorkerEventDefinition<?, ? extends R>
            > definitions
    ) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, WorkerEventDefinition<?, ? extends R>> copy =
                new LinkedHashMap<>();
        for (Map.Entry<
                String,
                ? extends WorkerEventDefinition<?, ? extends R>
        > entry : definitions.entrySet()) {
            String eventCode = entry.getKey();
            if (eventCode == null || eventCode.isBlank()) {
                throw new IllegalArgumentException(
                        "eventCode must be non-blank"
                );
            }
            copy.put(
                    eventCode,
                    Objects.requireNonNull(
                            entry.getValue(),
                            "definition"
                    )
            );
        }
        this.definitions = Collections.unmodifiableMap(copy);
    }

    public R dispatch(
            String eventCode,
            Map<String, Object> parameters
    ) throws Exception {
        WorkerEventDefinition<?, ? extends R> definition =
                definitions.get(eventCode);
        if (definition == null) {
            throw new WorkerException(
                    WorkerErrorCode.EVENT_NOT_FOUND,
                    "event.dispatch",
                    "Unknown Worker event: " + eventCode,
                    null
            );
        }
        return definition.invoke(
                Objects.requireNonNull(parameters, "parameters")
        );
    }
}
