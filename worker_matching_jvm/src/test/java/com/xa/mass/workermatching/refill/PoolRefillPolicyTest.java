package com.xa.mass.workermatching.refill;

import com.xa.mass.workermatching.functions.AnyQueryFunction;
import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.CandidatePool;
import com.xa.mass.workermatching.index.MessagingIndex;
import com.xa.mass.workermatching.index.PartitionedZsetIndex;
import com.xa.mass.workermatching.index.ProofFactsIndex;
import com.xa.mass.workermatching.storage.FactsIndexStore;

import com.xa.mass.kernel.assignment.RefillTarget;

import com.xa.mass.workermatching.*;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import io.lettuce.core.RedisClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PoolRefillPolicyTest {
    final CandidateBudget budget = new CandidateBudget();
    @Test void messagingKeepsCountryAndPhoneIntersection() {
        try(var storage=new FactsIndexStore(mock(RedisClient.class), new RedisKeyspace("test_rule"), Map.of())) {
            var stock=new CandidatePool(()->1000, budget);
            var handler=new MessagingPoolPolicy(()->1000, stock, new MessagingIndex(storage::commands, storage.keyspace()));
            var q=handler.normalizeQuery("g",new EligibilityQuery(Map.of("worker.country",List.of("CN"),
                    "worker.phone",List.of("+86123"))));
            var match=handler.target("g",q);
            assertTrue(match.matches("w",handler.memberships("g","w",new PartitionedZsetIndex.Projection("65",Set.of("phone:+86123")))));
            assertFalse(match.matches("w",handler.memberships("g","w",new PartitionedZsetIndex.Projection("65",Set.of("phone:other")))));
            assertFalse(match.matches("w",handler.memberships("g","w",new PartitionedZsetIndex.Projection("538",Set.of("phone:+86123")))));
            assertNull(handler.memberships("g","w",null));
        }
    }
    @Test void namedRulesAcceptAnyButRejectExplicitIdentity() {
        try(var storage=new FactsIndexStore(mock(RedisClient.class), new RedisKeyspace("test_rule"), Map.of())) {
            for(var rule:List.of(new CountryPoolPolicy(()->1000, new CandidatePool(()->1000, budget), storage::readWorkerFacts),new MessagingPoolPolicy(()->1000, new CandidatePool(()->1000, budget), new MessagingIndex(storage::commands, storage.keyspace())),new ProofFactsPoolPolicy(()->1000, new CandidatePool(()->1000, budget), new ProofFactsIndex(storage::commands, storage.keyspace())))) {
                assertDoesNotThrow(()->rule.normalizeQuery("g",new EligibilityQuery(Map.of())));
                assertThrows(IllegalArgumentException.class,()->rule.normalizeQuery("g",new EligibilityQuery(Map.of("workerId",List.of("w")))));
            }
        }
    }
    @Test void anyNeedsNoFactsAndRejectsIdentityAndCountryConditions() {
        var client=mock(RedisClient.class);
        try(var storage=new FactsIndexStore(client, new RedisKeyspace("test_any"), Map.of())) {
            var stock=new CandidatePool(()->1000, budget);
            var rule=new AnyPoolPolicy(()->1000, stock);
            var function=new AnyQueryFunction(stock);
            var target=new EligibilityQuery(Map.of());
            assertEquals(target,rule.normalizeQuery("g",target));
            assertEquals(2,rule.deficits("g",Map.of(target,2)).get(target));
            assertEquals(List.of("a","b"),rule.refill("g",Map.of(target,2),List.of(
                    new HeldCandidate("a",2,2000),new HeldCandidate("b",3,2000)),100));
            assertEquals(0,rule.deficits("g",Map.of(target,2)).get(target));
            var requests=new LinkedHashMap<String,Object>();requests.put("first",Map.of());requests.put("second",Map.of());
            var result=function.apply("g",requests);
            assertEquals(List.of("first","second"),List.copyOf(result.keySet()));
            assertEquals(List.of("a","b"),result.values().stream().map(c->c.workerId()).toList());
            assertThrows(UnsupportedOperationException.class,result::clear);
            for(var fields:List.of(Map.of("workerId",List.of("a")),Map.of("worker.country",List.of("CN"))))
                assertThrows(IllegalArgumentException.class,()->rule.normalizeQuery("g",new EligibilityQuery(fields)));
            for(var input:List.of(Map.of("workerId",List.of("a")),Map.of("country",List.of("CN")),List.of("CN")))
                assertThrows(IllegalArgumentException.class,()->function.normalizeInput("g",input));
            assertThrows(IllegalArgumentException.class,()->rule.refill("g",Map.of(target,0),List.of(),0));
            assertThrows(IllegalArgumentException.class,()->rule.deficits("g",Map.of(target,1001)));
            verifyNoInteractions(client);
        }
    }
}
