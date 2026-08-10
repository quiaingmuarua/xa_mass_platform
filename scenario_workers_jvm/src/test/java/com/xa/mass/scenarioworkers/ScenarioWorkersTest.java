package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class ScenarioWorkersTest {

    private static final URI RUNTIME_API =
            URI.create("http://127.0.0.1:18082");
    private static final String WORKER_ID_1 =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final String WORKER_ID_2 =
            "4a2f9bc3-c146-4dce-ae85-6f44e94b5cb3";

    @Test
    void publicAssemblyIsInertAndRejectsUnknownLocalEvent() {
        ScenarioWorkers empty = ScenarioWorkers.fromJson("{}", RUNTIME_API);
        empty.start();
        empty.start();
        empty.close();
        empty.close();

        assertThatThrownBy(() -> ScenarioWorkers.fromJson(
                config("missing.event", 1),
                RUNTIME_API
        )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                .hasMessageContaining("unknown eventCode");
    }

    @Test
    void productionAssemblyBuildsAGroupWithoutWaitingForConnection() {
        String json = Jsons.toJson(Map.of(
                "scenario-group",
                Map.of(
                        "eventCodes",
                        List.of(StringUtilityWorkerEvents.MD5_EVENT_CODE),
                        "workers",
                        List.of(
                                Map.of("clientWorkerKey", "client-1"),
                                Map.of("clientWorkerKey", "client-2")
                        )
                )
        ));
        ScenarioWorkers workers = ScenarioWorkers.fromJson(json, RUNTIME_API);

        workers.start();
        workers.close();
    }

    @Test
    void buildsOneManagerForOneGroupAndKeepsReplicaMetadata() {
        JavaWorkerManager manager = mock(JavaWorkerManager.class);
        when(manager.snapshot("client-1")).thenReturn(snapshot(WORKER_ID_1));
        when(manager.snapshot("client-2")).thenReturn(snapshot(WORKER_ID_2));
        List<String> indexedWorkerIds = new ArrayList<>();
        List<ScenarioWorkers.PreparedGroup> preparedGroups =
                new ArrayList<>();

        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE, 2),
                (group, workerId, updates, timeout) -> {
                    indexedWorkerIds.add(workerId);
                    return acceptedIndexResults(updates);
                },
                (runtimeApiBaseUrl, preparedGroup) -> {
                    assertThat(runtimeApiBaseUrl).isEqualTo(RUNTIME_API);
                    preparedGroups.add(preparedGroup);
                    return manager;
                }
        );

        workers.start();
        workers.close();

        assertThat(preparedGroups).hasSize(1);
        ScenarioWorkers.PreparedGroup prepared = preparedGroups.get(0);
        assertThat(prepared.group().config().workerGroupId())
                .isEqualTo("scenario-group");
        assertThat(prepared.group().definitionExtensions())
                .extracting(WorkerEventDefinition::eventCode)
                .containsExactly(StringUtilityWorkerEvents.MD5_EVENT_CODE);
        assertThat(prepared.replicas())
                .extracting(ScenarioWorkers.PreparedReplica::clientWorkerKey)
                .containsExactly("client-1", "client-2");
        assertThat(prepared.replicas().get(0).workerProperties())
                .containsEntry("region", "local");
        assertThat(indexedWorkerIds).containsExactly(
                WORKER_ID_1,
                WORKER_ID_2
        );

        InOrder lifecycle = inOrder(manager);
        lifecycle.verify(manager).start();
        lifecycle.verify(manager).close();
    }

    @Test
    void groupAssemblyFailureClosesEarlierManagersWithoutStartingAny() {
        JavaWorkerManager first = mock(JavaWorkerManager.class);
        AtomicInteger groups = new AtomicInteger();
        ScenarioWorkers workers = workers(
                twoGroupConfig(),
                acceptedIndexes(),
                (runtimeApiBaseUrl, preparedGroup) -> {
                    if (groups.incrementAndGet() == 1) {
                        return first;
                    }
                    throw new IllegalStateException("assemble second group");
                }
        );

        assertThatThrownBy(workers::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class)
                .hasMessageContaining("Could not start");

        verify(first, never()).start();
        InOrder closeOrder = inOrder(first);
        closeOrder.verify(first).close();
        assertThatThrownBy(workers::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void synchronousGroupStartFailureDoesNotSkipLaterGroups() {
        JavaWorkerManager first = mock(JavaWorkerManager.class);
        JavaWorkerManager second = mock(JavaWorkerManager.class);
        doThrow(new IllegalStateException("start first"))
                .when(first).start();
        AtomicInteger groups = new AtomicInteger();
        ScenarioWorkers workers = workers(
                twoGroupConfig(),
                acceptedIndexes(),
                (runtimeApiBaseUrl, preparedGroup) ->
                        groups.getAndIncrement() == 0 ? first : second
        );

        assertThatThrownBy(workers::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class);

        verify(first).start();
        verify(second).start();
        InOrder closeOrder = inOrder(first, second);
        closeOrder.verify(second).close();
        closeOrder.verify(first).close();
    }

    @Test
    void missingIdentitySkipsIndexUpdateWithoutFailingStartup() {
        JavaWorkerManager manager = mock(JavaWorkerManager.class);
        when(manager.snapshot("client-1")).thenReturn(snapshot(null));
        AtomicInteger indexCalls = new AtomicInteger();
        ScenarioWorkers workers = workers(
                configWithConnectTimeout(
                        StringUtilityWorkerEvents.MD5_EVENT_CODE,
                        1,
                        5
                ),
                (group, workerId, updates, timeout) -> {
                    indexCalls.incrementAndGet();
                    return Map.of();
                },
                (runtimeApiBaseUrl, preparedGroup) ->
                        manager
        );

        workers.start();
        assertThat(indexCalls).hasValue(0);
        workers.close();
    }

    @Test
    void indexFailureRemainsBestEffort() {
        JavaWorkerManager manager = mock(JavaWorkerManager.class);
        when(manager.snapshot("client-1")).thenReturn(snapshot(WORKER_ID_1));
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE, 1),
                (group, workerId, updates, timeout) -> {
                    throw new IllegalStateException("index unavailable");
                },
                (runtimeApiBaseUrl, preparedGroup) ->
                        manager
        );

        workers.start();
        workers.close();
    }

    @Test
    void sandboxIdentityAndPropertiesPassThroughWithoutRewrite(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path sandboxDirectory = temporaryDirectory.resolve("worker");
        List<Map<String, Object>> observedProperties = new ArrayList<>();
        List<Optional<String>> observedIds = new ArrayList<>();

        ScenarioWorkers first = sandboxWorkers(
                sandboxDirectory,
                "first",
                observedProperties,
                observedIds
        );
        first.start();
        first.close();

        Files.writeString(
                sandboxDirectory.resolve("worker-properties.json"),
                Jsons.toJson(Map.of("region", "edited"))
        );

        ScenarioWorkers second = sandboxWorkers(
                sandboxDirectory,
                "ignored-inline",
                observedProperties,
                observedIds
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
    void sandboxPreflightFailureCreatesNoManagerAndReleasesLocks(
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
        AtomicInteger managersCreated = new AtomicInteger();
        ScenarioWorkers workers = workers(
                config,
                acceptedIndexes(),
                (runtimeApiBaseUrl, preparedGroup) -> {
                    managersCreated.incrementAndGet();
                    return mock(JavaWorkerManager.class);
                }
        );

        assertThatThrownBy(workers::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class);
        assertThat(managersCreated).hasValue(0);

        ScenarioWorkerSandbox reopened = ScenarioWorkerSandbox.open(
                first,
                "scenario-group",
                "client-1",
                Map.of()
        );
        reopened.close();
    }

    @Test
    void closesManagersThenReleasesSandboxes(
            @TempDir Path temporaryDirectory
    ) {
        Path sandboxDirectory = temporaryDirectory.resolve("worker");
        JavaWorkerManager manager = mock(JavaWorkerManager.class);
        ScenarioWorkers workers = workers(
                sandboxConfig(sandboxDirectory, "local"),
                acceptedIndexes(),
                (runtimeApiBaseUrl, preparedGroup) ->
                        manager
        );

        workers.start();
        workers.close();

        verify(manager).close();
        ScenarioWorkerSandbox reopened = ScenarioWorkerSandbox.open(
                sandboxDirectory,
                "scenario-group",
                "client-1",
                Map.of()
        );
        reopened.close();
    }

    private static ScenarioWorkers sandboxWorkers(
            Path sandboxDirectory,
            String region,
            List<Map<String, Object>> observedProperties,
            List<Optional<String>> observedIds
    ) {
        JavaWorkerManager manager = mock(JavaWorkerManager.class);
        when(manager.snapshot("client-1")).thenReturn(snapshot(WORKER_ID_1));
        return workers(
                sandboxConfig(sandboxDirectory, region),
                acceptedIndexes(),
                (runtimeApiBaseUrl, preparedGroup) -> {
                    ScenarioWorkers.PreparedReplica replica =
                            preparedGroup.replicas().get(0);
                    doAnswer(invocation -> {
                        observedProperties.add(replica.workerProperties());
                        Optional<String> workerId =
                                replica.identityStore().loadWorkerId();
                        observedIds.add(workerId);
                        if (workerId.isEmpty()) {
                            replica.identityStore().saveWorkerId(WORKER_ID_1);
                        }
                        return null;
                    }).when(manager).start();
                    return manager;
                }
        );
    }

    private static ScenarioWorkers workers(
            String json,
            ScenarioWorkerIndexClient indexes,
            ScenarioWorkers.GroupManagerFactory managerFactory
    ) {
        return new ScenarioWorkers(
                RUNTIME_API,
                ScenarioWorkersJsonParser.parse(json),
                definitions(),
                indexes,
                managerFactory
        );
    }

    private static WorkerLifecycle.Snapshot snapshot(String workerId) {
        return new WorkerLifecycle.Snapshot(
                WorkerLifecycle.State.RUNNING,
                workerId,
                null,
                null
        );
    }

    private static ScenarioWorkerIndexClient acceptedIndexes() {
        return (group, workerId, updates, timeout) ->
                acceptedIndexResults(updates);
    }

    private static Map<String, ScenarioWorkerIndexResult>
    acceptedIndexResults(Map<String, Object> updates) {
        Map<String, ScenarioWorkerIndexResult> results =
                new LinkedHashMap<>();
        updates.keySet().forEach(field -> results.put(
                field,
                new ScenarioWorkerIndexResult("ok", null)
        ));
        return results;
    }

    private static Map<String, WorkerEventDefinition<?>> definitions() {
        WorkerEventDefinition<?> definition =
                StringUtilityWorkerEvents.definitions().get(0);
        return Map.of(definition.eventCode(), definition);
    }

    private static String config(String eventCode, int count) {
        return configWithConnectTimeout(eventCode, count, 30_000);
    }

    private static String configWithConnectTimeout(
            String eventCode,
            int count,
            long connectTimeoutMillis
    ) {
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
                        "connectTimeoutMillis",
                        connectTimeoutMillis,
                        "workers",
                        workers
                )
        ));
    }

    private static String twoGroupConfig() {
        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("group-1", groupJson("client-1"));
        groups.put("group-2", groupJson("client-2"));
        return Jsons.toJson(groups);
    }

    private static Map<String, Object> groupJson(String clientWorkerKey) {
        return Map.of(
                "eventCodes",
                List.of(StringUtilityWorkerEvents.MD5_EVENT_CODE),
                "workers",
                List.of(Map.of(
                        "clientWorkerKey",
                        clientWorkerKey,
                        "workerProperties",
                        Map.of("region", "local")
                ))
        );
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
}
