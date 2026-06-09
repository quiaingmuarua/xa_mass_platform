package com.xa.mass.transport.presence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerPresenceStoreTest {

    @Test
    void isWorkerOnlineUsesSingleWorkerPresenceInsteadOfFullActivePresenceScan() {
        CountingPresenceStore store = new CountingPresenceStore(new WorkerPresence(
                "worker-1",
                "polling",
                "worker-1",
                WorkerPresenceState.ONLINE,
                100L,
                System.currentTimeMillis() + 30_000L,
                "runtime-a",
                "connection-1",
                100L,
                null
        ));

        assertTrue(store.isWorkerOnline("worker-1"));
        assertEquals(1, store.getPresenceCalls);
        assertEquals(0, store.listActivePresencesCalls);
    }

    private static final class CountingPresenceStore implements WorkerPresenceStore {
        private final WorkerPresence presence;
        private int getPresenceCalls;
        private int listActivePresencesCalls;

        private CountingPresenceStore(WorkerPresence presence) {
            this.presence = presence;
        }

        @Override
        public WorkerPresence markOnline(String workerId,
                                         String adapterId,
                                         String routeKey,
                                         String connectionId,
                                         String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkerPresence refreshHeartbeat(String workerId,
                                               String adapterId,
                                               String routeKey,
                                               String connectionId,
                                               String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkerPresence markOffline(String workerId,
                                          String adapterId,
                                          String routeKey,
                                          String connectionId,
                                          String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkerPresence getPresence(String workerId) {
            getPresenceCalls++;
            return presence;
        }

        @Override
        public boolean isRouteOnline(String adapterId, String routeKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<WorkerPresence> listActivePresences() {
            listActivePresencesCalls++;
            return List.of(presence);
        }

        @Override
        public int pruneExpired() {
            throw new UnsupportedOperationException();
        }
    }
}
