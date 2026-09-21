package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.functions.*;
import com.xa.mass.workermatching.index.PhoneIndex;
import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PoolQueryFunctionTest {
    @Test void admissionNormalizesLocalInputsWithoutTouchingInjectedResources() {
        var pool = mock(WorkerCandidatePool.class);
        var phones = mock(PhoneIndex.class);
        assertEquals(Map.of(), new AnyQueryFunction(pool).normalizeInput("g", Map.of()));
        assertEquals(List.of("CN", "US"), new CountryQueryFunction(pool).normalizeInput("g", List.of("US", "CN", "US")));
        assertEquals(Map.of("country", List.of("CN")), new MessagingQueryFunction(pool)
                .normalizeInput("g", Map.of("country", List.of("CN", "CN"))));
        assertThrows(IllegalArgumentException.class, () -> new MessagingQueryFunction(pool)
                .normalizeInput("g", Map.of("phone", "number")));
        assertEquals(Map.of("convergenceSlot", "slot"), new ProofFactsQueryFunction(pool)
                .normalizeInput("g", Map.of("convergenceSlot", "slot")));
        assertEquals(" number ", new PhoneQueryFunction(phones).normalizeInput("g", " number "));
        assertThrows(IllegalArgumentException.class, () -> new AnyQueryFunction(pool).normalizeInput("g", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CountryQueryFunction(pool).normalizeInput("g", List.of("cn")));
        assertThrows(IllegalArgumentException.class, () -> new MessagingQueryFunction(pool).normalizeInput("g", Map.of("unknown", "x")));
        assertThrows(IllegalArgumentException.class, () -> new ProofFactsQueryFunction(pool)
                .normalizeInput("g", Map.of("convergenceSlot", "slot", "proofPool", "p")));
        verifyNoInteractions(pool, phones);
    }

    @Test void equivalentInterleavedInputsAreAdmittedOnceAndKeepOriginalAssignmentOrder() {
        var budget = new CandidateBudget();
        var pool = spy(new WorkerCandidatePool(() -> 1000, budget));
        pool.offerBatch("g", "CN", List.of(new WorkerCandidate("cn1", 11), new WorkerCandidate("cn2", 13)));
        pool.offerBatch("g", "US", List.of(new WorkerCandidate("us", 12)));
        var function = spy(new CountryQueryFunction(pool));
        var client = mock(RedisClient.class);
        try (var storage = new FactsIndexStore(client, new RedisKeyspace("test_strategy_order"), Map.of());
                var catalog = new RedisWorkerMatchingCatalog(storage, budget, Map.of("country", pool), () -> 1000,
                        Map.of(), Map.of("country", function), Map.of("g", new MatchingGroup(Set.of(), Set.of("country"))), List.of(), Set.of())) {
            var inputs = new LinkedHashMap<String, WorkerQuery>();
            inputs.put("a", new WorkerQuery("country", List.of("CN", "CN")));
            inputs.put("b", new WorkerQuery("country", List.of("US")));
            inputs.put("c", new WorkerQuery("country", List.of("CN")));
            inputs.put("miss", new WorkerQuery("country", Map.of()));
            var result = catalog.take("g", inputs);
            assertEquals(List.of("a", "b", "c"), List.copyOf(result.keySet()));
            assertEquals(List.of(new WorkerCandidate("cn1", 11), new WorkerCandidate("us", 12), new WorkerCandidate("cn2", 13)), List.copyOf(result.values()));
            verify(function, times(4)).normalizeInput(eq("g"), any());
            verify(function).apply(eq("g"), anyMap());
            verify(pool).pollBatch("g", Set.of("CN"), 2);
            verify(pool).pollBatch("g", Set.of("US"), 1);
            verify(pool).pollAnyBatch("g", 1);
            assertThrows(UnsupportedOperationException.class, result::clear);
            verifyNoInteractions(client);
        }
    }

    @Test void entryBudgetAndLateInvalidInputAreRejectedBeforeAnyPoolConsumption() {
        var budget = new CandidateBudget();
        var pool = new WorkerCandidatePool(() -> 1000, budget);
        pool.offerBatch("g", "CN", List.of(new WorkerCandidate("w", 19)));
        var identity = spy(new IdentityQueryFunction());
        try (var storage = new FactsIndexStore(mock(RedisClient.class), new RedisKeyspace("test_strategy_admission"), Map.of());
                var catalog = new RedisWorkerMatchingCatalog(storage, budget, Map.of("country", pool), () -> 1000,
                        Map.of(), Map.of("country", new CountryQueryFunction(pool), "workerId", identity),
                        Map.of("g", new MatchingGroup(Set.of(), Set.of("country"))), List.of(), Set.of("workerId"))) {
            var oversized = new LinkedHashMap<String, WorkerQuery>();
            for (int i = 0; i <= 100; i++) oversized.put("m" + i, new WorkerQuery("workerId", "w" + i));
            assertThrows(IllegalArgumentException.class, () -> catalog.take("g", oversized));
            verifyNoInteractions(identity);
            oversized.remove("m100");
            assertEquals(100, catalog.take("g", oversized).size());

            var invalid = new LinkedHashMap<String, WorkerQuery>();
            invalid.put("good", new WorkerQuery("country", List.of("CN")));
            invalid.put("bad", new WorkerQuery("country", List.of("cn")));
            assertThrows(IllegalArgumentException.class, () -> catalog.take("g", invalid));
            assertEquals(Map.of("next", new WorkerCandidate("w", 19)), catalog.take("g", Map.of("next", invalid.get("good"))));
        }
    }
    @Test void proofPartialQueriesUseOneDirectorySnapshotAndKeepEachEntryInOneBucket() {
        var pool = spy(new WorkerCandidatePool(() -> 1000, new CandidateBudget()));
        var first = new WorkerMatchingCatalog.WorkerFacts("a", "g", Map.of("proofPool", "*", "proofTarget", "yes", "convergenceSlot", "slot"), Map.of("proofEnabled", "yes"));
        var second = new WorkerMatchingCatalog.WorkerFacts("b", "g", Map.of("proofPool", "~", "proofTarget", "yes"), Map.of("proofEnabled", "yes"));
        String a = com.xa.mass.workermatching.buckets.ProofFactsBuckets.bucketKey(first);
        String b = com.xa.mass.workermatching.buckets.ProofFactsBuckets.bucketKey(second);
        pool.offerBatch("g", a, List.of(new WorkerCandidate("a", 11)));
        pool.offerBatch("g", b, List.of(new WorkerCandidate("b", 12)));
        assertEquals(Map.of(a, 1, b, 1), pool.countByKey("g"));
        clearInvocations(pool);
        var inputs = new LinkedHashMap<String, Object>();
        inputs.put("specific", Map.of("proofPool", "*"));
        inputs.put("already-consumed", Map.of("convergenceSlot", "slot"));
        inputs.put("partial", Map.of("proofTarget", "yes"));
        assertEquals(Map.of("specific", new WorkerCandidate("a", 11), "partial", new WorkerCandidate("b", 12)),
                new ProofFactsQueryFunction(pool).apply("g", inputs));
        verify(pool, times(1)).countByKey("g");
        assertTrue(pool.countByKey("g").isEmpty());
    }
}
