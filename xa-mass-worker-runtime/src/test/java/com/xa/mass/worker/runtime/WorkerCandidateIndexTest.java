package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.resource.EventBinding;
import com.xa.mass.worker.runtime.routing.WorkerCandidateBucketPolicies;
import com.xa.mass.runtime.worker.RandomWorkerCandidateSamplingPolicy;
import com.xa.mass.runtime.worker.WorkerCandidateBucketPolicy;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_STATE;
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
    void targetWorkerLookupDoesNotUseAdapterNodePlacement() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-node-a", "crawler", Map.of()),
                worker("worker-node-b", "crawler", Map.of())
        )));

        assertEquals(List.of("worker-node-b"),
                workerIds(index.workersFor(task("demoApp", "crawler.fetch", "worker-node-b", "crawler"))));
        assertEquals(List.of("worker-node-a"),
                workerIds(index.workersFor(task("demoApp", "crawler.fetch", "worker-node-a", "crawler"))));
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

        assertEquals(List.of("worker-1"), workerIds(index.workersFor(task("demoApp", null, null, "crawler"))));
    }

    @Test
    void nonTargetedGroupLookupUsesBoundedCandidateBucketAcquisition() {
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
    void targetWorkerLookupBypassesCandidateBucketLimitButStillChecksGroupCapability() {
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
    void groupLookupRejectsSlotLifecycleIneligibleWorkersBeforeStageTwo() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-fresh", "crawler"),
                worker("worker-disabled", "crawler"),
                worker("worker-removing", "crawler"),
                worker("worker-stale", "crawler")
        ));
        InMemoryWorkerRegistry registry = registryFor(snapshot);
        registry.upsertSlot(meta("worker-fresh", "crawler", 31_000L), 1, Set.of());
        registry.upsertSlot(meta("worker-disabled", "crawler", 31_000L), 1, Set.of());
        registry.upsertSlot(meta("worker-removing", "crawler", 31_000L), 1, Set.of());
        registry.disableDispatch("crawler", "worker-disabled", WORKER_STATE);
        registry.markSlotRemoving("crawler", "worker-removing", "test");
        registry.upsertSlot(meta("worker-stale", "crawler", 1_000L), 1, Set.of());
        WorkerCandidateIndex index = new WorkerCandidateIndex(
                snapshot,
                registry,
                WorkerCandidateBucketPolicies.defaultPolicy(),
                () -> 31_001L
        );

        assertEquals(List.of("worker-fresh"),
                workerIds(index.workersFor(task("demoApp", "crawler.fetch", null, "crawler"), 10)));
    }

    @Test
    void targetWorkerLookupRejectsSlotLifecycleIneligibleWorkerBeforeReserve() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-disabled", "crawler")
        ));
        InMemoryWorkerRegistry registry = registryFor(snapshot);
        registry.disableDispatch("crawler", "worker-disabled", WORKER_STATE);
        WorkerCandidateIndex index = new WorkerCandidateIndex(
                snapshot,
                registry,
                WorkerCandidateBucketPolicies.defaultPolicy(),
                () -> 1_000L
        );

        WorkerCandidateIndex.SourceGuardResult result = index.sourceGuard(
                task("demoApp", "crawler.fetch", "worker-disabled", "crawler"),
                "crawler",
                WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY,
                "worker-disabled"
        );

        assertFalse(result.accepted());
        assertEquals(WorkerCandidateIndex.SourceGuardRejectionReason.DISPATCH_DISABLED, result.rejectionReason());
        assertTrue(index.workersFor(task("demoApp", "crawler.fetch", "worker-disabled", "crawler")).isEmpty());
    }

    @Test
    void routeAttributesNarrowNonTargetedEventLookupThroughApprovedBucket() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-us", "crawler", Map.of("region", "us")),
                worker("worker-eu", "crawler", Map.of("region", "eu"))
        )));

        WorkerTaskSelector selector = taskWithRouteAttributes(Map.of("region", "us"), "crawler");

        assertEquals(List.of("worker-us"), workerIds(index.workersFor(selector, 10)));
    }

    @Test
    void sourceGuardRejectsStaleCandidateBucketEvidenceBeforeStageTwo() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-eu", "crawler", Map.of("region", "eu"))
        )));
        WorkerTaskSelector selector = taskWithRouteAttributes(Map.of("region", "us"), "crawler");
        String observedCandidateBucket = selector.candidateBucketKeys().iterator().next();

        WorkerCandidateIndex.SourceGuardResult result =
                index.sourceGuard(selector, "crawler", observedCandidateBucket, "worker-eu");

        assertFalse(result.accepted());
        assertEquals(WorkerCandidateIndex.SourceGuardRejectionReason.CANDIDATE_BUCKET_MISMATCH, result.rejectionReason());
    }

    @Test
    void sourceGuardDoesNotUseAdapterNodeEvidenceBeforeStageTwo() {
        WorkerCandidateIndex index = index(WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp")))
        ), List.of(
                worker("worker-node-b", "crawler", Map.of())
        )));

        WorkerCandidateIndex.SourceGuardResult result =
                index.sourceGuard(task("demoApp", "crawler.fetch", null, "crawler"),
                        "crawler",
                        WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY,
                        "worker-node-b");

        assertTrue(result.accepted());
    }

    private static List<String> workerIds(List<WorkerDeclarationRecord> workers) {
        return workers.stream().map(WorkerDeclarationRecord::workerId).toList();
    }

    private static WorkerCandidateIndex index(WorkerRegistrySnapshot snapshot) {
        return new WorkerCandidateIndex(
                snapshot,
                registryFor(snapshot),
                WorkerCandidateBucketPolicies.defaultPolicy(),
                () -> 1_000L
        );
    }

    private static InMemoryWorkerRegistry registryFor(WorkerRegistrySnapshot snapshot) {
        InMemoryWorkerRegistry registry = new InMemoryWorkerRegistry(
                WorkerCandidateBucketPolicies.defaultPolicy(),
                RandomWorkerCandidateSamplingPolicy.defaultPolicy()
        );
        for (WorkerDeclarationRecord worker : snapshot.workers()) {
            if (worker.workerGroupId() == null || snapshot.group(worker.workerGroupId()).isEmpty()) {
                continue;
            }
            registry.upsertSlot(new WorkerMeta(
                    worker.workerId(),
                    worker.workerGroupId(),
                    worker.transportHint(),
                    worker.attributes(),
                    worker.agentVersion(),
                    null,
                    1_000L,
                    null
            ), worker.maxConcurrentWork(), Set.of());
        }
        return registry;
    }

    private static WorkerMeta meta(String workerId, String groupId, long lastHeartbeatMillis) {
        return new WorkerMeta(
                workerId,
                groupId,
                null,
                Map.of(),
                null,
                null,
                lastHeartbeatMillis,
                null
        );
    }

    private static WorkerTaskSelector task(String project, String eventCode, String targetWorkerId, String... groupIds) {
        return new WorkerTaskSelector(
                "task-" + project,
                groupIdList(groupIds),
                targetWorkerId,
                Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY)
        );
    }

    private static WorkerTaskSelector taskWithRouteAttributes(Map<String, String> routeAttributes,
                                                              String... groupIds) {
        String candidateBucketKey = WorkerCandidateBucketPolicies.approvedAttributePolicy(
                        WorkerCandidateBucketPolicies.STANDARD_APPROVED_ROUTE_ATTRIBUTES)
                .exactCandidateBucketKeyForAttributes(routeAttributes);
        return new WorkerTaskSelector(
                "task-routed",
                groupIdList(groupIds),
                null,
                Set.of(candidateBucketKey)
        );
    }

    private static List<String> groupIdList(String... groupIds) {
        return groupIds == null ? List.of() : List.of(groupIds);
    }

    private static WorkerGroupRecord group(String groupId, String adapterNodeId, EventBinding binding) {
        return WorkerGroupRecord.builder(groupId)
                .eventBindings(List.of(binding))
                .build();
    }

    private static WorkerDeclarationRecord worker(String workerId, String groupId) {
        return worker(workerId, groupId, Map.of());
    }

    private static WorkerDeclarationRecord worker(String workerId, String groupId, Map<String, String> attributes) {
        return new WorkerDeclarationRecord(workerId, groupId, null, null, 1, attributes);
    }
}
