package com.xa.mass.workermatching;

import static com.xa.mass.kernel.assignment.WorkerMatchRuntime.DemandOfferStatus.CAPACITY;
import static com.xa.mass.kernel.assignment.WorkerMatchRuntime.DemandOfferStatus.OFFERED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemMatchKey;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemRuleMatchDemand;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemRuleMatchEvidence;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.TaskRuleMatchDemand;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.TaskRuleMatchEvidence;
import com.xa.mass.workermatching.WorkerMatchingCatalog.ItemRule;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationResult;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationStatus;
import com.xa.mass.workermatching.WorkerMatchingCatalog.TaskRule;
import com.xa.mass.workermatching.WorkerMatchingCatalog.WorkerFacts;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class WorkerMatchingRuntimeTest {

    @Test
    void taskDemandFiltersOnlySuppliedScoreEligibleWorkers() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        catalog.taskRules.put(
                "task-1",
                new TaskRule(
                        "task-1",
                        "group-1",
                        Map.of("worker.region", Map.of("$eq", "cn"))
                )
        );
        catalog.addWorker("worker-1", "cn");
        catalog.addWorker("worker-2", "us");
        catalog.addWorker("worker-3", "cn");

        try (WorkerMatchingRuntime runtime = runtime(catalog, 8, 8)) {
            runtime.start();
            TaskRuleMatchDemand demand = taskDemand(
                    "task-1",
                    List.of("worker-2", "worker-3", "worker-1")
            );

            assertEquals(
                    OFFERED,
                    runtime.offerTaskDemands(List.of(demand)).get("task-1")
            );
            TaskRuleMatchEvidence evidence = awaitTaskEvidence(
                    runtime,
                    "task-1"
            );

            assertEquals(List.of("worker-3", "worker-1"),
                    evidence.matchedWorkerIds());
            assertTrue(runtime.takeTaskEvidence(List.of("task-1")).isEmpty());
        }
    }

    @Test
    void itemDemandFiltersOnlySuppliedHeldWorkersAndReturnsAllMatches()
            throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        ItemMatchKey key = new ItemMatchKey("task-1", "message-1");
        catalog.itemRules.put(
                key,
                new ItemRule(
                        key,
                        "group-1",
                        Map.of("worker.slot", Map.of("$eq", "target"))
                )
        );
        catalog.workers.put("worker-1", facts("worker-1", "other"));
        catalog.workers.put("worker-2", facts("worker-2", "target"));
        catalog.workers.put("worker-3", facts("worker-3", "target"));

        try (WorkerMatchingRuntime runtime = runtime(catalog, 8, 8)) {
            runtime.start();
            ItemRuleMatchDemand demand = itemDemand(
                    key,
                    List.of("worker-1", "worker-3", "worker-2")
            );

            assertEquals(OFFERED,
                    runtime.offerItemDemands(List.of(demand)).get(key));
            assertEquals(
                    List.of("worker-3", "worker-2"),
                    awaitItemEvidence(runtime, key).matchedWorkerIds()
            );
        }
    }

    @Test
    void demandsSharingAHeldPoolLoadFactsOnce() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        ItemMatchKey first = new ItemMatchKey("task-1", "message-1");
        ItemMatchKey second = new ItemMatchKey("task-1", "message-2");
        catalog.itemRules.put(first, new ItemRule(first, "group-1", Map.of()));
        catalog.itemRules.put(second, new ItemRule(second, "group-1", Map.of()));
        catalog.workers.put("worker-1", facts("worker-1", "target"));
        catalog.workers.put("worker-2", facts("worker-2", "target"));
        List<String> held = List.of("worker-1", "worker-2");

        try (WorkerMatchingRuntime runtime = runtime(catalog, 8, 8)) {
            runtime.start();
            runtime.offerItemDemands(List.of(
                    itemDemand(first, held),
                    itemDemand(second, held)
            ));

            assertEquals(held, awaitItemEvidence(runtime, first)
                    .matchedWorkerIds());
            assertEquals(held, awaitItemEvidence(runtime, second)
                    .matchedWorkerIds());
            assertEquals(1, catalog.workerLoadCalls.get());
        }
    }

    @Test
    void demandQueueAndEvidenceCapacityRemainIndependentAndBounded()
            throws Exception {
        FakeCatalog blocked = new FakeCatalog();
        blocked.taskRules.put("task-1", emptyRule("task-1"));
        blocked.taskRules.put("task-2", emptyRule("task-2"));
        blocked.taskRules.put("task-3", emptyRule("task-3"));
        blocked.addWorker("worker-1", "cn");
        blocked.blockTaskLoads();

        try (WorkerMatchingRuntime runtime = runtime(blocked, 1, 1)) {
            runtime.start();
            assertEquals(OFFERED, runtime.offerTaskDemands(List.of(
                    taskDemand("task-1", List.of("worker-1"))
            )).get("task-1"));
            assertTrue(blocked.taskLoadEntered.await(2, TimeUnit.SECONDS));

            assertEquals(OFFERED, runtime.offerTaskDemands(List.of(
                    taskDemand("task-2", List.of("worker-1"))
            )).get("task-2"));
            assertEquals(CAPACITY, runtime.offerTaskDemands(List.of(
                    taskDemand("task-3", List.of("worker-1"))
            )).get("task-3"));
            blocked.releaseTaskLoads.countDown();

            awaitTaskEvidence(runtime, "task-1");
            awaitCondition(() -> runtime.snapshot().pendingDemands() == 0);
            assertTrue(runtime.takeTaskEvidence(List.of("task-2")).isEmpty());

            assertEquals(OFFERED, runtime.offerTaskDemands(List.of(
                    taskDemand("task-2", List.of("worker-1"))
            )).get("task-2"));
            awaitTaskEvidence(runtime, "task-2");
        }
    }

    @Test
    void catalogFailureReleasesPendingForALaterDemand() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        catalog.taskRules.put("task-1", emptyRule("task-1"));
        catalog.addWorker("worker-1", "cn");
        catalog.remainingTaskLoadFailures.set(1);

        try (WorkerMatchingRuntime runtime = runtime(catalog, 8, 8)) {
            runtime.start();
            TaskRuleMatchDemand demand = taskDemand(
                    "task-1",
                    List.of("worker-1")
            );
            assertEquals(OFFERED,
                    runtime.offerTaskDemands(List.of(demand)).get("task-1"));
            awaitCondition(() -> catalog.taskLoadCalls.get() == 1
                    && runtime.snapshot().pendingDemands() == 0);
            assertTrue(runtime.takeTaskEvidence(List.of("task-1")).isEmpty());

            assertEquals(OFFERED,
                    runtime.offerTaskDemands(List.of(demand)).get("task-1"));
            assertEquals(
                    List.of("worker-1"),
                    awaitTaskEvidence(runtime, "task-1").matchedWorkerIds()
            );
            assertTrue(runtime.isRunning());
        }
    }

    @Test
    void expiredEvidenceIsDiscardedAndUnexpectedErrorFailsRuntime()
            throws Exception {
        FakeCatalog expiringCatalog = new FakeCatalog();
        expiringCatalog.taskRules.put("task-1", emptyRule("task-1"));
        expiringCatalog.addWorker("worker-1", "cn");
        try (WorkerMatchingRuntime runtime = new WorkerMatchingRuntime(
                expiringCatalog,
                8,
                8
        )) {
            runtime.start();
            runtime.offerTaskDemands(List.of(new TaskRuleMatchDemand(
                    "task-1",
                    "group-1",
                    List.of("worker-1"),
                    System.currentTimeMillis() + 20
            )));
            awaitCondition(() -> runtime.snapshot().availableEvidence() == 1);
            Thread.sleep(50);
            assertTrue(runtime.takeTaskEvidence(List.of("task-1")).isEmpty());
        }

        FakeCatalog failedCatalog = new FakeCatalog();
        failedCatalog.taskLoadError = new AssertionError("unexpected");
        try (WorkerMatchingRuntime runtime = runtime(
                failedCatalog,
                8,
                8
        )) {
            runtime.start();
            runtime.offerTaskDemands(List.of(taskDemand(
                    "task-failed",
                    List.of("worker-1")
            )));
            awaitCondition(() -> runtime.snapshot().state()
                    == WorkerMatchingRuntime.State.FAILED);
            assertFalse(runtime.isRunning());
            assertThrows(IllegalStateException.class, () ->
                    runtime.offerTaskDemands(List.of(taskDemand(
                            "task-later",
                            List.of("worker-1")
                    ))));
        }
    }

    @Test
    void stopClearsTransientStateAndAllowsAnExplicitRestart()
            throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        catalog.taskRules.put("task-1", emptyRule("task-1"));
        catalog.addWorker("worker-1", "cn");
        WorkerMatchingRuntime runtime = runtime(catalog, 8, 8);
        try {
            runtime.start();
            runtime.offerTaskDemands(List.of(taskDemand(
                    "task-1",
                    List.of("worker-1")
            )));
            awaitCondition(() -> runtime.snapshot().availableEvidence() == 1);

            runtime.stop(2_000);
            assertEquals(
                    new WorkerMatchingRuntime.Snapshot(
                            WorkerMatchingRuntime.State.STOPPED,
                            0,
                            0,
                            0
                    ),
                    runtime.snapshot()
            );

            runtime.start();
            runtime.offerTaskDemands(List.of(taskDemand(
                    "task-1",
                    List.of("worker-1")
            )));
            assertEquals(
                    List.of("worker-1"),
                    awaitTaskEvidence(runtime, "task-1").matchedWorkerIds()
            );

            runtime.close();
            assertEquals(
                    WorkerMatchingRuntime.State.CLOSED,
                    runtime.snapshot().state()
            );
            assertThrows(IllegalStateException.class, runtime::start);
        } finally {
            runtime.close();
        }
    }

    private static WorkerMatchingRuntime runtime(
            WorkerMatchingCatalog catalog,
            int demandCapacity,
            int evidenceCapacity
    ) {
        return new WorkerMatchingRuntime(
                catalog,
                demandCapacity,
                evidenceCapacity
        );
    }

    private static TaskRule emptyRule(String taskId) {
        return new TaskRule(taskId, "group-1", Map.of());
    }

    private static TaskRuleMatchDemand taskDemand(
            String taskId,
            List<String> workers
    ) {
        return new TaskRuleMatchDemand(
                taskId,
                "group-1",
                workers,
                System.currentTimeMillis() + 5_000
        );
    }

    private static ItemRuleMatchDemand itemDemand(
            ItemMatchKey key,
            List<String> workers
    ) {
        return new ItemRuleMatchDemand(
                key,
                "group-1",
                workers,
                System.currentTimeMillis() + 5_000
        );
    }

    private static WorkerFacts facts(String workerId, String slot) {
        return new WorkerFacts(
                workerId,
                "group-1",
                Map.of("slot", slot),
                Map.of()
        );
    }

    private static TaskRuleMatchEvidence awaitTaskEvidence(
            WorkerMatchingRuntime runtime,
            String taskId
    ) throws Exception {
        final TaskRuleMatchEvidence[] evidence = new TaskRuleMatchEvidence[1];
        awaitCondition(() -> {
            evidence[0] = runtime.takeTaskEvidence(List.of(taskId)).get(taskId);
            return evidence[0] != null;
        });
        return evidence[0];
    }

    private static ItemRuleMatchEvidence awaitItemEvidence(
            WorkerMatchingRuntime runtime,
            ItemMatchKey key
    ) throws Exception {
        final ItemRuleMatchEvidence[] evidence =
                new ItemRuleMatchEvidence[1];
        awaitCondition(() -> {
            evidence[0] = runtime.takeItemEvidence(List.of(key)).get(key);
            return evidence[0] != null;
        });
        return evidence[0];
    }

    private static void awaitCondition(CheckedCondition condition)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.test()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition did not converge");
            }
            Thread.sleep(5);
        }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean test() throws Exception;
    }

    private static final class FakeCatalog implements WorkerMatchingCatalog {

        private final Map<String, WorkerFacts> workers =
                new ConcurrentHashMap<>();
        private final Map<String, TaskRule> taskRules =
                new ConcurrentHashMap<>();
        private final Map<ItemMatchKey, ItemRule> itemRules =
                new ConcurrentHashMap<>();
        private final AtomicInteger workerLoadCalls = new AtomicInteger();
        private final AtomicInteger taskLoadCalls = new AtomicInteger();
        private final AtomicInteger remainingTaskLoadFailures =
                new AtomicInteger();
        private volatile @Nullable AssertionError taskLoadError;
        private volatile CountDownLatch taskLoadEntered =
                new CountDownLatch(0);
        private volatile CountDownLatch releaseTaskLoads =
                new CountDownLatch(0);

        void addWorker(String workerId, String region) {
            workers.put(workerId, new WorkerFacts(
                    workerId,
                    "group-1",
                    Map.of("region", region),
                    Map.of()
            ));
        }

        void blockTaskLoads() {
            taskLoadEntered = new CountDownLatch(1);
            releaseTaskLoads = new CountDownLatch(1);
        }

        @Override
        public MutationResult upsertWorkerFacts(
                String workerId,
                String workerGroupId,
                Map<String, Object> workerProperties
        ) {
            return new MutationResult(MutationStatus.APPLIED);
        }

        @Override
        public MutationResult patchWorkerPlatformProperties(
                String workerGroupId,
                String workerId,
                Map<String, @Nullable Object> properties
        ) {
            return new MutationResult(MutationStatus.APPLIED);
        }

        @Override
        public Map<String, WorkerFacts> loadWorkerFacts(
                String workerGroupId,
                List<String> workerIds
        ) {
            workerLoadCalls.incrementAndGet();
            LinkedHashMap<String, WorkerFacts> result = new LinkedHashMap<>();
            workerIds.forEach(workerId -> result.put(
                    workerId,
                    workers.get(workerId)
            ));
            return Collections.unmodifiableMap(result);
        }

        @Override
        public MutationResult createTaskRule(
                String taskId,
                String workerGroupId,
                Map<String, Object> allocationRule
        ) {
            return new MutationResult(MutationStatus.APPLIED);
        }

        @Override
        public Map<String, TaskRule> loadTaskRules(List<String> taskIds) {
            taskLoadCalls.incrementAndGet();
            taskLoadEntered.countDown();
            try {
                if (!releaseTaskLoads.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("blocked test load timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            AssertionError error = taskLoadError;
            if (error != null) {
                throw error;
            }
            if (remainingTaskLoadFailures.getAndUpdate(value ->
                    Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("temporary catalog failure");
            }
            LinkedHashMap<String, TaskRule> result = new LinkedHashMap<>();
            taskIds.forEach(taskId -> result.put(
                    taskId,
                    taskRules.get(taskId)
            ));
            return Collections.unmodifiableMap(result);
        }

        @Override
        public Map<ItemMatchKey, MutationResult> createItemRules(
                List<ItemRule> rules
        ) {
            return Map.of();
        }

        @Override
        public Map<ItemMatchKey, ItemRule> loadItemRules(
                List<ItemMatchKey> keys
        ) {
            LinkedHashMap<ItemMatchKey, ItemRule> result =
                    new LinkedHashMap<>();
            keys.forEach(key -> result.put(key, itemRules.get(key)));
            return Collections.unmodifiableMap(result);
        }
    }
}
