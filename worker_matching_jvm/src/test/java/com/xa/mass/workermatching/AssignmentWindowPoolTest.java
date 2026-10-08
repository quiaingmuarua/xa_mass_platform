package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.*;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.MatchingGroup.AssignmentWindowPool;
import com.xa.mass.workermatching.WorkerProperties.WorkerFacts;
import com.xa.mass.workermatching.functions.AssignmentWindowQueryFunction;
import com.xa.mass.workermatching.pool.*;
import com.xa.mass.workermatching.refill.AssignmentWindowPoolPolicy;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import java.math.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssignmentWindowPoolTest {
    final AtomicLong clock = new AtomicLong(120_000), clockReads = new AtomicLong();
    final CandidateBudget budget = new CandidateBudget();
    final WorkerCandidatePool pool = new WorkerCandidatePool(clock::get, budget);
    final Map<String, WorkerFacts> facts = new HashMap<>();
    final Map<String, Long> offered = new LinkedHashMap<>();
    final List<List<String>> reads = new ArrayList<>();
    final EligibilityQuery target = new EligibilityQuery(Map.of());
    final Map<EligibilityQuery, Integer> targets = Map.of(target, 100);
    final Map<String, AssignmentWindowPool> windows = Map.of("g", new AssignmentWindowPool(60_000, 10),
            "other", new AssignmentWindowPool(60_000, 1));
    final AssignmentWindowPoolPolicy policy = new AssignmentWindowPoolPolicy(pool, (group, ids) -> {
        reads.add(List.copyOf(ids));
        var result = new LinkedHashMap<String, WorkerFacts>();
        ids.forEach(id -> { if (facts.containsKey(id)) result.put(id, facts.get(id)); });
        return result;
    }, () -> { clockReads.incrementAndGet(); return clock.get(); }, windows);
    final AssignmentWindowQueryFunction function = new AssignmentWindowQueryFunction(pool);

    long prepare(String id, Map<String, Object> properties) {
        facts.put(id, new WorkerFacts(id, "g", Map.of(), properties));
        long fence = offered.size() + 10L;
        offered.put(id, fence);
        return fence;
    }
    List<String> refill() { return policy.refill("g", targets, offered, offered.size()); }
    static Map<String, Object> window(Object time, Object count) {
        return Map.of("lastAssignedAt", time, "windowAssignmentCount", count);
    }
    static Map<String, Object> requests(int count) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < count; i++) result.put("m" + i, Map.of());
        return result;
    }

    @Test void qualifiesTheCompleteOfferedBatchBeforeAdmissionAndKeepsOriginalFences() {
        prepare("blocked", window(120_000L, 10));
        long below = prepare("below", window(120_001L, 9));
        prepare("old", window(119_999L, 999));
        prepare("future", window(180_000L, 0));
        prepare("new", Map.of("unrelated", "retained"));
        assertEquals(List.of("below", "old", "new"), refill());
        assertEquals(List.of(List.copyOf(offered.keySet())), reads);
        assertEquals(1, clockReads.get());
        assertEquals(3, pool.countByKey("g").get("available"));
        var result = function.apply("g", requests(5));
        assertEquals(List.of("m0", "m1", "m2"), List.copyOf(result.keySet()));
        assertEquals(List.of("below", "old", "new"), result.values().stream().map(WorkerCandidate::workerId).toList());
        assertEquals(below, result.get("m0").expectedScore());
        assertEquals(1, reads.size());
        assertEquals(Map.of(), pool.countByKey("g"));
        assertEquals(10_000, budget.available());
        assertEquals(window(120_000L, 10), facts.get("blocked").platformProperties());
    }

    @Test void admissionSnapshotIsNotRecheckedOrReservedByConsumption() {
        prepare("w", window(120_000L, 9));
        assertEquals(List.of("w"), refill());
        facts.put("w", new WorkerFacts("w", "g", Map.of(), window(120_000L, 10)));
        assertEquals("w", function.apply("g", requests(1)).get("m0").workerId());
        assertTrue(function.apply("g", requests(1)).isEmpty());
        assertEquals(1, reads.size());
        assertEquals(1, clockReads.get());
        assertEquals(window(120_000L, 10), facts.get("w").platformProperties());
    }

    @Test void onlyAnotherRefillCanAdmitAWorkerAfterItsWindowChanges() {
        prepare("w", window(120_000L, 10));
        clock.set(179_999);
        assertTrue(refill().isEmpty());
        clock.set(180_000);
        assertTrue(function.apply("g", requests(1)).isEmpty());
        offered.put("w", 20L);
        assertEquals(List.of("w"), refill());
        assertEquals(new WorkerCandidate("w", 20), function.apply("g", requests(1)).get("m0"));
        assertEquals(2, reads.size());
    }

    @Test void malformedRecordsAreIsolatedWithoutRepairsOrPartialFieldDefaults() {
        var invalid = new ArrayList<Map<String, Object>>(List.of(
                Map.of("lastAssignedAt", 120_000L), Map.of("windowAssignmentCount", 0),
                window(120_000L, "0"), window(120_000L, -1), window(-1, 0), window(120_000L, 0.0),
                window(120_000L, new BigDecimal("0.5")), window(120_000L, BigInteger.ONE.shiftLeft(64))));
        var nullValue = new HashMap<String, Object>(); nullValue.put("lastAssignedAt", null); nullValue.put("windowAssignmentCount", 0);
        invalid.add(nullValue);
        for (int i = 0; i < invalid.size(); i++) prepare("invalid" + i, invalid.get(i));
        prepare("integer", window(BigInteger.valueOf(120_000), new BigDecimal("9.0")));
        offered.put("missing", 50L);
        assertEquals(List.of("integer"), refill());
        for (int i = 0; i < invalid.size(); i++) assertEquals(invalid.get(i), facts.get("invalid" + i).platformProperties());
    }

    @Test void factsFailureDoesNotAdmitStockOrCompensateEarlierAdmissions() {
        prepare("retained", Map.of()); refill();
        var failing = new AssignmentWindowPoolPolicy(pool, (group, ids) -> { throw new IllegalStateException("unavailable"); }, clock::get, windows);
        assertThrows(IllegalStateException.class, () -> failing.refill("g", targets, Map.of("new", 20L), 1));
        assertEquals(1, pool.countByKey("g").get("available"));
        assertEquals("retained", function.apply("g", requests(2)).get("m0").workerId());
        assertEquals(10_000, budget.available());
    }

    @Test void qualificationUsesOneSnapshotForTheAdmittedBatchAndCapacityRemainsOwnerLocal() {
        IntStream.range(0, 1000).forEach(i -> prepare("w" + i, Map.of()));
        assertEquals(1000, refill().size());
        assertEquals(List.of(1000), reads.stream().map(List::size).toList());
        assertEquals(1, clockReads.get());
        assertEquals(Map.of(target, 0), policy.deficits("g", targets));
        prepare("overflow", Map.of());
        assertTrue(policy.refill("g", targets, Map.of("overflow", 2000L), 1).isEmpty());
        assertEquals(1000, pool.countByKey("g").get("available"));
        assertEquals(1000, function.apply("g", requests(1000)).size());
        assertEquals(2, reads.size());
    }

    @Test void configurationAdmissionAndEmptyBatchesReadNoFacts() {
        assertTrue(policy.refill("g", targets, Map.of(), 1).isEmpty());
        assertTrue(policy.refill("g", targets, Map.of("w", 10L), 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> policy.normalizeQuery("g", new EligibilityQuery(Map.of("country", List.of("CN")))));
        assertThrows(IllegalArgumentException.class, () -> function.normalizeInput("g", Map.of("maxAssignments", 30)));
        assertThrows(IllegalArgumentException.class, () -> policy.refill("g", targets, Map.of("w", 0L), 1));
        assertTrue(reads.isEmpty());
        assertEquals(0, clockReads.get());
    }

    @Test void groupIsolationDuplicateOccurrencesAndLazyExpiryUseTheExistingPool() {
        prepare("w", window(120_000L, 1)); refill();
        assertTrue(policy.refill("other", targets, offered, 1).isEmpty());
        assertTrue(function.apply("other", requests(1)).isEmpty());
        pool.offerBatch("g", "available", List.of(new WorkerCandidate("w", 99)));
        assertEquals(Map.of("m0", new WorkerCandidate("w", 10), "m1", new WorkerCandidate("w", 99)), function.apply("g", requests(2)));
        assertEquals(10_000, budget.available());
        refill(); clock.addAndGet(60_000);
        assertTrue(function.apply("g", requests(1)).isEmpty());
        assertEquals(10_000, budget.available());
    }

    @Test void compositionKeepsAnyStockIndependentAndValidatesEveryQueryBeforeConsumption() {
        var client = mock(RedisClient.class);
        var store = spy(new FactsIndexStore(client, new RedisKeyspace("test_assignment_window"), Map.of()));
        doReturn(Map.of("window", new WorkerFacts("window", "g", Map.of(), Map.of()))).when(store).readFactsSnapshot(anyString(), anyList());
        var config = new MatchingGroup(Set.of("any", "assignment-window"), Set.of("worker.any", "worker.assignment.available"), windows.get("g"));
        try (var composition = new MatchingComposition(store, Map.of("g", config), clock::get)) {
            var any = composition.pools().get("any");
            any.offerBatch("g", "any", List.of(new WorkerCandidate("any", 7)));
            var catalog = composition.catalog();
            var queries = new LinkedHashMap<String, WorkerQuery>();
            queries.put("first", new WorkerQuery("worker.assignment.available", Map.of()));
            assertTrue(catalog.take("g", queries).isEmpty());
            assertEquals(1, any.countByKey("g").get("any"));
            var supply = List.of(RefillTarget.of("assignment-window", target, 100));
            assertEquals(1, catalog.refill("g", supply, Map.of("window", 9L)));
            queries.put("bad", new WorkerQuery("worker.assignment.available", Map.of("maxAssignments", 50)));
            assertThrows(IllegalArgumentException.class, () -> catalog.take("g", queries));
            queries.remove("bad");
            assertEquals(new WorkerCandidate("window", 9), catalog.take("g", queries).get("first"));
            assertEquals(new WorkerCandidate("any", 7), catalog.take("g", Map.of("a", new WorkerQuery("worker.any", Map.of()))).get("a"));
            verify(store, times(1)).readFactsSnapshot("g", List.of("window"));
            verifyNoInteractions(client);
        }
    }

    @Test void sharedRefillRotatesPoolsAndKeepsEarlierAdmissionsOnLaterFailure() {
        var client = mock(RedisClient.class);
        var store = spy(new FactsIndexStore(client, new RedisKeyspace("test_window_rotation"), Map.of()));
        doAnswer(call -> {
            List<String> ids = call.getArgument(1);
            var snapshot = new LinkedHashMap<String, WorkerFacts>();
            ids.forEach(id -> snapshot.put(id, new WorkerFacts(id, "g", Map.of(), Map.of())));
            return snapshot;
        }).when(store).readFactsSnapshot(anyString(), anyList());
        var config = new MatchingGroup(Set.of("any", "assignment-window"), Set.of("worker.any", "worker.assignment.available"), windows.get("g"));
        try (var composition = new MatchingComposition(store, Map.of("g", config), clock::get)) {
            var supply = List.of(RefillTarget.of("any", target, 100), RefillTarget.of("assignment-window", target, 100));
            var catalog = composition.catalog();
            assertEquals(1, catalog.refill("g", supply, Map.of("first", 10L)));
            assertEquals(1, catalog.refill("g", supply, Map.of("second", 11L)));
            assertEquals(1, composition.pools().get("any").countByKey("g").get("any"));
            assertEquals(1, composition.pools().get("assignment-window").countByKey("g").get("available"));
            assertEquals(1, catalog.refill("g", supply, Map.of("third", 12L)));
            // The next round starts with Any. It can accept one occurrence before the other Pool fails.
            var any = composition.pools().get("any");
            any.offerBatch("g", "any", IntStream.range(0, 998).mapToObj(i -> new WorkerCandidate("resident" + i, 20)).toList());
            doThrow(new IllegalStateException("snapshot unavailable")).when(store).readFactsSnapshot(anyString(), anyList());
            var offered = new LinkedHashMap<String, Long>(); offered.put("accepted", 30L); offered.put("unconfirmed", 31L);
            assertThrows(IllegalStateException.class, () -> catalog.refill("g", supply, offered));
            assertEquals(1000, any.countByKey("g").get("any"));
            assertEquals(2, composition.pools().get("assignment-window").countByKey("g").get("available"));
            assertEquals(8998, composition.budget().available());
            verifyNoInteractions(client);
        }
    }

    @Test void incompleteOrUnusedConfigurationFailsBeforeAnyRedisAccess() {
        assertThrows(IllegalArgumentException.class, () -> new MatchingGroup(Set.of("assignment-window"), Set.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new MatchingGroup(Set.of("any"), Set.of(), windows.get("g")));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentWindowPool(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentWindowPool(60_000, 0));
        var client = mock(RedisClient.class);
        assertThrows(IllegalArgumentException.class, () -> MatchingComposition.create(client, new RedisKeyspace("test_assignment_config"),
                Map.of("g", new MatchingGroup(Set.of("any"), Set.of("worker.assignment.available"), null))));
        verifyNoInteractions(client);
    }
}
