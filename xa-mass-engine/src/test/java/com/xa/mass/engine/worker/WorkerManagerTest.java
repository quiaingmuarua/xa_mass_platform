package com.xa.mass.engine.worker;

import com.xa.mass.base.channel.eventbus.event.worker.WorkerHeartbeatEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_STATE;
import static org.junit.jupiter.api.Assertions.*;

public class WorkerManagerTest {

    private WorkerManager manager;

    @BeforeEach
    void setUp() {
        manager = new WorkerManager(new InMemoryWorkerStorage());
    }

    // ---- add / get ----

    @Test
    void addAndRetrieveWorker() {
        Worker w = worker("w1", "us");
        manager.addWorker(w);
        Worker found = manager.getWorker("w1");
        assertNotNull(found);
        assertEquals("w1", found.getWorkerId());
    }

    @Test
    void declaredWorkerGroupCanIndexCapabilityBeforeWorkerRegistration() {
        WorkerGroupRecord group = WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .defaultAttributes(Map.of("source", "declared"))
                .defaultMaxConcurrentWork(3)
                .build();

        manager.upsertWorkerGroup(group);

        assertEquals(group, manager.workerGroup("crawler").orElseThrow());
        assertEquals(List.of(group), manager.workerGroups());
        assertEquals(Set.of("crawler"), manager.getWorkerRegistrySnapshot()
                .groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")));
        assertTrue(manager.getWorkerRegistrySnapshot().workerIdsByGroupId("crawler").isEmpty());
    }

    @Test
    void declaredWorkerGroupOverridesWorkerLevelCompatibilityFields() {
        Worker worker = worker("w-crawler", "crawler");
        worker.setSupportedProjects(List.of("legacyApp"));
        worker.setSupportedEventCodes(List.of("legacy.fetch"));
        manager.addWorker(worker);

        manager.upsertWorkerGroup(WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .build());

        assertEquals(Set.of("crawler"), manager.getWorkerRegistrySnapshot()
                .groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")));
        assertTrue(manager.getWorkerRegistrySnapshot()
                .groupIdsByEventKey(new EventKey("legacyApp", "legacy.fetch"))
                .isEmpty());
        assertEquals(Set.of("w-crawler"), manager.getWorkerRegistrySnapshot().workerIdsByGroupId("crawler"));
    }

    @Test
    void capabilityReportKeepsDeclaredWorkerGroupTruth() {
        Worker worker = worker("w-crawler", "crawler");
        worker.setSupportedProjects(List.of("legacyApp"));
        worker.setSupportedEventCodes(List.of("legacy.fetch", "crawler.fetch"));
        manager.addWorker(worker);
        manager.upsertWorkerGroup(WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .build());

        WorkerCapabilityReportResult result = manager.applyWorkerCapabilityReport(
                WorkerCapabilityReport.builder("w-crawler", 1)
                        .availableEventCodes(List.of("legacy.fetch"))
                        .build()
        );

        assertEquals(WorkerCapabilityReportStatus.ACCEPTED, result.status());
        assertEquals(Set.of("crawler"), result.snapshot()
                .groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")));
        assertTrue(result.snapshot()
                .groupIdsByEventKey(new EventKey("legacyApp", "legacy.fetch"))
                .isEmpty());
    }

    @Test
    void getWorkerReturnsNullWhenNotFound() {
        assertNull(manager.getWorker("nonexistent"));
    }

    @Test
    void exposesObservedWorkerLoadFromWorkerRegistrySlot() {
        manager.addWorker(worker("worker-load", "us"));

        manager.recordWorkClaimed("worker-load", "task-1");
        manager.recordWorkClaimed("worker-load", "task-1");

        assertEquals(2, manager.getWorkerLoad("worker-load").activeLeaseCount());
        assertEquals(2.0, manager.getWorkerLoad("worker-load").estimatedLoadRatio());

        manager.recordWorkFinal("worker-load", "task-1");

        assertEquals(1, manager.getWorkerLoad("worker-load").activeLeaseCount());
    }

    @Test
    void exposesWorkerLoadReservationLifecycle() {
        manager.addWorker(worker("worker-reserve", "us"));

        assertTrue(manager.tryReserveWorkerCapacity("worker-reserve", "task-1"));
        assertFalse(manager.tryReserveWorkerCapacity("worker-reserve", "task-2"));
        assertEquals(1, manager.getWorkerLoad("worker-reserve").reservedCount());

        assertTrue(manager.confirmWorkerReservation("worker-reserve", "task-1"));

        assertEquals(0, manager.getWorkerLoad("worker-reserve").reservedCount());
        assertEquals(1, manager.getWorkerLoad("worker-reserve").activeLeaseCount());

        manager.recordWorkFinal("worker-reserve", "task-1");
        assertEquals(0, manager.getWorkerLoad("worker-reserve").activeLeaseCount());
    }

    @Test
    void addWorkerPublishesDeclaredCapacityToLoadView() {
        Worker worker = worker("worker-capacity", "us");
        worker.setMaxConcurrentWork(3);

        manager.addWorker(worker);

        assertEquals(3, manager.getWorkerLoad("worker-capacity").declaredCapacity());
        assertTrue(manager.tryReserveWorkerCapacity("worker-capacity", "task-1"));
        assertTrue(manager.tryReserveWorkerCapacity("worker-capacity", "task-2"));
        assertTrue(manager.tryReserveWorkerCapacity("worker-capacity", "task-3"));
        assertFalse(manager.tryReserveWorkerCapacity("worker-capacity", "task-4"));
    }

    @Test
    void updateWorkerRefreshesDeclaredCapacityInLoadView() {
        Worker worker = worker("worker-capacity-update", "us");
        worker.setMaxConcurrentWork(2);
        manager.addWorker(worker);

        Worker updated = worker("worker-capacity-update", "us");
        updated.setMaxConcurrentWork(4);
        assertTrue(manager.updateWorker(updated));

        assertEquals(4, manager.getWorkerLoad("worker-capacity-update").declaredCapacity());
    }

    @Test
    void dispatchAvailabilityOwnerCanDisableAndReenableNewAssignments() {
        Worker worker = worker("worker-draining", "us");
        manager.addWorker(worker);

        assertTrue(manager.isWorkerDispatchEnabled(worker));

        assertTrue(manager.getDispatchAvailabilityOwner()
                .disableForDraining("worker-draining", WORKER_STATE, "maintenance"));
        assertFalse(manager.isWorkerDispatchEnabled(worker));

        assertTrue(manager.getDispatchAvailabilityOwner()
                .clearSource("worker-draining", WORKER_STATE, "ready"));
        assertTrue(manager.isWorkerDispatchEnabled(worker));
    }

    @Test
    void adapterNodeRegistrySupportsUpsertAndReadViews() {
        Instant registeredAt = Instant.parse("2026-05-20T00:00:00Z");
        AdapterNodeRecord created = manager.registerAdapterNode(new AdapterNodeRecord(
                " node-a ",
                " polling ",
                "1.0.0",
                " endpoint-a ",
                true,
                true,
                registeredAt,
                null,
                Map.of(" region ", " us ")
        ));

        assertEquals("node-a", created.adapterNodeId());
        assertEquals("polling", created.adapterType());
        assertEquals("endpoint-a", created.endpointId());
        assertEquals(registeredAt, created.registeredAt());
        assertNotNull(created.lastSeenAt());
        assertEquals(Map.of("region", "us"), created.attributes());

        AdapterNodeRecord updated = manager.registerAdapterNode(new AdapterNodeRecord(
                "node-a",
                "polling",
                "1.0.1",
                "endpoint-b",
                false,
                false,
                null,
                null,
                Map.of("region", "eu")
        ));

        assertEquals(registeredAt, updated.registeredAt());
        assertEquals("1.0.1", updated.adapterVersion());
        assertFalse(updated.enabled());
        assertEquals(List.of(updated), manager.adapterNodes());
        assertEquals(updated, manager.adapterNode("node-a").orElseThrow());
    }

    @Test
    void nodeGroupBindingRegistryMaintainsManyToManyIndexes() {
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.registerAdapterNode(adapterNode("node-b"));

        NodeGroupBindingRecord nodeAGroupOne = manager.bindNodeGroup(binding("node-a", "group-one"));
        NodeGroupBindingRecord nodeAGroupTwo = manager.bindNodeGroup(binding("node-a", "group-two"));
        NodeGroupBindingRecord nodeBGroupOne = manager.bindNodeGroup(binding("node-b", "group-one"));

        assertEquals(Set.of("group-one", "group-two"), manager.groupIdsByAdapterNodeId("node-a"));
        assertEquals(Set.of("node-a", "node-b"), manager.adapterNodeIdsByGroupId("group-one"));
        assertEquals(nodeAGroupOne, manager.nodeGroupBinding("node-a", "group-one").orElseThrow());
        assertTrue(manager.nodeGroupBindings().containsAll(List.of(nodeAGroupOne, nodeAGroupTwo, nodeBGroupOne)));

        assertTrue(manager.unbindNodeGroup("node-a", "group-one"));

        assertEquals(Set.of("group-two"), manager.groupIdsByAdapterNodeId("node-a"));
        assertEquals(Set.of("node-b"), manager.adapterNodeIdsByGroupId("group-one"));
        assertTrue(manager.nodeGroupBinding("node-a", "group-one").isEmpty());
    }

    @Test
    void relationshipChangesWakeDispatchPumpWhenTheyCanMakeWorkEligible() {
        AtomicInteger wakeups = new AtomicInteger();
        manager.setDispatchWakeupCallback(wakeups::incrementAndGet);

        manager.upsertWorkerGroup(WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .build());
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        Worker worker = worker("w-binding", "crawler");
        worker.setAdapterNodeId("node-a");
        manager.addWorker(worker);
        manager.setNodeGroupBindingDraining("node-a", "crawler", true);

        int beforeDrainClear = wakeups.get();
        manager.setNodeGroupBindingDraining("node-a", "crawler", false);

        assertTrue(beforeDrainClear >= 4);
        assertEquals(beforeDrainClear + 1, wakeups.get());
    }

    @Test
    void nodeGroupBindingStateChangesDoNotAffectWorkerCandidates() {
        declareEventGroup("crawler", "demoApp", "crawler.fetch");
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        Worker worker = worker("w-binding", "crawler");
        worker.setAdapterNodeId("node-a");
        manager.addWorker(worker);

        NodeGroupBindingRecord disabled = manager.setNodeGroupBindingEnabled("node-a", "crawler", false);
        NodeGroupBindingRecord draining = manager.setNodeGroupBindingDraining("node-a", "crawler", true);

        assertFalse(disabled.enabled());
        assertTrue(draining.draining());
        assertFalse(manager.isWorkerDispatchEnabled(worker));
        assertEquals(List.of("w-binding"),
                manager.getWorkerCandidateIndex()
                        .workersFor(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler")))
                        .stream()
                        .map(Worker::getWorkerId)
                        .toList());
    }

    @Test
    void explicitWorkerRegistrationRequiresRegisteredNodeGroupBinding() {
        manager.registerAdapterNode(adapterNode("node-a"));
        Worker worker = worker("w-explicit", "crawler");
        worker.setAdapterNodeId("node-a");

        IllegalArgumentException missingBinding = assertThrows(IllegalArgumentException.class,
                () -> manager.addWorker(worker));
        assertTrue(missingBinding.getMessage().contains("node group binding is not registered"));

        manager.bindNodeGroup(binding("node-a", "crawler"));
        manager.addWorker(worker);

        assertEquals(Set.of("w-explicit"),
                manager.getWorkerRegistrySnapshot().workerIdsByAdapterNodeGroup("node-a", "crawler"));
        assertEquals(Set.of("w-explicit"),
                manager.getWorkerRegistrySnapshot().workerIdsByAdapterNodeId("node-a"));
    }

    @Test
    void workerRegistrationDoesNotCreateCompatibilityNodeGroupBindingFromAdapterId() {
        Worker worker = worker("w-legacy", "crawler");
        worker.setAdapterId("polling");

        manager.addWorker(worker);

        assertNull(manager.getWorker("w-legacy").getAdapterNodeId());
        assertTrue(manager.adapterNode("polling").isEmpty());
        assertTrue(manager.nodeGroupBinding("polling", "crawler").isEmpty());
        assertTrue(manager.getWorkerRegistrySnapshot()
                .workerIdsByAdapterNodeGroup("polling", "crawler")
                .isEmpty());
    }

    @Test
    void nodeLocalDrainExcludesOnlyWorkersOnThatNodeGroupPair() {
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.registerAdapterNode(adapterNode("node-b"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        manager.bindNodeGroup(binding("node-b", "crawler"));
        Worker workerA = worker("w-node-a", "crawler");
        workerA.setAdapterNodeId("node-a");
        Worker workerB = worker("w-node-b", "crawler");
        workerB.setAdapterNodeId("node-b");
        manager.addWorker(workerA);
        manager.addWorker(workerB);

        manager.setNodeGroupBindingDraining("node-a", "crawler", true);

        assertFalse(manager.isWorkerDispatchEnabled(workerA));
        assertTrue(manager.isWorkerDispatchEnabled(workerB));

        manager.setNodeGroupBindingDraining("node-a", "crawler", false);

        assertTrue(manager.isWorkerDispatchEnabled(workerA));
        assertTrue(manager.isWorkerDispatchEnabled(workerB));
    }

    @Test
    void nodeGroupDrainClearDoesNotClearWorkerStateDrain() {
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        Worker worker = worker("w-node-a", "crawler");
        worker.setAdapterNodeId("node-a");
        manager.addWorker(worker);

        manager.getDispatchAvailabilityOwner().disableForDraining(
                "w-node-a",
                WORKER_STATE,
                "state draining"
        );
        manager.setNodeGroupBindingDraining("node-a", "crawler", true);
        assertFalse(manager.isWorkerDispatchEnabled(worker));

        manager.setNodeGroupBindingDraining("node-a", "crawler", false);

        assertFalse(manager.isWorkerDispatchEnabled(worker));
        manager.getDispatchAvailabilityOwner().clearSource(
                "w-node-a",
                WORKER_STATE,
                "state available"
        );
        assertTrue(manager.isWorkerDispatchEnabled(worker));
    }

    @Test
    void rebindingNodeGroupUpdatesDispatchGateForExistingWorkers() {
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        Worker worker = worker("w-node-a", "crawler");
        worker.setAdapterNodeId("node-a");
        manager.addWorker(worker);
        assertTrue(manager.isWorkerDispatchEnabled(worker));

        manager.bindNodeGroup(new NodeGroupBindingRecord(
                "node-a",
                "crawler",
                null,
                null,
                false,
                false,
                null,
                null,
                Map.of()
        ));

        assertFalse(manager.isWorkerDispatchEnabled(worker));
    }

    @Test
    void unbindingNodeGroupDisablesDispatchForExistingWorkers() {
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        Worker worker = worker("w-node-a", "crawler");
        worker.setAdapterNodeId("node-a");
        manager.addWorker(worker);
        assertTrue(manager.isWorkerDispatchEnabled(worker));

        assertTrue(manager.unbindNodeGroup("node-a", "crawler"));

        assertFalse(manager.isWorkerDispatchEnabled(worker));
    }

    @Test
    void nodeGroupBindingRequiresRegisteredAdapterNode() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.bindNodeGroup(binding("missing-node", "crawler")));

        assertTrue(error.getMessage().contains("adapterNodeId is not registered"));
    }

    @Test
    void nodeGroupBindingRequiresDeclaredWorkerGroup() {
        manager.registerAdapterNode(adapterNode("node-a"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.bindNodeGroup(rawBinding("node-a", "missing-group")));

        assertTrue(error.getMessage().contains("workerGroupId is not declared"));
    }

    @Test
    void deletingAdapterNodeRemovesNodeGroupBindings() {
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.registerAdapterNode(adapterNode("node-b"));
        manager.bindNodeGroup(binding("node-a", "group-one"));
        manager.bindNodeGroup(binding("node-a", "group-two"));
        manager.bindNodeGroup(binding("node-b", "group-one"));

        assertTrue(manager.deleteAdapterNode("node-a"));

        assertTrue(manager.adapterNode("node-a").isEmpty());
        assertTrue(manager.groupIdsByAdapterNodeId("node-a").isEmpty());
        assertEquals(Set.of("node-b"), manager.adapterNodeIdsByGroupId("group-one"));
        assertTrue(manager.nodeGroupBinding("node-a", "group-one").isEmpty());
        assertTrue(manager.nodeGroupBinding("node-a", "group-two").isEmpty());
        assertTrue(manager.nodeGroupBinding("node-b", "group-one").isPresent());
    }

    @Test
    void loadReadSynchronizesCapacityFromStorageRegisteredWorker() {
        InMemoryWorkerStorage storage = new InMemoryWorkerStorage();
        Worker worker = worker("worker-storage-direct", "us");
        worker.setMaxConcurrentWork(2);
        storage.addWorker(worker);
        WorkerManager storageBackedManager = new WorkerManager(storage);

        assertEquals(2, storageBackedManager.getWorkerLoad("worker-storage-direct").declaredCapacity());
        assertTrue(storageBackedManager.tryReserveWorkerCapacity("worker-storage-direct", "task-1"));
        assertTrue(storageBackedManager.tryReserveWorkerCapacity("worker-storage-direct", "task-2"));
        assertFalse(storageBackedManager.tryReserveWorkerCapacity("worker-storage-direct", "task-3"));
    }

    @Test
    void getAllWorkersReturnsAllAdded() {
        manager.addWorker(worker("a", "us"));
        manager.addWorker(worker("b", "gb"));
        manager.addWorker(worker("c", "us"));
        assertEquals(3, manager.getAllWorkers().size());
    }

    @Test
    void findWorkerCandidatesUsesExplicitGroupSelectorForNonEventTasks() {
        declareProjectGroup("pool-a", "demoApp");
        declareProjectGroup("pool-b", "testApp");
        Worker demoWorker = worker("w-demo", "pool-a");
        Worker otherWorker = worker("w-other", "pool-b");
        manager.addWorker(demoWorker);
        manager.addWorker(otherWorker);

        Task task = task("demoApp", selector("pool-a"));

        assertEquals(List.of("w-demo"),
                manager.findWorkerCandidates(task).stream().map(Worker::getWorkerId).toList());
    }

    @Test
    void findWorkerCandidatesUsesExplicitGroupSelectorForSdkEventTasks() {
        declareEventGroup("pool-a", "demoApp", "demo.dispatch");
        declareEventGroup("pool-b", "demoApp", "other.event");
        Worker eventWorker = worker("w-event", "pool-a");
        Worker projectOnlyWorker = worker("w-project", "pool-b");
        manager.addWorker(eventWorker);
        manager.addWorker(projectOnlyWorker);

        Task task = task("demoApp", sharedConfig(
                Map.of(TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")),
                "pool-a"));

        assertEquals(List.of("w-event"),
                manager.findWorkerCandidates(task).stream().map(Worker::getWorkerId).toList());
    }

    @Test
    void findWorkerCandidatesUsesTargetWorkerWithGroupCapabilityGate() {
        declareEventGroup("pool-a", "testApp", "other.event");
        declareEventGroup("pool-b", "demoApp", "demo.dispatch");
        Worker targetWorker = worker("w-target", "pool-a");
        Worker indexedWorker = worker("w-indexed", "pool-b");
        manager.addWorker(targetWorker);
        manager.addWorker(indexedWorker);

        Task task = task("demoApp", Map.of(
                TaskSharedConfig.TARGET_WORKER_ID, "w-target",
                TaskSharedConfig.WORKER_GROUP_ID, "pool-b",
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")
        ));

        assertTrue(manager.findWorkerCandidates(task).isEmpty());

        Task supportedTargetTask = task("testApp", Map.of(
                TaskSharedConfig.TARGET_WORKER_ID, "w-target",
                TaskSharedConfig.WORKER_GROUP_ID, "pool-a",
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "other.event")
        ));

        assertEquals(List.of("w-target"),
                manager.findWorkerCandidates(supportedTargetTask).stream().map(Worker::getWorkerId).toList());
    }

    @Test
    void findWorkerCandidatesDoesNotFallbackWhenTargetWorkerIsMissing() {
        declareEventGroup("pool-b", "demoApp", "demo.dispatch");
        Worker indexedWorker = worker("w-indexed", "pool-b");
        manager.addWorker(indexedWorker);

        Task task = task("demoApp", Map.of(
                TaskSharedConfig.TARGET_WORKER_ID, "missing-worker",
                TaskSharedConfig.WORKER_GROUP_ID, "pool-b",
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")
        ));

        assertTrue(manager.findWorkerCandidates(task).isEmpty());
    }

    @Test
    void findWorkerCandidatesDoesNotFallbackToAllWorkersWithoutSelector() {
        manager.addWorker(worker("w-a", "pool-a"));
        manager.addWorker(worker("w-b", "pool-b"));

        Task task = task(null, Map.of());

        assertTrue(manager.findWorkerCandidates(task).isEmpty());
    }

    @Test
    void updateWorkerRefreshesCandidateIndexes() {
        declareProjectGroup("pool-a", "demoApp");
        declareProjectGroup("pool-b", "testApp");
        Worker worker = worker("w-reindex", "pool-a");
        manager.addWorker(worker);

        Worker updated = worker("w-reindex", "pool-b");
        assertTrue(manager.updateWorker(updated));

        assertTrue(manager.findWorkerCandidates(task("demoApp", selector("pool-a"))).isEmpty());
        assertEquals(List.of("w-reindex"),
                manager.findWorkerCandidates(task("testApp", selector("pool-b"))).stream()
                        .map(Worker::getWorkerId)
                        .toList());
    }

    @Test
    void updateWorkerRefreshesRouteBucketMembership() {
        declareProjectGroup("pool-a", "demoApp");
        Worker worker = worker("w-route-reindex", "pool-a");
        worker.setAttributes(Map.of("region", "us"));
        manager.addWorker(worker);

        assertEquals(List.of("w-route-reindex"),
                manager.findWorkerCandidates(task("demoApp", sharedConfig(
                                Map.of(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "us")),
                                "pool-a")))
                        .stream()
                        .map(Worker::getWorkerId)
                        .toList());

        Worker updated = worker("w-route-reindex", "pool-a");
        updated.setAttributes(Map.of("region", "eu"));
        assertTrue(manager.updateWorker(updated));

        assertTrue(manager.findWorkerCandidates(task("demoApp", sharedConfig(
                Map.of(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "us")),
                "pool-a"))).isEmpty());
        assertEquals(List.of("w-route-reindex"),
                manager.findWorkerCandidates(task("demoApp", sharedConfig(
                                Map.of(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "eu")),
                                "pool-a")))
                        .stream()
                        .map(Worker::getWorkerId)
                        .toList());
    }

    @Test
    void updateWorkerRefreshesCandidateIndexesAfterInPlaceMutation() {
        declareEventGroup("pool-a", "demoApp", "demo.dispatch");
        declareProjectGroup("pool-b", "testApp");
        Worker worker = worker("w-mutable-reindex", "pool-a");
        manager.addWorker(worker);

        Worker stored = manager.getWorker("w-mutable-reindex");
        stored.setWorkerGroupId("pool-b");
        assertTrue(manager.updateWorker(stored));

        assertTrue(manager.findWorkerCandidates(task("demoApp", selector("pool-a"))).isEmpty());
        assertTrue(manager.findWorkerCandidates(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")), "pool-a"))).isEmpty());
        assertEquals(List.of("w-mutable-reindex"),
                manager.findWorkerCandidates(task("testApp", selector("pool-b"))).stream()
                        .map(Worker::getWorkerId)
                .toList());
    }

    @Test
    void addWorkerRefreshesWorkerRegistrySnapshotFromDeclaredGroup() {
        manager.upsertWorkerGroup(WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .defaultMaxConcurrentWork(3)
                .build());
        Worker worker = worker("w-indexed-group", "crawler");
        worker.setAdapterId("adapter-a");
        worker.setMaxConcurrentWork(3);
        manager.addWorker(worker);

        assertEquals(List.of("w-indexed-group"),
                manager.getWorkerCandidateIndex()
                        .workersFor(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler")))
                        .stream()
                        .map(Worker::getWorkerId)
                        .toList());
        assertEquals(3,
                manager.getWorkerRegistrySnapshot().group("crawler").orElseThrow().defaultMaxConcurrentWork());
    }

    @Test
    void findWorkerCandidatesAcquiresCandidateIdsFromWorkerRegistry() {
        InMemoryWorkerRegistry registry = new InMemoryWorkerRegistry((context, workerIds, maxCandidateCount) ->
                workerIds.stream()
                        .filter("w-registry-selected"::equals)
                        .limit(maxCandidateCount)
                        .toList());
        WorkerManager registryBackedManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                null,
                null,
                null,
                registry
        );
        registryBackedManager.upsertWorkerGroup(WorkerGroupRecord.builder("pool-a")
                .projectCodes(List.of("demoApp"))
                .build());
        registryBackedManager.addWorker(worker("w-snapshot-visible", "pool-a"));
        registryBackedManager.addWorker(worker("w-registry-selected", "pool-a"));

        assertEquals(List.of("w-registry-selected"),
                registryBackedManager.findWorkerCandidates(task("demoApp", selector("pool-a")), 10)
                        .stream()
                        .map(Worker::getWorkerId)
                        .toList());
    }

    @Test
    void workerRegistrySnapshotPublicationSwapsPointInTimeSnapshotReference() {
        WorkerRegistrySnapshot before = manager.getWorkerRegistrySnapshot();
        declareEventGroup("crawler", "demoApp", "crawler.fetch");
        declareEventGroup("export", "testApp", "report.export");

        Worker worker = worker("w-published-snapshot", "crawler");
        manager.addWorker(worker);
        WorkerRegistrySnapshot afterAdd = manager.getWorkerRegistrySnapshot();

        assertNotSame(before, afterAdd);
        assertTrue(before.workers().isEmpty());
        assertTrue(before.group("crawler").isEmpty());
        assertEquals(List.of("w-published-snapshot"),
                afterAdd.workers().stream().map(Worker::getWorkerId).toList());

        Worker updated = worker("w-published-snapshot", "export");
        assertTrue(manager.updateWorker(updated));
        WorkerRegistrySnapshot afterUpdate = manager.getWorkerRegistrySnapshot();

        assertNotSame(afterAdd, afterUpdate);
        assertTrue(afterAdd.group("crawler").isPresent());
        assertTrue(afterAdd.group("export").isPresent());
        assertEquals(List.of("w-published-snapshot"), List.copyOf(afterAdd.workerIdsByGroupId("crawler")));
        assertTrue(afterAdd.workerIdsByGroupId("export").isEmpty());
        assertTrue(afterUpdate.group("crawler").isPresent());
        assertTrue(afterUpdate.group("export").isPresent());
        assertTrue(afterUpdate.workerIdsByGroupId("crawler").isEmpty());
        assertEquals(List.of("w-published-snapshot"), List.copyOf(afterUpdate.workerIdsByGroupId("export")));
    }

    @Test
    void updateWorkerRefreshesWorkerRegistrySnapshotCapability() {
        declareEventGroup("crawler", "demoApp", "crawler.fetch");
        declareEventGroup("parser", "testApp", "crawler.parse");
        Worker worker = worker("w-snapshot-update", "crawler");
        manager.addWorker(worker);

        Worker updated = worker("w-snapshot-update", "parser");
        assertTrue(manager.updateWorker(updated));

        assertTrue(manager.getWorkerCandidateIndex()
                .workersFor(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler")))
                .isEmpty());
        assertEquals(List.of("w-snapshot-update"),
                manager.getWorkerCandidateIndex()
                        .workersFor(task("testApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.parse")), "parser")))
                        .stream()
                        .map(Worker::getWorkerId)
                        .toList());
    }

    @Test
    void workerCapabilityReportRefreshesCandidateIndexThroughPublishedSnapshot() {
        declareEventGroup("crawler", "demoApp", "crawler.fetch");
        Worker worker = worker("w-report-capability", "crawler");
        manager.addWorker(worker);

        WorkerCapabilityReportResult result = manager.applyWorkerCapabilityReport(
                WorkerCapabilityReport.builder("w-report-capability", 1)
                        .availableEventCodes(List.of("crawler.parse"))
                        .build()
        );

        assertEquals(WorkerCapabilityReportStatus.ACCEPTED, result.status());
        assertEquals(List.of("w-report-capability"), manager.getWorkerCandidateIndex()
                .workersFor(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler")))
                .stream()
                .map(Worker::getWorkerId)
                .toList());
        assertEquals(List.of("w-report-capability"), manager.getWorkerCandidateIndex()
                .workersFor(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.parse")), "crawler")))
                .stream()
                .map(Worker::getWorkerId)
                .toList());
        assertFalse(result.snapshot().workerSupportsEventKey(
                "w-report-capability",
                new EventKey("demoApp", "crawler.parse")
        ));
    }

    @Test
    void deleteWorkerRefreshesWorkerRegistrySnapshot() {
        declareEventGroup("crawler", "demoApp", "crawler.fetch");
        Worker worker = worker("w-snapshot-delete", "crawler");
        manager.addWorker(worker);

        assertTrue(manager.deleteWorker("w-snapshot-delete"));

        assertTrue(manager.getWorkerCandidateIndex()
                .workersFor(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler")))
                .isEmpty());
    }

    @Test
    void workerRegistrySnapshotCanBeRefreshedAfterDirectStorageMutation() {
        InMemoryWorkerStorage storage = new InMemoryWorkerStorage();
        WorkerManager storageBackedManager = new WorkerManager(storage);
        storageBackedManager.upsertWorkerGroup(WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .build());
        Worker worker = worker("w-storage-direct-snapshot", "crawler");
        storage.addWorker(worker);

        assertTrue(storageBackedManager.getWorkerCandidateIndex()
                .workersFor(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler")))
                .isEmpty());

        storageBackedManager.refreshWorkerRegistrySnapshot();

        assertEquals(List.of("w-storage-direct-snapshot"),
                storageBackedManager.getWorkerCandidateIndex()
                        .workersFor(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler")))
                        .stream()
                        .map(Worker::getWorkerId)
                        .toList());
    }


    @Test
    void deleteWorkerRemovesCandidateIndexes() {
        declareEventGroup("pool-a", "demoApp", "demo.dispatch");
        Worker worker = worker("w-delete-index", "pool-a");
        worker.setAttributes(Map.of("region", "us"));
        manager.addWorker(worker);

        assertTrue(manager.deleteWorker("w-delete-index"));

        assertTrue(manager.findWorkerCandidates(task("demoApp", selector("pool-a"))).isEmpty());
        assertTrue(manager.findWorkerCandidates(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")), "pool-a"))).isEmpty());
        assertTrue(manager.findWorkerCandidates(task("demoApp", sharedConfig(
                Map.of(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "us")),
                "pool-a"))).isEmpty());
    }

    // ---- filter by group ----

    @Test
    void getWorkersByGroupIdFiltersCorrectly() {
        manager.addWorker(worker("us1", "us"));
        manager.addWorker(worker("us2", "us"));
        manager.addWorker(worker("gb1", "gb"));

        List<Worker> us = manager.getWorkersByGroupId("us");
        assertEquals(2, us.size());
        assertTrue(us.stream().allMatch(w -> "us".equals(w.getWorkerGroupId())));
    }

    // ---- update / delete ----

    @Test
    void updateWorkerReturnsTrue() {
        Worker w = worker("w2", "us");
        manager.addWorker(w);
        w.setStatus(WorkerStatus.OFFLINE);
        assertTrue(manager.updateWorker(w));
    }

    @Test
    void deleteWorkerRemovesIt() {
        manager.addWorker(worker("w3", "us"));
        assertTrue(manager.deleteWorker("w3"));
        assertNull(manager.getWorker("w3"));
    }

    @Test
    void deleteNonexistentWorkerReturnsFalse() {
        assertFalse(manager.deleteWorker("ghost"));
    }

    // ---- lock ----

    @Test
    void lockAndUnlockWorker() {
        manager.addWorker(worker("w6", "us"));
        assertTrue(manager.tryAcquireWorkerExclusiveLease("w6"));
        assertTrue(manager.hasWorkerExclusiveLease("w6"));

        manager.releaseWorkerExclusiveLease("w6");
        assertFalse(manager.hasWorkerExclusiveLease("w6"));
    }

    @Test
    void lockAlreadyLockedWorkerReturnsFalse() {
        manager.addWorker(worker("w7", "us"));
        assertTrue(manager.tryAcquireWorkerExclusiveLease("w7"));
        assertFalse(manager.tryAcquireWorkerExclusiveLease("w7"));
    }

    // ---- worker model status vs transport reachability ----

    @Test
    void updateOnlineStatusTracksWorkerModelAvailabilityOnly() {
        manager.addWorker(worker("w8", "us"));
        manager.updateOnlineStatus("w8", false);
        assertFalse(manager.isWorkerOnline("w8"));
        assertEquals(WorkerStatus.OFFLINE, manager.getWorker("w8").getStatus());

        manager.updateOnlineStatus("w8", true);
        assertTrue(manager.isWorkerOnline("w8"));
        assertEquals(WorkerStatus.ONLINE, manager.getWorker("w8").getStatus());

        manager.updateOnlineStatus("w8", false);
        assertFalse(manager.isWorkerOnline("w8"));
        assertEquals(WorkerStatus.OFFLINE, manager.getWorker("w8").getStatus());
    }

    @Test
    void workerStatusEventListenerOnlyRefreshesHeartbeatAndLeavesModelStatusUntouched() {
        AtomicInteger wakeups = new AtomicInteger();
        WorkerManager.WorkerStatusEventListener listener =
                new WorkerManager.WorkerStatusEventListener(manager, wakeups::incrementAndGet);
        manager.addWorker(worker("w9", "us"));
        manager.updateOnlineStatus("w9", false);

        listener.onWorkerOnline(new WorkerOnlineEvent("w9", "connected", null));
        assertFalse(manager.isWorkerOnline("w9"));
        assertNotNull(manager.getWorker("w9").getLastHeartbeat());
        assertEquals(WorkerStatus.OFFLINE, manager.getWorker("w9").getStatus());
        assertEquals(1, wakeups.get());

        listener.onWorkerOffline(new WorkerOfflineEvent("w9", "disconnected", null));
        assertFalse(manager.isWorkerOnline("w9"));
        assertEquals(WorkerStatus.OFFLINE, manager.getWorker("w9").getStatus());
        assertEquals(1, wakeups.get());
    }

    @Test
    void workerHeartbeatEventRefreshesLastHeartbeatWithoutChangingWorkerModelAvailability() {
        AtomicInteger wakeups = new AtomicInteger();
        WorkerManager.WorkerStatusEventListener listener =
                new WorkerManager.WorkerStatusEventListener(manager, wakeups::incrementAndGet);
        manager.addWorker(worker("w10", "us"));
        manager.updateOnlineStatus("w10", false);

        listener.onWorkerHeartbeat(new WorkerHeartbeatEvent("w10", "heartbeat", null));

        assertFalse(manager.isWorkerOnline("w10"));
        assertNotNull(manager.getWorker("w10").getLastHeartbeat());
        assertEquals(WorkerStatus.OFFLINE, manager.getWorker("w10").getStatus());
        assertEquals(0, wakeups.get());
    }

    @Test
    void workerReachabilityComesFromTransportViewInsteadOfWorkerModelStatus() {
        WorkerManager reachabilityAwareManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> switch (workerId) {
                    case "w-online" -> WorkerReachabilityState.ONLINE;
                    case "w-stale" -> WorkerReachabilityState.STALE;
                    case "w-offline" -> WorkerReachabilityState.OFFLINE;
                    default -> WorkerReachabilityState.UNKNOWN;
                }
        );
        Worker onlineModelWorker = worker("w-online", "us");
        onlineModelWorker.setStatus(WorkerStatus.ONLINE);
        Worker staleModelWorker = worker("w-stale", "us");
        staleModelWorker.setStatus(WorkerStatus.ONLINE);
        Worker offlineModelWorker = worker("w-offline", "us");
        offlineModelWorker.setStatus(WorkerStatus.ONLINE);
        reachabilityAwareManager.addWorker(onlineModelWorker);
        reachabilityAwareManager.addWorker(staleModelWorker);
        reachabilityAwareManager.addWorker(offlineModelWorker);

        assertEquals(WorkerReachabilityState.ONLINE, reachabilityAwareManager.getWorkerReachability("w-online"));
        assertEquals(WorkerReachabilityState.STALE, reachabilityAwareManager.getWorkerReachability("w-stale"));
        assertEquals(WorkerReachabilityState.OFFLINE, reachabilityAwareManager.getWorkerReachability("w-offline"));
        assertEquals(WorkerReachabilityState.UNKNOWN, reachabilityAwareManager.getWorkerReachability("missing"));

        // Worker model status can still say ONLINE while transport reachability has already converged to STALE.
        assertTrue(reachabilityAwareManager.isWorkerOnline("w-stale"));
        assertTrue(reachabilityAwareManager.isWorkerDispatchEnabled(staleModelWorker));
    }

    // ---- helpers ----

    private AdapterNodeRecord adapterNode(String adapterNodeId) {
        return new AdapterNodeRecord(
                adapterNodeId,
                "polling",
                "1.0.0",
                "endpoint-" + adapterNodeId,
                true,
                true,
                null,
                null,
                Map.of()
        );
    }

    private NodeGroupBindingRecord binding(String adapterNodeId, String groupId) {
        if (manager.workerGroup(groupId).isEmpty()) {
            manager.upsertWorkerGroup(WorkerGroupRecord.builder(groupId).build());
        }
        return rawBinding(adapterNodeId, groupId);
    }

    private NodeGroupBindingRecord rawBinding(String adapterNodeId, String groupId) {
        return new NodeGroupBindingRecord(
                adapterNodeId,
                groupId,
                "plugin-1",
                "deploy-1",
                true,
                false,
                null,
                null,
                Map.of()
        );
    }

    private WorkerGroupRecord declareProjectGroup(String groupId, String projectCode) {
        WorkerGroupRecord group = WorkerGroupRecord.builder(groupId)
                .projectCodes(List.of(projectCode))
                .build();
        manager.upsertWorkerGroup(group);
        return group;
    }

    private WorkerGroupRecord declareEventGroup(String groupId, String projectCode, String eventCode) {
        WorkerGroupRecord group = WorkerGroupRecord.builder(groupId)
                .eventBindings(List.of(EventBinding.of(eventCode, List.of(projectCode))))
                .build();
        manager.upsertWorkerGroup(group);
        return group;
    }

    private Worker worker(String id, String workerGroupId) {
        Worker w = new Worker();
        w.setWorkerId(id);
        w.setWorkerGroupId(workerGroupId);
        w.setStatus(WorkerStatus.ONLINE);
        return w;
    }

    private Task task(String project, Map<String, Object> sharedConfig) {
        Task task = new Task();
        task.setTid("task-" + project);
        task.setProject(project);
        task.setSharedConfig(sharedConfig);
        return task;
    }

    private static Map<String, Object> selector(String... groupIds) {
        return sharedConfig(Map.of(), groupIds);
    }

    private static Map<String, Object> sharedConfig(Map<String, Object> base, String... groupIds) {
        java.util.LinkedHashMap<String, Object> sharedConfig = new java.util.LinkedHashMap<>();
        if (base != null) {
            sharedConfig.putAll(base);
        }
        if (groupIds.length == 1) {
            sharedConfig.put(TaskSharedConfig.WORKER_GROUP_ID, groupIds[0]);
        } else if (groupIds.length > 1) {
            sharedConfig.put(TaskSharedConfig.WORKER_GROUP_IDS, List.of(groupIds));
        }
        return Map.copyOf(sharedConfig);
    }
}
