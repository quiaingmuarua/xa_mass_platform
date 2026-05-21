package com.xa.mass.engine.worker;

import com.xa.mass.base.channel.eventbus.event.worker.WorkerHeartbeatEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
    void getWorkerReturnsNullWhenNotFound() {
        assertNull(manager.getWorker("nonexistent"));
    }

    @Test
    void exposesObservedWorkerLoadView() {
        manager.recordWorkClaimed("worker-load", "task-1");
        manager.recordWorkClaimed("worker-load", "task-1");

        assertEquals(2, manager.getWorkerLoad("worker-load").activeLeaseCount());
        assertEquals(2.0, manager.getWorkerLoad("worker-load").estimatedLoadRatio());

        manager.recordWorkFinal("worker-load", "task-1");

        assertEquals(1, manager.getWorkerLoad("worker-load").activeLeaseCount());
    }

    @Test
    void exposesWorkerLoadReservationLifecycle() {
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
                .disableForDraining("worker-draining", "maintenance"));
        assertFalse(manager.isWorkerDispatchEnabled(worker));

        assertTrue(manager.getDispatchAvailabilityOwner()
                .enable("worker-draining", "ready"));
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
    void nodeGroupBindingStateChangesDoNotAffectWorkerCandidates() {
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        Worker worker = worker("w-binding", "crawler");
        worker.setAdapterNodeId("node-a");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));
        manager.addWorker(worker);

        NodeGroupBindingRecord disabled = manager.setNodeGroupBindingEnabled("node-a", "crawler", false);
        NodeGroupBindingRecord draining = manager.setNodeGroupBindingDraining("node-a", "crawler", true);

        assertFalse(disabled.enabled());
        assertTrue(draining.draining());
        assertFalse(manager.isWorkerDispatchEnabled(worker));
        assertEquals(List.of("w-binding"),
                manager.getWorkerCandidateIndex()
                        .workersFor(task("demoApp", Map.of(TaskSharedConfig.SDK_METADATA,
                                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch"))))
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
    void legacyWorkerRegistrationCreatesBoundedCompatibilityNodeGroupBinding() {
        Worker worker = worker("w-legacy", "crawler");
        worker.setAdapterId("polling");

        manager.addWorker(worker);

        assertEquals("polling", manager.getWorker("w-legacy").getAdapterNodeId());
        assertTrue(manager.adapterNode("polling").isPresent());
        assertTrue(manager.nodeGroupBinding("polling", "crawler").isPresent());
        assertEquals(Set.of("w-legacy"),
                manager.getWorkerRegistrySnapshot().workerIdsByAdapterNodeGroup("polling", "crawler"));
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
    void nodeGroupBindingRequiresRegisteredAdapterNode() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.bindNodeGroup(binding("missing-node", "crawler")));

        assertTrue(error.getMessage().contains("adapterNodeId is not registered"));
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
    void findWorkerCandidatesUsesProjectIndexForNonEventTasks() {
        Worker demoWorker = worker("w-demo", "pool-a");
        demoWorker.setSupportedProjects(List.of("demoApp"));
        Worker otherWorker = worker("w-other", "pool-b");
        otherWorker.setSupportedProjects(List.of("testApp"));
        manager.addWorker(demoWorker);
        manager.addWorker(otherWorker);

        Task task = task("demoApp", Map.of());

        assertEquals(List.of("w-demo"),
                manager.findWorkerCandidates(task).stream().map(Worker::getWorkerId).toList());
    }

    @Test
    void findWorkerCandidatesUsesEventIndexForSdkEventTasks() {
        Worker eventWorker = worker("w-event", "pool-a");
        eventWorker.setSupportedProjects(List.of("demoApp"));
        eventWorker.setSupportedEventCodes(List.of("demo.dispatch"));
        Worker projectOnlyWorker = worker("w-project", "pool-b");
        projectOnlyWorker.setSupportedProjects(List.of("demoApp"));
        projectOnlyWorker.setSupportedEventCodes(List.of("other.event"));
        manager.addWorker(eventWorker);
        manager.addWorker(projectOnlyWorker);

        Task task = task("demoApp", Map.of(TaskSharedConfig.SDK_METADATA,
                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")));

        assertEquals(List.of("w-event"),
                manager.findWorkerCandidates(task).stream().map(Worker::getWorkerId).toList());
    }

    @Test
    void findWorkerCandidatesUsesTargetWorkerWithGroupCapabilityGate() {
        Worker targetWorker = worker("w-target", "pool-a");
        targetWorker.setSupportedProjects(List.of("testApp"));
        targetWorker.setSupportedEventCodes(List.of("other.event"));
        Worker indexedWorker = worker("w-indexed", "pool-b");
        indexedWorker.setSupportedProjects(List.of("demoApp"));
        indexedWorker.setSupportedEventCodes(List.of("demo.dispatch"));
        manager.addWorker(targetWorker);
        manager.addWorker(indexedWorker);

        Task task = task("demoApp", Map.of(
                TaskSharedConfig.TARGET_WORKER_ID, "w-target",
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")
        ));

        assertTrue(manager.findWorkerCandidates(task).isEmpty());

        Task supportedTargetTask = task("testApp", Map.of(
                TaskSharedConfig.TARGET_WORKER_ID, "w-target",
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "other.event")
        ));

        assertEquals(List.of("w-target"),
                manager.findWorkerCandidates(supportedTargetTask).stream().map(Worker::getWorkerId).toList());
    }

    @Test
    void findWorkerCandidatesDoesNotFallbackWhenTargetWorkerIsMissing() {
        Worker indexedWorker = worker("w-indexed", "pool-b");
        indexedWorker.setSupportedProjects(List.of("demoApp"));
        indexedWorker.setSupportedEventCodes(List.of("demo.dispatch"));
        manager.addWorker(indexedWorker);

        Task task = task("demoApp", Map.of(
                TaskSharedConfig.TARGET_WORKER_ID, "missing-worker",
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")
        ));

        assertTrue(manager.findWorkerCandidates(task).isEmpty());
    }

    @Test
    void findWorkerCandidatesFallsBackToAllWorkersOnlyWithoutTargetEventOrProject() {
        manager.addWorker(worker("w-a", "pool-a"));
        manager.addWorker(worker("w-b", "pool-b"));

        Task task = task(null, Map.of());

        assertEquals(List.of("w-a", "w-b"),
                manager.findWorkerCandidates(task).stream().map(Worker::getWorkerId).toList());
    }

    @Test
    void updateWorkerRefreshesCandidateIndexes() {
        Worker worker = worker("w-reindex", "pool-a");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("demo.dispatch"));
        manager.addWorker(worker);

        Worker updated = worker("w-reindex", "pool-a");
        updated.setSupportedProjects(List.of("testApp"));
        updated.setSupportedEventCodes(List.of("other.event"));
        assertTrue(manager.updateWorker(updated));

        assertTrue(manager.findWorkerCandidates(task("demoApp", Map.of())).isEmpty());
        assertEquals(List.of("w-reindex"),
                manager.findWorkerCandidates(task("testApp", Map.of())).stream()
                        .map(Worker::getWorkerId)
                        .toList());
    }

    @Test
    void updateWorkerRefreshesCandidateIndexesAfterInPlaceMutation() {
        Worker worker = worker("w-mutable-reindex", "pool-a");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("demo.dispatch"));
        manager.addWorker(worker);

        Worker stored = manager.getWorker("w-mutable-reindex");
        stored.setSupportedProjects(List.of("testApp"));
        stored.setSupportedEventCodes(List.of("test.dispatch"));
        assertTrue(manager.updateWorker(stored));

        assertTrue(manager.findWorkerCandidates(task("demoApp", Map.of())).isEmpty());
        assertTrue(manager.findWorkerCandidates(task("demoApp", Map.of(TaskSharedConfig.SDK_METADATA,
                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")))).isEmpty());
        assertEquals(List.of("w-mutable-reindex"),
                manager.findWorkerCandidates(task("testApp", Map.of())).stream()
                        .map(Worker::getWorkerId)
                .toList());
    }

    @Test
    void addWorkerRefreshesWorkerRegistrySnapshotFromCompatibilityFields() {
        Worker worker = worker("w-indexed-group", "crawler");
        worker.setAdapterId("adapter-a");
        worker.setMaxConcurrentWork(3);
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));
        manager.addWorker(worker);

        assertEquals(List.of("w-indexed-group"),
                manager.getWorkerCandidateIndex()
                        .workersFor(task("demoApp", Map.of(TaskSharedConfig.SDK_METADATA,
                                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch"))))
                        .stream()
                        .map(Worker::getWorkerId)
                        .toList());
        assertEquals(3,
                manager.getWorkerRegistrySnapshot().group("crawler").orElseThrow().defaultMaxConcurrentWork());
    }

    @Test
    void workerRegistrySnapshotPublicationSwapsPointInTimeSnapshotReference() {
        WorkerRegistrySnapshot before = manager.getWorkerRegistrySnapshot();

        Worker worker = worker("w-published-snapshot", "crawler");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));
        manager.addWorker(worker);
        WorkerRegistrySnapshot afterAdd = manager.getWorkerRegistrySnapshot();

        assertNotSame(before, afterAdd);
        assertTrue(before.workers().isEmpty());
        assertTrue(before.group("crawler").isEmpty());
        assertEquals(List.of("w-published-snapshot"),
                afterAdd.workers().stream().map(Worker::getWorkerId).toList());

        Worker updated = worker("w-published-snapshot", "export");
        updated.setSupportedProjects(List.of("testApp"));
        updated.setSupportedEventCodes(List.of("report.export"));
        assertTrue(manager.updateWorker(updated));
        WorkerRegistrySnapshot afterUpdate = manager.getWorkerRegistrySnapshot();

        assertNotSame(afterAdd, afterUpdate);
        assertTrue(afterAdd.group("crawler").isPresent());
        assertTrue(afterAdd.group("export").isEmpty());
        assertTrue(afterUpdate.group("crawler").isEmpty());
        assertTrue(afterUpdate.group("export").isPresent());
    }

    @Test
    void updateWorkerRefreshesWorkerRegistrySnapshotCapability() {
        Worker worker = worker("w-snapshot-update", "crawler");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));
        manager.addWorker(worker);

        Worker updated = worker("w-snapshot-update", "crawler");
        updated.setSupportedProjects(List.of("testApp"));
        updated.setSupportedEventCodes(List.of("crawler.parse"));
        assertTrue(manager.updateWorker(updated));

        assertTrue(manager.getWorkerCandidateIndex()
                .workersFor(task("demoApp", Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch"))))
                .isEmpty());
        assertEquals(List.of("w-snapshot-update"),
                manager.getWorkerCandidateIndex()
                        .workersFor(task("testApp", Map.of(TaskSharedConfig.SDK_METADATA,
                                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.parse"))))
                        .stream()
                        .map(Worker::getWorkerId)
                        .toList());
    }

    @Test
    void workerCapabilityReportRefreshesCandidateIndexThroughPublishedSnapshot() {
        Worker worker = worker("w-report-capability", "crawler");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch", "crawler.parse"));
        manager.addWorker(worker);

        WorkerCapabilityReportResult result = manager.applyWorkerCapabilityReport(
                WorkerCapabilityReport.builder("w-report-capability", 1)
                        .availableEventCodes(List.of("crawler.parse"))
                        .build()
        );

        assertEquals(WorkerCapabilityReportStatus.ACCEPTED, result.status());
        assertTrue(manager.getWorkerCandidateIndex()
                .workersFor(task("demoApp", Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch"))))
                .isEmpty());
        assertEquals(List.of("w-report-capability"),
                manager.getWorkerCandidateIndex()
                        .workersFor(task("demoApp", Map.of(TaskSharedConfig.SDK_METADATA,
                                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.parse"))))
                        .stream()
                        .map(Worker::getWorkerId)
                        .toList());
    }

    @Test
    void deleteWorkerRefreshesWorkerRegistrySnapshot() {
        Worker worker = worker("w-snapshot-delete", "crawler");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));
        manager.addWorker(worker);

        assertTrue(manager.deleteWorker("w-snapshot-delete"));

        assertTrue(manager.getWorkerCandidateIndex()
                .workersFor(task("demoApp", Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch"))))
                .isEmpty());
    }

    @Test
    void workerRegistrySnapshotCanBeRefreshedAfterDirectStorageMutation() {
        InMemoryWorkerStorage storage = new InMemoryWorkerStorage();
        WorkerManager storageBackedManager = new WorkerManager(storage);
        Worker worker = worker("w-storage-direct-snapshot", "crawler");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));
        storage.addWorker(worker);

        assertTrue(storageBackedManager.getWorkerCandidateIndex()
                .workersFor(task("demoApp", Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch"))))
                .isEmpty());

        storageBackedManager.refreshWorkerRegistrySnapshot();

        assertEquals(List.of("w-storage-direct-snapshot"),
                storageBackedManager.getWorkerCandidateIndex()
                        .workersFor(task("demoApp", Map.of(TaskSharedConfig.SDK_METADATA,
                                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch"))))
                        .stream()
                        .map(Worker::getWorkerId)
                        .toList());
    }


    @Test
    void deleteWorkerRemovesCandidateIndexes() {
        Worker worker = worker("w-delete-index", "pool-a");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("demo.dispatch"));
        manager.addWorker(worker);

        assertTrue(manager.deleteWorker("w-delete-index"));

        assertTrue(manager.findWorkerCandidates(task("demoApp", Map.of())).isEmpty());
        assertTrue(manager.findWorkerCandidates(task("demoApp", Map.of(TaskSharedConfig.SDK_METADATA,
                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")))).isEmpty());
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
        assertTrue(manager.tryLockWorker("w6"));
        assertTrue(manager.isLocked("w6"));

        manager.unlockWorker("w6");
        assertFalse(manager.isLocked("w6"));
    }

    @Test
    void lockAlreadyLockedWorkerReturnsFalse() {
        manager.addWorker(worker("w7", "us"));
        assertTrue(manager.tryLockWorker("w7"));
        assertFalse(manager.tryLockWorker("w7"));
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
}
