package com.xa.mass.server.worker.identity;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore.SchedulingState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerRemovalStatus;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.redis.RedisTaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.redis.RedisTaskResourceCatalog;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.RegistrationStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.server.assembly.runtime.FixedWindowBindingsProperties;
import com.xa.mass.server.assembly.runtime.StartupSeededWorkerGroups;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.ProjectAssemblyProperties;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.testing.TaskCallers;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.server.worker.connection.WorkerConnectionControlStore;
import com.xa.mass.server.worker.group.WorkerGroupManagementService;
import com.xa.mass.server.worker.observation.WorkerGroupBindings;
import com.xa.mass.workermatching.MatchingComposition;
import com.xa.mass.workermatching.MatchingGroup;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("redis-owner")
class WorkerGroupManagementRedisIntegrationTest {

    private static final TaskCaller ADMIN = TaskCallers.caller("proof", "admin");

    private RedisTestScope testScope;
    private RedisKeyspace keyspace;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private final AtomicLong clockMillis = new AtomicLong(1_760_000_000_000L);

    private RedisWorkerScoreCore workerScoreCore;
    private RedisWorkerResourceCatalog workerCatalog;
    private RedisTaskScoreBandCore taskScoreCore;
    private RedisTaskItemScoreBandCore taskItemScoreCore;
    private RedisTaskRuntime taskRuntime;
    private RedisTaskResourceCatalog taskCatalog;
    private MatchingComposition matchingComposition;
    private RedisWorkerIdentityRegistry identityRegistry;
    private WorkerIdentityService identityService;
    private WorkerConnectionControlStore connectionControls;
    private WorkerGroupManagementService managementService;

    @BeforeEach
    void setUp() {
        testScope = RedisTestScope.create("wgm");
        keyspace = testScope.keyspace();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();

        workerScoreCore = new RedisWorkerScoreCore(redisClient, keyspace, clockMillis::get);
        workerCatalog = new RedisWorkerResourceCatalog(redisClient, workerScoreCore, keyspace);
        taskScoreCore = new RedisTaskScoreBandCore(redisClient, keyspace);
        taskItemScoreCore = new RedisTaskItemScoreBandCore(redisClient, keyspace);
        taskRuntime = new RedisTaskRuntime(
                redisClient,
                taskScoreCore,
                taskItemScoreCore,
                keyspace,
                Duration.ofDays(7).toMillis()
        );
        taskCatalog = new RedisTaskResourceCatalog(redisClient, keyspace);
        matchingComposition = MatchingComposition.create(
                redisClient,
                keyspace,
                Map.of("kept-group", new MatchingGroup(Set.of(), Set.of("worker.phone"))),
                List.of(),
                List.of(),
                List.of()
        );
        identityRegistry = new RedisWorkerIdentityRegistry(redisClient, keyspace);
        identityService = new WorkerIdentityService(identityRegistry);
        connectionControls = new WorkerConnectionControlStore(
                redisClient,
                keyspace,
                JsonMapper.builder().build()
        );
        var projects = new ProjectDirectory(new ProjectAssemblyProperties(List.of()), List.of());
        var bindings = new WorkerGroupBindings(
                Map.of("kept-group", new MatchingGroup(Set.of(), Set.of("worker.phone"))),
                new FixedWindowBindingsProperties(Map.of(), Map.of()),
                List.of()
        );
        managementService = new WorkerGroupManagementService(
                workerCatalog,
                workerScoreCore,
                taskScoreCore,
                taskCatalog,
                matchingComposition.catalog(),
                identityService,
                connectionControls,
                projects,
                bindings,
                StartupSeededWorkerGroups.empty(),
                new OperationGuard()
        );
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            testScope.cleanup(redis);
        }
        if (connectionControls != null) {
            connectionControls.close();
        }
        if (identityRegistry != null) {
            identityRegistry.close();
        }
        if (matchingComposition != null) {
            matchingComposition.close();
        }
        if (taskCatalog != null) {
            taskCatalog.close();
        }
        if (taskRuntime != null) {
            taskRuntime.close();
        }
        if (taskItemScoreCore != null) {
            taskItemScoreCore.close();
        }
        if (taskScoreCore != null) {
            taskScoreCore.close();
        }
        if (workerCatalog != null) {
            workerCatalog.close();
        }
        if (workerScoreCore != null) {
            workerScoreCore.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void kernelSummaryRemovalAndConditionalBindingCleanupRespectStateAndGroupOwnership() {
        workerCatalog.registerWorkerGroup(new WorkerGroupDescriptor("dyn-group", Map.of("v", 1), Set.of("ev.1")));
        workerCatalog.registerWorkerGroup(new WorkerGroupDescriptor("other-group", Map.of(), Set.of("ev.1")));

        assertThat(workerCatalog.updateWorkerGroup(
                new WorkerGroupDescriptor("dyn-group", Map.of("v", 2), Set.of("ev.1", "ev.2"))).status())
                .isEqualTo(RegistrationStatus.OK);
        assertThat(workerCatalog.updateWorkerGroup(
                new WorkerGroupDescriptor("dyn-group", Map.of("v", 2), Set.of("ev.1", "ev.2"))).status())
                .isEqualTo(RegistrationStatus.NOOP);
        assertThat(workerCatalog.updateWorkerGroup(
                new WorkerGroupDescriptor("absent-group", Map.of(), Set.of())).status())
                .isEqualTo(RegistrationStatus.NOT_FOUND);

        workerCatalog.registerWorkers("dyn-group", List.of("w-hot", "w-rec", "w-pactive", "w-poff", "w-cold"), "ep-1");
        workerCatalog.registerWorkers("other-group", List.of("w-other"), "ep-1");

        long now = clockMillis.get();
        workerScoreCore.rewriteCurrentPolarityWithinTimeFence(
                "dyn-group",
                Map.of("w-hot", now - 5_000L, "w-rec", now - 5_000L, "w-pactive", now - 5_000L),
                WorkerScorePolarity.HOT_ACQUIRE,
                now - 60_000L
        );
        long recObserved = workerScoreCore.observeDueHotScoreCandidates("dyn-group", null, 100).get("w-rec");
        workerScoreCore.deferObservedToRecovery("dyn-group", Map.of("w-rec", recObserved), 30_000L);
        workerScoreCore.pauseScheduling("dyn-group", "w-pactive");
        workerScoreCore.pauseScheduling("dyn-group", "w-poff");

        var summary = workerScoreCore.summarizeGroupStates("dyn-group");
        assertThat(summary.total()).isEqualTo(5);
        assertThat(summary.activeHot()).isEqualTo(1);
        assertThat(summary.recovery()).isEqualTo(1);
        assertThat(summary.pausedActive()).isEqualTo(1);
        assertThat(summary.pausedOffline()).isEqualTo(1);
        assertThat(summary.cold()).isEqualTo(1);
        assertThat(summary.activeTotal()).isEqualTo(3);

        assertThat(workerScoreCore.peekInactiveWorkerIds("dyn-group", 10).activeConflict()).isTrue();
        assertThat(workerScoreCore.removeInactiveWorkers("dyn-group", List.of("w-cold", "w-hot")).status())
                .isEqualTo(WorkerRemovalStatus.ACTIVE_CONFLICT);
        // Atomicity check: w-cold must NOT have been removed when the batch conflicted on w-hot.
        assertThat(workerScoreCore.observeSchedulingStates("dyn-group", List.of("w-cold")).statesByWorkerId().get("w-cold"))
                .isEqualTo(SchedulingState.COLD);

        // Conditional binding cleanup must never touch bindings owned by another group.
        int removedBindings = workerCatalog.removeWorkerBindings("dyn-group", List.of("w-cold", "w-other"));
        assertThat(removedBindings).isEqualTo(1);
        assertThat(workerCatalog.getWorkerDescriptors(List.of("w-cold")).get("w-cold")).isNull();
        assertThat(workerCatalog.getWorkerDescriptors(List.of("w-other")).get("w-other")).isNotNull();
    }

    @Test
    void endToEndManagementCleansAllRuntimeKeysForGroupAndSingleOfflineWorkerWithoutTouchingOtherGroups() {
        workerCatalog.registerWorkerGroup(new WorkerGroupDescriptor("temp-group", Map.of("owner", "ops"), Set.of("ev.a")));
        workerCatalog.registerWorkerGroup(new WorkerGroupDescriptor("kept-group", Map.of(), Set.of("ev.a")));

        List<String> tempIds = identityService.registerAll("temp-group", List.of("k-active", "k-cold"));
        String activeId = tempIds.get(0);
        String coldId = tempIds.get(1);
        List<String> keptIds = identityService.registerAll("kept-group", List.of("k-kept"));
        String keptId = keptIds.get(0);

        workerCatalog.registerWorkers("temp-group", List.of(activeId, coldId), "ep-1");
        workerCatalog.registerWorkers("kept-group", List.of(keptId), "ep-1");

        long now = clockMillis.get();
        workerScoreCore.rewriteCurrentPolarityWithinTimeFence(
                "temp-group",
                Map.of(activeId, now - 2_000L),
                WorkerScorePolarity.HOT_ACQUIRE,
                now - 60_000L
        );
        // Seed Matching facts, platform properties, timestamps, and connection controls.
        matchingComposition.properties().upsertWorkerFactsBatch(
                "temp-group",
                Map.of(activeId, Map.of("phone", "+10001"), coldId, Map.of("phone", "+10002"))
        );
        matchingComposition.properties().patchWorkerPlatformProperties(
                "temp-group", activeId, Map.of("region", "us"));
        matchingComposition.properties().patchWorkerPlatformProperties(
                "temp-group", coldId, Map.of("region", "eu"));
        matchingComposition.properties().upsertWorkerFactsBatch(
                "kept-group",
                Map.of(keptId, Map.of("phone", "+20001"))
        );
        matchingComposition.networkEvidenceTimestamps().filterAndAdvance("temp-group", Map.of(activeId, now, coldId, now));
        String tempFactsKey = keyspace.base() + ":matching:worker:facts:temp-group";
        String tempPlatformKey = keyspace.base() + ":matching:worker:platform-properties:temp-group";
        String tempEvidenceKey = com.xa.mass.workermatching.index.NetworkEvidenceTimestamps.key(keyspace, "temp-group");
        assertThat(redis.exists(tempFactsKey)).isEqualTo(1L);
        assertThat(redis.exists(tempPlatformKey)).isEqualTo(1L);
        assertThat(redis.exists(tempEvidenceKey)).isEqualTo(1L);
        connectionControls.update(
                "temp-group",
                coldId,
                new WorkerConnectionControlStore.Change("req-1", null, now + 60_000L, "maint"),
                "admin-proof",
                now
        );
        connectionControls.update(
                "kept-group",
                keptId,
                new WorkerConnectionControlStore.Change("req-2", null, now + 60_000L, "maint"),
                "admin-proof",
                now
        );

        // Create a non-terminal Task targeting temp-group; verify deleteGroup blocks on Task first.
        taskRuntime.createTask(new TaskDescriptor(
                "task-live",
                "temp-group",
                TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "1", "maxRetryTimes", "3"),
                List.of(),
                null,
                Map.of()
        ));
        assertThat(taskScoreCore.scanNonTerminalTasks("0", 50).tasks())
                .extracting("taskId").contains("task-live");
        assertThatThrownBy(() -> managementService.deleteGroup(ADMIN, "temp-group"))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT);

        // Complete the task (move to TERMINAL); verify scanNonTerminalTasks excludes it.
        taskScoreCore.closeScore("task-live");
        assertThat(taskScoreCore.scanNonTerminalTasks("0", 50).tasks()).isEmpty();

        // Group delete still blocked by activeId.
        assertThatThrownBy(() -> managementService.deleteGroup(ADMIN, "temp-group"))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT);

        // Single worker delete blocks activeId, succeeds on coldId while keeping identity mapping.
        assertThatThrownBy(() -> managementService.deleteOfflineWorker(ADMIN, "temp-group", activeId))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT);

        var singleDeleted = managementService.deleteOfflineWorker(ADMIN, "temp-group", coldId);
        assertThat(singleDeleted.status()).isEqualTo("deleted");
        assertThat(workerCatalog.getWorkerDescriptors(List.of(coldId)).get(coldId)).isNull();
        assertThat(workerScoreCore.observeSchedulingStates("temp-group", List.of(coldId)).statesByWorkerId().get(coldId))
                .isEqualTo(SchedulingState.MISSING);
        assertThat(connectionControls.read("temp-group", coldId)).isNull();
        assertThat(matchingComposition.properties().loadWorkerFacts("temp-group", List.of(coldId)).get(coldId)).isNull();
        // Single worker removal preserves identity registry mapping.
        assertThat(identityService.registerAll("temp-group", List.of("k-cold"))).containsExactly(coldId);

        // Move activeId to COLD (simulating Worker disconnect/cool-down).
        long activeScore = workerScoreCore.observeDueHotScoreCandidates("temp-group", null, 100).get(activeId);
        long recScore = workerScoreCore.deferObservedToRecovery("temp-group", Map.of(activeId, activeScore), 5_000L)
                .get(activeId).score();
        workerScoreCore.parkObservedRecoveryScore("temp-group", activeId, recScore);

        // Directory listing shows 0 active workers and 1 cold worker for temp-group.
        var directory = managementService.list(ADMIN, "0", 100);
        var tempRow = directory.groups().stream()
                .filter(g -> g.workerGroupId().equals("temp-group")).findFirst().orElseThrow();
        assertThat(tempRow.deletableByConfig()).isTrue();
        assertThat(tempRow.workerCounts().activeTotal()).isZero();
        assertThat(tempRow.workerCounts().cold()).isEqualTo(1);

        // Now deleteGroup succeeds and cleans all temp-group runtime keys!
        var deletedGroup = managementService.deleteGroup(ADMIN, "temp-group");
        assertThat(deletedGroup.status()).isEqualTo("deleted");
        assertThat(deletedGroup.removedWorkers()).isEqualTo(1);

        assertThat(workerCatalog.getWorkerGroupDescriptors(List.of("temp-group")).get("temp-group")).isNull();
        assertThat(workerCatalog.getWorkerDescriptors(List.of(activeId)).get(activeId)).isNull();
        assertThat(redis.exists(keyspace.base() + ":worker:score:temp-group")).isZero();
        assertThat(redis.exists(keyspace.base() + ":worker:identity:temp-group")).isZero();
        assertThat(redis.exists(keyspace.base() + ":server:worker-connection-control:temp-group")).isZero();
        assertThat(redis.exists(tempFactsKey)).isZero();
        assertThat(redis.exists(tempPlatformKey)).isZero();
        assertThat(redis.exists(tempEvidenceKey)).isZero();

        // Unrelated group remains completely intact.
        assertThat(workerCatalog.getWorkerGroupDescriptors(List.of("kept-group")).get("kept-group")).isNotNull();
        assertThat(workerCatalog.getWorkerDescriptors(List.of(keptId)).get(keptId)).isNotNull();
        assertThat(connectionControls.read("kept-group", keptId)).isNotNull();
        assertThat(matchingComposition.catalog().take("kept-group", Map.of("q", new WorkerQuery("worker.phone", "+20001"))))
                .containsKey("q");
    }
}
