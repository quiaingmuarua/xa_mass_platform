package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.resource.AdapterNodeRecord;
import com.xa.mass.worker.runtime.resource.EventBinding;
import com.xa.mass.worker.runtime.resource.NodeGroupBindingRecord;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.runtime.worker.RandomWorkerCandidateSamplingPolicy;
import com.xa.mass.runtime.worker.WorkerDispatchBlockRecord;
import com.xa.mass.runtime.worker.slot.WorkerScoreBand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandAcquireRequest;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlot;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionTarget;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSignal;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSource;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReport;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportResult;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportStatus;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.routing.WorkerCandidateBucketPolicies;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.memory.InMemoryWorkerScoreBandSlotRuntime;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_STATE;
import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_COMMAND;
import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.TRANSPORT_DISCONNECTED;
import static org.junit.jupiter.api.Assertions.*;

public class WorkerManagerTest {

    private WorkerManager manager;
    private TestWorkerDeclarationStore workerDeclarationStore;

    @BeforeEach
    void setUp() {
        workerDeclarationStore = new TestWorkerDeclarationStore();
        manager = new WorkerManager(workerDeclarationStore, platformRegistry());
    }

    // ---- add / get ----

    @Test
    void addAndRetrieveWorker() {
        Worker w = worker("w1", "us");
        addWorker(w);
        Worker found = workerModel("w1");
        assertNotNull(found);
        assertEquals("w1", found.getWorkerId());
    }

    @Test
    void addOnlineWorkerWithoutExplicitHeartbeatDoesNotPersistSyntheticHeartbeat() {
        Worker worker = new Worker();
        worker.setWorkerId("w-no-heartbeat");
        worker.setWorkerGroupId("us");
        worker.setStatus(WorkerStatus.ONLINE);

        addWorker(worker);

        Worker found = workerModel("w-no-heartbeat");
        assertNotNull(found);
        assertNull(found.getLastHeartbeat());
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
    }

    @Test
    void declaredWorkerGroupOverridesWorkerLevelCompatibilityFields() {
        Worker worker = worker("w-crawler", "crawler");
        worker.setSupportedProjects(List.of("legacyApp"));
        worker.setSupportedEventCodes(List.of("legacy.fetch"));
        addWorker(worker);

        manager.upsertWorkerGroup(WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .build());

        assertEquals(Set.of("crawler"), manager.getWorkerRegistrySnapshot()
                .groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")));
        assertTrue(manager.getWorkerRegistrySnapshot()
                .groupIdsByEventKey(new EventKey("legacyApp", "legacy.fetch"))
                .isEmpty());
        assertEquals("crawler", workerModel("w-crawler").getWorkerGroupId());
    }

    @Test
    void capabilityReportKeepsDeclaredWorkerGroupTruth() {
        Worker worker = worker("w-crawler", "crawler");
        worker.setSupportedProjects(List.of("legacyApp"));
        worker.setSupportedEventCodes(List.of("legacy.fetch", "crawler.fetch"));
        addWorker(worker);
        manager.upsertWorkerGroup(WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .build());

        WorkerCapabilityReportResult result = manager.applyWorkerCapabilityReport(
                WorkerCapabilityReport.builder("w-crawler", 1)
                        .availableEventCodes(List.of("legacy.fetch"))
                        .build()
        );

        assertEquals(WorkerCapabilityReportStatus.ACCEPTED, result.status());
        assertEquals(Set.of("crawler"), manager.getWorkerRegistrySnapshot()
                .groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")));
        assertTrue(manager.getWorkerRegistrySnapshot()
                .groupIdsByEventKey(new EventKey("legacyApp", "legacy.fetch"))
                .isEmpty());
    }

    @Test
    void getWorkerReturnsNullWhenNotFound() {
        assertNull(workerModel("nonexistent"));
    }

    @Test
    void exposesObservedWorkerLoadFromWorkerRegistrySlot() {
        addWorker(worker("worker-load", "us"));

        manager.recordWorkClaimed(admissionTarget("us", "worker-load", "task-1"));
        manager.recordWorkClaimed(admissionTarget("us", "worker-load", "task-1"));

        assertEquals(2, manager.getWorkerLoad("worker-load").activeLeaseCount());
        assertEquals(2.0, manager.getWorkerLoad("worker-load").estimatedLoadRatio());

        manager.recordWorkFinal(admissionTarget("us", "worker-load", "task-1"));

        assertEquals(1, manager.getWorkerLoad("worker-load").activeLeaseCount());
    }

    @Test
    void exposesWorkerLoadReservationLifecycle() {
        addWorker(worker("worker-reserve", "us"));

        assertTrue(manager.reserveWorkerCapacity(admissionTarget("us", "worker-reserve", "task-1")).accepted());
        assertFalse(manager.reserveWorkerCapacity(admissionTarget("us", "worker-reserve", "task-2")).accepted());
        assertEquals(1, manager.getWorkerLoad("worker-reserve").reservedCount());

        assertTrue(manager.confirmWorkerReservation(admissionTarget("us", "worker-reserve", "task-1")));

        assertEquals(0, manager.getWorkerLoad("worker-reserve").reservedCount());
        assertEquals(1, manager.getWorkerLoad("worker-reserve").activeLeaseCount());

        manager.recordWorkFinal(admissionTarget("us", "worker-reserve", "task-1"));
        assertEquals(0, manager.getWorkerLoad("worker-reserve").activeLeaseCount());
    }

    @Test
    void releaseAndFinalEvidenceWakeDispatchWithoutClearingBlocks() {
        Worker worker = worker("worker-release-wakeup", "us");
        worker.setMaxConcurrentWork(2);
        addWorker(worker);
        WorkerAdmissionTarget activeTarget = admissionTarget("us", worker.getWorkerId(), "task-active");
        WorkerAdmissionTarget reservedTarget = admissionTarget("us", worker.getWorkerId(), "task-reserved");
        assertTrue(manager.reserveWorkerCapacity(activeTarget).accepted());
        assertTrue(manager.confirmWorkerReservation(activeTarget));
        assertTrue(manager.reserveWorkerCapacity(reservedTarget).accepted());
        assertTrue(manager.tryAcquireWorkerExclusiveLease(worker.getWorkerId()));
        assertTrue(manager.blockWorkerDispatch(worker.getWorkerId(),
                blockSignal(WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED, "disconnect", 1_000L)));
        AtomicInteger wakeups = new AtomicInteger();
        manager.setDispatchWakeupCallback(wakeups::incrementAndGet);

        manager.recordWorkFinal(activeTarget);
        manager.releaseWorkerReservation(reservedTarget);
        manager.releaseWorkerExclusiveLease(worker.getWorkerId());

        assertEquals(3, wakeups.get());
        assertFalse(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
        assertTrue(manager.dispatchBlockRecord("us", worker.getWorkerId(), TRANSPORT_DISCONNECTED).isPresent());
    }

    @Test
    void addWorkerPublishesDeclaredCapacityToLoadView() {
        Worker worker = worker("worker-capacity", "us");
        worker.setMaxConcurrentWork(3);

        addWorker(worker);

        assertEquals(3, manager.getWorkerLoad("worker-capacity").declaredCapacity());
        assertTrue(manager.reserveWorkerCapacity(admissionTarget("us", "worker-capacity", "task-1")).accepted());
        assertTrue(manager.reserveWorkerCapacity(admissionTarget("us", "worker-capacity", "task-2")).accepted());
        assertTrue(manager.reserveWorkerCapacity(admissionTarget("us", "worker-capacity", "task-3")).accepted());
        assertFalse(manager.reserveWorkerCapacity(admissionTarget("us", "worker-capacity", "task-4")).accepted());
    }

    @Test
    void updateWorkerRefreshesDeclaredCapacityInLoadView() {
        Worker worker = worker("worker-capacity-update", "us");
        worker.setMaxConcurrentWork(2);
        addWorker(worker);

        Worker updated = worker("worker-capacity-update", "us");
        updated.setMaxConcurrentWork(4);
        assertTrue(updateWorker(updated));

        assertEquals(4, manager.getWorkerLoad("worker-capacity-update").declaredCapacity());
    }

    @Test
    void workerDeclarationProjectsScoreBandSlotWithoutHeartbeatPositiveWrite() {
        InMemoryWorkerScoreBandSlotRuntime scoreBandRuntime = new InMemoryWorkerScoreBandSlotRuntime();
        WorkerManager scoreBandManager = new WorkerManager(
                new TestWorkerDeclarationStore(),
                ignored -> WorkerReachabilityState.UNKNOWN,
                platformRegistry(),
                scoreBandRuntime,
                WorkerCandidateBucketPolicies.defaultPolicy()
        );
        Worker worker = worker("worker-score-band", "pool-a");

        scoreBandManager.addWorker(workerDeclaration(worker));
        WorkerScoreBandSlot initialSlot = scoreBandRuntime.slot("pool-a", "worker-score-band").orElseThrow();
        assertEquals("pool-a", initialSlot.homeBucketId());
        assertEquals("worker-score-band", initialSlot.workerId());
        assertTrue(WorkerScoreBand.isAcquireVisible(initialSlot.score(), System.currentTimeMillis()));

        scoreBandManager.refreshWorkerHeartbeat("worker-score-band", System.currentTimeMillis() + 10_000);

        assertEquals(initialSlot.score(),
                scoreBandRuntime.slot("pool-a", "worker-score-band").orElseThrow().score());
    }

    @Test
    void workerGroupUpdateMovesScoreBandSlotHomeBucket() {
        InMemoryWorkerScoreBandSlotRuntime scoreBandRuntime = new InMemoryWorkerScoreBandSlotRuntime();
        WorkerManager scoreBandManager = new WorkerManager(
                new TestWorkerDeclarationStore(),
                ignored -> WorkerReachabilityState.UNKNOWN,
                platformRegistry(),
                scoreBandRuntime,
                WorkerCandidateBucketPolicies.defaultPolicy()
        );
        Worker worker = worker("worker-score-band-move", "pool-a");
        scoreBandManager.addWorker(workerDeclaration(worker));

        Worker updated = worker("worker-score-band-move", "pool-b");
        assertTrue(scoreBandManager.updateWorker(workerDeclaration(updated)));

        assertTrue(scoreBandRuntime.slot("pool-a", "worker-score-band-move").isEmpty());
        assertTrue(scoreBandRuntime.acquire(
                WorkerScoreBandAcquireRequest.inHomeBucket("pool-a", 10, System.currentTimeMillis() + 10)
        ).isEmpty());
        assertEquals("pool-b",
                scoreBandRuntime.slot("pool-b", "worker-score-band-move").orElseThrow().homeBucketId());
    }

    @Test
    void updateWorkerHeartbeatRefreshesRegistryEvidenceButNotDeclarationTruth() {
        declareProjectGroup("us", "demoApp");
        Worker worker = worker("worker-heartbeat-projection", "us");
        worker.setAgentVersion("declared-v1");
        worker.setLastHeartbeat(null);
        addWorker(worker);

        LocalDateTime heartbeat = LocalDateTime.now();
        Worker update = worker("worker-heartbeat-projection", "us");
        update.setAgentVersion("declared-v2");
        update.setStatus(WorkerStatus.ONLINE);
        update.setLastHeartbeat(heartbeat);
        assertTrue(updateWorker(update));

        assertEquals(List.of("worker-heartbeat-projection"),
                candidateIds(task("demoApp", selector("us"))));
        Worker readModel = workerModel("worker-heartbeat-projection");
        assertNull(readModel.getLastHeartbeat());
        assertEquals(WorkerStatus.OFFLINE, readModel.getStatus());

        WorkerDeclarationRecord declaration = workerDeclarationStore.getWorker("worker-heartbeat-projection")
                .orElseThrow();
        assertEquals("declared-v2", declaration.agentVersion());
        assertEquals("us", declaration.workerGroupId());
        assertEquals("worker-heartbeat-projection", declaration.workerId());
    }

    @Test
    void workerRegistryDispatchGateCanDisableAndReenableNewAssignments() {
        Worker worker = worker("worker-draining", "us");
        addWorker(worker);

        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(manager.disableWorkerDispatch("worker-draining", WORKER_STATE, "maintenance"));
        assertFalse(manager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(manager.clearWorkerDispatchDisable("worker-draining", WORKER_STATE, "ready"));
        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
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
    void relationshipChangesDoNotWakeDispatchPump() {
        AtomicInteger wakeups = new AtomicInteger();
        manager.setDispatchWakeupCallback(wakeups::incrementAndGet);

        manager.upsertWorkerGroup(WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .build());
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        Worker worker = worker("w-binding", "crawler");
        worker.setAdapterNodeId("node-a");
        addWorker(worker);
        manager.setNodeGroupBindingDraining("node-a", "crawler", true);

        int beforeDrainClear = wakeups.get();
        manager.setNodeGroupBindingDraining("node-a", "crawler", false);

        assertEquals(2, beforeDrainClear);
        assertEquals(beforeDrainClear, wakeups.get());
    }

    @Test
    void nodeGroupBindingStateIsTopologyMetadataNotCandidateEligibility() {
        declareEventGroup("crawler", "demoApp", "crawler.fetch");
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        Worker worker = worker("w-binding", "crawler");
        worker.setAdapterNodeId("node-a");
        addWorker(worker);

        NodeGroupBindingRecord disabled = manager.setNodeGroupBindingEnabled("node-a", "crawler", false);
        NodeGroupBindingRecord draining = manager.setNodeGroupBindingDraining("node-a", "crawler", true);

        assertFalse(disabled.enabled());
        assertTrue(draining.draining());
        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
        assertEquals(List.of("w-binding"),
                candidateIndexIds(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler"))));

        manager.setNodeGroupBindingEnabled("node-a", "crawler", true);
        manager.setNodeGroupBindingDraining("node-a", "crawler", false);

        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
        assertEquals(List.of("w-binding"),
                candidateIndexIds(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler"))));
    }

    @Test
    void explicitWorkerRegistrationDoesNotRequireRegisteredNodeGroupBinding() {
        manager.registerAdapterNode(adapterNode("node-a"));
        Worker worker = worker("w-explicit", "crawler");
        worker.setAdapterNodeId("node-a");

        addWorker(worker);

        assertNull(workerModel("w-explicit").getAdapterNodeId());
        assertTrue(manager.nodeGroupBinding("node-a", "crawler").isEmpty());
    }

    @Test
    void workerRegistrationDoesNotCreateCompatibilityNodeGroupBindingFromAdapterId() {
        Worker worker = worker("w-legacy", "crawler");
        worker.setAdapterId("polling");

        addWorker(worker);

        assertNull(workerModel("w-legacy").getAdapterNodeId());
        assertTrue(manager.adapterNode("polling").isEmpty());
        assertTrue(manager.nodeGroupBinding("polling", "crawler").isEmpty());
    }

    @Test
    void nodeLocalDrainDoesNotChangeWorkerDispatchEligibility() {
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.registerAdapterNode(adapterNode("node-b"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        manager.bindNodeGroup(binding("node-b", "crawler"));
        Worker workerA = worker("w-node-a", "crawler");
        workerA.setAdapterNodeId("node-a");
        Worker workerB = worker("w-node-b", "crawler");
        workerB.setAdapterNodeId("node-b");
        addWorker(workerA);
        addWorker(workerB);

        manager.setNodeGroupBindingDraining("node-a", "crawler", true);

        assertTrue(manager.isWorkerDispatchEnabled(workerA.getWorkerId()));
        assertTrue(manager.isWorkerDispatchEnabled(workerB.getWorkerId()));

        manager.setNodeGroupBindingDraining("node-a", "crawler", false);

        assertTrue(manager.isWorkerDispatchEnabled(workerA.getWorkerId()));
        assertTrue(manager.isWorkerDispatchEnabled(workerB.getWorkerId()));
    }

    @Test
    void nodeGroupDrainClearDoesNotClearWorkerStateDrain() {
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        Worker worker = worker("w-node-a", "crawler");
        worker.setAdapterNodeId("node-a");
        addWorker(worker);

        manager.disableWorkerDispatch(
                "w-node-a",
                WORKER_STATE,
                "state draining"
        );
        manager.setNodeGroupBindingDraining("node-a", "crawler", true);
        assertFalse(manager.isWorkerDispatchEnabled(worker.getWorkerId()));

        manager.setNodeGroupBindingDraining("node-a", "crawler", false);

        assertFalse(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
        manager.clearWorkerDispatchDisable(
                "w-node-a",
                WORKER_STATE,
                "state available"
        );
        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
    }

    @Test
    void rebindingNodeGroupUpdatesTopologyMetadataOnly() {
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        Worker worker = worker("w-node-a", "crawler");
        worker.setAdapterNodeId("node-a");
        addWorker(worker);
        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));

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

        NodeGroupBindingRecord binding = manager.nodeGroupBinding("node-a", "crawler").orElseThrow();
        assertFalse(binding.enabled());
        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
    }

    @Test
    void negativeBlockPortDisablesDispatchWithoutExposingClear() {
        Worker worker = worker("worker-disconnected", "us");
        addWorker(worker);

        assertTrue(manager.blockWorkerDispatch("us", "worker-disconnected",
                blockSignal(WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED, "current disconnect", 2_000L)));

        assertFalse(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
        WorkerDispatchBlockRecord record = manager.dispatchBlockRecord(
                "us",
                "worker-disconnected",
                TRANSPORT_DISCONNECTED
        ).orElseThrow();
        assertEquals(TRANSPORT_DISCONNECTED, record.source());
        assertEquals("current disconnect", record.reason());
        assertEquals(2_000L, record.observedAtMillis());
    }

    @Test
    void staleNegativeBlockSignalDoesNotReblockAfterRecoveryClearsGateSource() {
        Worker worker = worker("worker-recovered", "us");
        addWorker(worker);

        assertTrue(manager.blockWorkerDispatch("us", "worker-recovered",
                blockSignal(WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED, "current disconnect", 2_000L)));
        assertFalse(manager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(manager.clearWorkerDispatchDisable("worker-recovered", TRANSPORT_DISCONNECTED, "worker-runtime recheck passed"));
        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertFalse(manager.blockWorkerDispatch("us", "worker-recovered",
                blockSignal(WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED, "stale disconnect", 1_000L)));
        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(manager.blockWorkerDispatch("us", "worker-recovered",
                blockSignal(WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED, "new disconnect", 3_000L)));
        assertFalse(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
        assertEquals("new disconnect", manager.dispatchBlockRecord("us", "worker-recovered", TRANSPORT_DISCONNECTED)
                .orElseThrow()
                .reason());
    }

    @Test
    void negativeBlockSignalCannotRedefineWorkerGroupMembership() {
        Worker worker = worker("worker-group-owned", "us");
        addWorker(worker);

        assertFalse(manager.blockWorkerDispatch("eu", "worker-group-owned",
                blockSignal(WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED, "wrong group", 1_000L)));

        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
    }

    @Test
    void workerRuntimeRecoveryClearsOnlyRequestedValidatedSource() {
        Worker worker = worker("worker-state-recovered", "us");
        addWorker(worker);

        manager.disableWorkerDispatch(worker.getWorkerId(), WORKER_STATE, "state draining");
        manager.disableWorkerDispatch(worker.getWorkerId(), WORKER_COMMAND, "drain command accepted");
        assertFalse(manager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(manager.recoverWorkerDispatch(worker.getWorkerId(), WORKER_STATE, "state available"));

        assertFalse(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
        assertTrue(manager.recoverWorkerDispatch(worker.getWorkerId(), WORKER_COMMAND, "command recovered"));
        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
    }

    @Test
    void workerRuntimeRecoveryRejectsRemovingWorkerSlot() {
        Worker worker = worker("worker-removing-recovery", "us");
        addWorker(worker);
        manager.disableWorkerDispatch(worker.getWorkerId(), WORKER_STATE, "state draining");

        assertTrue(manager.deleteWorker(worker.getWorkerId()));
        assertFalse(manager.recoverWorkerDispatch(worker.getWorkerId(), WORKER_STATE, "state available"));

        assertFalse(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
    }

    @Test
    void unbindingNodeGroupRemovesTopologyMetadataOnly() {
        manager.registerAdapterNode(adapterNode("node-a"));
        manager.bindNodeGroup(binding("node-a", "crawler"));
        Worker worker = worker("w-node-a", "crawler");
        worker.setAdapterNodeId("node-a");
        addWorker(worker);
        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(manager.unbindNodeGroup("node-a", "crawler"));

        assertTrue(manager.nodeGroupBinding("node-a", "crawler").isEmpty());
        assertTrue(manager.isWorkerDispatchEnabled(worker.getWorkerId()));
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
    void loadReadSynchronizesCapacityButDoesNotMakeDeclarationOnlyWorkerReservable() {
        TestWorkerDeclarationStore storage = new TestWorkerDeclarationStore();
        Worker worker = worker("worker-storage-direct", "us");
        worker.setMaxConcurrentWork(2);
        storage.addWorker(workerDeclaration(worker));
        WorkerManager storageBackedManager = new WorkerManager(storage, new InMemoryWorkerRegistry());

        assertEquals(2, storageBackedManager.getWorkerLoad("worker-storage-direct").declaredCapacity());
        assertFalse(storageBackedManager.reserveWorkerCapacity(
                admissionTarget("us", "worker-storage-direct", "task-1")).accepted());
    }

    @Test
    void getAllWorkersReturnsAllAdded() {
        addWorker(worker("a", "us"));
        addWorker(worker("b", "gb"));
        addWorker(worker("c", "us"));
        assertEquals(3, manager.workers().size());
    }

    @Test
    void findWorkerCandidatesUsesExplicitGroupSelectorForNonEventTasks() {
        declareProjectGroup("pool-a", "demoApp");
        declareProjectGroup("pool-b", "testApp");
        Worker demoWorker = worker("w-demo", "pool-a");
        Worker otherWorker = worker("w-other", "pool-b");
        addWorker(demoWorker);
        addWorker(otherWorker);

        Task task = task("demoApp", selector("pool-a"));

        assertEquals(List.of("w-demo"),
                candidateIds(task));
    }

    @Test
    void findWorkerCandidatesUsesExplicitGroupSelectorForSdkEventTasks() {
        declareEventGroup("pool-a", "demoApp", "demo.dispatch");
        declareEventGroup("pool-b", "demoApp", "other.event");
        Worker eventWorker = worker("w-event", "pool-a");
        Worker projectOnlyWorker = worker("w-project", "pool-b");
        addWorker(eventWorker);
        addWorker(projectOnlyWorker);

        Task task = task("demoApp", sharedConfig(
                Map.of(TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")),
                "pool-a"));

        assertEquals(List.of("w-event"),
                candidateIds(task));
    }

    @Test
    void findWorkerCandidatesUsesTargetWorkerWithGroupCapabilityGate() {
        declareEventGroup("pool-a", "testApp", "other.event");
        declareEventGroup("pool-b", "demoApp", "demo.dispatch");
        Worker targetWorker = worker("w-target", "pool-a");
        Worker indexedWorker = worker("w-indexed", "pool-b");
        addWorker(targetWorker);
        addWorker(indexedWorker);

        Task task = task("demoApp", Map.of(
                TaskSharedConfig.TARGET_WORKER_ID, "w-target",
                TaskSharedConfig.WORKER_GROUP_ID, "pool-b",
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")
        ));

        assertTrue(candidateRows(task).isEmpty());

        Task supportedTargetTask = task("testApp", Map.of(
                TaskSharedConfig.TARGET_WORKER_ID, "w-target",
                TaskSharedConfig.WORKER_GROUP_ID, "pool-a",
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "other.event")
        ));

        assertEquals(List.of("w-target"),
                candidateIds(supportedTargetTask));
    }

    @Test
    void findWorkerCandidatesDoesNotFallbackWhenTargetWorkerIsMissing() {
        declareEventGroup("pool-b", "demoApp", "demo.dispatch");
        Worker indexedWorker = worker("w-indexed", "pool-b");
        addWorker(indexedWorker);

        Task task = task("demoApp", Map.of(
                TaskSharedConfig.TARGET_WORKER_ID, "missing-worker",
                TaskSharedConfig.WORKER_GROUP_ID, "pool-b",
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")
        ));

        assertTrue(candidateRows(task).isEmpty());
    }

    @Test
    void findWorkerCandidatesDoesNotFallbackToAllWorkersWithoutSelector() {
        addWorker(worker("w-a", "pool-a"));
        addWorker(worker("w-b", "pool-b"));

        Task task = task(null, Map.of());

        assertTrue(candidateRows(task).isEmpty());
    }

    @Test
    void updateWorkerRefreshesCandidateIndexes() {
        declareProjectGroup("pool-a", "demoApp");
        declareProjectGroup("pool-b", "testApp");
        Worker worker = worker("w-reindex", "pool-a");
        addWorker(worker);

        Worker updated = worker("w-reindex", "pool-b");
        assertTrue(updateWorker(updated));

        assertTrue(candidateRows(task("demoApp", selector("pool-a"))).isEmpty());
        assertEquals(List.of("w-reindex"),
                candidateIds(task("testApp", selector("pool-b"))));
    }

    @Test
    void updateWorkerRefreshesCandidateBucketMembership() {
        declareProjectGroup("pool-a", "demoApp");
        Worker worker = worker("w-route-reindex", "pool-a");
        worker.setAttributes(Map.of("region", "us"));
        addWorker(worker);

        assertEquals(List.of("w-route-reindex"),
                candidateIds(task("demoApp", sharedConfig(
                        Map.of(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "us")),
                        "pool-a"))));

        Worker updated = worker("w-route-reindex", "pool-a");
        updated.setAttributes(Map.of("region", "eu"));
        assertTrue(updateWorker(updated));

        assertTrue(candidateRows(task("demoApp", sharedConfig(
                Map.of(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "us")),
                "pool-a"))).isEmpty());
        assertEquals(List.of("w-route-reindex"),
                candidateIds(task("demoApp", sharedConfig(
                        Map.of(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "eu")),
                        "pool-a"))));
    }

    @Test
    void updateWorkerRefreshesCandidateIndexesAfterInPlaceMutation() {
        declareEventGroup("pool-a", "demoApp", "demo.dispatch");
        declareProjectGroup("pool-b", "testApp");
        Worker worker = worker("w-mutable-reindex", "pool-a");
        addWorker(worker);

        Worker stored = workerModel("w-mutable-reindex");
        stored.setWorkerGroupId("pool-b");
        assertTrue(updateWorker(stored));

        assertTrue(candidateRows(task("demoApp", selector("pool-a"))).isEmpty());
        assertTrue(candidateRows(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")), "pool-a"))).isEmpty());
        assertEquals(List.of("w-mutable-reindex"),
                candidateIds(task("testApp", selector("pool-b"))));
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
        addWorker(worker);

        assertEquals(List.of("w-indexed-group"),
                candidateIndexIds(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler"))));
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
                new TestWorkerDeclarationStore(),
                null,
                registry
        );
        registryBackedManager.upsertWorkerGroup(WorkerGroupRecord.builder("pool-a")
                .projectCodes(List.of("demoApp"))
                .build());
        addWorker(registryBackedManager, worker("w-snapshot-visible", "pool-a"));
        addWorker(registryBackedManager, worker("w-registry-selected", "pool-a"));

        assertEquals(List.of("w-registry-selected"),
                candidateIds(registryBackedManager, task("demoApp", selector("pool-a")), 10));
    }

    @Test
    void findWorkerCandidatesUsesBoundedSamplingBeforeWorkerRowsAreMaterialized() {
        InMemoryWorkerRegistry registry = new InMemoryWorkerRegistry(
                new RandomWorkerCandidateSamplingPolicy(bound -> bound - 1)
        );
        WorkerManager registryBackedManager = new WorkerManager(
                new TestWorkerDeclarationStore(),
                null,
                registry
        );
        registryBackedManager.upsertWorkerGroup(WorkerGroupRecord.builder("pool-a")
                .projectCodes(List.of("demoApp"))
                .build());
        for (int index = 0; index < 10; index++) {
            addWorker(registryBackedManager, worker("w-sampled-" + index, "pool-a"));
        }

        assertEquals(List.of("w-sampled-7", "w-sampled-8", "w-sampled-9"),
                candidateIds(registryBackedManager, task("demoApp", selector("pool-a")), 3));
    }

    @Test
    void workerRegistrySnapshotPublicationSwapsPointInTimeSnapshotReference() {
        WorkerRegistrySnapshot before = manager.getWorkerRegistrySnapshot();
        declareEventGroup("crawler", "demoApp", "crawler.fetch");
        declareEventGroup("export", "testApp", "report.export");

        Worker worker = worker("w-published-snapshot", "crawler");
        addWorker(worker);
        WorkerRegistrySnapshot afterAdd = manager.getWorkerRegistrySnapshot();

        assertNotSame(before, afterAdd);
        assertTrue(before.workers().isEmpty());
        assertTrue(before.group("crawler").isEmpty());
        assertEquals(List.of("w-published-snapshot"),
                afterAdd.workers().stream().map(WorkerDeclarationRecord::workerId).toList());

        Worker updated = worker("w-published-snapshot", "export");
        assertTrue(updateWorker(updated));
        WorkerRegistrySnapshot afterUpdate = manager.getWorkerRegistrySnapshot();

        assertNotSame(afterAdd, afterUpdate);
        assertTrue(afterAdd.group("crawler").isPresent());
        assertTrue(afterAdd.group("export").isPresent());
        assertEquals("crawler", afterAdd.worker("w-published-snapshot").orElseThrow().workerGroupId());
        assertTrue(afterUpdate.group("crawler").isPresent());
        assertTrue(afterUpdate.group("export").isPresent());
        assertEquals("export", afterUpdate.worker("w-published-snapshot").orElseThrow().workerGroupId());
    }

    @Test
    void updateWorkerRefreshesWorkerRegistrySnapshotCapability() {
        declareEventGroup("crawler", "demoApp", "crawler.fetch");
        declareEventGroup("parser", "testApp", "crawler.parse");
        Worker worker = worker("w-snapshot-update", "crawler");
        addWorker(worker);

        Worker updated = worker("w-snapshot-update", "parser");
        assertTrue(updateWorker(updated));

        assertTrue(manager.getWorkerCandidateIndex()
                .workersFor(workerTaskSelector(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler"))))
                .isEmpty());
        assertEquals(List.of("w-snapshot-update"),
                candidateIndexIds(task("testApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.parse")), "parser"))));
    }

    @Test
    void workerCapabilityReportRefreshesCandidateIndexThroughPublishedSnapshot() {
        declareEventGroup("crawler", "demoApp", "crawler.fetch");
        Worker worker = worker("w-report-capability", "crawler");
        addWorker(worker);

        WorkerCapabilityReportResult result = manager.applyWorkerCapabilityReport(
                WorkerCapabilityReport.builder("w-report-capability", 1)
                        .availableEventCodes(List.of("crawler.parse"))
                        .build()
        );

        assertEquals(WorkerCapabilityReportStatus.ACCEPTED, result.status());
        assertEquals(List.of("w-report-capability"), candidateIndexIds(task("demoApp", sharedConfig(Map.of(
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler"))));
        assertEquals(List.of("w-report-capability"), candidateIndexIds(task("demoApp", sharedConfig(Map.of(
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.parse")), "crawler"))));
        assertFalse(manager.getWorkerRegistrySnapshot().workerSupportsEventKey(
                "w-report-capability",
                new EventKey("demoApp", "crawler.parse")
        ));
    }

    @Test
    void deleteWorkerRefreshesWorkerRegistrySnapshot() {
        declareEventGroup("crawler", "demoApp", "crawler.fetch");
        Worker worker = worker("w-snapshot-delete", "crawler");
        addWorker(worker);

        assertTrue(manager.deleteWorker("w-snapshot-delete"));

        assertTrue(manager.getWorkerCandidateIndex()
                .workersFor(workerTaskSelector(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler"))))
                .isEmpty());
    }

    @Test
    void directStorageSnapshotRefreshDoesNotCreateLifecycleEligibleCandidate() {
        TestWorkerDeclarationStore storage = new TestWorkerDeclarationStore();
        WorkerManager storageBackedManager = new WorkerManager(storage, new InMemoryWorkerRegistry());
        storageBackedManager.upsertWorkerGroup(WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .build());
        Worker worker = worker("w-storage-direct-snapshot", "crawler");
        storage.addWorker(workerDeclaration(worker));

        assertTrue(storageBackedManager.getWorkerCandidateIndex()
                .workersFor(workerTaskSelector(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                        Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler"))))
                .isEmpty());

        storageBackedManager.refreshWorkerRegistrySnapshot();

        assertEquals("crawler", storageBackedManager.getWorkerRegistrySnapshot()
                .worker("w-storage-direct-snapshot")
                .orElseThrow()
                .workerGroupId());
        assertTrue(candidateIndexIds(storageBackedManager, task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "crawler.fetch")), "crawler"))).isEmpty());
    }


    @Test
    void deleteWorkerRemovesCandidateIndexes() {
        declareEventGroup("pool-a", "demoApp", "demo.dispatch");
        Worker worker = worker("w-delete-index", "pool-a");
        worker.setAttributes(Map.of("region", "us"));
        addWorker(worker);

        assertTrue(manager.deleteWorker("w-delete-index"));

        assertTrue(candidateRows(task("demoApp", selector("pool-a"))).isEmpty());
        assertTrue(candidateRows(task("demoApp", sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")), "pool-a"))).isEmpty());
        assertTrue(candidateRows(task("demoApp", sharedConfig(
                Map.of(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "us")),
                "pool-a"))).isEmpty());
    }

    // ---- update / delete ----

    @Test
    void updateWorkerReturnsTrue() {
        Worker w = worker("w2", "us");
        addWorker(w);
        w.setStatus(WorkerStatus.OFFLINE);
        assertTrue(updateWorker(w));
    }

    @Test
    void deleteWorkerRemovesIt() {
        addWorker(worker("w3", "us"));
        assertTrue(manager.deleteWorker("w3"));
        assertNull(workerModel("w3"));
    }

    @Test
    void deleteNonexistentWorkerReturnsFalse() {
        assertFalse(manager.deleteWorker("ghost"));
    }

    // ---- lock ----

    @Test
    void lockAndUnlockWorker() {
        addWorker(worker("w6", "us"));
        assertTrue(manager.tryAcquireWorkerExclusiveLease("w6"));
        assertTrue(manager.hasWorkerExclusiveLease("w6"));
        assertEquals(List.of("w6"), manager.getExclusiveLeaseWorkerIds());

        manager.releaseWorkerExclusiveLease("w6");
        assertFalse(manager.hasWorkerExclusiveLease("w6"));
        assertTrue(manager.getExclusiveLeaseWorkerIds().isEmpty());
    }

    @Test
    void lockAlreadyLockedWorkerReturnsFalse() {
        addWorker(worker("w7", "us"));
        assertTrue(manager.tryAcquireWorkerExclusiveLease("w7"));
        assertFalse(manager.tryAcquireWorkerExclusiveLease("w7"));
    }

    // ---- worker model status vs reachability diagnostics ----

    @Test
    void workerReachabilityComesFromInjectedPointReadInsteadOfWorkerModelStatus() {
        WorkerManager reachabilityAwareManager = new WorkerManager(
                new TestWorkerDeclarationStore(),
                workerId -> switch (workerId) {
                    case "w-online" -> WorkerReachabilityState.ONLINE;
                    case "w-stale" -> WorkerReachabilityState.STALE;
                    case "w-offline" -> WorkerReachabilityState.OFFLINE;
                    default -> WorkerReachabilityState.UNKNOWN;
                },
                new InMemoryWorkerRegistry()
        );
        Worker onlineModelWorker = worker("w-online", "us");
        onlineModelWorker.setStatus(WorkerStatus.ONLINE);
        Worker staleModelWorker = worker("w-stale", "us");
        staleModelWorker.setStatus(WorkerStatus.ONLINE);
        Worker offlineModelWorker = worker("w-offline", "us");
        offlineModelWorker.setStatus(WorkerStatus.ONLINE);
        addWorker(reachabilityAwareManager, onlineModelWorker);
        addWorker(reachabilityAwareManager, staleModelWorker);
        addWorker(reachabilityAwareManager, offlineModelWorker);

        assertEquals(WorkerReachabilityState.ONLINE, reachabilityAwareManager.getWorkerReachability("w-online"));
        assertEquals(WorkerReachabilityState.STALE, reachabilityAwareManager.getWorkerReachability("w-stale"));
        assertEquals(WorkerReachabilityState.OFFLINE, reachabilityAwareManager.getWorkerReachability("w-offline"));
        assertEquals(WorkerReachabilityState.UNKNOWN, reachabilityAwareManager.getWorkerReachability("missing"));

        // Default worker lookup no longer exposes runtime reachability/status evidence.
        assertEquals(WorkerStatus.OFFLINE, workerModel(reachabilityAwareManager, "w-stale").getStatus());
        assertTrue(reachabilityAwareManager.isWorkerDispatchEnabled(staleModelWorker.getWorkerId()));
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
        w.setLastHeartbeat(LocalDateTime.now());
        return w;
    }

    private void addWorker(Worker worker) {
        addWorker(manager, worker);
    }

    private static void addWorker(WorkerManager workerManager, Worker worker) {
        workerManager.addWorker(workerDeclaration(worker));
        refreshHeartbeatEvidence(workerManager, worker);
    }

    private boolean updateWorker(Worker worker) {
        boolean updated = manager.updateWorker(workerDeclaration(worker));
        if (updated) {
            refreshHeartbeatEvidence(manager, worker);
        }
        return updated;
    }

    private Worker workerModel(String workerId) {
        return workerModel(manager, workerId);
    }

    private static Worker workerModel(WorkerManager workerManager, String workerId) {
        return toWorker(workerManager.worker(workerId).orElse(null));
    }

    private static WorkerDeclarationRecord workerDeclaration(Worker worker) {
        return new WorkerDeclarationRecord(
                worker.getWorkerId(),
                worker.getWorkerGroupId(),
                worker.getOnlineStrategy(),
                worker.getAgentVersion(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes()
        );
    }

    private static void refreshHeartbeatEvidence(WorkerManager workerManager, Worker worker) {
        if (worker == null || worker.getLastHeartbeat() == null) {
            return;
        }
        long observedAtMillis = worker.getLastHeartbeat()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        workerManager.refreshWorkerHeartbeat(worker.getWorkerId(), observedAtMillis);
    }

    private static Worker toWorker(WorkerResourceRecord record) {
        if (record == null) {
            return null;
        }
        Worker worker = new Worker();
        worker.setWorkerId(record.workerId());
        worker.setAgentVersion(record.agentVersion());
        worker.setWorkerGroupId(record.workerGroupId());
        worker.setOnlineStrategy(record.transportHint());
        worker.setMaxConcurrentWork(record.maxConcurrentWork());
        worker.setAttributes(record.attributes());
        return worker;
    }

    private Task task(String project, Map<String, Object> sharedConfig) {
        Task task = new Task();
        task.setTid("task-" + project);
        task.setProject(project);
        task.setSharedConfig(sharedConfig);
        return task;
    }

    private List<WorkerCandidateRow> candidateRows(Task task) {
        return manager.findWorkerCandidates(workerTaskSelector(task), 512);
    }

    private List<String> candidateIds(Task task) {
        return candidateIds(manager, task, 512);
    }

    private List<String> candidateIds(WorkerManager workerManager, Task task, int maxCandidateCount) {
        return workerManager.findWorkerCandidates(workerTaskSelector(task), maxCandidateCount).stream()
                .map(WorkerCandidateRow::workerId)
                .toList();
    }

    private List<String> candidateIndexIds(Task task) {
        return candidateIndexIds(manager, task);
    }

    private List<String> candidateIndexIds(WorkerManager workerManager, Task task) {
        return workerManager.getWorkerCandidateIndex()
                .workersFor(workerTaskSelector(task))
                .stream()
                .map(WorkerDeclarationRecord::workerId)
                .toList();
    }

    private static WorkerTaskSelector workerTaskSelector(Task task) {
        return new WorkerTaskSelector(
                task == null ? null : task.getTid(),
                TaskSharedConfig.workerGroupSelector(task),
                TaskSharedConfig.targetWorkerId(task),
                java.util.Set.of(WorkerCandidateBucketPolicies.approvedAttributePolicy(
                                WorkerCandidateBucketPolicies.STANDARD_APPROVED_ROUTE_ATTRIBUTES)
                        .exactCandidateBucketKeyForAttributes(TaskSharedConfig.routeAttributes(task)))
        );
    }

    private static WorkerAdmissionTarget admissionTarget(String groupId, String workerId, String taskId) {
        return WorkerAdmissionTarget.groupScoped(groupId, workerId, taskId);
    }

    private static WorkerDispatchBlockSignal blockSignal(WorkerDispatchBlockSource source,
                                                         String reason,
                                                         long observedAtMillis) {
        return new WorkerDispatchBlockSignal(source, reason, observedAtMillis, 0L);
    }

    private static InMemoryWorkerRegistry platformRegistry() {
        return new InMemoryWorkerRegistry(
                WorkerCandidateBucketPolicies.defaultPolicy(),
                RandomWorkerCandidateSamplingPolicy.defaultPolicy()
        );
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

    private static final class Worker {
        private String workerId;
        private WorkerStatus status = WorkerStatus.OFFLINE;
        private String agentVersion;
        private LocalDateTime lastHeartbeat;
        private String workerGroupId;
        private String adapterNodeId;
        private String adapterId;
        private String onlineStrategy;
        private int maxConcurrentWork = 1;
        private Map<String, String> attributes = Map.of();
        @SuppressWarnings("unused")
        private List<String> supportedProjects = List.of();
        @SuppressWarnings("unused")
        private List<String> supportedEventCodes = List.of();

        String getWorkerId() {
            return workerId;
        }

        void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        WorkerStatus getStatus() {
            return status;
        }

        void setStatus(WorkerStatus status) {
            this.status = status;
        }

        String getAgentVersion() {
            return agentVersion;
        }

        void setAgentVersion(String agentVersion) {
            this.agentVersion = agentVersion;
        }

        LocalDateTime getLastHeartbeat() {
            return lastHeartbeat;
        }

        void setLastHeartbeat(LocalDateTime lastHeartbeat) {
            this.lastHeartbeat = lastHeartbeat;
        }

        String getWorkerGroupId() {
            return workerGroupId;
        }

        void setWorkerGroupId(String workerGroupId) {
            this.workerGroupId = workerGroupId;
        }

        String getAdapterNodeId() {
            return adapterNodeId;
        }

        void setAdapterNodeId(String adapterNodeId) {
            this.adapterNodeId = adapterNodeId;
        }

        @SuppressWarnings("unused")
        String getAdapterId() {
            return adapterId;
        }

        void setAdapterId(String adapterId) {
            this.adapterId = adapterId;
        }

        String getOnlineStrategy() {
            return onlineStrategy;
        }

        void setOnlineStrategy(String onlineStrategy) {
            this.onlineStrategy = onlineStrategy;
        }

        int getMaxConcurrentWork() {
            return Math.max(1, maxConcurrentWork);
        }

        void setMaxConcurrentWork(int maxConcurrentWork) {
            this.maxConcurrentWork = Math.max(1, maxConcurrentWork);
        }

        Map<String, String> getAttributes() {
            return attributes;
        }

        void setAttributes(Map<String, String> attributes) {
            this.attributes = attributes == null || attributes.isEmpty()
                    ? Map.of()
                    : Map.copyOf(attributes);
        }

        void setSupportedProjects(List<String> supportedProjects) {
            this.supportedProjects = supportedProjects == null ? List.of() : List.copyOf(supportedProjects);
        }

        void setSupportedEventCodes(List<String> supportedEventCodes) {
            this.supportedEventCodes = supportedEventCodes == null ? List.of() : List.copyOf(supportedEventCodes);
        }
    }
}
