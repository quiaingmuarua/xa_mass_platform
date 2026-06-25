package com.xa.mass.runtime.memory;

import com.xa.mass.runtime.contract.WorkerRegistryContractTest;
import com.xa.mass.runtime.worker.DispatchAvailabilitySource;
import com.xa.mass.runtime.worker.WorkerDispatchBlockRecord;
import com.xa.mass.runtime.worker.WorkerRegistry;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryWorkerRegistryTest extends WorkerRegistryContractTest {

    @Override
    protected WorkerRegistry createRegistry() {
        return new InMemoryWorkerRegistry();
    }

    @Test
    void concurrentExclusiveLeaseAcquireAllowsOneWinner() throws InterruptedException {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        int contenders = 16;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        Thread[] threads = new Thread[contenders];
        for (int index = 0; index < contenders; index++) {
            threads[index] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (registry.tryAcquireExclusiveLease("group-a", "worker-1")) {
                    accepted.incrementAndGet();
                }
            });
            threads[index].start();
        }

        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, accepted.get());
        assertTrue(registry.hasExclusiveLease("worker-1"));
    }

    @Test
    void dispatchBlockRecordRejectsStaleSignalsAndSurvivesGateClear() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        assertTrue(registry.blockDispatch("group-a", "worker-1", blockRecord("first", 2_000L)));
        assertFalse(registry.isDispatchEnabled("worker-1"));

        assertTrue(registry.clearDispatchDisable(
                "group-a",
                "worker-1",
                DispatchAvailabilitySource.TRANSPORT_DISCONNECTED
        ));
        assertTrue(registry.isDispatchEnabled("worker-1"));

        assertFalse(registry.blockDispatch("group-a", "worker-1", blockRecord("stale", 1_000L)));
        assertTrue(registry.isDispatchEnabled("worker-1"));
        assertEquals("first", registry.dispatchBlockRecord(
                        "group-a",
                        "worker-1",
                        DispatchAvailabilitySource.TRANSPORT_DISCONNECTED
                )
                .orElseThrow()
                .reason());

        assertTrue(registry.blockDispatch("group-a", "worker-1", blockRecord("newer", 3_000L)));
        assertFalse(registry.isDispatchEnabled("worker-1"));
        assertEquals("newer", registry.dispatchBlockRecord(
                        "group-a",
                        "worker-1",
                        DispatchAvailabilitySource.TRANSPORT_DISCONNECTED
                )
                .orElseThrow()
                .reason());
    }

    private static WorkerDispatchBlockRecord blockRecord(String reason, long observedAtMillis) {
        return new WorkerDispatchBlockRecord(
                DispatchAvailabilitySource.TRANSPORT_DISCONNECTED,
                reason,
                observedAtMillis,
                0L
        );
    }
}
