package com.xa.mass.scenarioworkers;

import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ScenarioWorkers implements AutoCloseable {

    private final List<ScenarioWorkerGroup> groups;
    private boolean started;
    private boolean closed;

    ScenarioWorkers(
            List<ScenarioWorkerGroup> groups
    ) {
        this.groups = List.copyOf(groups);
    }

    public static ScenarioWorkers fromJson(
            String configJson,
            Map<String, WorkerEventDefinition<?>> definitionsByEventCode,
            WorkerResourceCatalog workerCatalog,
            WorkerRuntime workerRuntime,
            WorkerPropertyIndexRuntime propertyIndexRuntime
    ) {
        Objects.requireNonNull(workerCatalog, "workerCatalog");
        Objects.requireNonNull(workerRuntime, "workerRuntime");
        Objects.requireNonNull(
                propertyIndexRuntime,
                "propertyIndexRuntime"
        );

        List<ScenarioWorkerGroupConfig> configs;
        Map<String, WorkerEventDefinition<?>> definitions;
        Map<String, List<String>> eventCodesByWorkerGroupId;
        try {
            definitions = immutableDefinitions(definitionsByEventCode);
            configs = ScenarioWorkersJsonParser.parse(configJson);
            eventCodesByWorkerGroupId = immutableEventCodesByWorkerGroupId(
                    configs
            );
        } catch (IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    14012,
                    "scenarioWorkers.parseConfig",
                    "Scenario Worker configuration is invalid: "
                            + error.getMessage(),
                    error
            );
        }

        List<ScenarioWorkerGroup> groups =
                new ArrayList<>(configs.size());
        try {
            for (ScenarioWorkerGroupConfig config : configs) {
                groups.add(new ScenarioWorkerGroup(
                        config,
                        resolveDefinitions(
                                config.workerGroupId(),
                                eventCodesByWorkerGroupId,
                                definitions
                        ),
                        workerCatalog,
                        workerRuntime,
                        propertyIndexRuntime
                ));
            }
        } catch (IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    14012,
                    "scenarioWorkers.resolveDefinitions",
                    "Scenario Worker configuration is invalid: "
                            + error.getMessage(),
                    error
            );
        }
        return new ScenarioWorkers(groups);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        List<ScenarioWorkerGroup> closing =
                new ArrayList<>(groups);
        Collections.reverse(closing);
        RuntimeException failure = null;
        for (ScenarioWorkerGroup group : closing) {
            try {
                group.close();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "Scenario Workers are closed"
            );
        }
        if (started) {
            return;
        }

        List<ScenarioWorkerGroup> startedGroups =
                new ArrayList<>();
        ScenarioWorkerGroup starting = null;
        try {
            for (ScenarioWorkerGroup group : groups) {
                starting = group;
                group.start();
                startedGroups.add(group);
            }
            started = true;
        } catch (RuntimeException failure) {
            closed = true;
            if (starting != null
                    && !startedGroups.contains(starting)) {
                closeAndSuppress(starting, failure);
            }
            Collections.reverse(startedGroups);
            for (ScenarioWorkerGroup group : startedGroups) {
                closeAndSuppress(group, failure);
            }
            throw failure;
        }
    }

    private static void closeAndSuppress(
            ScenarioWorkerGroup group,
            RuntimeException failure
    ) {
        try {
            group.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static Map<String, WorkerEventDefinition<?>>
    immutableDefinitions(
            Map<String, WorkerEventDefinition<?>> definitionsByEventCode
    ) {
        if (definitionsByEventCode == null) {
            throw new IllegalArgumentException(
                    "definitionsByEventCode must be present"
            );
        }
        Map<String, WorkerEventDefinition<?>> copy = new LinkedHashMap<>();
        definitionsByEventCode.forEach((eventCode, definition) -> {
            if (eventCode == null || eventCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Definition eventCode key must be non-blank"
                );
            }
            if (definition == null) {
                throw new IllegalArgumentException(
                        "Definition must be present for " + eventCode
                );
            }
            if (!eventCode.equals(definition.eventCode())) {
                throw new IllegalArgumentException(
                        "Definition key does not match eventCode: "
                                + eventCode
                );
            }
            if (!WorkerMessageEndpoint.TASK.wireValue()
                    .equals(definition.src())) {
                throw new IllegalArgumentException(
                        "Scenario Definition src must be TASK: "
                                + eventCode
                );
            }
            copy.put(eventCode, definition);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static List<WorkerEventDefinition<?>> resolveDefinitions(
            String workerGroupId,
            Map<String, List<String>> eventCodesByWorkerGroupId,
            Map<String, WorkerEventDefinition<?>> definitionsByEventCode
    ) {
        List<String> eventCodes = eventCodesByWorkerGroupId.get(
                workerGroupId
        );
        if (eventCodes == null) {
            throw new IllegalArgumentException(
                    "WorkerGroup capability mapping is missing: "
                            + workerGroupId
            );
        }
        List<WorkerEventDefinition<?>> resolved =
                new ArrayList<>(eventCodes.size());
        for (String eventCode : eventCodes) {
            WorkerEventDefinition<?> definition =
                    definitionsByEventCode.get(eventCode);
            if (definition == null) {
                throw new IllegalArgumentException(
                        "WorkerGroup "
                                + workerGroupId
                                + " references unknown eventCode "
                                + eventCode
                );
            }
            resolved.add(definition);
        }
        return List.copyOf(resolved);
    }

    private static Map<String, List<String>>
    immutableEventCodesByWorkerGroupId(
            List<ScenarioWorkerGroupConfig> configs
    ) {
        Map<String, List<String>> eventCodesByWorkerGroupId =
                new LinkedHashMap<>();
        for (ScenarioWorkerGroupConfig config : configs) {
            List<String> existing = eventCodesByWorkerGroupId.putIfAbsent(
                    config.workerGroupId(),
                    List.copyOf(config.eventCodes())
            );
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Duplicate WorkerGroup: " + config.workerGroupId()
                );
            }
        }
        return Collections.unmodifiableMap(eventCodesByWorkerGroupId);
    }
}
