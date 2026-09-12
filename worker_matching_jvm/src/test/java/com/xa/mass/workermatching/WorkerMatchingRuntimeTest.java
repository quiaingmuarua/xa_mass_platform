package com.xa.mass.workermatching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.assignment.InMemoryWorkerMatchQueue;
import com.xa.mass.kernel.assignment.TaskRuleMatchDemand;
import com.xa.mass.kernel.assignment.TaskRuleMatchDemand.TaskCandidateNeed;
import com.xa.mass.kernel.assignment.WorkerMatchQueue;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MatchingRule;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationResult;
import com.xa.mass.workermatching.WorkerMatchingCatalog.WorkerFacts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class WorkerMatchingRuntimeTest {

    @Test
    void eachNewDemandReadsCurrentFactsWithoutRevokingEarlierCandidates() {
        FakeCatalog catalog = catalogWithWorkers("worker-a");
        catalog.facts.put("worker-a", new WorkerFacts("worker-a", "group-1",
                Map.of("network.type", "wifi", "ssid", "lab"), Map.of()));
        catalog.rules.put("wifi-old", rule("wifi-old", Map.of("worker.network.type", Map.of("$eq", "wifi"))));
        catalog.rules.put("cellular-new", rule("cellular-new", Map.of("worker.network.type", Map.of("$eq", "cellular"))));
        catalog.rules.put("deleted-new", rule("deleted-new", Map.of("worker.ssid", Map.of("$exists", false))));
        RecordingCandidateCache cache = new RecordingCandidateCache();
        WorkerMatchQueue queue = queue(4);
        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, queue)) {
            runtime.start();
            assertTrue(queue.offer(singleTaskDemand("group-1", "wifi-old", "worker-a")));
            await(() -> cache.appends.size() == 1);
            catalog.facts.put("worker-a", new WorkerFacts("worker-a", "group-1",
                    Map.of("network.type", "cellular", "ssid", "lab"), Map.of()));
            assertTrue(queue.offer(singleTaskDemand("group-1", "cellular-new", "worker-a")));
            await(() -> cache.appends.size() == 2);
            catalog.facts.put("worker-a", new WorkerFacts("worker-a", "group-1", Map.of(), Map.of()));
            assertTrue(queue.offer(singleTaskDemand("group-1", "deleted-new", "worker-a")));
            await(() -> cache.appends.size() == 3);
            assertEquals(List.of("wifi-old", "cellular-new", "deleted-new"),
                    cache.appends.stream().map(Append::taskId).toList());
        }
    }

    @Test
    void unrestrictedRuleSkipsMissingFactsButAcceptsAnObservedEmptyMap() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.facts.put("observed", new WorkerFacts("observed", "group-1", Map.of(), Map.of()));
        catalog.rules.put("unrestricted", rule("unrestricted", Map.of()));
        RecordingCandidateCache cache = new RecordingCandidateCache();
        WorkerMatchQueue queue = queue(1);
        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, queue)) {
            runtime.start();
            assertTrue(queue.offer(demand("group-1", List.of(new TaskCandidateNeed("unrestricted", 2)),
                    linkedScores("missing", 101L, "observed", 202L))));
            await(() -> cache.appends.size() == 1);
            assertEquals(List.of(new CandidateWorkerEntry("observed", 202L)), cache.appends.getFirst().candidates());
        }
    }

    @Test
    void sharedRulePreservesIndependentTaskLimitsAndDoesNotReuseAcceptedHolds() {
        FakeCatalog catalog = catalogWithWorkers("worker-a", "worker-b");
        AtomicInteger normalizations = new AtomicInteger();
        Map<String, Object> operators = new LinkedHashMap<>() {
            @Override public void forEach(BiConsumer<? super String, ? super Object> action) {
                normalizations.incrementAndGet();
                super.forEach(action);
            }
        };
        operators.put("$exists", true);
        MatchingRule shared = rule("rule-shared", Map.of("worker.region", operators));
        catalog.rules.put("task-first", shared);
        catalog.rules.put("task-second", shared);
        RecordingCandidateCache cache = new RecordingCandidateCache();
        WorkerMatchQueue queue = queue(4);

        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, queue)) {
            runtime.start();
            assertTrue(queue.offer(demand(
                    "group-1",
                    List.of(
                            new TaskCandidateNeed("task-first", 1),
                            new TaskCandidateNeed("task-second", 7)
                    ),
                    linkedScores("worker-a", 101L, "worker-b", 202L)
            )));

            await(() -> cache.appends.size() == 2);
            assertEquals(1, normalizations.get());
            assertEquals(
                    "task-first",
                    cache.appends.get(0).taskId()
            );
            assertEquals(1, cache.appends.get(0).maximum());
            assertEquals(
                    List.of(
                            new CandidateWorkerEntry("worker-a", 101L),
                            new CandidateWorkerEntry("worker-b", 202L)
                    ),
                    cache.appends.get(0).candidates()
            );
            assertEquals(
                    "task-second",
                    cache.appends.get(1).taskId()
            );
            assertEquals(7, cache.appends.get(1).maximum());
            assertEquals(
                    List.of(new CandidateWorkerEntry("worker-b", 202L)),
                    cache.appends.get(1).candidates()
            );
        }
    }

    @Test
    void cacheRejectionLeavesWorkerAvailableForLaterTask() {
        FakeCatalog catalog = catalogWithWorkers("worker-a");
        MatchingRule shared = rule("rule-shared", Map.of());
        catalog.rules.put("task-full", shared);
        catalog.rules.put("task-next", shared);
        RecordingCandidateCache cache = new RecordingCandidateCache();
        cache.rejectCandidates.add("task-full");
        WorkerMatchQueue queue = queue(4);

        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, queue)) {
            runtime.start();
            queue.offer(demand(
                    "group-1",
                    List.of(
                            new TaskCandidateNeed("task-full", 1),
                            new TaskCandidateNeed("task-next", 1)
                    ),
                    Map.of("worker-a", 101L)
            ));

            await(() -> cache.appends.size() == 2);
            assertEquals(
                    List.of(new CandidateWorkerEntry("worker-a", 101L)),
                    cache.appends.get(1).candidates()
            );
        }
    }

    @Test
    void missingOrInvalidRuleDoesNotConsumeWorkers() {
        FakeCatalog catalog = catalogWithWorkers("worker-a");
        catalog.rules.put(
                "task-invalid",
                rule(
                        "task-invalid",
                        Map.of("worker.region", Map.of("$bad", 1))
                )
        );
        catalog.rules.put("task-valid", rule("task-valid", Map.of()));
        catalog.rules.put("task-wrong-group", new MatchingRule("rule-other-group", "other", Map.of()));
        RecordingCandidateCache cache = new RecordingCandidateCache();
        WorkerMatchQueue queue = queue(4);

        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, queue)) {
            runtime.start();
            queue.offer(demand(
                    "group-1",
                    List.of(
                            new TaskCandidateNeed("task-missing", 1),
                            new TaskCandidateNeed("task-invalid", 1),
                            new TaskCandidateNeed("task-wrong-group", 1),
                            new TaskCandidateNeed("task-valid", 1)
                    ),
                    Map.of("worker-a", 101L)
            ));

            await(() -> cache.appends.size() == 1);
            assertEquals(
                    "task-valid",
                    cache.appends.get(0).taskId()
            );
        }
    }

    @Test
    void queueCapacityRemainsIndependentOfDemandProcessing() throws Exception {
        FakeCatalog catalog = catalogWithWorkers(
                "worker-a",
                "worker-b",
                "worker-c"
        );
        catalog.rules.put("task-a", rule("task-a", Map.of()));
        catalog.rules.put("task-b", new MatchingRule(
                "task-b",
                "group-2",
                Map.of()
        ));
        catalog.rules.put("task-c", new MatchingRule(
                "task-c",
                "group-3",
                Map.of()
        ));
        catalog.blockLoads.set(true);
        RecordingCandidateCache cache = new RecordingCandidateCache();
        WorkerMatchQueue queue = queue(1);

        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, queue)) {
            runtime.start();
            assertTrue(queue.offer(singleTaskDemand(
                    "group-1", "task-a", "worker-a"
            )));
            assertTrue(catalog.loadEntered.await(1, TimeUnit.SECONDS));
            assertEquals(0, queue.size());
            assertTrue(queue.offer(singleTaskDemand(
                    "group-2", "task-b", "worker-b"
            )));
            assertFalse(queue.offer(singleTaskDemand(
                    "group-3", "task-c", "worker-c"
            )));
            catalog.releaseLoads.countDown();
            await(() -> queue.size() == 0);
        }
    }

    @Test
    void processingFailureClearsGroupAdmissionForLaterDemand() {
        FakeCatalog catalog = catalogWithWorkers("worker-a");
        catalog.rules.put("task-a", rule("task-a", Map.of()));
        RecordingCandidateCache cache = new RecordingCandidateCache();
        cache.failNext.set(true);
        WorkerMatchQueue queue = queue(2);

        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, queue)) {
            runtime.start();
            assertTrue(queue.offer(singleTaskDemand(
                    "group-1", "task-a", "worker-a"
            )));
            await(() -> cache.appends.size() == 1);
            assertTrue(queue.offer(singleTaskDemand(
                    "group-1", "task-a", "worker-a"
            )));
            await(() -> cache.appends.size() == 2);
        }
    }

    @Test
    void lifecycleAndExpiredDemandAreExplicit() {
        FakeCatalog catalog = catalogWithWorkers("worker-a");
        RecordingCandidateCache cache = new RecordingCandidateCache();
        WorkerMatchQueue queue = queue(1);
        WorkerMatchingRuntime runtime = runtime(catalog, cache, queue);

        assertTrue(queue.offer(new TaskRuleMatchDemand(
                "group-1",
                List.of(new TaskCandidateNeed("task-a", 1)),
                Map.of("worker-a", 101L),
                1L
        )));
        runtime.start();
        await(() -> queue.size() == 0);
        runtime.stop(2_000);
        assertTrue(cache.appends.isEmpty());
        assertEquals(
                WorkerMatchingRuntime.State.STOPPED,
                runtime.snapshot().state()
        );
        runtime.close();
        assertEquals(
                WorkerMatchingRuntime.State.CLOSED,
                runtime.snapshot().state()
        );
        assertThrows(IllegalStateException.class, runtime::start);
    }

    private static WorkerMatchingRuntime runtime(
            FakeCatalog catalog,
            RecordingCandidateCache cache,
            WorkerMatchQueue queue
    ) {
        return new WorkerMatchingRuntime(catalog, cache, queue);
    }

    private static WorkerMatchQueue queue(
            int capacity
    ) {
        return new InMemoryWorkerMatchQueue(capacity);
    }

    private static FakeCatalog catalogWithWorkers(String... workerIds) {
        FakeCatalog catalog = new FakeCatalog();
        for (String workerId : workerIds) {
            catalog.facts.put(workerId, new WorkerFacts(
                    workerId,
                    "group-1",
                    Map.of("region", "local"),
                    Map.of()
            ));
        }
        return catalog;
    }

    private static MatchingRule rule(
            String ruleId,
            Map<String, Object> allocationRule
    ) {
        return new MatchingRule(
                ruleId,
                "group-1",
                allocationRule
        );
    }

    private static TaskRuleMatchDemand singleTaskDemand(
            String workerGroupId,
            String taskId,
            String workerId
    ) {
        return demand(
                workerGroupId,
                List.of(new TaskCandidateNeed(taskId, 1)),
                Map.of(workerId, 101L)
        );
    }

    private static TaskRuleMatchDemand demand(
            String workerGroupId,
            List<TaskCandidateNeed> needs,
            Map<String, Long> scores
    ) {
        return new TaskRuleMatchDemand(
                workerGroupId,
                needs,
                scores,
                System.currentTimeMillis() + 10_000
        );
    }

    private static Map<String, Long> linkedScores(
            String firstId,
            long firstScore,
            String secondId,
            long secondScore
    ) {
        LinkedHashMap<String, Long> scores = new LinkedHashMap<>();
        scores.put(firstId, firstScore);
        scores.put(secondId, secondScore);
        return scores;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not satisfied");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    private record Append(
            String taskId,
            int maximum,
            List<CandidateWorkerEntry> candidates
    ) {
    }

    private static final class RecordingCandidateCache
            implements CandidateWorkerCache {
        private final List<Append> appends = Collections.synchronizedList(
                new ArrayList<>()
        );
        private final Set<String> rejectCandidates =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final AtomicBoolean failNext = new AtomicBoolean();

        @Override
        public List<String> appendCandidateWorkers(
                String taskId,
                int maximumCandidateWorkers,
                List<CandidateWorkerEntry> candidates,
                long expiresAtMillis
        ) {
            appends.add(new Append(
                    taskId,
                    maximumCandidateWorkers,
                    List.copyOf(candidates)
            ));
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("offline");
            }
            return rejectCandidates.contains(taskId)
                    ? List.of()
                    : candidates.stream()
                            .limit(maximumCandidateWorkers)
                            .map(CandidateWorkerEntry::workerId)
                            .toList();
        }

        @Override
        public Map<String, Integer> candidateWorkerCounts(
                List<String> taskIds
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CandidateWorkerEntry> consumeCandidateWorkers(
                String taskId,
                int limit
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeCatalog implements WorkerMatchingCatalog {
        @Override public TaskQuery prepareTaskQuery(String taskId, String group) {
            throw new UnsupportedOperationException("DSL fixture has no named Rule");
        }
        @Override public MutationResult bindTaskRule(String taskId, String group, String ruleId) {
            throw new UnsupportedOperationException("DSL fixture has no named Rule");
        }
        @Override public void validateWorkerSelector(String group, com.xa.mass.kernel.task.TaskItemWorkerSelector query) {
            throw new UnsupportedOperationException("PRECOMPUTED fixture has no index");
        }
        @Override public List<String> takeWorkerIds(String group, com.xa.mass.kernel.task.TaskItemWorkerSelector query, int limit) {
            throw new UnsupportedOperationException("PRECOMPUTED fixture has no index");
        }
        @Override public java.util.Set<String> retainWorkerIds(String group, com.xa.mass.kernel.task.TaskItemWorkerSelector query, List<String> ids) {
            throw new UnsupportedOperationException("PRECOMPUTED fixture has no index");
        }
        private final Map<String, MatchingRule> rules =
                new LinkedHashMap<>();
        private final Map<String, WorkerFacts> facts = new LinkedHashMap<>();
        private final AtomicBoolean blockLoads = new AtomicBoolean();
        private final CountDownLatch loadEntered = new CountDownLatch(1);
        private final CountDownLatch releaseLoads = new CountDownLatch(1);

        @Override
        public Map<String, MutationResult> upsertWorkerFactsBatch(
                String workerGroupId,
                Map<String, Map<String, String>> propertiesByWorkerId
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MutationResult patchWorkerPlatformProperties(
                String workerGroupId,
                String workerId,
                Map<String, Object> properties
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, WorkerFacts> loadWorkerFacts(
                String workerGroupId,
                List<String> workerIds
        ) {
            LinkedHashMap<String, WorkerFacts> result = new LinkedHashMap<>();
            workerIds.forEach(workerId ->
                    result.put(workerId, facts.get(workerId)));
            return result;
        }

        @Override
        public MutationResult bindTaskAllocationRule(
                String taskId,
                String workerGroupId,
                Map<String, Object> allocationRule
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, MatchingRule> loadTaskRules(
                List<String> taskIds
        ) {
            if (blockLoads.get()) {
                loadEntered.countDown();
                try {
                    releaseLoads.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            LinkedHashMap<String, MatchingRule> result =
                    new LinkedHashMap<>();
            taskIds.forEach(taskId -> result.put(
                    taskId,
                    rules.get(taskId)
            ));
            return result;
        }
    }
}
