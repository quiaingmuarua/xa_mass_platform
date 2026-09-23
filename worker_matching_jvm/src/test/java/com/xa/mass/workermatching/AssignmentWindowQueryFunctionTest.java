package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.MatchingGroup.AssignmentWindow;
import com.xa.mass.workermatching.WorkerProperties.WorkerFacts;
import com.xa.mass.workermatching.functions.AssignmentWindowQueryFunction;
import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssignmentWindowQueryFunctionTest {
    private final AtomicLong clock = new AtomicLong(120_000);
    private final CandidateBudget budget = new CandidateBudget();
    private final WorkerCandidatePool pool = new WorkerCandidatePool(clock::get, budget);
    private final Map<String, WorkerFacts> facts = new HashMap<>();
    private final List<List<String>> reads = new ArrayList<>();
    private final AssignmentWindowQueryFunction function = new AssignmentWindowQueryFunction(pool, (group, ids) -> {
        reads.add(List.copyOf(ids));
        var result = new LinkedHashMap<String, WorkerFacts>();
        ids.forEach(id -> { if (facts.containsKey(id)) result.put(id, facts.get(id)); });
        return result;
    }, clock::get, Map.of("g", new AssignmentWindow(60_000, 10), "other", new AssignmentWindow(60_000, 1)));

    private void offer(String id, Map<String, Object> properties) {
        facts.put(id, new WorkerFacts(id, "g", Map.of(), properties));
        pool.offerBatch("g", "any", List.of(new WorkerCandidate(id, id.hashCode() + 1L)));
    }
    private static Map<String, Object> requests(int count) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < count; i++) result.put("m" + i, Map.of());
        return result;
    }
    private static Map<String, Object> window(Object time, Object count) {
        return Map.of("lastAssignedAt", time, "windowAssignmentCount", count);
    }

    @Test void windowBoundariesKeepOriginalCandidatesAndAssociateInRequestOrder() {
        offer("blocked", window(120_000L, 10));
        offer("below", window(120_001L, 9));
        offer("old", window(119_999L, 999));
        offer("future", window(180_000L, 0));
        offer("new", Map.of("unrelated", "retained"));
        var result = function.apply("g", requests(5));
        assertEquals(List.of("m0", "m1", "m2"), List.copyOf(result.keySet()));
        assertEquals(List.of("below", "old", "new"), result.values().stream().map(WorkerCandidate::workerId).toList());
        assertEquals("below".hashCode() + 1L, result.get("m0").expectedScore());
        assertThrows(UnsupportedOperationException.class, result::clear);
        assertEquals(1, reads.size());
        assertEquals(Map.of(), pool.countByKey("g"));
        assertEquals(10_000, budget.available());
        assertEquals(window(120_000L, 10), facts.get("blocked").platformProperties());
    }

    @Test void clockBoundaryRequalifiesRetainedPropertiesWithoutResetOrReservation() {
        offer("first", window(120_000L, 10));
        clock.set(179_999);
        assertTrue(function.apply("g", requests(1)).isEmpty());
        clock.set(180_000);
        offer("second", window(120_000L, 10));
        assertEquals("second", function.apply("g", requests(1)).get("m0").workerId());
        offer("third", Map.of());
        assertEquals("third", function.apply("g", requests(1)).get("m0").workerId());
        assertEquals(Map.of(), facts.get("third").platformProperties());
    }

    @Test void malformedRecordsAreIsolatedAndNeverRepaired() {
        var invalid = new ArrayList<Map<String, Object>>(List.of(
                Map.of("lastAssignedAt", 120_000L), Map.of("windowAssignmentCount", 0),
                window(120_000L, "0"), window(120_000L, -1), window(-1, 0), window(120_000L, 0.0),
                window(120_000L, new BigDecimal("0.5")), window(120_000L, BigInteger.ONE.shiftLeft(64))));
        var nullValue = new HashMap<String, Object>(); nullValue.put("lastAssignedAt", null); nullValue.put("windowAssignmentCount", 0);
        invalid.add(nullValue);
        for (int i = 0; i < invalid.size(); i++) offer("invalid" + i, invalid.get(i));
        offer("integer", window(BigInteger.valueOf(120_000), new BigDecimal("9.0")));
        pool.offerBatch("g", "any", List.of(new WorkerCandidate("missing", 7)));
        var result = function.apply("g", requests(invalid.size() + 2));
        assertEquals(Set.of("m0"), result.keySet());
        assertEquals("integer", result.get("m0").workerId());
        for (int i = 0; i < invalid.size(); i++) assertEquals(invalid.get(i), facts.get("invalid" + i).platformProperties());
    }

    @Test void consumesOnlyRequestedStockWithoutReplacementAndReadsNoEmptyPage() {
        assertEquals(Map.of(), function.apply("g", requests(1)));
        assertTrue(reads.isEmpty());
        offer("blocked", window(120_000L, 10)); offer("available", Map.of());
        assertEquals(Map.of(), function.apply("g", Map.of()));
        assertTrue(reads.isEmpty());
        assertEquals(Map.of(), function.apply("g", requests(1)));
        assertEquals(List.of(List.of("blocked")), reads);
        assertEquals(1, pool.countByKey("g").get("any"));
        assertEquals("available", function.apply("g", requests(1)).get("m0").workerId());
    }

    @Test void duplicateOccurrencesUseFirstFenceAndDoNotTriggerReplacementPoll() {
        offer("w", Map.of());
        pool.offerBatch("g", "any", List.of(new WorkerCandidate("w", 99)));
        offer("next", Map.of());
        var result = function.apply("g", requests(2));
        assertEquals(Map.of("m0", new WorkerCandidate("w", "w".hashCode() + 1L)), result);
        assertEquals(List.of(List.of("w")), reads);
        assertEquals(1, pool.countByKey("g").get("any"));
    }

    @Test void factsFailureConsumesOfferedPageWithoutFallbackOrSecondPoll() {
        offer("w", Map.of()); offer("next", Map.of());
        var failing = new AssignmentWindowQueryFunction(pool, (group, ids) -> { throw new IllegalStateException("read failed"); },
                clock::get, Map.of("g", new AssignmentWindow(60_000, 10)));
        assertThrows(IllegalStateException.class, () -> failing.apply("g", requests(1)));
        assertEquals("next", function.apply("g", requests(1)).get("m0").workerId());
        assertTrue(pool.countByKey("g").isEmpty());
    }

    @Test void groupsUseIndependentThresholdsAndObservationLagIsNotAReservation() {
        offer("w", window(120_000L, 1));
        pool.offerBatch("other", "any", List.of(new WorkerCandidate("w", 2)));
        assertTrue(function.apply("other", requests(1)).isEmpty());
        assertEquals("w", function.apply("g", requests(1)).get("m0").workerId());
        // No new observation has arrived: another independent occurrence can pass again.
        pool.offerBatch("g", "any", List.of(new WorkerCandidate("w", 3)));
        assertEquals(3, function.apply("g", requests(1)).get("m0").expectedScore());
        assertEquals(window(120_000L, 1), facts.get("w").platformProperties());
    }

    @Test void qualificationChunksByFactsOwnerBudgetWithoutAddingAnExecutorLimit() {
        int count = WorkerProperties.MAX_BATCH_SIZE + 1;
        IntStream.range(0, count).forEach(i -> offer("w" + i, Map.of()));
        assertEquals(count, function.apply("g", requests(count)).size());
        assertEquals(List.of(WorkerProperties.MAX_BATCH_SIZE, 1), reads.stream().map(List::size).toList());
    }

    @Test void compositionSharesAnyStockAndRejectsTheEntireInvalidBatchBeforeConsumption() {
        var client = mock(RedisClient.class);
        var store = spy(new FactsIndexStore(client, new RedisKeyspace("test_assignment_window"), Map.of()));
        doAnswer(call -> Map.of("w", new WorkerFacts("w", "g", Map.of(), Map.of())))
                .when(store).readFactsSnapshot(eq("g"), anyList());
        var config = new MatchingGroup(Set.of("any"), Set.of("worker.any", "worker.assignment.available"), new AssignmentWindow(60_000, 10));
        try (var composition = new MatchingComposition(store, Map.of("g", config), clock::get)) {
            var stock = composition.pools().get("any");
            stock.offerBatch("g", "any", List.of(new WorkerCandidate("w", 7)));
            var queries = new LinkedHashMap<String, WorkerQuery>();
            queries.put("first", new WorkerQuery("worker.assignment.available", Map.of()));
            queries.put("bad", new WorkerQuery("worker.assignment.available", Map.of("maxAssignments", 50)));
            assertThrows(IllegalArgumentException.class, () -> composition.catalog().take("g", queries));
            assertEquals(1, stock.countByKey("g").get("any"));
            verify(store, never()).readFactsSnapshot(anyString(), anyList());
            queries.remove("bad");
            assertEquals(new WorkerCandidate("w", 7), composition.catalog().take("g", queries).get("first"));
            assertTrue(composition.catalog().take("g", Map.of("any", new WorkerQuery("worker.any", Map.of()))).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> composition.catalog().take("other", queries));
            verifyNoInteractions(client);
        }
    }

    @Test void invalidConfigurationFailsBeforeRedisOrStockAccess() {
        var client = mock(RedisClient.class);
        for (var config : List.of(
                new MatchingGroup(Set.of("any"), Set.of("worker.assignment.available"), null),
                new MatchingGroup(Set.of(), Set.of("worker.assignment.available"), new AssignmentWindow(60_000, 10)))) {
            assertThrows(IllegalArgumentException.class, () -> MatchingComposition.create(client,
                    new RedisKeyspace("test_assignment_config"), Map.of("g", config)));
        }
        assertThrows(IllegalArgumentException.class, () -> new AssignmentWindow(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentWindow(60_000, 0));
        verifyNoInteractions(client);
    }
}
