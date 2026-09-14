package com.xa.mass.workermatching;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.rules.*;
import io.lettuce.core.RedisClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkerSelectorAdmissionTest {
    @Test void ruleFunctionsCannotReceiveSchedulingCapabilitiesOrReturnDiscoveredIdentities() {
        var methods=java.util.Arrays.stream(RuleHandler.Bound.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("normalize","selector","compile","snapshot"),methods);
        for(var method:RuleHandler.Bound.class.getDeclaredMethods()) {
            for(var parameter:method.getParameterTypes())org.junit.jupiter.api.Assertions.assertFalse(
                    parameter.getName().startsWith("com.xa.mass.kernel"));
        }
    }

    @Test void boundAdmissionIsLocalAndRejectsIdentityForNamedRules() {
        var handler=new CountryRuleHandler().bind(()->{throw new AssertionError("Redis read");},"index",Set.of());
        var index=new SharedEligibility(new SharedEligibilityInventory(),new SharedEligibilityInventory.Scope("g","worker.country"),handler);
        assertDoesNotThrow(()->index.normalize(Map.of("worker.country",List.of("CN")),1));
        assertThrows(IllegalArgumentException.class,()->index.selector(Map.of("worker.country",List.of("CN")),1));
        assertDoesNotThrow(()->index.selector(Map.of("worker.country",Map.of("op","eq","values",List.of("CN"))),1));
        for(var expression:List.of(Map.of("workerId",List.of("w")),Map.of("workerid",List.of("w")),
                Map.of("country",List.of("CN")),Map.of("worker.country",List.of("cn")),
                Map.of("worker.country",Map.of("op","range","values",List.of("CN","US"))))) {
            assertThrows(IllegalArgumentException.class,()->index.normalize(expression,1));
        }
    }
    @Test void invalidCompositionAndDefaultsFailWithoutRedis() {
        var client=RedisClient.create("redis://127.0.0.1:1");
        try {
            var handlers=Map.<String,RuleHandler>of("worker.default",new DefaultRuleHandler(),"worker.country",new CountryRuleHandler());
            assertThrows(IllegalArgumentException.class,()->new RedisWorkerMatchingCatalog(client,new RedisKeyspace("test_rule_admission"),handlers,Map.of("g",Set.of("unknown")),Map.of()));
            assertThrows(IllegalArgumentException.class,()->new RedisWorkerMatchingCatalog(client,new RedisKeyspace("test_rule_admission"),handlers,Map.of("g",Set.of("worker.country")),
                    Map.of("g",Map.of("worker.country",List.of(new EligibilityQuery(Map.of("workerId",List.of("w")),1))))));
            assertThrows(IllegalArgumentException.class,()->new RedisWorkerMatchingCatalog(client,new RedisKeyspace("test_rule_admission"),
                    Map.of("worker.default",new DefaultRuleHandler(),"one",new CountryRuleHandler(),"two",new CountryRuleHandler()),Map.of(),Map.of()));
        } finally { client.shutdown(); }
    }
}
