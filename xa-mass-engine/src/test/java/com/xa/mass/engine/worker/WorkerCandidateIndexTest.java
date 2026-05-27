package com.xa.mass.engine.worker;

import com.xa.mass.runtime.worker.EventBinding;
import com.xa.mass.runtime.worker.WorkerGroupRecord;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.worker.runtime.WorkerRegistrySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerCandidateIndexTest {

    @Test
    void groupSelectorLookupReturnsWorkersFromMatchingGroupsOnly() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler-us", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp"))),
                group("crawler-eu", "node-a", EventBinding.of("crawler.fetch", List.of("euApp"))),
                group("export", "node-b", EventBinding.of("report.export", List.of("demoApp")))
        ), List.of(
                worker("worker-1", "crawler-us"),
                worker("worker-2", "crawler-us"),
                worker("worker-3", "crawler-eu"),
                worker("worker-4", "export")
        )));

        assertEquals(List.of("worker-1", "worker-2"),
                workerIds(index.workersFor(task("demoApp", "crawler.fetch", null, "crawler-us"))));
    }

    @Test
    void adapterNodeCanHostMultipleGroupsWithoutMergingCapabilities() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "adapter-node-1", EventBinding.of("crawler.fetch", List.of("demoApp"))),
                group("export", "adapter-node-1", EventBinding.of("report.export", List.of("demoApp")))
        ), List.of(
                worker("worker-crawler", "crawler"),
                worker("worker-export", "export")
        )));

        assertEquals(List.of("worker-crawler"),
                workerIds(index.workersFor(task("demoApp", "crawler.fetch", null, "crawler"))));
        assertEquals(List.of("worker-export"),
                workerIds(index.workersFor(task("demoApp", "report.export", null, "export"))));
    }

    @Test
    void targetWorkerLookupUsesDirectWorkerAndGroupCapabilityCheck() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp"))),
                group("export", "node-b", EventBinding.of("report.export", List.of("demoApp")))
        ), List.of(
                worker("worker-crawler", "crawler"),
                worker("worker-export", "export")
        )));

        assertEquals(List.of("worker-crawler"),
                workerIds(index.workersFor(task("demoApp", "crawler.fetch", "worker-crawler", "crawler"))));
        assertTrue(index.workersFor(task("demoApp", "crawler.fetch", "worker-export", "crawler")).isEmpty());
        assertTrue(index.workersFor(task("demoApp", "crawler.fetch", "missing-worker", "crawler")).isEmpty());
    }

    @Test
    void targetWorkerLookupStillRespectsAdapterNodePlacement() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-node-a", "crawler", "node-a", Map.of()),
                worker("worker-node-b", "crawler", "node-b", Map.of())
        )));

        Task task = task("demoApp", "crawler.fetch", "worker-node-b", "crawler");
        task.setSharedConfig(new java.util.LinkedHashMap<>(task.getSharedConfig()));
        task.getSharedConfig().put(TaskSharedConfig.ADAPTER_NODE_ID, "node-a");

        assertTrue(index.workersFor(task).isEmpty());

        task.getSharedConfig().put(TaskSharedConfig.TARGET_WORKER_ID, "worker-node-a");
        assertEquals(List.of("worker-node-a"), workerIds(index.workersFor(task)));
    }

    @Test
    void targetWorkerWithoutRegisteredGroupIsNotAStageOneCandidate() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-without-group", null),
                worker("worker-missing-group", "missing-group")
        )));

        assertTrue(index.workersFor(task("demoApp", "crawler.fetch", "worker-without-group", "crawler")).isEmpty());
        assertTrue(index.workersFor(task("demoApp", "crawler.fetch", "worker-missing-group", "crawler")).isEmpty());
    }

    @Test
    void groupSelectorTasksUseSelectedGroup() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                WorkerGroupRecord.builder("crawler")
                        .projectCodes(List.of("demoApp"))
                        .build(),
                WorkerGroupRecord.builder("export")
                        .projectCodes(List.of("otherApp"))
                        .build()
        ), List.of(
                worker("worker-1", "crawler"),
                worker("worker-2", "export")
        )));

        Task task = new Task();
        task.setProject("demoApp");
        task.setSharedConfig(Map.of(TaskSharedConfig.WORKER_GROUP_ID, "crawler"));

        assertEquals(List.of("worker-1"), workerIds(index.workersFor(task)));
    }

    @Test
    void nonTargetedGroupLookupUsesBoundedRouteBucketAcquisition() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-1", "crawler"),
                worker("worker-2", "crawler"),
                worker("worker-3", "crawler")
        )));

        List<String> workerIds = workerIds(index.workersFor(task("demoApp", "crawler.fetch", null, "crawler"), 2));
        assertEquals(2, workerIds.size());
        assertTrue(List.of("worker-1", "worker-2", "worker-3").containsAll(workerIds));
    }

    @Test
    void multiGroupLookupUsesFairSourceBudgetBeforeStageTwo() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler-a", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp"))),
                group("crawler-b", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-a-1", "crawler-a"),
                worker("worker-a-2", "crawler-a"),
                worker("worker-a-3", "crawler-a"),
                worker("worker-b-1", "crawler-b")
        )));

        List<String> workerIds = workerIds(index.workersFor(
                task("demoApp", "crawler.fetch", null, "crawler-a", "crawler-b"),
                2
        ));

        assertEquals(2, workerIds.size());
        assertTrue(workerIds.stream().anyMatch(workerId -> workerId.startsWith("worker-a-")));
        assertTrue(workerIds.stream().anyMatch(workerId -> workerId.startsWith("worker-b-")));
    }

    @Test
    void targetWorkerLookupBypassesRouteBucketLimitButStillChecksGroupCapability() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp"))),
                group("export", "node-a", EventBinding.of("report.export", List.of("demoApp")))
        ), List.of(
                worker("worker-1", "crawler"),
                worker("worker-2", "crawler"),
                worker("worker-3", "crawler"),
                worker("worker-export", "export")
        )));

        assertEquals(List.of("worker-3"),
                workerIds(index.workersFor(task("demoApp", "crawler.fetch", "worker-3", "crawler"), 1)));
        assertTrue(index.workersFor(task("demoApp", "crawler.fetch", "worker-export", "crawler"), 1).isEmpty());
    }

    @Test
    void routeAttributesNarrowNonTargetedEventLookupThroughApprovedBucket() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-us", "crawler", Map.of("region", "us")),
                worker("worker-eu", "crawler", Map.of("region", "eu"))
        )));

        Task task = task("demoApp", "crawler.fetch", null, "crawler");
        task.setSharedConfig(new java.util.LinkedHashMap<>(task.getSharedConfig()));
        task.getSharedConfig().put(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "us"));

        assertEquals(List.of("worker-us"), workerIds(index.workersFor(task, 10)));
    }

    @Test
    void sourceGuardRejectsStaleRouteEvidenceBeforeStageTwo() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-eu", "crawler", Map.of("region", "eu"))
        )));
        Task task = task("demoApp", "crawler.fetch", null, "crawler");
        task.setSharedConfig(new java.util.LinkedHashMap<>(task.getSharedConfig()));
        task.getSharedConfig().put(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "us"));
        String observedRouteBucket = WorkerRoutingPolicy.defaultPolicy()
                .routeBucketKeysForTask(task)
                .iterator()
                .next();

        WorkerCandidateIndex.SourceGuardResult result =
                index.sourceGuard(task, "crawler", null, observedRouteBucket, "worker-eu");

        assertFalse(result.accepted());
        assertEquals(WorkerCandidateIndex.SourceGuardRejectionReason.ROUTE_MISMATCH, result.rejectionReason());
    }

    @Test
    void sourceGuardRejectsStaleAdapterNodeEvidenceBeforeStageTwo() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-node-b", "crawler", "node-b", Map.of())
        )));

        WorkerCandidateIndex.SourceGuardResult result =
                index.sourceGuard(task("demoApp", "crawler.fetch", null, "crawler"),
                        "crawler",
                        "node-a",
                        WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY,
                        "worker-node-b");

        assertFalse(result.accepted());
        assertEquals(WorkerCandidateIndex.SourceGuardRejectionReason.ADAPTER_NODE_MISMATCH, result.rejectionReason());
    }

    private static List<String> workerIds(List<Worker> workers) {
        return workers.stream().map(Worker::getWorkerId).toList();
    }

    private static WorkerCandidateIndex index(WorkerRegistrySnapshot snapshot) {
        InMemoryWorkerRegistry registry = new InMemoryWorkerRegistry();
        for (Worker worker : snapshot.workers()) {
            if (worker.getWorkerGroupId() == null || snapshot.group(worker.getWorkerGroupId()).isEmpty()) {
                continue;
            }
            registry.upsertSlot(new WorkerMeta(
                    worker.getWorkerId(),
                    worker.getWorkerGroupId(),
                    worker.getAdapterNodeId(),
                    worker.getAdapterId(),
                    worker.getOnlineStrategy(),
                    worker.getAttributes(),
                    worker.getAgentVersion(),
                    null,
                    1_000L,
                    worker.getStatus() == null ? null : worker.getStatus().name()
            ), worker.getMaxConcurrentWork(), Set.of());
        }
        return new WorkerCandidateIndex(snapshot, registry);
    }

    private static Task task(String project, String eventCode, String targetWorkerId, String... groupIds) {
        Task task = new Task();
        task.setProject(project);
        Map<String, Object> sharedConfig = new java.util.LinkedHashMap<>();
        if (groupIds != null && groupIds.length == 1) {
            sharedConfig.put(TaskSharedConfig.WORKER_GROUP_ID, groupIds[0]);
        } else if (groupIds != null && groupIds.length > 1) {
            sharedConfig.put(TaskSharedConfig.WORKER_GROUP_IDS, List.of(groupIds));
        }
        if (eventCode != null) {
            sharedConfig.put(TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, eventCode));
        }
        if (targetWorkerId != null) {
            sharedConfig.put(TaskSharedConfig.TARGET_WORKER_ID, targetWorkerId);
        }
        task.setSharedConfig(sharedConfig);
        return task;
    }

    private static WorkerGroupRecord group(String groupId, String adapterNodeId, EventBinding binding) {
        return WorkerGroupRecord.builder(groupId)
                .eventBindings(List.of(binding))
                .build();
    }

    private static Worker worker(String workerId, String groupId) {
        return worker(workerId, groupId, Map.of());
    }

    private static Worker worker(String workerId, String groupId, Map<String, String> attributes) {
        return worker(workerId, groupId, null, attributes);
    }

    private static Worker worker(String workerId, String groupId, String adapterNodeId, Map<String, String> attributes) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId(groupId);
        worker.setAdapterNodeId(adapterNodeId);
        worker.setAttributes(attributes);
        return worker;
    }
}
