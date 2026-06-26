package com.xa.mass.worker.runtime;

import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.memory.InMemoryWorkerScoreBandSlotRuntime;
import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.runtime.worker.ReserveStatus;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandAcquireRequest;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlot;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSignal;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSource;
import com.xa.mass.worker.runtime.control.WorkerDispatchRecoveryMode;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.resource.AdapterNodeRecord;
import com.xa.mass.worker.runtime.resource.EventBinding;
import com.xa.mass.worker.runtime.resource.NodeGroupBindingRecord;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import com.xa.mass.worker.runtime.selection.WorkerSelectionIntent;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRequest;
import com.xa.mass.worker.runtime.selection.WorkerSelectionResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.TRANSPORT_DISCONNECTED;
import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerManagerTest {

    private TestWorkerDeclarationStore workerDeclarationStore;
    private InMemoryWorkerRegistry workerRegistry;
    private InMemoryWorkerScoreBandSlotRuntime scoreBandRuntime;
    private WorkerManager manager;

    @BeforeEach
    void setUp() {
        workerDeclarationStore = new TestWorkerDeclarationStore();
        workerRegistry = new InMemoryWorkerRegistry();
        scoreBandRuntime = new InMemoryWorkerScoreBandSlotRuntime();
        manager = new WorkerManager(workerDeclarationStore, workerRegistry, scoreBandRuntime);
    }

    @Test
    void addAndRetrieveWorker() {
        manager.addWorker(worker("w1", "group-a"));

        WorkerResourceRecord found = manager.worker("w1").orElse(null);
        assertNotNull(found);
        assertEquals("w1", found.workerId());
        assertEquals("group-a", found.workerGroupId());
    }

    @Test
    void addWorkerDoesNotPersistSyntheticHeartbeat() {
        manager.addWorker(worker("w-no-heartbeat", "group-a"));

        assertEquals(0L, workerRegistry.slot("group-a", "w-no-heartbeat")
                .orElseThrow()
                .meta()
                .lastHeartbeatMillis());
    }

    @Test
    void declaredWorkerGroupOwnsCapabilityTruth() {
        WorkerGroupRecord group = WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .defaultAttributes(Map.of("source", "declared"))
                .defaultMaxConcurrentWork(3)
                .build();

        manager.upsertWorkerGroup(group);

        assertEquals(group, manager.workerGroup("crawler").orElseThrow());
        assertEquals(Set.of("crawler"), manager.getWorkerRegistrySnapshot()
                .groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")));
    }

    @Test
    void workerDeclarationProjectsRegistryAndScoreBandSlot() {
        manager.addWorker(worker("worker-1", "group-a", 3, Map.of("routingTag", "lane-a")));

        assertEquals(3, manager.getWorkerLoad("worker-1").declaredCapacity());
        WorkerScoreBandSlot slot = scoreBandRuntime.slot("group-a", "worker-1").orElseThrow();
        assertEquals("worker-1", slot.workerId());
        assertEquals("group-a", slot.homeBucketId());
        assertEquals("lane-a", slot.metadata().attributes().get("routingTag"));
    }

    @Test
    void workerGroupUpdateMovesScoreBandHomeBucket() {
        manager.addWorker(worker("worker-1", "group-a"));

        manager.updateWorker(worker("worker-1", "group-b"));

        assertTrue(scoreBandRuntime.slot("group-a", "worker-1").isEmpty());
        assertTrue(scoreBandRuntime.slot("group-b", "worker-1").isPresent());
        assertTrue(workerRegistry.workerIdsByGroupId("group-a").isEmpty());
        assertEquals(Set.of("worker-1"), workerRegistry.workerIdsByGroupId("group-b"));
    }

    @Test
    void deleteWorkerRemovesScoreBandSlotAndMarksRegistrySlotRemoving() {
        manager.addWorker(worker("worker-1", "group-a"));

        assertTrue(manager.deleteWorker("worker-1"));

        assertTrue(scoreBandRuntime.slot("group-a", "worker-1").isEmpty());
        assertEquals(ReserveStatus.REMOVING_SLOT,
                workerRegistry.slotLifecycleStatus("group-a", "worker-1", System.currentTimeMillis()));
    }

    @Test
    void heartbeatRefreshUpdatesRegistryEvidenceButNotDeclarationTruth() {
        manager.addWorker(worker("worker-1", "group-a"));

        assertTrue(manager.refreshWorkerHeartbeat("worker-1", 1234L));

        assertEquals(1234L, workerRegistry.slot("group-a", "worker-1").orElseThrow().meta().lastHeartbeatMillis());
        assertEquals("agent-1", manager.worker("worker-1").orElseThrow().agentVersion());
    }

    @Test
    void selectAndReserveUsesScoreBandAndClaimCloseReopensSlot() {
        manager.upsertWorkerGroup(WorkerGroupRecord.builder("group-a")
                .eventBindings(List.of(EventBinding.of("event-a", List.of("project-a"))))
                .build());
        manager.addWorker(worker("worker-1", "group-a", 1, Map.of("routingTag", "lane-a")));
        manager.refreshWorkerHeartbeat("worker-1", System.currentTimeMillis());

        WorkerSelectionResult result = manager.selectAndReserve(new WorkerSelectionRequest(
                "scope-1",
                new WorkerSelectionIntent(
                        "project-a",
                        "event-a",
                        List.of("group-a"),
                        "lane-a",
                        Map.of(),
                        null,
                        Map.of()),
                1,
                false));

        assertEquals(1, result.selectedCount());
        SelectedWorkerHandle handle = result.selectedWorkers().get(0);
        assertEquals("worker-1", handle.workerId());
        assertTrue(scoreBandRuntime.acquire(WorkerScoreBandAcquireRequest.inHomeBucket(
                "group-a",
                1,
                System.currentTimeMillis()
        )).isEmpty());

        manager.releaseSelected(handle);

        assertEquals(List.of("worker-1"), scoreBandRuntime.acquire(WorkerScoreBandAcquireRequest.inHomeBucket(
                        "group-a",
                        1,
                        System.currentTimeMillis())
                ).stream()
                .map(WorkerScoreBandSlot::workerId)
                .toList());
    }

    @Test
    void selectionConsumesReachabilityThroughDispatchEligibility() {
        WorkerManager reachabilityAwareManager = new WorkerManager(
                new TestWorkerDeclarationStore(),
                workerId -> "worker-online".equals(workerId)
                        ? WorkerReachabilityState.ONLINE
                        : WorkerReachabilityState.OFFLINE,
                new InMemoryWorkerRegistry(),
                new InMemoryWorkerScoreBandSlotRuntime()
        );
        reachabilityAwareManager.upsertWorkerGroup(WorkerGroupRecord.builder("group-a")
                .eventBindings(List.of(EventBinding.of("event-a", List.of("project-a"))))
                .build());
        reachabilityAwareManager.addWorker(worker("worker-online", "group-a", 1, Map.of()));
        reachabilityAwareManager.addWorker(worker("worker-offline", "group-a", 1, Map.of()));

        assertTrue(reachabilityAwareManager.isWorkerDispatchEnabled("worker-online"));
        assertFalse(reachabilityAwareManager.isWorkerDispatchEnabled("worker-offline"));

        WorkerSelectionResult result = reachabilityAwareManager.selectAndReserve(new WorkerSelectionRequest(
                "scope-1",
                new WorkerSelectionIntent(
                        "project-a",
                        "event-a",
                        List.of("group-a"),
                        null,
                        Map.of(),
                        null,
                        Map.of()),
                2,
                false));

        assertEquals(List.of("worker-online"), result.selectedWorkers().stream()
                .map(SelectedWorkerHandle::workerId)
                .toList());
        assertEquals(1, result.rejectedCountByReason().get("worker dispatch disabled"));
    }

    @Test
    void negativeBlockSignalDisablesDispatchAndRejectsStaleSignal() {
        manager.addWorker(worker("worker-1", "group-a"));

        assertTrue(manager.blockWorkerDispatch("group-a", "worker-1",
                blockSignal("current", 2_000L)));
        assertFalse(manager.isWorkerDispatchEnabled("worker-1"));
        assertEquals("current", manager.dispatchBlockRecord("group-a", "worker-1", TRANSPORT_DISCONNECTED)
                .orElseThrow()
                .reason());

        assertTrue(manager.clearWorkerDispatchDisable("worker-1", TRANSPORT_DISCONNECTED, "test"));
        assertTrue(manager.isWorkerDispatchEnabled("worker-1"));
        assertFalse(manager.blockWorkerDispatch("group-a", "worker-1",
                blockSignal("stale", 1_000L)));
        assertTrue(manager.isWorkerDispatchEnabled("worker-1"));
    }

    @Test
    void recoveryModeControlsTransportFreshnessRecovery() {
        manager.addWorker(worker("explicit", "group-a"));
        manager.addWorker(worker("freshness", "group-a", 1,
                Map.of(WorkerDispatchRecoveryMode.ATTRIBUTE_KEY, "FRESHNESS_EVIDENCE")));
        manager.blockWorkerDispatch("group-a", "explicit", blockSignal("disconnect", 1_000L));
        manager.blockWorkerDispatch("group-a", "freshness", blockSignal("disconnect", 1_000L));

        assertFalse(manager.recoverWorkerDispatch("explicit", TRANSPORT_DISCONNECTED, "freshness"));
        assertTrue(manager.recoverWorkerDispatch("freshness", TRANSPORT_DISCONNECTED, "freshness"));
        assertFalse(manager.isWorkerDispatchEnabled("explicit"));
        assertTrue(manager.isWorkerDispatchEnabled("freshness"));
    }

    @Test
    void dispatchClearAndRecoveryNotifyWakeupWhenEligibilityCanOpen() {
        manager.addWorker(worker("freshness", "group-a", 1,
                Map.of(WorkerDispatchRecoveryMode.ATTRIBUTE_KEY, "FRESHNESS_EVIDENCE")));
        manager.blockWorkerDispatch("group-a", "freshness", blockSignal("disconnect", 1_000L));

        AtomicInteger wakeups = new AtomicInteger();
        manager.setDispatchWakeupCallback(wakeups::incrementAndGet);
        assertEquals(0, wakeups.get());

        assertTrue(manager.clearWorkerDispatchDisable("freshness", TRANSPORT_DISCONNECTED, "manual clear"));
        assertTrue(manager.isWorkerDispatchEnabled("freshness"));
        assertEquals(1, wakeups.get());

        manager.blockWorkerDispatch("group-a", "freshness", blockSignal("disconnect", 2_000L));
        assertTrue(manager.recoverWorkerDispatch("freshness", TRANSPORT_DISCONNECTED, "current session connected"));
        assertTrue(manager.isWorkerDispatchEnabled("freshness"));
        assertEquals(2, wakeups.get());
    }

    @Test
    void controlledRecoverySourcesDoNotRequireFreshnessMode() {
        manager.addWorker(worker("worker-1", "group-a"));
        manager.disableWorkerDispatch("worker-1", WORKER_STATE, "state");

        assertTrue(manager.recoverWorkerDispatch("worker-1", WORKER_STATE, "available"));
        assertTrue(manager.isWorkerDispatchEnabled("worker-1"));
    }

    @Test
    void exclusiveLeaseRoundTripUsesNarrowAdmissionSurface() {
        manager.addWorker(worker("worker-1", "group-a"));

        assertTrue(manager.tryAcquireWorkerExclusiveLease("worker-1"));
        assertTrue(manager.hasWorkerExclusiveLease("worker-1"));
        assertEquals(List.of("worker-1"), manager.getExclusiveLeaseWorkerIds());

        manager.releaseWorkerExclusiveLease("worker-1");
        assertFalse(manager.hasWorkerExclusiveLease("worker-1"));
    }

    @Test
    void nodeGroupBindingStateIsTopologyMetadataNotDispatchEligibility() {
        manager.upsertWorkerGroup(WorkerGroupRecord.builder("group-a")
                .projectCodes(List.of("project-a"))
                .build());
        manager.addWorker(worker("worker-1", "group-a"));
        manager.registerAdapterNode(adapterNode("adapter-1"));
        manager.bindNodeGroup(nodeGroupBinding("adapter-1", "group-a"));

        manager.setNodeGroupBindingDraining("adapter-1", "group-a", true);

        assertTrue(manager.isWorkerDispatchEnabled("worker-1"));
        assertTrue(manager.nodeGroupBinding("adapter-1", "group-a").orElseThrow().draining());
    }

    @Test
    void relationshipChangesDoNotWakeDispatchPump() {
        AtomicInteger wakeups = new AtomicInteger();
        manager.setDispatchWakeupCallback(wakeups::incrementAndGet);
        manager.upsertWorkerGroup(WorkerGroupRecord.builder("group-a")
                .projectCodes(List.of("project-a"))
                .build());
        manager.registerAdapterNode(adapterNode("adapter-1"));
        manager.bindNodeGroup(nodeGroupBinding("adapter-1", "group-a"));
        int afterInitialDeclarations = wakeups.get();

        manager.setNodeGroupBindingEnabled("adapter-1", "group-a", false);
        manager.setNodeGroupBindingDraining("adapter-1", "group-a", true);
        manager.unbindNodeGroup("adapter-1", "group-a");

        assertEquals(afterInitialDeclarations, wakeups.get());
    }

    private static WorkerDeclarationRecord worker(String workerId, String groupId) {
        return worker(workerId, groupId, 1, Map.of());
    }

    private static WorkerDeclarationRecord worker(String workerId,
                                                  String groupId,
                                                  int maxConcurrentWork,
                                                  Map<String, String> attributes) {
        return new WorkerDeclarationRecord(
                workerId,
                groupId,
                "polling",
                "agent-1",
                maxConcurrentWork,
                attributes
        );
    }

    private static WorkerDispatchBlockSignal blockSignal(String reason, long observedAtMillis) {
        return new WorkerDispatchBlockSignal(
                WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED,
                reason,
                observedAtMillis,
                0L
        );
    }

    private static AdapterNodeRecord adapterNode(String adapterNodeId) {
        return new AdapterNodeRecord(
                adapterNodeId,
                "socket",
                "1.0.0",
                "node-1",
                true,
                true,
                null,
                null,
                Map.of()
        );
    }

    private static NodeGroupBindingRecord nodeGroupBinding(String adapterNodeId, String groupId) {
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
}
