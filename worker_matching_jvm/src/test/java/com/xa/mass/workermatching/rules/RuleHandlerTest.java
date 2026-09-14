package com.xa.mass.workermatching.rules;

import com.xa.mass.workermatching.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuleHandlerTest {
    @Test void messagingKeepsCountryAndPhoneIntersection() {
        var handler=new MessagingRuleHandler().bind(()->{throw new AssertionError("read");},"index",Set.of());
        var q=handler.normalize(Map.of("worker.country",Map.of("op","eq","values",List.of("CN")),
                "worker.phone",Map.of("op","eq","values",List.of("+86123"))),1);
        var compiled=handler.compile(q);
        assertTrue(compiled.matches(new RuleHandler.Member("w",new PartitionedZsetIndex.Projection("65",Set.of("phone:+86123")))));
        assertFalse(compiled.matches(new RuleHandler.Member("w",new PartitionedZsetIndex.Projection("65",Set.of("phone:other")))));
        assertFalse(compiled.matches(new RuleHandler.Member("w",new PartitionedZsetIndex.Projection("538",Set.of("phone:+86123")))));
        assertFalse(compiled.matches(new RuleHandler.Member("w",null)));
    }
    @Test void namedRulesAcceptAnyButRejectExplicitIdentity() {
        for(var rule:List.of(new CountryRuleHandler(),new MessagingRuleHandler(),new ProofFactsRuleHandler())) {
            var bound=rule.bind(()->{throw new AssertionError("read");},"index",Set.of());
            assertDoesNotThrow(()->bound.normalize(Map.of(),1));
            assertThrows(IllegalArgumentException.class,()->bound.normalize(Map.of("workerId",List.of("w")),1));
        }
    }
    @Test void defaultHasFiniteIdentityTargetAndOptionalCountryCapability() {
        var rule=new DefaultRuleHandler(); var bound=rule.bind(()->{throw new AssertionError("read");},"index",Set.of());
        var query=bound.normalize(Map.of("workerId",List.of("b","a","a")),100);
        assertEquals(List.of("a","b"),query.query().get("workerId")); assertEquals(100,query.count());
        assertEquals(2,bound.compile(query).target(100));
        assertThrows(IllegalArgumentException.class,()->bound.normalize(Map.of("worker.country",List.of("CN")),1));
        assertDoesNotThrow(()->rule.bind(()->{throw new AssertionError("read");},"index",Set.of("worker.country"))
                .normalize(Map.of("worker.country",Map.of("op","eq","values",List.of("CN"))),1));
    }
}
