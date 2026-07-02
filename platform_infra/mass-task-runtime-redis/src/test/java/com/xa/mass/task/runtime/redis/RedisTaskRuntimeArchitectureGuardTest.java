package com.xa.mass.task.runtime.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisTaskRuntimeArchitectureGuardTest {

    private static final List<String> FORBIDDEN_SNIPPETS = List.of(
            "com.xa.mass.runtime.",
            "com.xa.mass.engine",
            "com.xa.mass.transport",
            "org.springframework"
    );

    private static final List<String> FORBIDDEN_OLD_KEY_SNIPPETS = List.of(
            ":tasks",
            ":dirty",
            ":ids",
            ":ready",
            ":delayed",
            ":active",
            ":eligibility",
            ":final:order",
            ":final:seq",
            ":worker:"
    );

    private static final List<String> FORBIDDEN_OLD_API_SNIPPETS = List.of(
            "TaskRuntimeAppendPort",
            "TaskRuntimeSchedulerPort",
            "TaskRuntimeClaimPort",
            "TaskRuntimeResultPort",
            "TaskRuntimeRepairPort",
            "TaskRuntimeProgressPort",
            "TaskRuntimeDiscardPort",
            "AppendBatchCommand",
            "SchedulerDiscoveryCommand",
            "ClaimReadyCommand",
            "ResultApplyCommand",
            "PollActiveLeaseRepairCommand",
            "ActiveWorkQuery",
            "DiscardTaskRuntimeCommand",
            "DiscardTaskWorkCommand",
            "appendBatch(",
            "discoverEligibleTasks(",
            "markTaskDirty(",
            "claimReady(",
            "getResultCorrelation(",
            "pollExpiredActiveLeases(",
            "getActiveWorkForWorker(",
            "discardTaskRuntime(",
            "discardTaskWork("
    );

    private static final List<Path> SCORE_BAND_SOURCES = List.of(
            Path.of("src/main/java/com/xa/mass/task/runtime/redis/TaskRuntimeRedisKeyspaceV1.java"),
            Path.of("src/main/java/com/xa/mass/task/runtime/redis/TaskRuntimeRedisKeyspaceProofHarness.java"),
            Path.of("src/main/java/com/xa/mass/task/runtime/redis/RedisTaskRuntime.java"),
            Path.of("src/main/java/com/xa/mass/task/runtime/redis/RedisScoreBandTaskRuntime.java"),
            Path.of("src/main/java/com/xa/mass/task/runtime/redis/RedisScoreBandTaskRuntimeScripts.java")
    );

    @Test
    void redisAdapterDoesNotImportOldRuntimeOrOuterOwners() throws IOException {
        var violations = new ArrayList<String>();
        try (var files = Files.walk(Path.of("src", "main", "java"))) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectViolations(path, violations));
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void scoreBandKeyspaceBuilderOnlyExposesApprovedV0Keys() {
        var keyspace = new TaskRuntimeRedisKeyspaceV1("tr", new TaskRuntimeRedisKeyCodecV1());

        assertThat(List.of(
                keyspace.lanesKey(),
                keyspace.taskScoreKey("lane-a"),
                keyspace.taskMetaKey("task-a"),
                keyspace.taskBacklogKey("task-a"),
                keyspace.taskRetryScoreKey("task-a"),
                keyspace.taskRetryItemKey("task-a"),
                keyspace.taskRuntimeStateKey("task-a"),
                keyspace.taskResultKey("task-a")))
                .containsExactly(
                        "tr:lanes",
                        "tr:task:score:lane-a",
                        "tr:task:task-a:meta",
                        "tr:task:task-a:backlog",
                        "tr:task:task-a:retry:score",
                        "tr:task:task-a:retry:item",
                        "tr:task:task-a:rt",
                        "tr:task:task-a:result");
    }

    @Test
    void scoreBandImplementationDoesNotReferenceForbiddenOldRedisKeys() throws IOException {
        var violations = new ArrayList<String>();
        for (Path path : SCORE_BAND_SOURCES) {
            String source = Files.readString(path);
            for (String forbidden : FORBIDDEN_OLD_KEY_SNIPPETS) {
                if (source.contains(forbidden)) {
                    violations.add(path + " contains old Redis key snippet " + forbidden);
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void scoreBandImplementationDoesNotReferenceOldRuntimeApiVocabulary() throws IOException {
        var violations = new ArrayList<String>();
        for (Path path : SCORE_BAND_SOURCES) {
            String source = Files.readString(path);
            for (String forbidden : FORBIDDEN_OLD_API_SNIPPETS) {
                if (source.contains(forbidden)) {
                    violations.add(path + " contains old runtime API snippet " + forbidden);
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void redisTaskRuntimeExposesOnlyScoreBandGroupedServingPorts() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/xa/mass/task/runtime/redis/RedisTaskRuntime.java"));

        assertThat(source)
                .contains("return delegate.appendBacklog(taskId, frames, maxBatchSize);")
                .contains("return delegate.claimBacklog(candidate, reservations, maxItems, leaseMillis, nowMillis);")
                .contains("delegate.putRuntimeMeta(meta);")
                .contains("delegate.setTaskScore(taskId, laneKey, epoch, score);")
                .contains("delegate.removeTaskScore(taskId, laneKey, epoch);")
                .contains("return delegate.discoverSchedulable(laneKey, maxScore, limit);")
                .contains("return delegate.promoteDueRetries(laneKey, nowMillis, taskLimit, itemLimit);")
                .contains("return delegate.scanExpiredLeases(laneKey, nowMillis, taskLimit, itemLimit);")
                .contains("return delegate.applyResult(fact);")
                .contains("return delegate.closeIfDrained(taskId, laneKey, epoch);")
                .contains("return delegate.discardRuntime(taskId, laneKey, epoch, reason);")
                .contains("return delegate.discardWork(taskId, epoch, reason);")
                .contains("return delegate.resultCorrelation(taskId, messageId);")
                .contains("return delegate.activeWorkForTask(taskId, limit);")
                .contains("return delegate.readFinalResults(request);")
                .contains("return delegate.getFinalResultByMessageId(taskId, messageId);")
                .contains("return delegate.progressSnapshot(taskId);")
                .doesNotContain("TaskRuntimeAppendPort")
                .doesNotContain("TaskRuntimeSchedulerPort")
                .doesNotContain("TaskRuntimeClaimPort")
                .doesNotContain("TaskRuntimeResultPort")
                .doesNotContain("TaskRuntimeRepairPort")
                .doesNotContain("TaskRuntimeProgressPort")
                .doesNotContain("TaskRuntimeDiscardPort")
                .doesNotContain("appendBatch(")
                .doesNotContain("discoverEligibleTasks(")
                .doesNotContain("markTaskDirty(")
                .doesNotContain("claimReady(")
                .doesNotContain("applyResult(ResultApplyCommand")
                .doesNotContain("getResultCorrelation(")
                .doesNotContain("pollExpiredActiveLeases(")
                .doesNotContain("getActiveWorkForWorker(")
                .doesNotContain("discardTaskRuntime(")
                .doesNotContain("discardTaskWork(");
    }

    private static void collectViolations(Path path, List<String> violations) {
        try {
            var source = Files.readString(path);
            for (String forbidden : FORBIDDEN_SNIPPETS) {
                if (source.contains(forbidden)) {
                    violations.add(path + " contains " + forbidden);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
