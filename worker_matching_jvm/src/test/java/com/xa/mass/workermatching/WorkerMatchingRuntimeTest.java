package com.xa.mass.workermatching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.TaskCandidateNeed;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.TaskRuleMatchDemand;
import com.xa.mass.workermatching.WorkerMatchingCatalog.CandidateRule;
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
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class WorkerMatchingRuntimeTest {

    @Test
    void writesCandidatesInPacerTaskAndWorkerOrder() {
        FakeCatalog catalog = catalogWithWorkers("worker-a", "worker-b");
        catalog.rules.put("task-first", rule("task-first", Map.of()));
        catalog.rules.put("task-second", rule("task-second", Map.of()));
        RecordingCandidateCache cache = new RecordingCandidateCache();

        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, 4)) {
            runtime.start();
            assertTrue(runtime.offerTaskDemand(demand(
                    "group-1",
                    List.of(
                            new TaskCandidateNeed("task-first", 1),
                            new TaskCandidateNeed("task-second", 7)
                    ),
                    linkedScores("worker-a", 101L, "worker-b", 202L)
            )));

            await(() -> cache.appends.size() == 2);
            assertEquals(
                    "task-first",
                    cache.appends.get(0).candidateId()
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
                    cache.appends.get(1).candidateId()
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
        catalog.rules.put("task-full", rule("task-full", Map.of()));
        catalog.rules.put("task-next", rule("task-next", Map.of()));
        RecordingCandidateCache cache = new RecordingCandidateCache();
        cache.rejectCandidates.add("task-full");

        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, 4)) {
            runtime.start();
            runtime.offerTaskDemand(demand(
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
        RecordingCandidateCache cache = new RecordingCandidateCache();

        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, 4)) {
            runtime.start();
            runtime.offerTaskDemand(demand(
                    "group-1",
                    List.of(
                            new TaskCandidateNeed("task-missing", 1),
                            new TaskCandidateNeed("task-invalid", 1),
                            new TaskCandidateNeed("task-valid", 1)
                    ),
                    Map.of("worker-a", 101L)
            ));

            await(() -> cache.appends.size() == 1);
            assertEquals(
                    "task-valid",
                    cache.appends.get(0).candidateId()
            );
        }
    }

    @Test
    void admissionIsSingleFlightPerGroupAndQueueBounded() throws Exception {
        FakeCatalog catalog = catalogWithWorkers(
                "worker-a",
                "worker-b",
                "worker-c"
        );
        catalog.rules.put("task-a", rule("task-a", Map.of()));
        catalog.rules.put("task-b", new CandidateRule(
                "task-b",
                "group-2",
                Map.of()
        ));
        catalog.rules.put("task-c", new CandidateRule(
                "task-c",
                "group-3",
                Map.of()
        ));
        catalog.blockLoads.set(true);
        RecordingCandidateCache cache = new RecordingCandidateCache();

        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, 1)) {
            runtime.start();
            assertTrue(runtime.offerTaskDemand(singleTaskDemand(
                    "group-1", "task-a", "worker-a"
            )));
            assertTrue(catalog.loadEntered.await(1, TimeUnit.SECONDS));
            assertFalse(runtime.offerTaskDemand(singleTaskDemand(
                    "group-1", "task-a", "worker-a"
            )));
            assertTrue(runtime.offerTaskDemand(singleTaskDemand(
                    "group-2", "task-b", "worker-b"
            )));
            assertFalse(runtime.offerTaskDemand(singleTaskDemand(
                    "group-3", "task-c", "worker-c"
            )));
            catalog.releaseLoads.countDown();
            await(() -> runtime.snapshot().pendingDemands() == 0);
        }
    }

    @Test
    void processingFailureClearsGroupAdmissionForLaterDemand() {
        FakeCatalog catalog = catalogWithWorkers("worker-a");
        catalog.rules.put("task-a", rule("task-a", Map.of()));
        RecordingCandidateCache cache = new RecordingCandidateCache();
        cache.failNext.set(true);

        try (WorkerMatchingRuntime runtime = runtime(catalog, cache, 2)) {
            runtime.start();
            assertTrue(runtime.offerTaskDemand(singleTaskDemand(
                    "group-1", "task-a", "worker-a"
            )));
            await(() -> runtime.snapshot().pendingDemands() == 0);
            assertTrue(runtime.offerTaskDemand(singleTaskDemand(
                    "group-1", "task-a", "worker-a"
            )));
            await(() -> cache.appends.size() == 2);
        }
    }

    @Test
    void lifecycleAndExpiredDemandAreExplicit() {
        FakeCatalog catalog = catalogWithWorkers("worker-a");
        RecordingCandidateCache cache = new RecordingCandidateCache();
        WorkerMatchingRuntime runtime = runtime(catalog, cache, 1);

        assertThrows(
                IllegalStateException.class,
                () -> runtime.offerTaskDemand(singleTaskDemand(
                        "group-1", "task-a", "worker-a"
                ))
        );
        runtime.start();
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.offerTaskDemand(new TaskRuleMatchDemand(
                        "group-1",
                        List.of(new TaskCandidateNeed("task-a", 1)),
                        Map.of("worker-a", 101L),
                        1L
                ))
        );
        runtime.stop(2_000);
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
            int capacity
    ) {
        return new WorkerMatchingRuntime(catalog, cache, capacity);
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

    private static CandidateRule rule(
            String candidateId,
            Map<String, Object> allocationRule
    ) {
        return new CandidateRule(
                candidateId,
                "group-1",
                allocationRule
        );
    }

    private static TaskRuleMatchDemand singleTaskDemand(
            String workerGroupId,
            String candidateId,
            String workerId
    ) {
        return demand(
                workerGroupId,
                List.of(new TaskCandidateNeed(candidateId, 1)),
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
            String candidateId,
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
                String candidateId,
                int maximumCandidateWorkers,
                List<CandidateWorkerEntry> candidates,
                long expiresAtMillis
        ) {
            appends.add(new Append(
                    candidateId,
                    maximumCandidateWorkers,
                    List.copyOf(candidates)
            ));
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("offline");
            }
            return rejectCandidates.contains(candidateId)
                    ? List.of()
                    : candidates.stream()
                            .limit(maximumCandidateWorkers)
                            .map(CandidateWorkerEntry::workerId)
                            .toList();
        }

        @Override
        public Map<String, Integer> candidateWorkerCounts(
                List<String> candidateIds
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CandidateWorkerEntry> consumeCandidateWorkers(
                String candidateId,
                int limit
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeCatalog implements WorkerMatchingCatalog {
        private final Map<String, CandidateRule> rules =
                new LinkedHashMap<>();
        private final Map<String, WorkerFacts> facts = new LinkedHashMap<>();
        private final AtomicBoolean blockLoads = new AtomicBoolean();
        private final CountDownLatch loadEntered = new CountDownLatch(1);
        private final CountDownLatch releaseLoads = new CountDownLatch(1);

        @Override
        public MutationResult upsertWorkerFacts(
                String workerId,
                String workerGroupId,
                Map<String, Object> workerProperties
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
        public MutationResult createCandidateRule(
                String candidateId,
                String workerGroupId,
                Map<String, Object> allocationRule
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, CandidateRule> loadCandidateRules(
                List<String> candidateIds
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
            LinkedHashMap<String, CandidateRule> result =
                    new LinkedHashMap<>();
            candidateIds.forEach(candidateId -> result.put(
                    candidateId,
                    rules.get(candidateId)
            ));
            return result;
        }
    }
}
