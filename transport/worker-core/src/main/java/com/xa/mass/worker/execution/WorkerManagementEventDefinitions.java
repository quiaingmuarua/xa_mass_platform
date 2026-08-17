package com.xa.mass.worker.execution;

import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Static platform events and effective Worker Definition assembly. */
public final class WorkerManagementEventDefinitions {

    public static final String PROBE_EVENT = "platform.worker.probe";
    public static final String PROPERTIES_SNAPSHOT_EVENT =
            "platform.worker.properties.snapshot";
    public static final String EVENTS_SNAPSHOT_EVENT =
            "platform.worker.events.snapshot";

    private static final String EXTENSION_WORKER_PREFIX = "extension.worker.";
    private static final String PROBE_CAPABILITY = "probe";
    private static final String PROPERTIES_SNAPSHOT_CAPABILITY =
            "properties.snapshot";
    private static final String EVENTS_SNAPSHOT_CAPABILITY =
            "events.snapshot";

    private static final String CLIENT_WORKER_KEY = "clientWorkerKey";
    private static final int MAX_RESULT_PAYLOAD_BYTES = 1_000_000;

    private WorkerManagementEventDefinitions() {
    }

    public static List<WorkerEventDefinition<?>> assemble(
            WorkerPropertiesProvider propertiesProvider,
            Collection<? extends WorkerEventDefinition<?>>
                    extensionDefinitions
    ) {
        WorkerPropertiesProvider provider = Objects.requireNonNull(
                propertiesProvider,
                "propertiesProvider"
        );
        List<WorkerEventDefinition<?>> extensions = copyExtensions(
                extensionDefinitions
        );
        String eventsSnapshot = eventsSnapshot(extensions);

        List<WorkerEventDefinition<?>> definitions = new ArrayList<>(
                3 + extensions.size()
        );
        definitions.add(WorkerEventDefinition.platform(
                PROBE_CAPABILITY,
                WorkerManagementEventDefinitions::requireNullPayload,
                ignored -> "{\"reachable\":true}"
        ));
        definitions.add(WorkerEventDefinition.platform(
                PROPERTIES_SNAPSHOT_CAPABILITY,
                WorkerManagementEventDefinitions::requireNullPayload,
                ignored -> propertiesSnapshot(provider)
        ));
        definitions.add(WorkerEventDefinition.platform(
                EVENTS_SNAPSHOT_CAPABILITY,
                WorkerManagementEventDefinitions::requireNullPayload,
                ignored -> eventsSnapshot
        ));
        definitions.addAll(extensions);
        return List.copyOf(definitions);
    }

    private static Void requireNullPayload(String payload) {
        if (!"null".equals(payload)) {
            throw new IllegalArgumentException(
                    "Worker management event payload must be null"
            );
        }
        return null;
    }

    private static List<WorkerEventDefinition<?>> copyExtensions(
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) {
        Objects.requireNonNull(definitions, "extensionDefinitions");
        List<WorkerEventDefinition<?>> copied = new ArrayList<>();
        Set<String> eventNames = new HashSet<>();
        for (WorkerEventDefinition<?> definition : definitions) {
            WorkerEventDefinition<?> present = Objects.requireNonNull(
                    definition,
                    "extensionDefinition"
            );
            String eventName = present.eventName();
            if (!eventName.startsWith(EXTENSION_WORKER_PREFIX)) {
                throw new IllegalArgumentException(
                        "Worker Host accepts only extension.worker events"
                );
            }
            if (!eventNames.add(eventName)) {
                throw new IllegalArgumentException(
                        "Duplicate Worker event: " + eventName
                );
            }
            copied.add(present);
        }
        return List.copyOf(copied);
    }

    private static String eventsSnapshot(
            List<WorkerEventDefinition<?>> extensions
    ) {
        List<String> eventNames = new ArrayList<>(3 + extensions.size());
        eventNames.add(PROBE_EVENT);
        eventNames.add(PROPERTIES_SNAPSHOT_EVENT);
        eventNames.add(EVENTS_SNAPSHOT_EVENT);
        for (WorkerEventDefinition<?> extension : extensions) {
            eventNames.add(extension.eventName());
        }
        Collections.sort(eventNames);
        return requireResultPayloadLimit(
                Jsons.toJson(Map.of("eventNames", eventNames)),
                "workerEvents snapshot"
        );
    }

    private static String propertiesSnapshot(
            WorkerPropertiesProvider provider
    ) throws Exception {
        Map<String, Object> properties = provider.loadProperties();
        if (properties == null) {
            throw new IllegalArgumentException(
                    "workerProperties must be present"
            );
        }
        if (properties.containsKey(CLIENT_WORKER_KEY)) {
            throw new IllegalArgumentException(
                    "workerProperties must not expose " + CLIENT_WORKER_KEY
            );
        }
        return requireResultPayloadLimit(
                Jsons.toJson(Map.of("properties", properties)),
                "workerProperties snapshot"
        );
    }

    private static String requireResultPayloadLimit(
            String payload,
            String name
    ) {
        if (payload.getBytes(StandardCharsets.UTF_8).length
                > MAX_RESULT_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    name + " exceeds the result limit"
            );
        }
        return payload;
    }
}
