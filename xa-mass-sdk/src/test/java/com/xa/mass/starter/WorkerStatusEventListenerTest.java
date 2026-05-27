package com.xa.mass.starter;

import com.xa.mass.base.channel.eventbus.event.worker.WorkerHeartbeatEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.runtime.worker.AdapterNodeRecord;
import com.xa.mass.runtime.worker.NodeGroupBindingRecord;
import com.xa.mass.runtime.worker.WorkerGroupRecord;
import com.xa.mass.runtime.worker.WorkerResourceRecord;
import com.xa.mass.runtime.worker.WorkerResourceRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkerStatusEventListenerTest {

    @Test
    void workerOnlineEventRefreshesHeartbeatAndLeavesModelStatusUntouched() {
        AtomicInteger wakeups = new AtomicInteger();
        FakeWorkerResourceRuntime runtime = new FakeWorkerResourceRuntime();
        WorkerStatusEventListener listener = new WorkerStatusEventListener(runtime, wakeups::incrementAndGet);
        runtime.addWorker(worker("w9", WorkerStatus.OFFLINE));

        listener.onWorkerOnline(new WorkerOnlineEvent("w9", "connected", null));
        WorkerResourceRecord afterOnline = runtime.worker("w9").orElseThrow();
        assertNotNull(afterOnline.lastHeartbeat());
        assertEquals(WorkerStatus.OFFLINE.name(), afterOnline.statusName());
        assertEquals(1, wakeups.get());

        listener.onWorkerOffline(new WorkerOfflineEvent("w9", "disconnected", null));
        assertEquals(WorkerStatus.OFFLINE.name(), runtime.worker("w9").orElseThrow().statusName());
        assertEquals(1, wakeups.get());
    }

    @Test
    void workerHeartbeatEventRefreshesLastHeartbeatWithoutChangingWorkerModelAvailability() {
        AtomicInteger wakeups = new AtomicInteger();
        FakeWorkerResourceRuntime runtime = new FakeWorkerResourceRuntime();
        WorkerStatusEventListener listener = new WorkerStatusEventListener(runtime, wakeups::incrementAndGet);
        runtime.addWorker(worker("w10", WorkerStatus.OFFLINE));

        listener.onWorkerHeartbeat(new WorkerHeartbeatEvent("w10", "heartbeat", null));

        WorkerResourceRecord afterHeartbeat = runtime.worker("w10").orElseThrow();
        assertNotNull(afterHeartbeat.lastHeartbeat());
        assertEquals(WorkerStatus.OFFLINE.name(), afterHeartbeat.statusName());
        assertEquals(0, wakeups.get());
    }

    private static WorkerResourceRecord worker(String workerId, WorkerStatus status) {
        return new WorkerResourceRecord(
                workerId,
                status.name(),
                "test",
                null,
                List.of("demoApp"),
                List.of(),
                "group",
                null,
                null,
                null,
                1,
                Map.of(),
                null,
                null
        );
    }

    private static final class FakeWorkerResourceRuntime implements WorkerResourceRuntime {
        private final Map<String, WorkerResourceRecord> workers = new LinkedHashMap<>();

        @Override
        public void addWorker(WorkerResourceRecord worker) {
            workers.put(worker.workerId(), worker);
        }

        @Override
        public Optional<WorkerResourceRecord> worker(String workerId) {
            return Optional.ofNullable(workers.get(workerId));
        }

        @Override
        public List<WorkerResourceRecord> workers() {
            return new ArrayList<>(workers.values());
        }

        @Override
        public boolean updateWorker(WorkerResourceRecord worker) {
            if (!workers.containsKey(worker.workerId())) {
                return false;
            }
            workers.put(worker.workerId(), worker);
            return true;
        }

        @Override
        public boolean deleteWorker(String workerId) {
            return workers.remove(workerId) != null;
        }

        @Override
        public WorkerGroupRecord upsertWorkerGroup(WorkerGroupRecord group) {
            throw unsupported();
        }

        @Override
        public Optional<WorkerGroupRecord> workerGroup(String groupId) {
            throw unsupported();
        }

        @Override
        public List<WorkerGroupRecord> workerGroups() {
            throw unsupported();
        }

        @Override
        public boolean deleteWorkerGroup(String groupId) {
            throw unsupported();
        }

        @Override
        public AdapterNodeRecord registerAdapterNode(AdapterNodeRecord adapterNode) {
            throw unsupported();
        }

        @Override
        public Optional<AdapterNodeRecord> adapterNode(String adapterNodeId) {
            throw unsupported();
        }

        @Override
        public List<AdapterNodeRecord> adapterNodes() {
            throw unsupported();
        }

        @Override
        public boolean deleteAdapterNode(String adapterNodeId) {
            throw unsupported();
        }

        @Override
        public NodeGroupBindingRecord bindNodeGroup(NodeGroupBindingRecord binding) {
            throw unsupported();
        }

        @Override
        public Optional<NodeGroupBindingRecord> nodeGroupBinding(String adapterNodeId, String groupId) {
            throw unsupported();
        }

        @Override
        public List<NodeGroupBindingRecord> nodeGroupBindings() {
            throw unsupported();
        }

        @Override
        public boolean unbindNodeGroup(String adapterNodeId, String groupId) {
            throw unsupported();
        }

        @Override
        public Set<String> groupIdsByAdapterNodeId(String adapterNodeId) {
            throw unsupported();
        }

        @Override
        public Set<String> adapterNodeIdsByGroupId(String groupId) {
            throw unsupported();
        }

        @Override
        public NodeGroupBindingRecord setNodeGroupBindingEnabled(String adapterNodeId,
                                                                 String groupId,
                                                                 boolean enabled) {
            throw unsupported();
        }

        @Override
        public NodeGroupBindingRecord setNodeGroupBindingDraining(String adapterNodeId,
                                                                  String groupId,
                                                                  boolean draining) {
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not needed by worker status listener test");
        }
    }
}
