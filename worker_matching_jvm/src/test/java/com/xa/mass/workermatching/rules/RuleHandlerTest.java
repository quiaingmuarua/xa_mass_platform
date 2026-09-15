package com.xa.mass.workermatching.rules;

import com.xa.mass.workermatching.*;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import io.lettuce.core.RedisClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleHandlerTest {
    @Test void messagingKeepsCountryAndPhoneIntersection() {
        try(var storage=new RedisRuleStorage(mock(RedisClient.class),new RedisKeyspace("test_rule"))) {
            var handler=new MessagingRuleHandler(storage);
            var q=handler.normalizeTarget("g",new EligibilityQuery(Map.of("worker.country",List.of("CN"),
                    "worker.phone",List.of("+86123")),1));
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
                assertDoesNotThrow(()->rule.normalizeTarget("g",new EligibilityQuery(Map.of(),1)));
                assertThrows(IllegalArgumentException.class,()->rule.normalizeTarget("g",new EligibilityQuery(Map.of("workerId",List.of("w")),1)));
            }
        }
    }
    @Test void defaultFiniteIdsNeedNoFactsAndCountryIsExplicitlyEnabled() {
        var client=mock(RedisClient.class);
        try(var storage=new RedisRuleStorage(client,new RedisKeyspace("test_rule"),Map.of(),()->1000)) {
            var rule=new DefaultRuleHandler(storage,Map.of("country",Set.of("worker.country")));
            var target=rule.normalizeTarget("g",new EligibilityQuery(Map.of("workerId",List.of("b","a","a")),100));
            assertEquals(List.of("a","b"),target.query().get("workerId")); assertEquals(100,target.count());
            assertEquals(2,rule.deficits("g",List.of(target)).get(target));
            assertEquals(List.of("a","b"),rule.refill("g",List.of(target),List.of(
                    new HeldCandidate("outside",1,2000),new HeldCandidate("a",2,2000),new HeldCandidate("b",3,2000)),100));
            assertEquals(0,rule.deficits("g",List.of(target)).get(target));
            var selector=TaskItemWorkerSelector.parse(Map.of("workerId",List.of("a","b")));
            assertEquals(2,rule.take("g",Map.of(selector,100)).get(selector).size());
            assertThrows(IllegalArgumentException.class,()->rule.normalizeTarget("g",new EligibilityQuery(Map.of("worker.country",List.of("CN")),1)));
            assertDoesNotThrow(()->rule.normalizeTarget("country",new EligibilityQuery(Map.of("worker.country",List.of("CN")),1)));
            verifyNoInteractions(client);
        }
    }
}
