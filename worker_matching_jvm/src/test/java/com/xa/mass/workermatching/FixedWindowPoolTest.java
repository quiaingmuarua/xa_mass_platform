package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.*;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.FixedWindowPoolDefinition.WindowLimit;
import com.xa.mass.workermatching.WorkerProperties.WorkerFacts;
import com.xa.mass.workermatching.functions.EmptyInputPoolQueryFunction;
import com.xa.mass.workermatching.pool.*;
import com.xa.mass.workermatching.refill.FixedWindowPoolPolicy;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import java.math.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FixedWindowPoolTest {
    final AtomicLong clock = new AtomicLong(120_000), clockReads = new AtomicLong();
    final CandidateBudget budget = new CandidateBudget();
    final WorkerCandidatePool pool = new WorkerCandidatePool(clock::get, budget);
    final Map<String, WorkerFacts> facts = new HashMap<>();
    final Map<String, Long> offered = new LinkedHashMap<>();
    final List<List<String>> reads = new ArrayList<>();
    final EligibilityQuery target = new EligibilityQuery(Map.of());
    final Map<EligibilityQuery, Integer> targets = Map.of(target, 100);
    final Map<String, WindowLimit> windows = Map.of("g", new WindowLimit(60_000, 10),
            "other", new WindowLimit(60_000, 1));
    final FixedWindowPoolDefinition definition = definition(windows);
    final FixedWindowPoolPolicy policy = new FixedWindowPoolPolicy(pool, (group, ids) -> {
        reads.add(List.copyOf(ids));
        var result = new LinkedHashMap<String, WorkerFacts>();
        ids.forEach(id -> { if (facts.containsKey(id)) result.put(id, facts.get(id)); });
        return result;
    }, () -> { clockReads.incrementAndGet(); return clock.get(); }, definition);
    final EmptyInputPoolQueryFunction function = new EmptyInputPoolQueryFunction(pool);

    long prepare(String id, Map<String, Object> properties) {
        facts.put(id, new WorkerFacts(id, "g", Map.of(), properties));
        long fence = offered.size() + 10L;
        offered.put(id, fence);
        return fence;
    }
    List<String> refill() { return policy.refill("g", targets, offered, offered.size()); }
    static Map<String, Object> window(Object time, Object count) {
        return Map.of("seenAt", time, "seenCount", count);
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
                Map.of("seenAt", 120_000L), Map.of("seenCount", 0),
                window(120_000L, "0"), window(120_000L, -1), window(-1, 0), window(120_000L, 0.0),
                window(120_000L, new BigDecimal("0.5")), window(120_000L, BigInteger.ONE.shiftLeft(64))));
        var nullValue = new HashMap<String, Object>(); nullValue.put("seenAt", null); nullValue.put("seenCount", 0);
        invalid.add(nullValue);
        for (int i = 0; i < invalid.size(); i++) prepare("invalid" + i, invalid.get(i));
        prepare("integer", window(BigInteger.valueOf(120_000), new BigDecimal("9.0")));
        offered.put("missing", 50L);
        assertEquals(List.of("integer"), refill());
        for (int i = 0; i < invalid.size(); i++) assertEquals(invalid.get(i), facts.get("invalid" + i).platformProperties());
    }

    @Test void factsFailureDoesNotAdmitStockOrCompensateEarlierAdmissions() {
        prepare("retained", Map.of()); refill();
        var failing = new FixedWindowPoolPolicy(pool, (group, ids) -> { throw new IllegalStateException("unavailable"); }, clock::get, definition);
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
        var config = new MatchingGroup(Set.of("any", "sample-window"), Set.of("worker.any", "worker.sample.available"));
        try (var composition = new MatchingComposition(store, Map.of("g", config), clock::get, List.of(definition(Map.of("g", windows.get("g")))))) {
            var any = composition.pools().get("any");
            any.offerBatch("g", "any", List.of(new WorkerCandidate("any", 7)));
            var catalog = composition.catalog();
            var queries = new LinkedHashMap<String, WorkerQuery>();
            queries.put("first", new WorkerQuery("worker.sample.available", Map.of()));
            assertTrue(catalog.take("g", queries).isEmpty());
            assertEquals(1, any.countByKey("g").get("any"));
            var supply = List.of(RefillTarget.of("sample-window", target, 100));
            assertEquals(1, catalog.refill("g", supply, Map.of("window", 9L)));
            queries.put("bad", new WorkerQuery("worker.sample.available", Map.of("maxAssignments", 50)));
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
        var config = new MatchingGroup(Set.of("any", "sample-window"), Set.of("worker.any", "worker.sample.available"));
        try (var composition = new MatchingComposition(store, Map.of("g", config), clock::get, List.of(definition(Map.of("g", windows.get("g")))))) {
            var supply = List.of(RefillTarget.of("any", target, 100), RefillTarget.of("sample-window", target, 100));
            var catalog = composition.catalog();
            assertEquals(1, catalog.refill("g", supply, Map.of("first", 10L)));
            assertEquals(1, catalog.refill("g", supply, Map.of("second", 11L)));
            assertEquals(1, composition.pools().get("any").countByKey("g").get("any"));
            assertEquals(1, composition.pools().get("sample-window").countByKey("g").get("available"));
            assertEquals(1, catalog.refill("g", supply, Map.of("third", 12L)));
            // The next round starts with Any. It can accept one occurrence before the other Pool fails.
            var any = composition.pools().get("any");
            any.offerBatch("g", "any", IntStream.range(0, 998).mapToObj(i -> new WorkerCandidate("resident" + i, 20)).toList());
            doThrow(new IllegalStateException("snapshot unavailable")).when(store).readFactsSnapshot(anyString(), anyList());
            var offered = new LinkedHashMap<String, Long>(); offered.put("accepted", 30L); offered.put("unconfirmed", 31L);
            assertThrows(IllegalStateException.class, () -> catalog.refill("g", supply, offered));
            assertEquals(1000, any.countByKey("g").get("any"));
            assertEquals(2, composition.pools().get("sample-window").countByKey("g").get("available"));
            assertEquals(8998, composition.budget().available());
            verifyNoInteractions(client);
        }
    }

    @Test void newNamesAndLiteralPropertyKeysCreateIndependentPoolsWithoutSpecialBranches() {
        var client = mock(RedisClient.class);
        var store = spy(new FactsIndexStore(client, new RedisKeyspace("test_declared_windows"), Map.of()));
        var alpha = new FixedWindowPoolDefinition("alpha", "custom.alpha", "a.last", "a.count", Map.of("g", new WindowLimit(60_000, 1)));
        var beta = new FixedWindowPoolDefinition("beta", "custom.beta", "bLast", "bCount", Map.of("g", new WindowLimit(30_000, 2)));
        var data = Map.of(
                "a", new WorkerFacts("a", "g", Map.of(), Map.of("a.last", 120_000, "a.count", 0, "bLast", 120_000, "bCount", 2)),
                "b", new WorkerFacts("b", "g", Map.of(), Map.of("a.last", 120_000, "a.count", 1, "bLast", 120_000, "bCount", 1)));
        doAnswer(call -> {
            List<String> ids = call.getArgument(1);
            var snapshot = new LinkedHashMap<String, WorkerFacts>();
            ids.forEach(id -> snapshot.put(id, data.get(id)));
            return snapshot;
        }).when(store).readFactsSnapshot(eq("g"), anyList());
        var group = new MatchingGroup(Set.of("alpha", "beta", "any"), Set.of("custom.alpha", "custom.beta", "worker.any"));
        try (var composition = new MatchingComposition(store, Map.of("g", group), clock::get, List.of(beta, alpha))) {
            assertEquals(List.of("alpha", "beta", "any"), composition.poolOrder());
            var offered = new LinkedHashMap<String, Long>(); offered.put("a", 11L); offered.put("b", 12L);
            assertEquals(2, composition.catalog().refill("g", List.of(RefillTarget.of("alpha", target, 1),
                    RefillTarget.of("beta", target, 1), RefillTarget.of("any", target, 1)), offered));
            var result = composition.catalog().take("g", Map.of("first", new WorkerQuery("custom.alpha", Map.of()),
                    "second", new WorkerQuery("custom.beta", Map.of()), "third", new WorkerQuery("worker.any", Map.of())));
            assertEquals(Map.of("first", new WorkerCandidate("a", 11), "second", new WorkerCandidate("b", 12)), result);
            verify(store).readFactsSnapshot("g", List.of("a", "b"));
            verify(store).readFactsSnapshot("g", List.of("b"));
            verify(store, times(2)).readFactsSnapshot(anyString(), anyList());
            assertEquals(10_000, composition.budget().available());
            assertThrows(IllegalArgumentException.class, () -> composition.catalog().take("other", Map.of("m", new WorkerQuery("custom.alpha", Map.of()))));
        }
        verifyNoInteractions(client);
    }

    @Test void definitionsAreImmutableAndInvalidDeclarationsFailBeforeRedisAccess() {
        var limits = new HashMap<>(Map.of("g", new WindowLimit(60_000, 10)));
        var declaration = definition(limits); limits.clear();
        assertEquals(1, declaration.limitsByGroup().size());
        assertThrows(UnsupportedOperationException.class, () -> declaration.limitsByGroup().clear());
        assertThrows(IllegalArgumentException.class, () -> new WindowLimit(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new WindowLimit(60_000, 0));
        assertThrows(IllegalArgumentException.class, () -> new FixedWindowPoolDefinition("p", "f", "same", "same", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new FixedWindowPoolDefinition("p", "f", " ", "count", Map.of()));
        var enabled = new MatchingGroup(Set.of("sample-window"), Set.of("worker.sample.available"));
        var client = mock(RedisClient.class);
        var keyspace = new RedisKeyspace("test_window_definitions");
        for (var definitions : List.of(List.<FixedWindowPoolDefinition>of(), List.of(definition(Map.of())),
                List.of(declaration, declaration), List.of(definition(Map.of("unknown", new WindowLimit(1, 1)))),
                List.of(declaration, new FixedWindowPoolDefinition("second", "worker.sample.available", "time", "count", Map.of())),
                List.of(new FixedWindowPoolDefinition("any", "custom", "time", "count", Map.of())),
                List.of(new FixedWindowPoolDefinition("custom", "workerId", "time", "count", Map.of()))))
            assertThrows(IllegalArgumentException.class, () -> MatchingComposition.create(client, keyspace, Map.of("g", enabled), definitions));
        assertThrows(IllegalArgumentException.class, () -> MatchingComposition.create(client, keyspace,
                Map.of("g", new MatchingGroup(Set.of("any"), Set.of())), List.of(declaration)));
        assertThrows(IllegalArgumentException.class, () -> MatchingComposition.create(client, keyspace,
                Map.of("g", new MatchingGroup(Set.of(), Set.of("worker.sample.available"))), List.of(definition(Map.of()))));
        verifyNoInteractions(client);
    }

    static FixedWindowPoolDefinition definition(Map<String, WindowLimit> limits) {
        return new FixedWindowPoolDefinition("sample-window", "worker.sample.available", "seenAt", "seenCount", limits);
    }
}
