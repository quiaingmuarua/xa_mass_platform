package com.xa.mass.base.model;

import com.xa.mass.base.enums.worker.WorkerStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerStateTest {

    @Test
    void constructorInitializesStableDefaults() {
        Worker worker = new Worker();

        assertEquals(WorkerStatus.OFFLINE, worker.getStatus());
        assertTrue(worker.getSupportedProjects().isEmpty());
        assertNotNull(worker.getCreateTime());
        assertNotNull(worker.getUpdateTime());
    }

    @Test
    void supportedProjectsAreCopiedAndReadOnly() {
        Worker worker = new Worker();
        List<String> input = new ArrayList<>();
        input.add("demoApp");

        worker.setSupportedProjects(input);
        input.clear();

        assertEquals(List.of("demoApp"), worker.getSupportedProjects());
        assertThrows(UnsupportedOperationException.class,
                () -> worker.getSupportedProjects().add("testApp"));
    }

    @Test
    void workerStateTransitionsFollowExplicitRules() {
        Worker worker = new Worker();

        assertTrue(worker.transitionTo(WorkerStatus.ONLINE));
        assertEquals(WorkerStatus.ONLINE, worker.getStatus());

        assertTrue(worker.transitionTo(WorkerStatus.EXPIRED));
        assertEquals(WorkerStatus.EXPIRED, worker.getStatus());

        assertTrue(worker.transitionTo(WorkerStatus.OFFLINE));
        assertEquals(WorkerStatus.OFFLINE, worker.getStatus());

        assertFalse(worker.transitionTo(WorkerStatus.OFFLINE));
        assertFalse(worker.transitionTo(null));
    }

    @Test
    void heartbeatRevivesExpiredWorkerToOnline() {
        Worker worker = new Worker();
        worker.transitionTo(WorkerStatus.ONLINE);
        worker.transitionTo(WorkerStatus.EXPIRED);

        worker.updateHeartbeat();

        assertEquals(WorkerStatus.ONLINE, worker.getStatus());
        assertNotNull(worker.getLastHeartbeat());
    }

    @Test
    void setStatusRejectsNull() {
        Worker worker = new Worker();

        assertThrows(NullPointerException.class, () -> worker.setStatus(null));
    }

    @Test
    void heartbeatExpiryDependsOnLastHeartbeatTimestamp() {
        Worker worker = new Worker();
        worker.setLastHeartbeat(LocalDateTime.now().minusSeconds(60));

        assertTrue(worker.isHeartbeatExpired(30));
        assertFalse(worker.isHeartbeatExpired(120));
    }
}
