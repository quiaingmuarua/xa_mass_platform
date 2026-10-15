package com.xa.mass.server.task.directory;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.redis.RedisTaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.redis.RedisTaskResourceCatalog;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.*;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskCreationUnconfirmedException;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("redis-owner")
class TaskDirectoryRedisIntegrationTest {
    private static final com.xa.mass.server.task.access.TaskCaller CALLER = com.xa.mass.server.testing.TaskCallers.caller("1001", "admin");
    RedisTestScope scope;
    RedisClient client;
    StatefulRedisConnection<String, String> connection;
    RedisTaskScoreBandCore scores;
    RedisTaskItemScoreBandCore items;
    RedisTaskRuntime runtime;
    RedisTaskResourceCatalog catalog;
    TaskDirectory directory;
    com.xa.mass.server.task.directory.TaskCreationRegistry creations;
    ProjectTaskQueryService queries;
    ProjectDirectory projects;
    WorkerResourceCatalog groups;
    WorkerMatchingCatalog matching;
    final OperationGuard guard = new OperationGuard();

    @BeforeEach void start() {
        scope = RedisTestScope.create("server_task_directory");
        client = RedisClient.create(REDIS_URL); connection = client.connect();
        creations = new com.xa.mass.server.task.directory.TaskCreationRegistry(client, scope.keyspace());
        scores = new RedisTaskScoreBandCore(client, scope.keyspace());
        items = new RedisTaskItemScoreBandCore(client, scope.keyspace());
        runtime = new RedisTaskRuntime(client, scores, items, scope.keyspace(), java.time.Duration.ofDays(7).toMillis());
        catalog = new RedisTaskResourceCatalog(client, scope.keyspace());
        projects = new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                new ProjectDefinition("p", List.of("g")), new ProjectDefinition("q", List.of("g")))), List.of());
        groups = mock(WorkerResourceCatalog.class);
        when(groups.getWorkerGroupDescriptors(anyList())).thenReturn(Map.of("g",
                new WorkerResourceCatalog.WorkerGroupDescriptor("g", Map.of(), Set.of("event"))));
        matching = mock(WorkerMatchingCatalog.class);
        when(matching.normalizeRefill(anyString(), anyList())).thenAnswer(call -> call.getArgument(1));
        directory = newDirectory(); queries = new ProjectTaskQueryService(projects, catalog, scores, directory, org.mockito.Mockito.mock(com.xa.mass.kernel.score.TaskItemScoreBandCore.class), runtime, com.xa.mass.server.testsupport.TaskRetentionFixtures.properties());
    }
    TaskDirectory newDirectory() { return new TaskDirectory(client, scope.keyspace(), projects, catalog, scores, groups, guard, runtime); }
    TaskCreationService creator() { return new TaskCreationService(groups, matching, runtime, projects, catalog, scores, guard, directory, creations); }
    String key(String project) {
        return scope.keyspace().base() + ":server:task-directory:" + Base64.getUrlEncoder().withoutPadding().encodeToString(project.getBytes(StandardCharsets.UTF_8));
    }
    TaskDescriptor create(String id, String project, String user, long time) {
        var task = new TaskDescriptor(id, "g", TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "0", "maxRetryTimes", "3"), List.of(), null,
                new TaskAttribution(project, time, user).stamp(user == null ? Map.of() : Map.of("userId", user)));
        assertThat(runtime.createTask(task).status()).isEqualTo(TaskCreationStatus.CREATED);
        directory.ensure(CALLER, id);
        return task;
    }
    @AfterEach void stop() {
        directory.close(); creations.close(); runtime.close(); catalog.close(); items.close(); scores.close();
        scope.cleanup(connection.sync()); connection.close(); client.shutdown();
    }

    @Test void oneProjectKeySelectsUsersBeforeTheBatchLimitAndDoesNotPromiseLatestTasks() {
        create("unowned", "p", null, 1);
        for (int i = 0; i < 105; i++) create("other-" + i, "p", "1001", i + 2);
        create("target", "p", "1002", 9999);
        assertThat(queries.list(CALLER, "p", 1000, null).tasks()).hasSize(107);
        assertThat(queries.list(CALLER, "p", 100, null).tasks()).noneMatch(row -> row.taskId().equals("target"));
        assertThat(queries.list(CALLER, "p", 100, "1002").tasks()).extracting(ProjectTaskQueryService.Entry::taskId).containsExactly("target");
        assertThat(queries.list(CALLER, "p", 100, "1001").truncated()).isTrue();
        assertThat(directory.list("p", "1001", 1).members().getFirst().taskId()).isEqualTo("other-0");
        assertThat(queries.list(CALLER, "p", 1, "1001").tasks().getFirst().createdAtMillis()).isEqualTo(2L);
        assertThat(connection.sync().zscore(key("p"), "unowned")).isZero();
        assertThat(connection.sync().zscore(key("p"), "target")).isEqualTo((double) TaskUserId.score("1002"));
        var keys = new ArrayList<String>(); ScanCursor cursor = ScanCursor.INITIAL;
        do {
            var page = connection.sync().scan(cursor, ScanArgs.Builder.matches(scope.keyspace().base() + ":server:task-directory:*").limit(100));
            keys.addAll(page.getKeys()); cursor = page;
        } while (!cursor.isFinished());
        assertThat(keys).containsExactly(key("p"));
        assertThat(connection.sync().exists(scope.keyspace().base() + ":task:project:p")).isZero();
    }

    @Test void projectDirectoriesRemainSeparateAndForeignMembershipIsNotReinterpreted() {
        create("p-task", "p", "1001", 1);
        create("q-task", "q", "1001", 2);
        assertThat(queries.list(CALLER, "p", 100, "1001").tasks()).extracting(ProjectTaskQueryService.Entry::taskId).containsExactly("p-task");
        assertThat(queries.list(CALLER, "q", 100, "1001").tasks()).extracting(ProjectTaskQueryService.Entry::taskId).containsExactly("q-task");
        connection.sync().zadd(key("q"), TaskUserId.score("1001"), "p-task");
        assertThatThrownBy(() -> queries.list(CALLER, "q", 100, null)).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> queries.get(CALLER, "q", "p-task")).isInstanceOf(ServerException.class);
        assertThat(connection.sync().zcard(key("q"))).isEqualTo(2);
        assertThat(directory.ensure(CALLER, "p-task").status()).isEqualTo(ActionOutcome.Status.UNCHANGED);
        assertThat(connection.sync().zcard(key("p"))).isEqualTo(1);
    }

    @Test void failedDirectoryWriteKeepsTheKnownTaskAndExplicitRetryUsesItsSavedTime() {
        var interruptedRuntime = mock(com.xa.mass.kernel.task.TaskRuntime.class);
        when(interruptedRuntime.createTask(any())).thenAnswer(call -> {
            var outcome = runtime.createTask(call.getArgument(0));
            connection.sync().set(key("p"), "wrong-type");
            return outcome;
        });
        var interrupted = new TaskCreationService(groups, matching, interruptedRuntime, projects, catalog, scores, guard, directory, creations);
        var request = new TaskCreateRequest("request", "p", "g", 50, 3, List.of(), "first", Map.of());
        var failure = catchThrowableOfType(TaskCreationUnconfirmedException.class,
                () -> interrupted.createForRequest(CALLER, "raw", request, "a".repeat(64)));
        var saved = catalog.loadTaskAllocationDescriptors(List.of(failure.taskId())).get(failure.taskId());
        assertThat(saved).isNotNull();
        assertThat(queries.get(CALLER, "p", failure.taskId()).task().metadata()).isEqualTo(saved.metadata());
        assertThat(connection.sync().get(key("p"))).isEqualTo("wrong-type");
        connection.sync().unlink(key("p"));
        assertThat(creator().createForRequest(CALLER, "raw", request, "a".repeat(64)).taskId()).isEqualTo(failure.taskId());
        assertThat(catalog.loadTaskAllocationDescriptors(List.of(failure.taskId())).get(failure.taskId())).isEqualTo(saved);
        assertThat(connection.sync().zcard(key("p"))).isEqualTo(1);
        assertThat(connection.sync().zcard(scope.keyspace().base() + ":task:score")).isEqualTo(1);
        directory.close(); directory = newDirectory();
        assertThat(directory.ensure(CALLER, failure.taskId()).status()).isEqualTo(ActionOutcome.Status.UNCHANGED);
        assertThatThrownBy(() -> creator().createForRequest(CALLER, "raw", request, "b".repeat(64))).isInstanceOf(ServerException.class);
    }

    @Test void explicitEnsureIsIdempotentAcrossOwnersAndNeverOverwritesOrRepairsKernelState() throws Exception {
        var saved = create("t", "p", "zzzzzzzzz", 123);
        connection.sync().unlink(key("p"));
        assertThat(queries.get(CALLER, "p", "t").createdAtMillis()).isEqualTo(123L);
        assertThat(connection.sync().exists(key("p"))).isZero();
        try (var other = new TaskDirectory(client, scope.keyspace(), projects, catalog, scores, groups, new OperationGuard(), runtime);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> directory.ensure(CALLER, "t"));
            var b = executor.submit(() -> other.ensure(CALLER, "t"));
            assertThat(List.of(a.get().status(), b.get().status())).containsExactlyInAnyOrder(ActionOutcome.Status.APPLIED, ActionOutcome.Status.UNCHANGED);
        }
        assertThat(catalog.loadTaskAllocationDescriptors(List.of("t")).get("t")).isEqualTo(saved);
        connection.sync().zadd(key("p"), TaskUserId.score("1001"), "t");
        assertThatThrownBy(() -> directory.ensure(CALLER, "t")).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> queries.list(CALLER, "p", 100, "1001")).isInstanceOf(ServerException.class);
        assertThat(connection.sync().zscore(key("p"), "t")).isEqualTo((double) TaskUserId.score("1001"));
        connection.sync().unlink(key("p"));
        connection.sync().zrem(scope.keyspace().base() + ":task:score", "t");
        assertThatThrownBy(() -> directory.ensure(CALLER, "t")).isInstanceOf(ServerException.class);
        assertThat(connection.sync().exists(key("p"))).isZero();
        assertThatThrownBy(() -> directory.ensure(CALLER, "missing")).isInstanceOf(ServerException.class);
    }

    @Test void invalidAndReservedMetadataIsRejectedBeforeTaskCreation() {
        for (var metadata : List.of(Map.of("userId", "0001"), Map.of("userId", "TOOLONGID10"), Map.of("server.projectId", "q")))
            assertThatThrownBy(() -> creator().create(CALLER, new TaskCreateRequest("request", "p", "g", 50, 3, List.of(), null, metadata)))
                    .isInstanceOf(ServerException.class);
        assertThat(connection.sync().exists(key("p"), scope.keyspace().base() + ":task:score")).isZero();
    }

    @Test void rpcPreparationNeverRepairsMissingKernelResourcesOrApprovesBeforeDirectoryConfirmation() {
        var request = new com.xa.mass.server.api.v1.contract.task.TaskRpcPrepareRequest("p", "g", null, Map.of(), null);
        var lifecycle = new com.xa.mass.kernel.task.DefaultTaskLifecycleCommands(scores, catalog);
        var rpc = new com.xa.mass.server.task.call.TaskRpcProperties(java.time.Duration.ofDays(7), 1000, 1000, 10, 10, 10, 50, 100, 250, Map.of());
        var interruptedRuntime = mock(com.xa.mass.kernel.task.TaskRuntime.class);
        when(interruptedRuntime.createTask(any())).thenAnswer(call -> {
            var outcome = runtime.createTask(call.getArgument(0));
            connection.sync().set(key("p"), "wrong-type");
            return outcome;
        });
        var interruptedPreparation = new com.xa.mass.server.task.call.TaskRpcPreparationService(groups, catalog, interruptedRuntime,
                lifecycle, matching, rpc, projects, guard, directory, scores);
        assertThatThrownBy(() -> interruptedPreparation.prepare(CALLER, "raw", "rpc", request)).isInstanceOf(ServerException.class);
        var saved = catalog.loadTaskAllocationDescriptors(List.of("rpc")).get("rpc");
        assertThat(saved).isNotNull();
        assertThat(scores.getScoreStates(List.of("rpc")).get("rpc").band()).isEqualTo(com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand.PRE_REVIEW);
        connection.sync().unlink(key("p"));
        var preparation = new com.xa.mass.server.task.call.TaskRpcPreparationService(groups, catalog, runtime,
                lifecycle, matching, rpc, projects, guard, directory, scores);
        preparation.prepare(CALLER, "raw", "rpc", request);
        assertThat(catalog.loadTaskAllocationDescriptors(List.of("rpc")).get("rpc")).isEqualTo(saved);
        assertThat(scores.getScoreStates(List.of("rpc")).get("rpc").isInitial()).isTrue();

        create("partial", "p", "1001", 123);
        connection.sync().unlink(scope.keyspace().base() + ":task:partial:descriptor", key("p"));
        var service = preparation;
        assertThatThrownBy(() -> service.prepare(CALLER, "raw", "partial", request)).isInstanceOf(ServerException.class);
        assertThat(catalog.loadTaskAllocationDescriptors(List.of("partial")).get("partial")).isNull();
        assertThat(connection.sync().exists(key("p"))).isZero();
    }
}
