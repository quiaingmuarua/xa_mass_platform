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
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    private static final String GROUP = "scenario-group";
    private static final String WORKER_ID_1 =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final String WORKER_ID_2 =
            "4a2f9bc3-c146-4dce-ae85-6f44e94b5cb3";

    @TempDir
    Path temporaryDirectory;

    @Test
    void publicAssemblyIsInertAndRejectsUnknownLocalEvent() {
        ScenarioWorkers empty = ScenarioWorkers.fromJson(
                "{}",
                labRoot().toString(),
                RUNTIME_API
        );
        empty.start();
        empty.start();
        empty.close();
        empty.close();

        assertThat(labRoot()).doesNotExist();
        assertThatThrownBy(() -> ScenarioWorkers.fromJson(
                config("missing.event"),
                labRoot().toString(),
                RUNTIME_API
        )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                .hasMessageContaining("unknown eventCode");
    }

    @Test
    void buildsOneManagerFromSortedLabFilesAndKeepsReplicaMetadata()
            throws Exception {
        createLabRoot();
        writeWorker(
                GROUP,
                "client-2",
                Map.of("region", "second"),
                Map.of("index.worker.region", "local"),
                null
        );
        writeWorker(
                GROUP,
                "client-1",
                Map.of("region", "first"),
                Map.of("index.worker.region", "local"),
                null
        );
        JavaWorkerManager manager = mock(JavaWorkerManager.class);
        when(manager.snapshot("client-1")).thenReturn(snapshot(WORKER_ID_1));
        when(manager.snapshot("client-2")).thenReturn(snapshot(WORKER_ID_2));
        List<String> indexedWorkerIds = new ArrayList<>();
        List<ScenarioWorkers.PreparedGroup> preparedGroups =
                new ArrayList<>();

        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE),
                (group, workerId, updates, timeout) -> {
                    indexedWorkerIds.add(workerId);
                    return acceptedIndexResults(updates);
                },
                (runtimeApiBaseUrl, preparedGroup) -> {
                    preparedGroups.add(preparedGroup);
                    return manager;
                }
        );

        workers.start();
        workers.close();

        assertThat(preparedGroups).hasSize(1);
        ScenarioWorkers.PreparedGroup prepared = preparedGroups.get(0);
        assertThat(prepared.group().config().workerGroupId())
                .isEqualTo(GROUP);
        assertThat(prepared.group().definitionExtensions())
                .extracting(WorkerEventDefinition::eventCode)
                .containsExactly(StringUtilityWorkerEvents.MD5_EVENT_CODE);
        assertThat(prepared.replicas())
                .extracting(ScenarioWorkers.PreparedReplica::clientWorkerKey)
                .containsExactly("client-1", "client-2");
        assertThat(prepared.replicas().get(0).workerProperties())
                .containsEntry("region", "first");
        assertThat(indexedWorkerIds).containsExactly(
                WORKER_ID_1,
                WORKER_ID_2
        );

        InOrder lifecycle = inOrder(manager);
        lifecycle.verify(manager).start();
        lifecycle.verify(manager).close();
    }

    @Test
    void emptyConfiguredGroupCreatesNoManager() throws Exception {
        createLabRoot();
        AtomicInteger managersCreated = new AtomicInteger();
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE),
                acceptedIndexes(),
                (runtimeApiBaseUrl, preparedGroup) -> {
                    managersCreated.incrementAndGet();
                    return mock(JavaWorkerManager.class);
                }
        );

        workers.start();
        workers.close();

        assertThat(managersCreated).hasValue(0);
        assertThat(labRoot().resolve(GROUP)).isDirectory();
    }

    @Test
    void invalidWorkerFileCreatesNoManagerOrNetworkActivity()
            throws Exception {
        createLabRoot();
        Path group = labRoot().resolve(GROUP);
        Files.createDirectories(group);
        Files.writeString(
                group.resolve("broken.json"),
                "not-json",
                StandardCharsets.UTF_8
        );
        AtomicInteger managersCreated = new AtomicInteger();
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE),
                acceptedIndexes(),
                (runtimeApiBaseUrl, preparedGroup) -> {
                    managersCreated.incrementAndGet();
                    return mock(JavaWorkerManager.class);
                }
        );

        assertThatThrownBy(workers::start)
                .isInstanceOf(ScenarioWorkerAssemblyException.class);
        assertThat(managersCreated).hasValue(0);
    }

    @Test
    void workerIdentityWrittenByPreparationIsReusedAfterRestart()
            throws Exception {
        createLabRoot();
        writeWorker(
                GROUP,
                "client-1",
                Map.of("region", "persistent"),
                Map.of(),
                null
        );
        List<Optional<String>> observedIds = new ArrayList<>();

        ScenarioWorkers first = identityWorkers(observedIds);
        first.start();
        first.close();
        ScenarioWorkers second = identityWorkers(observedIds);
        second.start();
        second.close();

        assertThat(observedIds).containsExactly(
                Optional.empty(),
                Optional.of(WORKER_ID_1)
        );
        assertThat(Jsons.parseObject(Files.readString(
                labRoot().resolve(GROUP).resolve("client-1.json"),
                StandardCharsets.UTF_8
        ))).containsEntry("workerId", WORKER_ID_1)
                .containsEntry(
                        "workerProperties",
                        Map.of("region", "persistent")
                );
    }

    @Test
    void groupAssemblyFailureClosesEarlierManagersWithoutStartingAny()
            throws Exception {
        createLabRoot();
        writeWorker("group-1", "client-1", Map.of(), Map.of(), null);
        writeWorker("group-2", "client-2", Map.of(), Map.of(), null);
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
        verify(first).close();
    }

    @Test
    void synchronousGroupStartFailureStillAttemptsAndClosesEveryGroup()
            throws Exception {
        createLabRoot();
        writeWorker("group-1", "client-1", Map.of(), Map.of(), null);
        writeWorker("group-2", "client-2", Map.of(), Map.of(), null);
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
    void missingIdentitySkipsIndexUpdateWithoutFailingStartup()
            throws Exception {
        createLabRoot();
        writeWorker(
                GROUP,
                "client-1",
                Map.of(),
                Map.of("index.worker.region", "local"),
                null
        );
        JavaWorkerManager manager = mock(JavaWorkerManager.class);
        when(manager.snapshot("client-1")).thenReturn(snapshot(null));
        AtomicInteger indexCalls = new AtomicInteger();
        ScenarioWorkers workers = workers(
                configWithConnectTimeout(
                        StringUtilityWorkerEvents.MD5_EVENT_CODE,
                        5
                ),
                (group, workerId, updates, timeout) -> {
                    indexCalls.incrementAndGet();
                    return Map.of();
                },
                (runtimeApiBaseUrl, preparedGroup) -> manager
        );

        workers.start();
        workers.close();

        assertThat(indexCalls).hasValue(0);
    }

    private ScenarioWorkers identityWorkers(
            List<Optional<String>> observedIds
    ) {
        JavaWorkerManager manager = mock(JavaWorkerManager.class);
        return workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE),
                acceptedIndexes(),
                (runtimeApiBaseUrl, preparedGroup) -> {
                    ScenarioWorkers.PreparedReplica replica =
                            preparedGroup.replicas().get(0);
                    doAnswer(invocation -> {
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

    private ScenarioWorkers workers(
            String json,
            ScenarioWorkerIndexClient indexes,
            ScenarioWorkers.GroupManagerFactory managerFactory
    ) {
        return new ScenarioWorkers(
                RUNTIME_API,
                labRoot().toString(),
                ScenarioWorkersJsonParser.parse(json),
                definitions(),
                indexes,
                managerFactory
        );
    }

    private void createLabRoot() throws Exception {
        Files.createDirectories(labRoot());
    }

    private void writeWorker(
            String workerGroupId,
            String clientWorkerKey,
            Map<String, Object> properties,
            Map<String, Object> indexes,
            String workerId
    ) throws Exception {
        Path group = labRoot().resolve(workerGroupId);
        Files.createDirectories(group);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        if (workerId != null) {
            value.put("workerId", workerId);
        }
        value.put("workerProperties", properties);
        value.put("indexedPropertyUpdates", indexes);
        Files.writeString(
                group.resolve(clientWorkerKey + ".json"),
                Jsons.toJson(value),
                StandardCharsets.UTF_8
        );
    }

    private Path labRoot() {
        return temporaryDirectory.resolve("data/scenario-workers");
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

    private static String config(String eventCode) {
        return configWithConnectTimeout(eventCode, 30_000);
    }

    private static String configWithConnectTimeout(
            String eventCode,
            long connectTimeoutMillis
    ) {
        return Jsons.toJson(Map.of(
                GROUP,
                Map.of(
                        "eventCodes",
                        List.of(eventCode),
                        "connectTimeoutMillis",
                        connectTimeoutMillis
                )
        ));
    }

    private static String twoGroupConfig() {
        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("group-1", groupJson());
        groups.put("group-2", groupJson());
        return Jsons.toJson(groups);
    }

    private static Map<String, Object> groupJson() {
        return Map.of(
                "eventCodes",
                List.of(StringUtilityWorkerEvents.MD5_EVENT_CODE)
        );
    }
}
