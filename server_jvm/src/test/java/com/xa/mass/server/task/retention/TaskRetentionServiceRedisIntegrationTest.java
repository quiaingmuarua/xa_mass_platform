package com.xa.mass.server.task.retention;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.TaskRedisKeys;
import com.xa.mass.kernel.score.redis.*;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.kernel.task.redis.*;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.*;
import com.xa.mass.server.task.*;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.directory.*;
import com.xa.mass.server.task.result.TaskResultsExportService;
import com.xa.mass.server.testing.TaskCallers;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("redis-owner")
class TaskRetentionServiceRedisIntegrationTest {
    private static final TaskCaller CALLER = TaskCallers.caller("1000", "admin");
    @TempDir Path files;
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisTaskScoreBandCore scores;
    private RedisTaskItemScoreBandCore items;
    private RedisTaskRuntime runtime;
    private RedisTaskResourceCatalog catalog;
    private TaskDirectory directory;
    private TaskCreationRegistry creations;
    private TaskCreationService creator;
    private TaskResultArchive archive;
    private TaskRetentionProperties properties;
    private TaskResultsExportService exports;
    private final OperationGuard guard = new OperationGuard();
    private final AtomicLong clock = new AtomicLong(2_000_000_000_000L);

    @BeforeEach void start() {
        scope = RedisTestScope.create("server_retention");
        client = RedisClient.create(REDIS_URL); connection = client.connect();
        scores = new RedisTaskScoreBandCore(client, scope.keyspace(), clock::get);
        items = new RedisTaskItemScoreBandCore(client, scope.keyspace(), clock::get);
        runtime = new RedisTaskRuntime(client, scores, items, scope.keyspace(), clock::get);
        catalog = new RedisTaskResourceCatalog(client, scope.keyspace());
        creations = new TaskCreationRegistry(client, scope.keyspace());
        var projects = new ProjectDirectory(new ProjectAssemblyProperties(List.of(new ProjectDefinition("p", List.of("g")))), List.of());
        var groups = mock(WorkerResourceCatalog.class);
        when(groups.getWorkerGroupDescriptors(anyList())).thenReturn(Map.of("g", new WorkerResourceCatalog.WorkerGroupDescriptor("g", Map.of(), Set.of("event"))));
        var matching = mock(WorkerMatchingCatalog.class);
        when(matching.normalizeRefill(anyString(), anyList())).thenAnswer(call -> call.getArgument(1));
        directory = new TaskDirectory(client, scope.keyspace(), projects, catalog, scores, groups, guard, runtime);
        creator = new TaskCreationService(groups, matching, runtime, projects, catalog, scores, guard, directory, creations);
        properties = new TaskRetentionProperties(true, Duration.ofHours(1), Duration.ofDays(7), Duration.ofHours(1), files);
        archive = new TaskResultArchive(runtime, JsonMapper.builder().build(), scope.keyspace(), properties);
        exports = new TaskResultsExportService(catalog, scores, runtime, JsonMapper.builder().build(), guard, archive);
    }
    @AfterEach void stop() {
        if (connection != null) scope.cleanup(connection.sync());
        if (directory != null) directory.close();
        if (creations != null) creations.close();
        if (runtime != null) runtime.close();
        if (catalog != null) catalog.close();
        if (items != null) items.close();
        if (scores != null) scores.close();
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }
    private TaskRetentionService maintenance(TaskResultArchive archive, TaskCreationRegistry registry) {
        return new TaskRetentionService(runtime, catalog, scores, directory, registry, archive, guard, properties, clock::get);
    }
    private String create() {
        return creator.create(CALLER, new TaskCreateRequest("same-request", "p", "g", 1, 1, List.of(), "test", Map.of())).taskId();
    }
    private long closeWithResult(String id) {
        runtime.appendItems(id, List.of(new TaskItem("one", "event", clock.get(), Map.of("number", "one"), 1, clock.get() + 60000, new WorkerQuery("workerId", "w")),
                new TaskItem("pending", "event", clock.get(), Map.of(), 1, clock.get() + 60000, new WorkerQuery("workerId", "w"))));
        runtime.storeTaskItemSuccessResults(id, Map.of("one", new TaskItemSuccessResult(6, clock.get(), "answer")));
        return scores.closeScore(id).score();
    }
    @Test void archivesOriginalInputKeepsCountsThenDeletesAllAndCreatesANewExecution() throws Exception {
        String id = create(); closeWithResult(id);
        var service = maintenance(archive, creations);
        clock.addAndGet(Duration.ofMinutes(59).toMillis());
        assertThat(service.maintain(id, clock.get()).retention().phase()).isEqualTo("OPEN");
        clock.addAndGet(Duration.ofMinutes(1).toMillis());
        assertThat(service.maintain(id, clock.get()).retention().phase()).isEqualTo("RELEASED");
        assertThat(create()).isEqualTo(id);
        assertThat(connection.sync().exists(TaskRedisKeys.items(scope.keyspace(), id), TaskRedisKeys.results(scope.keyspace(), id))).isZero();
        assertThat(items.observeItemScoreCounts(List.of(id)).get(id).total()).isEqualTo(2);
        var preview = exports.preview(CALLER, id);
        assertThat(preview.results()).hasSize(1);
        assertThat(preview.results().getFirst().item().payload()).containsEntry("number", "one");
        var output = exports.export(CALLER, id);
        try { assertThat(Files.readAllLines(output.file())).containsExactly("{\"messageId\":\"one\",\"opaqueResultPayload\":\"answer\"}"); }
        finally { Files.deleteIfExists(output.file()); }
        clock.addAndGet(Duration.ofDays(7).toMillis());
        assertThat(service.maintain(id, clock.get()).retention().phase()).isEqualTo("PURGED");
        assertThat(directory.contains("p", id)).isFalse();
        assertThat(catalog.loadTaskAllocationDescriptors(List.of(id)).get(id)).isNull();
        assertThat(scores.getScoreStates(List.of(id)).get(id)).isNull();
        try (var paths = Files.walk(files)) { assertThat(paths.filter(Files::isRegularFile).count()).isZero(); }
        String replacement = create();
        assertThat(replacement).isNotEqualTo(id);
        runtime.storeTaskItemSuccessResults(id, Map.of("one", new TaskItemSuccessResult(9, clock.get(), "old")));
        assertThat(connection.sync().exists(TaskRedisKeys.results(scope.keyspace(), id))).isZero();
        assertThat(create()).isEqualTo(replacement);
    }
    @Test void failedArchiveKeepsRedisAndPublishedArchiveIsReusedAfterReconstruction() throws Exception {
        String id = create(); long score = closeWithResult(id);
        clock.addAndGet(Duration.ofHours(1).toMillis());
        var broken = spy(archive);
        doThrow(new IOException("disk unavailable")).when(broken).publish(any(), any());
        assertThatThrownBy(() -> maintenance(broken, creations).maintain(id, clock.get())).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        assertThat(runtime.loadTaskDataStates(List.of(id)).get(id).phase()).isEqualTo(TaskDataPhase.FROZEN);
        assertThat(runtime.loadTaskItemResults(id, List.of("one")).get("one")).isNotNull();
        archive.publish(catalog.loadTaskAllocationDescriptors(List.of(id)).get(id), runtime.loadTaskDataStates(List.of(id)).get(id));
        var before = archive.verify(id);
        var reopened = new TaskResultArchive(runtime, JsonMapper.builder().build(), scope.keyspace(), properties);
        maintenance(reopened, creations).maintain(id, clock.get());
        assertThat(reopened.verify(id)).isEqualTo(before);
        assertThat(scores.getScoreStates(List.of(id)).get(id).score()).isEqualTo(score);
        Path result;
        try (var paths = Files.walk(files)) { result = paths.filter(path -> path.getFileName().toString().equals("results.jsonl")).findFirst().orElseThrow(); }
        Files.writeString(result, "corrupt");
        assertThatThrownBy(() -> exports.preview(CALLER, id)).isInstanceOf(com.xa.mass.server.error.ServerException.class);
    }
    @Test void deletionCanResumeAfterLostAssociationDeleteWithoutErasingTheNewGeneration() {
        String old = create(); closeWithResult(old);
        clock.addAndGet(Duration.ofDays(8).toMillis());
        var interrupted = spy(creations);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("lost response"); }).when(interrupted).remove(anyString(), eq(old));
        assertThatThrownBy(() -> maintenance(archive, interrupted).maintain(old, clock.get())).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        assertThat(runtime.loadTaskDataStates(List.of(old)).get(old).phase()).isEqualTo(TaskDataPhase.PURGING);
        assertThat(scores.getScoreStates(List.of(old)).get(old)).isNotNull();
        String next = create();
        assertThat(next).isNotEqualTo(old);
        maintenance(archive, creations).maintain(old, clock.get());
        assertThat(create()).isEqualTo(next);
    }
    @Test void aCorruptManifestCountRejectsPreviewBeforeReturningAnyRows() throws Exception {
        String id = create(); closeWithResult(id);
        clock.addAndGet(Duration.ofHours(1).toMillis());
        maintenance(archive, creations).maintain(id, clock.get());
        Path metadata;
        try (var paths = Files.walk(files)) {
            metadata = paths.filter(path -> path.getFileName().toString().equals("manifest.json")).findFirst().orElseThrow();
        }
        var mapper = JsonMapper.builder().build();
        var manifest = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(metadata));
        manifest.put("records", 0);
        Files.writeString(metadata, mapper.writeValueAsString(manifest));
        assertThatThrownBy(() -> exports.preview(CALLER, id)).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        assertThat(runtime.loadTaskDataStates(List.of(id)).get(id).phase()).isEqualTo(TaskDataPhase.RELEASED);
    }
    @Test void authorizationPrecedesFilesAndExpiredTasksDoNotRequireAnArchive() throws Exception {
        var files = spy(archive);
        var service = maintenance(files, creations);
        assertThatThrownBy(() -> service.run(TaskCallers.caller("1001", "operator", "p"), "unknown"))
                .isInstanceOfSatisfying(com.xa.mass.server.error.ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(com.xa.mass.server.error.ServerErrorCode.TASK_ACCESS_DENIED));
        verifyNoInteractions(files);
        String id = create(); closeWithResult(id);
        clock.addAndGet(Duration.ofDays(8).toMillis());
        service.maintain(id, clock.get());
        verify(files, never()).publish(any(), any());
        assertThat(scores.getScoreStates(List.of(id)).get(id)).isNull();
    }

    @Test void invalidCreationDoesNotLeaveAnUnreachableRequestAssociation() {
        for (var input : List.of(
                new TaskCreateRequest("invalid", "p", "missing", 1, 1, List.of(), "valid", Map.of()),
                new TaskCreateRequest("invalid", "p", "g", 1, 1, List.of(), " ", Map.of()))) {
            assertThatThrownBy(() -> creator.create(CALLER, input)).isInstanceOf(com.xa.mass.server.error.ServerException.class);
            assertThat(creations.find("p", CALLER.identityKey(), "invalid", "raw", "0".repeat(64))).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"release", "directory", "finish"})
    void resumesAfterEachCommittedStepLosesItsResponse(String step) {
        String id = create(); closeWithResult(id);
        var interruptedRuntime = spy(runtime);
        var interruptedDirectory = spy(directory);
        var interrupted = new TaskRetentionService(interruptedRuntime, catalog, scores, interruptedDirectory,
                creations, archive, guard, properties, clock::get);
        clock.addAndGet(step.equals("release") ? Duration.ofHours(1).toMillis() : Duration.ofDays(8).toMillis());
        if (step.equals("release")) doAnswer(call -> {
            call.callRealMethod(); throw new IllegalStateException("release response lost");
        }).when(interruptedRuntime).releaseTaskPayloads(eq(id), anyLong());
        else if (step.equals("directory")) doAnswer(call -> {
            call.callRealMethod(); throw new IllegalStateException("directory response lost");
        }).when(interruptedDirectory).remove("p", id);
        else doAnswer(call -> {
            call.callRealMethod(); throw new IllegalStateException("final response lost");
        }).when(interruptedRuntime).finishTaskPurge(eq(id), anyLong());
        assertThatThrownBy(() -> interrupted.maintain(id, clock.get())).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        var resumed = maintenance(archive, creations).maintain(id, clock.get());
        assertThat(resumed.retention().phase()).isEqualTo(step.equals("release") ? "RELEASED" : "PURGED");
        if (step.equals("release")) {
            assertThat(create()).isEqualTo(id);
            assertThat(exports.preview(CALLER, id).results()).hasSize(1);
        } else {
            assertThat(directory.contains("p", id)).isFalse();
            assertThat(create()).isNotEqualTo(id);
        }
    }

    @Test void interruptedJsonlWriteIsRebuiltFromTheFrozenSource() throws Exception {
        String id = create(); closeWithResult(id);
        clock.addAndGet(Duration.ofHours(1).toMillis());
        var interruptedRuntime = spy(runtime);
        doReturn(new TaskItemResultPage("42", Map.of("one", TaskItemResult.succeeded("answer"))))
                .when(interruptedRuntime).scanTaskItemResults(eq(id), eq("0"), anyInt());
        doThrow(new IllegalStateException("process interrupted during scan"))
                .when(interruptedRuntime).scanTaskItemResults(eq(id), eq("42"), anyInt());
        var partialArchive = new TaskResultArchive(interruptedRuntime, JsonMapper.builder().build(), scope.keyspace(), properties);
        assertThatThrownBy(() -> maintenance(partialArchive, creations).maintain(id, clock.get()))
                .isInstanceOf(com.xa.mass.server.error.ServerException.class);
        assertThat(runtime.loadTaskDataStates(List.of(id)).get(id).phase()).isEqualTo(TaskDataPhase.FROZEN);
        assertThat(runtime.loadTaskItemResults(id, List.of("one")).get("one")).isNotNull();
        try (var paths = Files.walk(files)) {
            assertThat(paths.anyMatch(path -> path.getFileName().toString().endsWith(".pending"))).isTrue();
        }
        maintenance(archive, creations).maintain(id, clock.get());
        assertThat(archive.verify(id).records()).isEqualTo(1);
        try (var paths = Files.walk(files)) {
            assertThat(paths.anyMatch(path -> path.getFileName().toString().endsWith(".pending"))).isFalse();
        }
    }

    @Test void sweepAdvancesPastArchivedAndFailingPagesAndLaterDeletesThemAll() throws Exception {
        var failing = spy(archive);
        doThrow(new IOException("unavailable file")).when(failing).publish(
                argThat(task -> task.taskId().equals("page-0101") || task.taskId().equals("page-0102")), any());
        var service = maintenance(failing, creations);
        for (int i = 0; i < 205; i++) {
            String id = "page-%04d".formatted(i);
            runtime.createTask(new TaskDescriptor(id, "g", TaskIdleDisposition.CLOSE_WHEN_IDLE,
                    Map.of("priority", "1", "maxRetryTimes", "1"), List.of(), null, Map.of()));
            scores.closeScore(id);
        }
        clock.addAndGet(Duration.ofHours(1).toMillis());
        for (int i = 0; i < 101; i++) service.maintain("page-%04d".formatted(i), clock.get());
        service.runOnce();
        assertThat(runtime.loadTaskDataStates(List.of("page-0101", "page-0102", "page-0204")))
                .satisfies(states -> {
                    assertThat(states.get("page-0101").phase()).isEqualTo(TaskDataPhase.FROZEN);
                    assertThat(states.get("page-0102").phase()).isEqualTo(TaskDataPhase.FROZEN);
                    assertThat(states.get("page-0204").phase()).isEqualTo(TaskDataPhase.RELEASED);
                });
        clock.addAndGet(Duration.ofDays(7).toMillis());
        service.runOnce();
        assertThat(connection.sync().zcard(TaskRedisKeys.scores(scope.keyspace()))).isZero();
    }

    @Test void aTaskThatExpiresDuringTraversalIsDeletedWithoutAnArchive() throws Exception {
        String id = create(); closeWithResult(id);
        clock.addAndGet(properties.retention().toMillis() - 1);
        var observedScores = spy(scores);
        doAnswer(call -> {
            Object page = call.callRealMethod();
            clock.incrementAndGet();
            return page;
        }).when(observedScores).scanTerminalTasks(anyLong(), anyString(), anyInt());
        var files = spy(archive);
        var service = new TaskRetentionService(runtime, catalog, observedScores, directory, creations,
                files, guard, properties, clock::get);
        service.runOnce();
        verify(files, never()).publish(any(), any());
        assertThat(scores.getScoreStates(List.of(id)).get(id)).isNull();
    }
}
