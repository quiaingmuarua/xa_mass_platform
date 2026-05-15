package com.xa.mass.engine.load;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryWorkerLoadViewTest {

    @Test
    void claimAndFinalUpdateActiveLeaseCount() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();

        loadView.recordWorkClaimed("worker-1", "task-1");
        loadView.recordWorkClaimed("worker-1", "task-1");

        assertEquals(2, loadView.getActiveLeaseCount("worker-1"));
        assertEquals(2.0, loadView.getEstimatedLoadRatio("worker-1"));

        loadView.recordWorkFinal("worker-1", "task-1");

        WorkerLoadSnapshot snapshot = loadView.snapshot("worker-1");
        assertEquals(1, snapshot.activeLeaseCount());
        assertEquals(0, snapshot.reservedCount());
        assertEquals(1, snapshot.declaredCapacity());
        assertEquals(1.0, snapshot.estimatedLoadRatio());
    }

    @Test
    void finalDoesNotUnderflow() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();

        loadView.recordWorkFinal("worker-1", "task-1");
        loadView.recordWorkClaimed("worker-1", "task-1");
        loadView.recordWorkFinal("worker-1", "task-1");
        loadView.recordWorkFinal("worker-1", "task-1");

        assertEquals(0, loadView.getActiveLeaseCount("worker-1"));
        assertEquals(0.0, loadView.getEstimatedLoadRatio("worker-1"));
    }

    @Test
    void reservationUsesDeclaredCapacityAsOptimisticGate() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();

        assertTrue(loadView.tryReserveCapacity("worker-1", "task-1"));
        assertEquals(1, loadView.getReservedCount("worker-1"));
        assertEquals(1.0, loadView.getEstimatedLoadRatio("worker-1"));
        assertFalse(loadView.tryReserveCapacity("worker-1", "task-2"));

        assertTrue(loadView.confirmReservation("worker-1", "task-1"));

        WorkerLoadSnapshot snapshot = loadView.snapshot("worker-1");
        assertEquals(1, snapshot.activeLeaseCount());
        assertEquals(0, snapshot.reservedCount());
        assertEquals(1.0, snapshot.estimatedLoadRatio());
    }

    @Test
    void registeredDeclaredCapacityAllowsMultipleReservations() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();
        loadView.recordDeclaredCapacity("worker-1", 3);

        assertTrue(loadView.tryReserveCapacity("worker-1", "task-1"));
        assertTrue(loadView.tryReserveCapacity("worker-1", "task-2"));
        assertTrue(loadView.tryReserveCapacity("worker-1", "task-3"));
        assertFalse(loadView.tryReserveCapacity("worker-1", "task-4"));

        WorkerLoadSnapshot snapshot = loadView.snapshot("worker-1");
        assertEquals(0, snapshot.activeLeaseCount());
        assertEquals(3, snapshot.reservedCount());
        assertEquals(3, snapshot.declaredCapacity());
        assertEquals(1.0, snapshot.estimatedLoadRatio());
    }

    @Test
    void declaredCapacityFallsBackToOneWhenInvalid() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();
        loadView.recordDeclaredCapacity("worker-1", 0);

        assertTrue(loadView.tryReserveCapacity("worker-1", "task-1"));
        assertFalse(loadView.tryReserveCapacity("worker-1", "task-2"));
        assertEquals(1, loadView.snapshot("worker-1").declaredCapacity());
    }

    @Test
    void confirmWithoutReservationDoesNotCreateActiveLease() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();

        assertFalse(loadView.confirmReservation("worker-1", "task-1"));

        assertEquals(0, loadView.getActiveLeaseCount("worker-1"));
        assertEquals(0, loadView.getReservedCount("worker-1"));
    }

    @Test
    void releaseReservationDoesNotUnderflow() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();

        loadView.releaseReservation("worker-1", "task-1");
        assertTrue(loadView.tryReserveCapacity("worker-1", "task-1"));
        loadView.releaseReservation("worker-1", "task-1");
        loadView.releaseReservation("worker-1", "task-1");

        assertEquals(0, loadView.getReservedCount("worker-1"));
        assertEquals(0.0, loadView.getEstimatedLoadRatio("worker-1"));
    }

    @Test
    void concurrentClaimAndFinalRemainBounded() throws Exception {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();
        int threads = 8;
        int iterations = 1_000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        loadView.recordWorkClaimed("worker-1", "task-1");
                        loadView.recordWorkFinal("worker-1", "task-1");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        executor.shutdownNow();

        assertEquals(0, loadView.getActiveLeaseCount("worker-1"));
    }

    @Test
    void concurrentReservationsRespectDefaultCapacity() throws Exception {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger reserved = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            int taskIndex = i;
            executor.submit(() -> {
                try {
                    start.await();
                    if (loadView.tryReserveCapacity("worker-1", "task-" + taskIndex)) {
                        reserved.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        executor.shutdownNow();

        assertEquals(1, reserved.get());
        assertEquals(1, loadView.getReservedCount("worker-1"));
    }
}
