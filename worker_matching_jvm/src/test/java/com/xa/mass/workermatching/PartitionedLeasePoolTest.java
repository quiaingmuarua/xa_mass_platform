package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.PlatformLeaseState.Coordinate;
import com.xa.mass.workermatching.pool.*;
import com.xa.mass.workermatching.refill.PartitionedLeasePoolPolicy;
import com.xa.mass.workermatching.functions.PartitionedLeaseQueryFunction;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartitionedLeasePoolTest {
    final AtomicLong clock = new AtomicLong(1000);
    final CandidateBudget budget = new CandidateBudget();
    final WorkerCandidatePool pool = new WorkerCandidatePool(clock::get, budget);
    final PlatformLeaseState leases = mock(PlatformLeaseState.class);
    final PartitionedLeasePoolDefinition definition = new PartitionedLeasePoolDefinition("leases", "available", "phone", "country", Set.of("A", "B"));
    @SuppressWarnings("unchecked") final BiFunction<String, List<String>, Map<String, Map<String, Object>>> facts = mock(BiFunction.class);
    final PartitionedLeasePoolPolicy policy = new PartitionedLeasePoolPolicy(pool, facts, leases, definition);
    EligibilityQuery target(String app) { return new EligibilityQuery(Map.of("partition", List.of(app), "worker.country", List.of("CN"))); }
    Map<EligibilityQuery, Integer> targets() { return Map.of(target("A"), 1, target("B"), 1); }
    void facts() { when(facts.apply("g", List.of("w"))).thenReturn(Map.of("w", Map.of("phone", "123", "country", "CN"))); }

    @Test void consumingSharedGenerationRemovesAllViewsAndReleasesEveryReference() {
        facts(); when(leases.read(anyString(), anyString(), anyList())).thenReturn(Map.of());
        assertEquals(List.of("w"), policy.refill("g", targets(), Map.of("w", 31L), 1));
        assertEquals(9998, budget.available());
        assertEquals(Map.of(target("A"), 0, target("B"), 0), policy.deficits("g", targets()));
        var query = new PartitionedLeaseQueryFunction(pool, definition);
        assertEquals(Map.of("m", new WorkerCandidate("w", 31)), query.apply("g", Map.of("m", Map.of("partition", "A", "country", "CN"))));
        assertTrue(query.apply("g", Map.of("m2", Map.of("partition", "B", "country", "CN"))).isEmpty());
        assertEquals(10000, budget.available());
        verify(leases, never()).record(anyString(), anyString(), anyMap());
    }
    @Test void liveLeaseExcludesOnlyThatPartitionAndExpiryDoesNotRequireDeletion() {
        facts(); var coordinate = new Coordinate("123", "A", "w");
        when(leases.read(anyString(), anyString(), anyList())).thenReturn(Map.of(coordinate, 2000L));
        policy.refill("g", targets(), Map.of("w", 31L), 1);
        assertEquals(Map.of(definition.bucket("B", "CN"), 1), pool.liveCountByKey("g"));
        assertTrue(pool.pollBatch("g", Set.of(definition.bucket("A", "CN")), 1).isEmpty());
        clock.set(2000);
        assertEquals(List.of(new WorkerCandidate("w", 31)), pool.pollBatch("g", Set.of(definition.bucket("A", "CN")), 1));
        assertTrue(pool.countByKey("g").isEmpty());
        verify(leases).read(anyString(), anyString(), anyList());
        verify(facts).apply("g", List.of("w"));
    }
    @Test void fullTargetsSkipFactsAndLeaseReadsAndExpiredViewsBecomeDeficits() {
        var candidate = new WorkerCandidate("w", 31);
        pool.offerSharedBatch("g", Map.of(candidate, Map.of(definition.bucket("A", "CN"), 0L, definition.bucket("B", "CN"), 0L)),
                Map.of(definition.bucket("A", "CN"), 1, definition.bucket("B", "CN"), 1));
        assertTrue(policy.refill("g", targets(), Map.of("other", 32L), 1).isEmpty());
        verifyNoInteractions(facts, leases);
        clock.set(61_000);
        assertEquals(Map.of(target("A"), 1, target("B"), 1), policy.deficits("g", targets()));
        assertEquals(10000, budget.available());
    }
    @Test void oneThousandPairBudgetRotatesRatherThanMaterializingAllAppWorkerPairs() {
        var apps = new TreeSet<String>(); var targets = new LinkedHashMap<EligibilityQuery, Integer>();
        for (int i = 0; i < 100; i++) { String app = "app" + i; apps.add(app); targets.put(target(app), 100); }
        var definition = new PartitionedLeasePoolDefinition("leases", "available", "phone", "country", apps);
        var rows = new LinkedHashMap<String, Map<String, Object>>(); var offered = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 20; i++) { String id = "w" + i; rows.put(id, Map.of("phone", "p" + i, "country", "CN")); offered.put(id, 31L); }
        when(facts.apply(anyString(), anyList())).thenReturn(rows);
        var batches = new ArrayList<List<Coordinate>>();
        when(leases.read(anyString(), anyString(), anyList())).thenAnswer(call -> { batches.add(List.copyOf(call.getArgument(2))); return Map.of(); });
        var policy = new PartitionedLeasePoolPolicy(pool, facts, leases, definition);
        assertEquals(20, policy.refill("g", targets, offered, 20).size());
        assertEquals(1000, batches.getFirst().size());
        assertEquals(9000, budget.available());
        pool.pollAnyBatch("g", 20);
        policy.refill("g", targets, offered, 20);
        assertEquals(2, batches.size());
        assertTrue(Collections.disjoint(batches.get(0), batches.get(1)));
        assertEquals(1000, batches.get(1).size());
    }
    @Test void qualificationFailureDoesNotPartiallyAdmitStock() {
        facts(); when(leases.read(anyString(), anyString(), anyList())).thenThrow(new IllegalStateException("Redis unavailable"));
        assertThrows(IllegalStateException.class, () -> policy.refill("g", targets(), Map.of("w", 31L), 1));
        assertTrue(pool.countByKey("g").isEmpty()); assertEquals(10000, budget.available());
    }
    @Test void shortageWatermarkDoesNotDiscardTheRestOfAnAlreadyQualifiedBatch() {
        String key = definition.bucket("A", "CN");
        pool.offerSharedBatch("g", Map.of(new WorkerCandidate("first", 31), Map.of(key, 0L)), Map.of(key, 2));
        var offered = new LinkedHashMap<WorkerCandidate, Map<String, Long>>();
        for (int i = 0; i < 10; i++) offered.put(new WorkerCandidate("batch" + i, 31), Map.of(key, 0L));
        assertEquals(10, pool.offerSharedBatch("g", offered, Map.of(key, 2)).size());
        assertEquals(Map.of(key, 11), pool.liveCountByKey("g"));
        assertEquals(9989, budget.available());
        assertTrue(policy.refill("g", Map.of(target("A"), 2), Map.of("other", 31L), 1).isEmpty());
        verifyNoInteractions(facts, leases);
    }
    @Test void deferredViewsDoNotFillTargetsOrBlockReadyStockAtTheCapacityLimit() {
        String key = definition.bucket("A", "CN");
        var blocked = new LinkedHashMap<WorkerCandidate, Map<String, Long>>();
        for (int i = 0; i < 1000; i++) blocked.put(new WorkerCandidate("future" + i, 31), Map.of(key, 5000L));
        pool.offerSharedBatch("g", blocked, Map.of(key, 1000));
        assertTrue(pool.liveCountByKey("g").isEmpty());
        assertEquals(0, pool.remainingCapacity("g"));
        var ready = new WorkerCandidate("ready", 41);
        pool.offerSharedBatch("g", Map.of(ready, Map.of(key, 0L)), Map.of(key, 1000));
        assertEquals(Map.of(key, 1), pool.liveCountByKey("g"));
        pool.reclaimDeferred("g");
        assertEquals(Map.of(key, 1), pool.countByKey("g"));
        assertEquals(List.of(ready), pool.pollBatch("g", Set.of(key), 1));
        assertEquals(10000, budget.available());
    }
    @Test void malformedPartitionAndCountryFailBeforeResourceReads() {
        var query = new PartitionedLeaseQueryFunction(pool, definition);
        assertThrows(IllegalArgumentException.class, () -> query.normalizeInput("g", Map.of("partition", "missing", "country", "CN")));
        assertThrows(IllegalArgumentException.class, () -> query.normalizeInput("g", Map.of("partition", "A", "country", "cn")));
        verifyNoInteractions(facts, leases);
    }
}
