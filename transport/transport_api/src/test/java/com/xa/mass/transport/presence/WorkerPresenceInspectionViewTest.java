package com.xa.mass.transport.presence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerPresenceInspectionViewTest {

    @Test
    void isWorkerOnlineUsesSingleWorkerPresenceInsteadOfFullActivePresenceScan() {
        CountingInspectionView view = new CountingInspectionView(new WorkerPresence(
                "worker-1",
                "polling",
                "worker-1",
                100L,
                System.currentTimeMillis() + 30_000L,
                "runtime-a",
                "connection-1",
                100L
        ));

        assertTrue(view.isWorkerOnline("worker-1"));
        assertEquals(1, view.getPresenceCalls);
        assertEquals(0, view.listActivePresencesCalls);
    }

    private static final class CountingInspectionView implements WorkerPresenceInspectionView {
        private final WorkerPresence presence;
        private int getPresenceCalls;
        private int listActivePresencesCalls;

        private CountingInspectionView(WorkerPresence presence) {
            this.presence = presence;
        }

        @Override
        public WorkerPresence getPresence(String workerId) {
            getPresenceCalls++;
            return presence;
        }

        @Override
        public List<WorkerPresence> listActivePresences() {
            listActivePresencesCalls++;
            return List.of(presence);
        }
    }
}
