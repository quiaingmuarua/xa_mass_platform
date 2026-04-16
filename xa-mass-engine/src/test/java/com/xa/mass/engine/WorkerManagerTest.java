package com.xa.mass.engine;

import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.storage.InMemoryWorkerStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        manager.addWorkerContext("w4", workerContext);

        WorkerContext found = manager.getWorkerContext("w4");
        assertNotNull(found);
        assertEquals("ctx-1", found.getWorkerContextId());
        assertEquals(List.of(workerContext), manager.getWorkerContexts("w4"));
        assertEquals("ctx-1", manager.getWorkerContextById("ctx-1").getWorkerContextId());
    }

    @Test
    void deleteWorkerContextRemovesIt() {
        manager.addWorker(worker("w5", "us"));
        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-2");
        manager.addWorkerContext("w5", workerContext);
        assertTrue(manager.deleteWorkerContext("w5"));
        assertNull(manager.getWorkerContext("w5"));
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

        manager.addWorkerContext("w10", first);
        manager.addWorkerContext("w10", second);

        assertEquals(2, manager.getWorkerContexts("w10").size());
        assertNotNull(manager.getWorkerContextById("ctx-10-a"));
        assertNotNull(manager.getWorkerContextById("ctx-10-b"));
    }

    @Test
    void compatibilityGetWorkerContextPrefersAllocatableContext() {
        manager.addWorker(worker("w11", "us"));
        WorkerContext blocked = new WorkerContext();
        blocked.setWorkerContextId("ctx-11-blocked");
        blocked.setWorkerId("w11");
        blocked.block();
        WorkerContext idle = new WorkerContext();
        idle.setWorkerContextId("ctx-11-idle");
        idle.setWorkerId("w11");

        manager.addWorkerContext("w11", blocked);
        manager.addWorkerContext("w11", idle);

        WorkerContext found = manager.getWorkerContext("w11");
        assertNotNull(found);
        assertEquals("ctx-11-idle", found.getWorkerContextId());
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
        assertTrue(manager.isWorkerOnline("w9"));
        assertEquals(WorkerStatus.ONLINE, manager.getWorker("w9").getStatus());

        listener.onWorkerOffline(new WorkerOfflineEvent("w9", "disconnected", null));
        assertFalse(manager.isWorkerOnline("w9"));
        assertEquals(WorkerStatus.OFFLINE, manager.getWorker("w9").getStatus());
    }

    // ---- helpers ----

    private Worker worker(String id, String workerGroupId) {
        Worker w = new Worker();
        w.setWorkerId(id);
        w.setWorkerGroupId(workerGroupId);
        w.setStatus(WorkerStatus.ONLINE);
        return w;
    }
}
