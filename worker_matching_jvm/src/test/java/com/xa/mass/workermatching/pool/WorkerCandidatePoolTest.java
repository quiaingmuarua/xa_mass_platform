package com.xa.mass.workermatching.pool;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerCandidatePoolTest {
    final AtomicLong clock = new AtomicLong(1000);
    final CandidateBudget budget = new CandidateBudget();
    final WorkerCandidatePool pool = new WorkerCandidatePool(clock::get, budget);
    WorkerCandidate row(String id, long score) { return new WorkerCandidate(id, score); }

    @Test void entriesAreImmutableIndependentOccurrencesInOneBucket() {
        var old = row("w", 17);
        assertEquals(List.of(old, old), pool.offerBatch("g", "CN", List.of(old, old)));
        assertEquals(List.of(row("w", 18)), pool.offerBatch("g", "US", List.of(row("w", 18))));
        assertEquals(Map.of("CN", 2, "US", 1), pool.countByKey("g"));
        assertEquals(List.of(old, old), pool.pollBatch("g", Set.of("CN"), 100));
        assertEquals(List.of(row("w", 18)), pool.pollAnyBatch("g", 100));
        assertTrue(pool.countByKey("g").isEmpty());
        assertEquals(CandidateBudget.PROCESS, budget.available());
    }

    @Test void repeatedFenceHasItsOwnAdmissionTimeWithoutRenewingOldEntry() {
        var candidate = row("w", 17);
        pool.offerBatch("g", "CN", List.of(candidate));
        clock.set(2000); pool.offerBatch("g", "CN", List.of(candidate));
        clock.set(61_000);
        assertEquals(Map.of("CN", 2), pool.countByKey("g"));
        assertEquals(List.of(candidate), pool.pollBatch("g", Set.of("CN"), 10));
        assertEquals(CandidateBudget.PROCESS, budget.available());
        pool.offerBatch("g", "CN", List.of(candidate));
        clock.set(120_999); assertEquals(List.of(candidate), pool.pollAnyBatch("g", 1));
        pool.offerBatch("g", "CN", List.of(candidate));
        clock.set(180_999); assertTrue(pool.pollAnyBatch("g", 1).isEmpty());
    }

    @Test void explicitKeysAreLexicographicAndAnyUsesBucketCreationOrderWithFifo() {
        pool.offerBatch("g", "US", List.of(row("u1", 11), row("u2", 12)));
        pool.offerBatch("g", "CN", List.of(row("c1", 13), row("c2", 14)));
        assertEquals(List.of(row("c1", 13), row("c2", 14), row("u1", 11)),
                pool.pollBatch("g", Set.of("US", "CN", "missing"), 3));
        pool.offerBatch("g", "CN", List.of(row("c3", 15)));
        assertEquals(List.of(row("u2", 12), row("c3", 15)), pool.pollAnyBatch("g", 10));
    }

    @Test void groupsAreIsolatedAndDifferentQueriesConsumeTheSameBucketEntries() {
        pool.offerBatch("a", "CN", List.of(row("w", 1)));
        pool.offerBatch("b", "CN", List.of(row("w", 2)));
        assertTrue(pool.pollBatch("a", Set.of(), 5).isEmpty());
        assertEquals(List.of(row("w", 1)), pool.pollAnyBatch("a", 1));
        assertTrue(pool.pollBatch("a", Set.of("CN"), 1).isEmpty());
        assertEquals(List.of(row("w", 2)), pool.pollBatch("b", Set.of("CN"), 1));
    }

    @Test void wholeBatchValidationPrecedesMutationAndResultsAreImmutable() {
        assertThrows(IllegalArgumentException.class, () -> pool.offerBatch("g", "CN", List.of(row("a", 1), row("b", 0))));
        assertTrue(pool.countByKey("g").isEmpty());
        var result = pool.offerBatch("g", "CN", List.of(row("a", 1)));
        assertThrows(UnsupportedOperationException.class, result::clear);
        assertThrows(UnsupportedOperationException.class, () -> pool.countByKey("g").clear());
        assertThrows(IllegalArgumentException.class, () -> pool.pollBatch("g", Set.of("CN", " "), 1));
        assertThrows(IllegalArgumentException.class, () -> pool.pollAnyBatch("g", -1));
        assertEquals(List.of(row("a", 1)), pool.pollAnyBatch("g", 1));
    }

    @Test void countsAndNoopBatchesDoNotReadClockOrWalkEntries() {
        LongSupplier time = mock(LongSupplier.class);
        when(time.getAsLong()).thenReturn(1000L);
        var stock = new WorkerCandidatePool(time, budget);
        stock.offerBatch("g", "CN", Collections.nCopies(100, row("w", 1)));
        clearInvocations(time);
        assertEquals(Map.of("CN", 100), stock.countByKey("g"));
        stock.offerBatch("g", "CN", List.of());
        stock.pollBatch("g", Set.of("CN"), 0);
        stock.pollBatch("g", Set.of(), 10);
        stock.pollAnyBatch("g", 0);
        verifyNoInteractions(time);
        stock.pollAnyBatch("g", 100);
        verify(time, times(1)).getAsLong();
    }

    @Test void fullCapacityAcceptsAPrefixAndHasNoReplacementPrivilege() {
        pool.offerBatch("g", "CN", Collections.nCopies(CandidateBudget.PER_ELIGIBILITY - 1, row("w", 1)));
        assertEquals(List.of(row("w", 2)), pool.offerBatch("g", "US", List.of(row("w", 2), row("other", 3))));
        assertTrue(pool.offerBatch("g", "US", List.of(row("w", 4))).isEmpty());
        assertEquals(CandidateBudget.PER_ELIGIBILITY - 1, pool.countByKey("g").get("CN"));
        assertEquals(List.of(row("w", 2)), pool.pollBatch("g", Set.of("US"), 5));
        assertEquals(List.of(row("w", 4)), pool.offerBatch("g", "US", List.of(row("w", 4))));
    }

    @Test void pressureCleanupReleasesOnlyExpiredHeadsIncludingIdleGroups() {
        pool.offerBatch("idle", "CN", List.of(row("old", 1)));
        pool.offerBatch("g", "CN", List.of(row("old", 2)));
        clock.set(2000);
        pool.offerBatch("g", "CN", List.of(row("fresh", 3)));
        pool.offerBatch("g", "US", List.of(row("fresh", 4)));
        clock.set(61_000); pool.discardExpired();
        assertTrue(pool.countByKey("idle").isEmpty());
        assertEquals(Map.of("CN", 1, "US", 1), pool.countByKey("g"));
        assertEquals(CandidateBudget.PROCESS - 2, budget.available());
        assertEquals(List.of(row("fresh", 3), row("fresh", 4)), pool.pollAnyBatch("g", 5));
    }

    @Test void sharedCapacityIsReleasedByConsumptionAndExpiryWithoutIdentityAccounting() {
        for (int i = 0; i < 10; i++)
            pool.offerBatch("g" + i, "CN", Collections.nCopies(1000, row("same", 1)));
        assertEquals(0, budget.available());
        assertTrue(pool.offerBatch("new", "CN", List.of(row("same", 1))).isEmpty());
        assertEquals(1, pool.pollAnyBatch("g0", 1).size());
        assertEquals(1, pool.offerBatch("new", "CN", List.of(row("same", 1))).size());
        clock.set(61_000); pool.discardExpired();
        assertEquals(CandidateBudget.PROCESS, budget.available());
    }

    @Test void concurrentPollsConsumeEachEntryOnceWhileAllowingDuplicateWorkerIds() throws Exception {
        var entries = new ArrayList<WorkerCandidate>();
        for (int i = 1; i <= 100; i++) entries.add(row("same-worker", i));
        pool.offerBatch("g", "CN", entries);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return pool.pollAnyBatch("g", 100); });
            var second = executor.submit(() -> { start.await(); return pool.pollBatch("g", Set.of("CN"), 100); });
            start.countDown();
            var taken = new ArrayList<>(first.get(5, TimeUnit.SECONDS));
            taken.addAll(second.get(5, TimeUnit.SECONDS));
            assertEquals(100, taken.size());
            assertEquals(new HashSet<>(entries), new HashSet<>(taken));
            assertEquals(1, taken.stream().map(WorkerCandidate::workerId).distinct().count());
        }
        assertEquals(CandidateBudget.PROCESS, budget.available());
    }
}
