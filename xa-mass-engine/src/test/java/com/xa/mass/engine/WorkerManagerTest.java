package com.xa.mass.engine;

import com.xa.mass.base.channel.eventbus.event.worker.WorkerHeartbeatEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkerManagerTest {

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
        eventWorker.setSupportedProjects(List.of());
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
    void findWorkerCandidatesUsesTargetWorkerBeforeCapabilityIndexes() {
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

        assertEquals(List.of("w-target"),
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

    // ---- workerContext ----

    @Test
    void addAndRetrieveWorkerContext() {
        manager.addWorker(worker("w4", "us"));
        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-1");
        workerContext.setWorkerId("w4");
        manager.addWorkerContext(workerContext);

        assertEquals(List.of(workerContext), manager.getWorkerContexts("w4"));
        assertEquals("ctx-1", manager.getWorkerContextById("ctx-1").getWorkerContextId());
    }

    @Test
    void deleteWorkerContextByIdRemovesIt() {
        manager.addWorker(worker("w5", "us"));
        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-2");
        workerContext.setWorkerId("w5");
        manager.addWorkerContext(workerContext);
        assertTrue(manager.deleteWorkerContextById("ctx-2"));
        assertTrue(manager.getWorkerContexts("w5").isEmpty());
        assertNull(manager.getWorkerContextById("ctx-2"));
    }

    @Test
    void sameWorkerCanOwnMultipleContextsWithoutOverwrite() {
        manager.addWorker(worker("w10", "us"));
        WorkerContext first = new WorkerContext();
        first.setWorkerContextId("ctx-10-a");
        first.setWorkerId("w10");
        WorkerContext second = new WorkerContext();
        second.setWorkerContextId("ctx-10-b");
        second.setWorkerId("w10");

        manager.addWorkerContext(first);
        manager.addWorkerContext(second);

        assertEquals(2, manager.getWorkerContexts("w10").size());
        assertNotNull(manager.getWorkerContextById("ctx-10-a"));
        assertNotNull(manager.getWorkerContextById("ctx-10-b"));
    }

    @Test
    void getWorkerContextsReturnsAllOwnedContexts() {
        manager.addWorker(worker("w11", "us"));
        WorkerContext blocked = new WorkerContext();
        blocked.setWorkerContextId("ctx-11-blocked");
        blocked.setWorkerId("w11");
        blocked.block();
        WorkerContext idle = new WorkerContext();
        idle.setWorkerContextId("ctx-11-idle");
        idle.setWorkerId("w11");

        manager.addWorkerContext(blocked);
        manager.addWorkerContext(idle);

        assertEquals(
                List.of("ctx-11-blocked", "ctx-11-idle"),
                manager.getWorkerContexts("w11").stream().map(WorkerContext::getWorkerContextId).toList()
        );
    }

    @Test
    void addWorkerContextRejectsMissingOwnerWorkerId() {
        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-missing-owner");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> manager.addWorkerContext(workerContext)
        );
        assertEquals("workerId is required on workerContext", error.getMessage());
    }

    @Test
    void updateWorkerContextByIdRejectsChangingOwnerWorkerId() {
        manager.addWorker(worker("w12", "us"));
        manager.addWorker(worker("w13", "gb"));

        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-12");
        workerContext.setWorkerId("w12");
        manager.addWorkerContext(workerContext);

        WorkerContext moved = new WorkerContext();
        moved.setWorkerContextId("ctx-12");
        moved.setWorkerId("w13");

        assertFalse(manager.updateWorkerContextById("ctx-12", moved));
        assertEquals("w12", manager.getWorkerContextById("ctx-12").getWorkerId());
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

    // ---- online status ----

    @Test
    void onlineStatusTracking() {
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
    void workerStatusEventListenerKeepsModelStatusInSync() {
        WorkerManager.WorkerStatusEventListener listener = new WorkerManager.WorkerStatusEventListener(manager);
        manager.addWorker(worker("w9", "us"));
        manager.updateOnlineStatus("w9", false);

        listener.onWorkerOnline(new WorkerOnlineEvent("w9", "connected", null));
        assertFalse(manager.isWorkerOnline("w9"));
        assertNotNull(manager.getWorker("w9").getLastHeartbeat());
        assertEquals(WorkerStatus.OFFLINE, manager.getWorker("w9").getStatus());

        listener.onWorkerOffline(new WorkerOfflineEvent("w9", "disconnected", null));
        assertFalse(manager.isWorkerOnline("w9"));
        assertEquals(WorkerStatus.OFFLINE, manager.getWorker("w9").getStatus());
    }

    @Test
    void workerHeartbeatEventRefreshesLastHeartbeatWithoutChangingStatus() {
        WorkerManager.WorkerStatusEventListener listener = new WorkerManager.WorkerStatusEventListener(manager);
        manager.addWorker(worker("w10", "us"));
        manager.updateOnlineStatus("w10", false);

        listener.onWorkerHeartbeat(new WorkerHeartbeatEvent("w10", "heartbeat", null));

        assertFalse(manager.isWorkerOnline("w10"));
        assertNotNull(manager.getWorker("w10").getLastHeartbeat());
        assertEquals(WorkerStatus.OFFLINE, manager.getWorker("w10").getStatus());
    }

    // ---- helpers ----

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
