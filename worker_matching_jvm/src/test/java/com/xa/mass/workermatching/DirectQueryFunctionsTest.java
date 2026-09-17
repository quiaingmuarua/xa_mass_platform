package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.rules.*;
import io.lettuce.core.RedisClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectQueryFunctionsTest {
    @Test void identityNeedsNoFactsStockOrRedisAndRetainsInvocationLocalCorrelation() {
        var client = mock(RedisClient.class);
        try (var storage = new MatchingStorage(client, new RedisKeyspace("test_direct_identity"));
             var catalog = catalog(storage)) {
            var input = new LinkedHashMap<String, WorkerQuery>();
            input.put("first", new WorkerQuery("workerId", "w1"));
            input.put("second", new WorkerQuery("workerId", "w2"));
            input.put("duplicate", new WorkerQuery("workerId", "w1"));
            var found = catalog.take("unconfigured-group", input);
            assertEquals(List.of("first", "second"), List.copyOf(found.keySet()));
            assertEquals(new WorkerCandidate("w1", 0), found.get("first"));
            assertEquals(found, catalog.take("unconfigured-group", input));
            assertThrows(UnsupportedOperationException.class, found::clear);
            assertThrows(IllegalArgumentException.class, () -> catalog.normalizeRefill("g",List.of(new com.xa.mass.kernel.assignment.RefillTarget("workerId",new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()),1))));
            assertThrows(IllegalArgumentException.class, () -> catalog.normalizeRefill("g",List.of(new com.xa.mass.kernel.assignment.RefillTarget("worker.phone",new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()),1))));
            verifyNoInteractions(client);
        }
    }

    @Test void localAdmissionIsStrictAndPhoneEnablementDoesNotReadRedis() {
        var client = mock(RedisClient.class);
        try (var storage = new MatchingStorage(client, new RedisKeyspace("test_direct_admission"));
             var catalog = catalog(storage)) {
            for (Object input : List.of("", " ", 42, true, List.of("w"), Map.of("workerId", "w"))) {
                assertThrows(IllegalArgumentException.class, () -> catalog.normalizeQuery("g", new WorkerQuery("workerId", input)));
            }
            for (Object input : List.of("", 42, List.of("+1"), Map.of("phone", "+1"))) {
                assertThrows(IllegalArgumentException.class, () -> catalog.normalizeQuery("g", new WorkerQuery("worker.phone", input)));
            }
            var literal = new WorkerQuery("worker.phone", " +86123 ");
            assertEquals(literal, catalog.normalizeQuery("g", literal));
            assertThrows(IllegalArgumentException.class, () -> catalog.normalizeQuery("other", literal));
            var input = new LinkedHashMap<String, WorkerQuery>();
            input.put("valid", new WorkerQuery("worker.phone", "+1"));
            input.put("invalid", new WorkerQuery("workerId", " "));
            assertThrows(IllegalArgumentException.class, () -> catalog.take("g", input));
            assertEquals(Map.of(), catalog.take("g", Map.of()));
            verifyNoInteractions(client);
        }
    }

    private RedisWorkerMatchingCatalog catalog(MatchingStorage storage) {
        return new RedisWorkerMatchingCatalog(storage,
                Map.of("default", new DefaultPoolPolicy(storage,new CandidatePool(storage),Set.of())),
                Map.of("workerId", DirectQueryFunctions.identity(), "worker.phone", new DirectQueryFunctions(storage).phone()),
                Map.of("g",new MatchingGroup(Set.of(),Set.of("worker.phone"))), Map.of());
    }
}
