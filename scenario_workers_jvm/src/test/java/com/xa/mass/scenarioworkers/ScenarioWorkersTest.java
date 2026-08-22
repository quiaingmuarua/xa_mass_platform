package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class ScenarioWorkersTest {

    private static final URI RUNTIME_API =
            URI.create("http://127.0.0.1:18082");
    private static final String GROUP = "scenario-group";
    private static final String WORKER_ID_1 =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void resolveTemporaryDirectory() throws IOException {
        temporaryDirectory = temporaryDirectory.toRealPath();
    }

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
                null
        );
        writeWorker(
                GROUP,
                "client-1",
                Map.of("region", "first"),
                null
        );
        JavaWorkerManager manager = mock(JavaWorkerManager.class);
        List<ScenarioWorkers.PreparedGroup> preparedGroups =
                new ArrayList<>();

        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE),
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
                .extracting(WorkerEventDefinition::eventName)
                .containsExactly(StringUtilityWorkerEvents.MD5_EVENT_CODE);
        assertThat(prepared.replicas())
                .extracting(ScenarioWorkers.PreparedReplica::clientWorkerKey)
                .containsExactly("client-1", "client-2");
        assertThat(prepared.replicas().get(0).workerProperties())
                .containsEntry("region", "first");

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
    void legacyWorkerIdentityIsRemovedBeforeManagerAssembly()
            throws Exception {
        createLabRoot();
        writeWorker(
                GROUP,
                "client-1",
                Map.of("region", "persistent"),
                WORKER_ID_1
        );
        JavaWorkerManager manager = mock(JavaWorkerManager.class);
        ScenarioWorkers workers = workers(
                config(StringUtilityWorkerEvents.MD5_EVENT_CODE),
                (runtimeApiBaseUrl, preparedGroup) -> manager
        );

        workers.start();
        workers.close();

        assertThat(Jsons.parseObject(Files.readString(
                labRoot().resolve(GROUP).resolve("client-1.json"),
                StandardCharsets.UTF_8
        ))).containsEntry("schemaVersion", 2L)
                .containsEntry(
                        "workerProperties",
                        Map.of("region", "persistent")
                )
                .doesNotContainKey("workerId");
    }

    @Test
    void groupAssemblyFailureClosesEarlierManagersWithoutStartingAny()
            throws Exception {
        createLabRoot();
        writeWorker("group-1", "client-1", Map.of(), null);
        writeWorker("group-2", "client-2", Map.of(), null);
        JavaWorkerManager first = mock(JavaWorkerManager.class);
        AtomicInteger groups = new AtomicInteger();
        ScenarioWorkers workers = workers(
                twoGroupConfig(),
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
        writeWorker("group-1", "client-1", Map.of(), null);
        writeWorker("group-2", "client-2", Map.of(), null);
        JavaWorkerManager first = mock(JavaWorkerManager.class);
        JavaWorkerManager second = mock(JavaWorkerManager.class);
        doThrow(new IllegalStateException("start first"))
                .when(first).start();
        AtomicInteger groups = new AtomicInteger();
        ScenarioWorkers workers = workers(
                twoGroupConfig(),
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

    private ScenarioWorkers workers(
            String json,
            ScenarioWorkers.GroupManagerFactory managerFactory
    ) {
        return new ScenarioWorkers(
                RUNTIME_API,
                labRoot().toString(),
                ScenarioWorkersJsonParser.parse(json),
                definitions(),
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
        Files.writeString(
                group.resolve(clientWorkerKey + ".json"),
                Jsons.toJson(value),
                StandardCharsets.UTF_8
        );
    }

    private Path labRoot() {
        return temporaryDirectory.resolve("data/scenario-workers");
    }

    private static Map<String, WorkerEventDefinition<?>> definitions() {
        WorkerEventDefinition<?> definition =
                StringUtilityWorkerEvents.definitions().get(0);
        return Map.of(definition.eventName(), definition);
    }

    private static String config(String eventCode) {
        return Jsons.toJson(Map.of(
                GROUP,
                Map.of(
                        "eventCodes",
                        List.of(eventCode)
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
