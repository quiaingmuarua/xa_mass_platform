package com.xa.mass.workermatching.rules;

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

class RuleHandlerTest {
    @Test void messagingKeepsCountryAndPhoneIntersection() {
        try(var storage=new RedisRuleStorage(mock(RedisClient.class),new RedisKeyspace("test_rule"))) {
            var handler=new MessagingRuleHandler(storage);
            var q=handler.normalizeQuery("g",new EligibilityQuery(Map.of("worker.country",List.of("CN"),
                    "worker.phone",List.of("+86123"))));
            var match=handler.predicate("g",q);
            assertTrue(match.test("w",new PartitionedZsetIndex.Projection("65",Set.of("phone:+86123"))));
            assertFalse(match.test("w",new PartitionedZsetIndex.Projection("65",Set.of("phone:other"))));
            assertFalse(match.test("w",new PartitionedZsetIndex.Projection("538",Set.of("phone:+86123"))));
            assertFalse(match.test("w",null));
        }
    }
    @Test void namedRulesAcceptAnyButRejectExplicitIdentity() {
        try(var storage=new RedisRuleStorage(mock(RedisClient.class),new RedisKeyspace("test_rule"))) {
            for(var rule:List.of(new CountryRuleHandler(storage),new MessagingRuleHandler(storage),new ProofFactsRuleHandler(storage))) {
                assertDoesNotThrow(()->rule.normalizeQuery("g",new EligibilityQuery(Map.of())));
                assertThrows(IllegalArgumentException.class,()->rule.normalizeQuery("g",new EligibilityQuery(Map.of("workerId",List.of("w")))));
            }
        }
    }
    @Test void defaultFiniteIdsNeedNoFactsAndCountryIsExplicitlyEnabled() {
        var client=mock(RedisClient.class);
        try(var storage=new RedisRuleStorage(client,new RedisKeyspace("test_rule"),Map.of(),()->1000)) {
            var rule=new DefaultRuleHandler(storage,Map.of("country",Set.of("worker.country")));
            var target=rule.normalizeQuery("g",new EligibilityQuery(Map.of("workerId",List.of("b","a","a"))));
            assertEquals(target,rule.normalizeQuery("g",target));
            assertEquals(List.of("a","b"),target.query().get("workerId"));
            assertEquals(2,rule.deficits("g",Map.of(target,100)).get(target));
            assertEquals(List.of("a","b"),rule.refill("g",Map.of(target,100),List.of(
                    new HeldCandidate("outside",1,2000),new HeldCandidate("a",2,2000),new HeldCandidate("b",3,2000)),100));
            assertEquals(0,rule.deficits("g",Map.of(target,100)).get(target));
            var selector=EligibilityQuery.parse(Map.of("workerId",List.of("a","b")));
            assertEquals(2,rule.take("g",Map.of(selector,100)).get(selector).size());
            assertThrows(IllegalArgumentException.class,()->rule.normalizeQuery("g",new EligibilityQuery(Map.of("worker.country",List.of("CN")))));
            assertDoesNotThrow(()->rule.normalizeQuery("country",new EligibilityQuery(Map.of("worker.country",List.of("CN")))));
            verifyNoInteractions(client);
        }
    }
    @Test void normalizedMatchesKeepOriginalInputKeysAndOperationOrder() {
        var client=mock(RedisClient.class);
        try(var storage=new RedisRuleStorage(client,new RedisKeyspace("test_query_keys"),Map.of(),()->1000)) {
            var rule=new DefaultRuleHandler(storage,Map.of());
            var any=new EligibilityQuery(Map.of());
            var supplied=new EligibilityQuery(Map.of("workerId",List.of("b","a","a")));
            var targets=new LinkedHashMap<EligibilityQuery,Integer>();
            targets.put(supplied,100); targets.put(any,3);
            var deficits=rule.deficits("g",targets);
            assertEquals(List.of(supplied,any),List.copyOf(deficits.keySet()));
            assertEquals(2,deficits.get(supplied));
            assertThrows(UnsupportedOperationException.class,deficits::clear);
            rule.refill("g",targets,List.of(new HeldCandidate("a",11,2000),new HeldCandidate("b",12,2000)),100);
            var limits=new LinkedHashMap<EligibilityQuery,Integer>(); limits.put(supplied,1); limits.put(any,1);
            var taken=rule.take("g",limits);
            assertEquals(List.of(supplied,any),List.copyOf(taken.keySet()));
            assertEquals(2,taken.values().stream().flatMap(List::stream).map(HeldCandidate::workerId).distinct().count());
            assertThrows(IllegalArgumentException.class,()->rule.refill("g",Map.of(any,0),List.of(),0));
            assertThrows(IllegalArgumentException.class,()->rule.deficits("g",Map.of(any,1001)));
            verifyNoInteractions(client);
        }
    }
}
