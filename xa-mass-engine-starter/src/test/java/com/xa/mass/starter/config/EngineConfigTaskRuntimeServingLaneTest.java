package com.xa.mass.starter.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.TaskRuntimeServingLane;
import com.xa.mass.task.runtime.ClaimLeasePolicy;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import com.xa.mass.task.runtime.starter.TaskRuntimeBackendKind;
import io.lettuce.core.RedisClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class EngineConfigTaskRuntimeServingLaneTest {

    @Test
    void defaultEngineConfigRoutesAppendClaimAndResultThroughTaskRuntimeServingLane() {
        EngineConfig config = new EngineConfig();
        try {
            assertThat(config.getTaskRuntimeBootstrapConfig().backendKind()).isEqualTo(TaskRuntimeBackendKind.MEMORY);
            assertThat(config.getTaskAssignmentRuntimePort()).isInstanceOf(TaskRuntimeServingLane.class);
            assertThat(config.getTaskLeaseMaintenancePort()).isSameAs(config.getTaskAssignmentRuntimePort());
            assertThat(config.getTaskDispatchWakeupPort()).isSameAs(config.getTaskAssignmentRuntimePort());
            assertThat(config.getTaskRuntimeRecoveryPort()).isSameAs(config.getTaskAssignmentRuntimePort());

            assertAppendClaimResultPath(config, "engine-config-serving-lane");
        } finally {
            config.shutdownTaskRuntime();
        }
    }

    @Test
    void redisEngineConfigRoutesAppendClaimAndResultThroughTaskRuntimeServingLaneWhenRedisAvailable() {
        String redisUri = redisUri();
        String namespace = "xa:mass:test:engine-config-task-runtime:" + UUID.randomUUID();
        Assumptions.assumeTrue(redisAvailable(redisUri), "Redis is not available for engine-config task-runtime proof");
        EngineConfig config = new EngineConfig();
        config.useRedisTaskRuntime(redisUri, namespace);
        try {
            assertThat(config.getTaskRuntimeBootstrapConfig().backendKind()).isEqualTo(TaskRuntimeBackendKind.REDIS);
            assertAppendClaimResultPath(config, "engine-config-redis-serving-lane");
        } finally {
            config.shutdownTaskRuntime();
            cleanupRedisNamespace(redisUri, namespace);
        }
    }

    @Test
    void taskRuntimeBootstrapConfigCannotChangeAfterServingLaneMaterializes() {
        EngineConfig config = new EngineConfig();
        try {
            config.getTaskAssignmentRuntimePort();

            org.assertj.core.api.Assertions.assertThatThrownBy(config::useMemoryTaskRuntime)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("taskRuntimeBootstrapConfig");
        } finally {
            config.shutdownTaskRuntime();
        }
    }

    @Test
    void copiedEngineConfigDoesNotShareMaterializedTaskRuntimeServingLane() {
        EngineConfig source = new EngineConfig();
        try {
            var sourceAssignment = source.getTaskAssignmentRuntimePort();
            EngineConfig copy = new EngineConfig(source);

            assertThat(copy.getTaskRuntimeBootstrapConfig()).isEqualTo(source.getTaskRuntimeBootstrapConfig());
            assertThat(copy.getTaskAssignmentRuntimePort()).isInstanceOf(TaskRuntimeServingLane.class);
            assertThat(copy.getTaskAssignmentRuntimePort()).isNotSameAs(sourceAssignment);
            copy.shutdownTaskRuntime();
        } finally {
            source.shutdownTaskRuntime();
        }
    }

    @Test
    void engineConfigDoesNotKeepOrPassLegacyTaskRuntimeFallback() throws IOException {
        String engineConfigSource = Files.readString(engineConfigSource(), StandardCharsets.UTF_8);
        Pattern oldRuntimeTaskManagerConstructor = Pattern.compile(
                "new\\s+TaskManager\\s*\\([\\s\\S]*?TaskWorkRuntime[\\s\\S]*?TaskResultRuntime[\\s\\S]*?\\)");

        assertThat(engineConfigSource)
                .doesNotContain("import com.xa.mass.runtime.api.TaskWorkRuntime;")
                .doesNotContain("import com.xa.mass.runtime.api.TaskResultRuntime;")
                .doesNotContain("taskWorkRuntime")
                .doesNotContain("taskResultRuntime")
                .doesNotContain("disabledLegacyRuntime")
                .doesNotContain("new InMemoryTaskWorkRuntime")
                .doesNotContain("new InMemoryTaskResultRuntime")
                .doesNotContain("legacyTaskWorkRuntimeForUnmigratedPath")
                .doesNotContain("legacyTaskResultRuntimeForUnmigratedPath");
        assertThat(oldRuntimeTaskManagerConstructor.matcher(engineConfigSource).find())
                .as("EngineConfig must construct TaskManager without passing old task runtime truth")
                .isFalse();
    }

    @Test
    void starterBackedServingLaneDoesNotStartLegacyResultRepairPump() throws IOException {
        String engineConfigSource = Files.readString(engineConfigSource(), StandardCharsets.UTF_8);
        String taskManagerSource = Files.readString(taskManagerSource(), StandardCharsets.UTF_8);
        Pattern oldSevenArgumentTaskManagerConstructor = Pattern.compile(
                "new\\s+TaskManager\\s*\\([\\s\\S]*?getExecutionEventSink\\(\\)\\s*,\\s*(true|false)\\s*\\)");

        assertThat(engineConfigSource)
                .doesNotContain("TaskResultRepairPump")
                .doesNotContain("repairPumpEnabled")
                .doesNotContain("resultRepairPumpEnabled");
        assertThat(taskManagerSource)
                .doesNotContain("TaskResultRepairPump")
                .doesNotContain("repairPumpEnabled")
                .doesNotContain("resultRepairPumpEnabled");
        assertThat(oldSevenArgumentTaskManagerConstructor.matcher(engineConfigSource).find())
                .as("starter-backed task-runtime serving path must not pass the deleted result repair-pump flag")
                .isFalse();
    }

    @Test
    void shutdownTaskRuntimeClosesStarterHandleAndMaterializesFreshServingLane() throws IOException {
        EngineConfig config = new EngineConfig();
        var firstLane = config.getTaskAssignmentRuntimePort();

        config.shutdownTaskRuntime();

        assertThat(config.getTaskAssignmentRuntimePort())
                .isInstanceOf(TaskRuntimeServingLane.class)
                .isNotSameAs(firstLane);

        String source = Files.readString(engineConfigSource(), StandardCharsets.UTF_8);
        Pattern closesStarterHandle = Pattern.compile(
                "TaskRuntimeHandle\\s+handle\\s*=\\s*taskRuntimeHandle;[\\s\\S]*?"
                        + "taskRuntimeHandle\\s*=\\s*null;[\\s\\S]*?"
                        + "if\\s*\\(handle\\s*!=\\s*null\\)\\s*\\{\\s*handle\\.close\\(\\);\\s*\\}");
        assertThat(closesStarterHandle.matcher(source).find())
                .as("EngineConfig shutdown must close the starter-owned task runtime handle")
                .isTrue();

        config.shutdownTaskRuntime();
    }

    private static void assertAppendClaimResultPath(EngineConfig config, String sourceRef) {
        TaskCommandService commands = config.getTaskCommandService();
        TaskQueryService queries = config.getTaskQueryService();
        Task task = commands.createTaskShell(batchShell(sourceRef));
        assertThat(commands.approveTask(task.getTid())).isTrue();

        var receipt = commands.appendTaskItemsWithReceipt(task.getTid(), List.of(Map.of(
                "eventCode", "demo.event",
                "payloadRef", "payload-ref-1",
                "value", 1)));
        assertThat(receipt.added()).isEqualTo(1);
        assertThat(commands.sealTask(task.getTid())).isTrue();

        assertThat(config.getTaskAssignmentRuntimePort().countDispatchReadyWork(task.getTid())).isEqualTo(1);
        assertThat(config.getTaskRuntimeRecoveryPort().getRuntimeDispatchableTasks(10))
                .extracting(Task::getTid)
                .containsExactly(task.getTid());

        var claimed = config.getTaskAssignmentRuntimePort().claimReady(
                task.getTid(),
                List.of(new WorkerReservationEvidence(
                        "worker-1",
                        "group-1",
                        "worker-1:batch-1",
                        null,
                        "batch-1",
                        123L)),
                new ClaimLeasePolicy(1, 30_000L, 1L, RuntimeEpoch.of(task.getTid(), 1L)));
        assertThat(claimed.claimedItems()).hasSize(1);
        var claimedItem = claimed.claimedItems().getFirst();
        assertThat(claimedItem.payloadRef()).isEqualTo("payload-ref-1");
        assertThat(claimedItem.scoreBandClaimScore()).isEqualTo(123L);
        assertThat(config.getTaskAssignmentRuntimePort().countActiveDispatchWorkers(task.getTid())).isEqualTo(1);

        TaskResultIngestFacade resultIngest = config.getTaskResultIngestFacade();
        assertThat(resultIngest.getResultCorrelation(task.getTid(), claimedItem.messageId())
                .activeLeasePresent()).isTrue();
        assertThat(resultIngest.ingestTaskResult(
                task.getTid(),
                claimedItem.messageId(),
                true,
                "done",
                null,
                Map.of("ok", true))).isTrue();

        Task refreshed = queries.getTask(task.getTid());
        assertThat(refreshed.getStatus()).isEqualTo(TaskStatus.TERMINAL);
        assertThat(refreshed.getTerminalReason()).isEqualTo(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED);
        assertThat(refreshed.getTaskSuccessNumber()).isEqualTo(1);
        assertThat(config.getTaskAssignmentRuntimePort().countDispatchReadyWork(task.getTid())).isZero();
        assertThat(config.getTaskAssignmentRuntimePort().countActiveDispatchWorkers(task.getTid())).isZero();
        assertThat(config.countVisibleTaskResults(task.getTid())).isEqualTo(1);
        assertThat(config.readTaskResults(task.getTid(), 0, 10).getItems())
                .extracting(row -> row.getMessageId(), row -> row.getWorkerId())
                .containsExactly(org.assertj.core.api.Assertions.tuple(
                        claimedItem.messageId(),
                        claimedItem.workerId()));
        assertThat(config.readTaskResults(task.getTid(), 0, 10).getItems().getFirst().getAttemptId())
                .contains(claimedItem.workerId());
        var visibleResult = config.getVisibleTaskResultByMessageId(task.getTid(), claimedItem.messageId());
        assertThat(visibleResult).isPresent();
        assertThat(visibleResult.get().status()).isEqualTo("SUCCESS");
    }

    private static TaskShellCreateRequestDto batchShell(String sourceRef) {
        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSourceRef(sourceRef);
        dto.setContract(TaskContract.BATCH);
        dto.setExecutionSpec(taskExecutionSpec());
        dto.setSharedConfig(Map.of(TaskSharedConfig.WORKER_GROUP_ID, "group-1"));
        return dto;
    }

    private static TaskExecutionSpec taskExecutionSpec() {
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setDefaultMaxRetryCount(0);
        spec.setBatchSize(1);
        return spec;
    }

    private static String redisUri() {
        return System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
    }

    private static boolean redisAvailable(String redisUri) {
        var client = RedisClient.create(redisUri);
        try (var connection = client.connect()) {
            connection.sync().ping();
            return true;
        } catch (RuntimeException exception) {
            return false;
        } finally {
            client.shutdown();
        }
    }

    private static void cleanupRedisNamespace(String redisUri, String namespace) {
        var client = RedisClient.create(redisUri);
        try (var connection = client.connect()) {
            var keys = connection.sync().keys(namespace + ":*");
            if (!keys.isEmpty()) {
                connection.sync().del(keys.toArray(String[]::new));
            }
        } catch (RuntimeException ignored) {
            // Redis-backed test is skipped when unavailable; cleanup is best-effort.
        } finally {
            client.shutdown();
        }
    }

    private static Path engineConfigSource() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineConfig.java");
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate EngineConfig.java from " + System.getProperty("user.dir"));
    }

    private static Path taskManagerSource() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java");
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate TaskManager.java from " + System.getProperty("user.dir"));
    }
}
