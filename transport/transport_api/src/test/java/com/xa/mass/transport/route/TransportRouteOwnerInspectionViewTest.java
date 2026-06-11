package com.xa.mass.transport.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportRouteOwnerInspectionViewTest {

    @Test
    void isWorkerReachableUsesSingleWorkerOwnerInsteadOfFullActiveOwnerScan() {
        CountingInspectionView view = new CountingInspectionView(new TransportRouteOwnerRecord(
                "worker-1",
                "polling",
                "worker-1",
                100L,
                System.currentTimeMillis() + 30_000L,
                "runtime-a",
                "connection-1",
                100L
        ));

        assertTrue(view.isWorkerReachable("worker-1"));
        assertEquals(1, view.getOwnerCalls);
        assertEquals(0, view.listActiveOwnerCalls);
    }

    private static final class CountingInspectionView implements TransportRouteOwnerInspectionView {
        private final TransportRouteOwnerRecord owner;
        private int getOwnerCalls;
        private int listActiveOwnerCalls;

        private CountingInspectionView(TransportRouteOwnerRecord owner) {
            this.owner = owner;
        }

        @Override
        public TransportRouteOwnerRecord getLatestOwnerByWorker(String workerId) {
            getOwnerCalls++;
            return owner;
        }

        @Override
        public List<TransportRouteOwnerRecord> listActiveRouteOwners() {
            listActiveOwnerCalls++;
            return List.of(owner);
        }
    }
}
