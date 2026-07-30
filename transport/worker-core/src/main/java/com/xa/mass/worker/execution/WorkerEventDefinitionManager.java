package com.xa.mass.worker.execution;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class WorkerEventDefinitionManager {

    private final Map<String, WorkerEventDefinition<?>> definitions;

    public WorkerEventDefinitionManager(
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, WorkerEventDefinition<?>> copy =
                new LinkedHashMap<>();
        for (WorkerEventDefinition<?> definition : definitions) {
            WorkerEventDefinition<?> present =
                    Objects.requireNonNull(definition, "definition");
            String key = mint(present.src(), present.eventCode());
            if (copy.putIfAbsent(key, present) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Worker event: "
                                + present.src()
                                + "/"
                                + present.eventCode()
                );
            }
        }
        this.definitions = Collections.unmodifiableMap(copy);
    }

    public WorkerEventDefinition<?> require(
            String src,
            String eventCode
    ) {
        WorkerEventDefinition<?> definition =
                definitions.get(mint(src, eventCode));
        if (definition == null) {
            throw new WorkerException(
                    WorkerErrorCode.EVENT_NOT_FOUND,
                    "event.require",
                    "Unknown Worker event: "
                            + src
                            + "/"
                            + eventCode,
                    null
            );
        }
        return definition;
    }

    private static String mint(String src, String eventCode) {
        if (src == null || src.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "src must be non-blank"
            );
        }
        WorkerMessageEndpoint endpoint =
                WorkerMessageEndpoint.fromWire(src);
        if (endpoint == WorkerMessageEndpoint.WORKER) {
            throw new IllegalArgumentException(
                    "Worker event src cannot be WORKER"
            );
        }
        if (eventCode == null || eventCode.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "eventCode must be non-blank"
            );
        }
        return endpoint.wireValue() + ":" + eventCode;
    }
}
