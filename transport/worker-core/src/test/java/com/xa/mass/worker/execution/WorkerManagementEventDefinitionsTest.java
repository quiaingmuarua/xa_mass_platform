package com.xa.mass.worker.execution;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkerManagementEventDefinitionsTest {

    @Test
    void probeDoesNotReadProperties() {
        AtomicInteger calls = new AtomicInteger();
        WorkerCommandDispatcher dispatcher = dispatcher(() -> {
            calls.incrementAndGet();
            return Map.of("runtime", "java");
        });

        WorkerCommandOutcome outcome = execute(
                dispatcher,
                WorkerManagementEventDefinitions.PROBE_EVENT,
                "null"
        );

        assertEquals("200", outcome.outcomeCode());
        assertEquals(Map.of("reachable", true), Jsons.parseObject(
                outcome.payload()
        ));
        assertEquals(0, calls.get());
    }

    @Test
    void propertiesSnapshotReadsTheLiveHostProviderEveryTime() {
        AtomicReference<Map<String, Object>> properties =
                new AtomicReference<>(Map.of("battery", 80));
        WorkerCommandDispatcher dispatcher = dispatcher(properties::get);

        assertEquals(
                Map.of("properties", Map.of("battery", 80L)),
                Jsons.parseObject(execute(
                        dispatcher,
                        WorkerManagementEventDefinitions
                                .PROPERTIES_SNAPSHOT_EVENT,
                        "null"
                ).payload())
        );

        properties.set(Map.of("battery", 67, "charging", true));

        assertEquals(
                Map.of(
                        "properties",
                        Map.of("battery", 67L, "charging", true)
                ),
                Jsons.parseObject(execute(
                        dispatcher,
                        WorkerManagementEventDefinitions
                                .PROPERTIES_SNAPSHOT_EVENT,
                        "null"
                ).payload())
        );
    }

    @Test
    void invalidInputUsesTheExistingInputFailure() {
        for (String eventName : List.of(
                WorkerManagementEventDefinitions.PROBE_EVENT,
                WorkerManagementEventDefinitions.PROPERTIES_SNAPSHOT_EVENT,
                WorkerManagementEventDefinitions.EVENTS_SNAPSHOT_EVENT
        )) {
            WorkerCommandOutcome outcome = execute(
                    dispatcher(Map::of),
                    eventName,
                    "{}"
            );

            assertEquals(
                    Integer.toString(
                            WorkerErrorCode.EVENT_INPUT_INVALID.code()
                    ),
                    outcome.outcomeCode()
            );
        }
    }

    @Test
    void invalidPropertiesUseTheExistingExecutionFailure() {
        List<WorkerPropertiesProvider> providers =
                List.of(
                        () -> null,
                        () -> {
                            throw new IllegalStateException("unavailable");
                        },
                        () -> Map.of("unsupported", new Object()),
                        () -> Map.of("clientWorkerKey", "private")
                );

        for (WorkerPropertiesProvider provider : providers) {
            WorkerCommandOutcome outcome = execute(
                    dispatcher(provider),
                    WorkerManagementEventDefinitions
                            .PROPERTIES_SNAPSHOT_EVENT,
                    "null"
            );
            assertEquals(
                    Integer.toString(
                            WorkerErrorCode.EVENT_EXECUTION_FAILED.code()
                    ),
                    outcome.outcomeCode()
            );
        }
    }

    @Test
    void eventSnapshotDescribesTheExactImmutableAssembly() {
        List<WorkerEventDefinition<?>> extensions = new ArrayList<>();
        extensions.add(WorkerEventDefinition.extension(
                "zeta.observe",
                payload -> payload,
                payload -> payload
        ));
        extensions.add(WorkerEventDefinition.extension(
                "device.custom-event",
                payload -> payload,
                payload -> payload
        ));
        WorkerCommandDispatcher dispatcher = dispatcher(Map::of, extensions);
        extensions.clear();

        assertEquals(
                List.of(
                        "extension.worker.device.custom-event",
                        "extension.worker.zeta.observe",
                        "platform.worker.events.snapshot",
                        "platform.worker.probe",
                        "platform.worker.properties.snapshot"
                ),
                Jsons.parseObject(execute(
                        dispatcher,
                        WorkerManagementEventDefinitions
                                .EVENTS_SNAPSHOT_EVENT,
                        "null"
                ).payload()).get("eventNames")
        );
        assertEquals(
                "{\"custom\":true}",
                execute(
                        dispatcher,
                        "extension.worker.device.custom-event",
                        "{\"custom\":true}"
                ).payload()
        );
    }

    @Test
    void assemblyAcceptsOnlyUniqueWorkerExtensions() {
        WorkerEventDefinition<?> extension =
                WorkerEventDefinition.extension(
                        "device.custom-event",
                        payload -> payload,
                        payload -> payload
                );
        WorkerEventDefinition<?> platform =
                WorkerManagementEventDefinitions.assemble(
                        Map::of,
                        List.of()
                ).get(0);

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerManagementEventDefinitions.assemble(
                        Map::of,
                        List.of(extension, extension)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerManagementEventDefinitions.assemble(
                        Map::of,
                        List.of(platform)
                )
        );
    }

    @Test
    void assemblyRejectsAnEventSnapshotBeyondTheResultLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerManagementEventDefinitions.assemble(
                        Map::of,
                        List.of(WorkerEventDefinition.extension(
                                "a".repeat(1_000_000),
                                payload -> payload,
                                payload -> payload
                        ))
                )
        );
    }

    private static WorkerCommandDispatcher dispatcher(
            WorkerPropertiesProvider provider
    ) {
        return dispatcher(provider, List.of());
    }

    private static WorkerCommandDispatcher dispatcher(
            WorkerPropertiesProvider provider,
            List<WorkerEventDefinition<?>> extensions
    ) {
        return WorkerCommandDispatcher.forWorker(
                WorkerManagementEventDefinitions.assemble(
                        provider,
                        extensions
                )
        );
    }

    private static WorkerCommandOutcome execute(
            WorkerCommandDispatcher dispatcher,
            String eventCode,
            String payload
    ) {
        return dispatcher.execute(DeliveryCommand.create(
                SYSTEM,
                WORKER,
                eventCode,
                Long.MAX_VALUE,
                payload,
                "direct-call"
        )).orElseThrow();
    }
}
