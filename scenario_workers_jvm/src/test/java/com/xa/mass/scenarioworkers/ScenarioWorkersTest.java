package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.runtime.WorkerIdentityStore;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioWorkersTest {

    private static final String WORKER_ID_1 =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final String WORKER_ID_2 =
            "4a2f9bc3-c146-4dce-ae85-6f44e94b5cb3";

    @Test
    void publicAssemblyIsInertAndRejectsUnknownLocalEvent() {
        ScenarioWorkers empty = ScenarioWorkers.fromJson(
                "{}",
                java.net.URI.create("http://127.0.0.1:18082")
        );
        empty.start();
        empty.start();
        empty.close();
        empty.close();

        assertThatThrownBy(() -> ScenarioWorkers.fromJson(
                config("missing.event", 1),
                java.net.URI.create("http://127.0.0.1:18082")
        )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                .hasMessageContaining("unknown eventCode");
    }

    @Test
    void startsWorkersWithResolvedDefinitionsAndClosesInReverse() {
        List<String> lifecycle = new ArrayList<>();
        List<String> indexedWorkerIds = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE, 2),
                (group, worker, identity, properties, definitions) -> {
                    int ordinal = created.incrementAndGet();
                    assertThat(group.workerGroupId()).isEqualTo(
                            "scenario-group"
                    );
                    assertThat(definitions).extracting(
                            WorkerEventDefinition::eventCode
                    ).containsExactly(StringUtilityWorkerEvents.MD5_EVENT_CODE);
                    try {
                        assertThat(properties.loadProperties()).containsEntry(
                                "region",
                                "local"
                        );
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                    return handle(
                            ordinal == 1 ? WORKER_ID_1 : WORKER_ID_2,
                            true,
                            lifecycle,
                            "worker-" + ordinal
                    );
                },
                (group, workerId, updates, timeout) -> {
                    indexedWorkerIds.add(workerId);
                    return Map.of(
                            "index.worker.region",
                            new ScenarioWorkerIndexResult("ok", null)
                    );
                }
        );

        workers.start();
        workers.close();

        assertThat(indexedWorkerIds).containsExactly(
                WORKER_ID_1,
                WORKER_ID_2
        );
        assertThat(lifecycle).containsExactly(
                "start:worker-1",
                "start:worker-2",
                "close:worker-2",
                "close:worker-1"
        );
    }

    @Test
    void startupFailureClosesAlreadyCreatedWorkersInReverse() {
        List<String> lifecycle = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE, 2),
                (group, worker, identity, properties, definitions) -> {
                    int ordinal = created.incrementAndGet();
                    if (ordinal == 2) {
                        throw new IllegalStateException("factory failed");
                    }
                    return handle(
                            WORKER_ID_1,
                            true,
                            lifecycle,
                            "worker-1"
                    );
                },
                acceptedIndexes()
        );

        assertThatThrownBy(workers::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .hasMessageContaining("Could not start");
        assertThat(lifecycle).containsExactly(
                "start:worker-1",
                "close:worker-1"
        );
        assertThatThrownBy(workers::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void connectionTimeoutPreventsIndexUpdate() {
        AtomicInteger indexCalls = new AtomicInteger();
        ScenarioWorkerGroupConfig group = groupConfig(
                StringUtilityWorkerEvents.MD5_EVENT_CODE,
                1,
                Duration.ofMillis(5)
        );
        ScenarioWorkers workers = new ScenarioWorkers(
                List.of(group),
                definitions(),
                (workerGroupId, workerId, updates, timeout) -> {
                    indexCalls.incrementAndGet();
                    return Map.of();
                },
                (ignoredGroup, worker, identity, properties, definitions) ->
                        handle(WORKER_ID_1, false, new ArrayList<>(), "worker")
        );

        assertThatThrownBy(workers::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .hasMessageContaining("0 of 1 Workers connected");
        assertThat(indexCalls).hasValue(0);
    }

    @Test
    void indexFailureRemainsBestEffort() {
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE, 1),
                (group, worker, identity, properties, definitions) ->
                        handle(WORKER_ID_1, true, new ArrayList<>(), "worker"),
                (group, workerId, updates, timeout) -> {
                    throw new IllegalStateException("index unavailable");
                }
        );

        workers.start();
        workers.close();
    }

    @Test
    void sandboxIdentityAndPropertiesArePassedThroughWithoutRewrite(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path sandboxDirectory = temporaryDirectory.resolve("worker");
        List<Map<String, Object>> observedProperties = new ArrayList<>();
        List<Optional<String>> observedIds = new ArrayList<>();
        ScenarioWorkers.WorkerFactory firstFactory = storingFactory(
                observedProperties,
                observedIds
        );
        ScenarioWorkers first = workers(
                sandboxConfig(sandboxDirectory, "first"),
                firstFactory,
                acceptedIndexes()
        );
        first.start();
        first.close();

        Files.writeString(
                sandboxDirectory.resolve("worker-properties.json"),
                Jsons.toJson(Map.of("region", "edited"))
        );
        ScenarioWorkers second = workers(
                sandboxConfig(sandboxDirectory, "ignored-inline"),
                storingFactory(observedProperties, observedIds),
                acceptedIndexes()
        );
        second.start();
        second.close();

        assertThat(observedIds).containsExactly(
                Optional.empty(),
                Optional.of(WORKER_ID_1)
        );
        assertThat(observedProperties).containsExactly(
                Map.of("region", "first"),
                Map.of("region", "edited")
        );
    }

    @Test
    void sandboxPreflightFailureCreatesNoWorkerAndReleasesLocks(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path first = temporaryDirectory.resolve("first");
        Path broken = temporaryDirectory.resolve("broken");
        Files.createDirectories(broken);
        Files.writeString(
                broken.resolve("worker-properties.json"),
                "not-json"
        );
        String config = Jsons.toJson(Map.of(
                "scenario-group",
                Map.of(
                        "eventCodes",
                        List.of(StringUtilityWorkerEvents.MD5_EVENT_CODE),
                        "workers",
                        List.of(
                                sandboxWorker("client-1", first),
                                sandboxWorker("client-2", broken)
                        )
                )
        ));
        AtomicInteger created = new AtomicInteger();
        ScenarioWorkers workers = workers(
                config,
                (group, worker, identity, properties, definitions) -> {
                    created.incrementAndGet();
                    return handle(WORKER_ID_1, true, new ArrayList<>(), "worker");
                },
                acceptedIndexes()
        );

        assertThatThrownBy(workers::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class);
        assertThat(created).hasValue(0);

        ScenarioWorkerSandbox reopened = ScenarioWorkerSandbox.open(
                first,
                "scenario-group",
                "client-1",
                Map.of()
        );
        reopened.close();
    }

    private static ScenarioWorkers workers(
            String json,
            ScenarioWorkers.WorkerFactory factory,
            ScenarioWorkerIndexClient indexes
    ) {
        return new ScenarioWorkers(
                ScenarioWorkersJsonParser.parse(json),
                definitions(),
                indexes,
                factory
        );
    }

    private static ScenarioWorkers.WorkerFactory storingFactory(
            List<Map<String, Object>> observedProperties,
            List<Optional<String>> observedIds
    ) {
        return (group, worker, identity, properties, definitions) ->
                new ScenarioWorkers.WorkerRuntimeHandle() {
                    private String workerId;

                    @Override
                    public void start() {
                        try {
                            observedProperties.add(properties.loadProperties());
                            Optional<String> loaded = identity.loadWorkerId();
                            observedIds.add(loaded);
                            workerId = loaded.orElse(WORKER_ID_1);
                            if (loaded.isEmpty()) {
                                identity.saveWorkerId(workerId);
                            }
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    }

                    @Override
                    public boolean isConnected() {
                        return true;
                    }

                    @Override
                    public String workerId() {
                        return workerId;
                    }

                    @Override
                    public void close() {
                    }
                };
    }

    private static ScenarioWorkers.WorkerRuntimeHandle handle(
            String workerId,
            boolean connected,
            List<String> lifecycle,
            String name
    ) {
        return new ScenarioWorkers.WorkerRuntimeHandle() {
            @Override
            public void start() {
                lifecycle.add("start:" + name);
            }

            @Override
            public boolean isConnected() {
                return connected;
            }

            @Override
            public String workerId() {
                return workerId;
            }

            @Override
            public void close() {
                lifecycle.add("close:" + name);
            }
        };
    }

    private static ScenarioWorkerIndexClient acceptedIndexes() {
        return (group, workerId, updates, timeout) -> Map.of(
                "index.worker.region",
                new ScenarioWorkerIndexResult("ok", null)
        );
    }

    private static Map<String, WorkerEventDefinition<?>> definitions() {
        WorkerEventDefinition<?> definition =
                StringUtilityWorkerEvents.definitions().get(0);
        return Map.of(definition.eventCode(), definition);
    }

    private static String config(String eventCode, int count) {
        List<Map<String, Object>> workers = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            workers.add(Map.of(
                    "clientWorkerKey",
                    "client-" + index,
                    "workerProperties",
                    Map.of("region", "local"),
                    "indexedPropertyUpdates",
                    Map.of("index.worker.region", "local")
            ));
        }
        return Jsons.toJson(Map.of(
                "scenario-group",
                Map.of(
                        "eventCodes",
                        List.of(eventCode),
                        "workers",
                        workers
                )
        ));
    }

    private static String sandboxConfig(Path sandbox, String region) {
        return Jsons.toJson(Map.of(
                "scenario-group",
                Map.of(
                        "eventCodes",
                        List.of(StringUtilityWorkerEvents.MD5_EVENT_CODE),
                        "workers",
                        List.of(Map.of(
                                "clientWorkerKey",
                                "client-1",
                                "workerProperties",
                                Map.of("region", region),
                                "sandboxDirectory",
                                sandbox.toString()
                        ))
                )
        ));
    }

    private static Map<String, Object> sandboxWorker(
            String clientWorkerKey,
            Path sandbox
    ) {
        return Map.of(
                "clientWorkerKey",
                clientWorkerKey,
                "workerProperties",
                Map.of(),
                "sandboxDirectory",
                sandbox.toString()
        );
    }

    private static ScenarioWorkerGroupConfig groupConfig(
            String eventCode,
            int count,
            Duration connectTimeout
    ) {
        return new ScenarioWorkerGroupConfig(
                "scenario-group",
                List.of(eventCode),
                ScenarioWorkersJsonParser.parse(
                        config(eventCode, count)
                ).get(0).workers(),
                Duration.ofSeconds(10),
                Duration.ofMillis(10),
                connectTimeout
        );
    }
}
