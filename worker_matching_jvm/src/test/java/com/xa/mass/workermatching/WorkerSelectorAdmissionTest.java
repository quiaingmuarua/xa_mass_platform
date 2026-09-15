package com.xa.mass.workermatching;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.rules.*;
import io.lettuce.core.RedisClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerSelectorAdmissionTest {
    @Test void publicRuleContractContainsOnlyEligibilityOperations() {
        var methods=Arrays.stream(RuleHandler.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("normalizeQuery","deficits","refill","take"),methods);
        assertEquals(0,RuleHandler.class.getDeclaredClasses().length);
        for(var method:RuleHandler.class.getDeclaredMethods())for(var type:method.getParameterTypes())
            assertFalse(type.getName().contains("Redis") || type.getName().contains("Lease")
                    || type.getName().contains("TaskQuery"));
    }
    @Test void admissionIsLocalAndNamedRulesRejectIds() {
        var client=mock(RedisClient.class);
        try(var storage=new RedisRuleStorage(client,new RedisKeyspace("test_admission"))) {
            var rule=new CountryRuleHandler(storage);
            assertDoesNotThrow(()->rule.normalizeQuery("g",new EligibilityQuery(Map.of("worker.country",List.of("CN")))));
            assertThrows(IllegalArgumentException.class,()->EligibilityQuery.parse(Map.of("worker.country",Map.of("op","in","values",List.of("CN")))));
            assertDoesNotThrow(()->rule.normalizeQuery("g",EligibilityQuery.parse(Map.of("worker.country",List.of("CN")))));
            for(var query:List.of(Map.of("workerId",List.of("w")),Map.of("workerid",List.of("w")),
                    Map.of("country",List.of("CN")),Map.of("worker.country",List.of("cn"))))
                assertThrows(IllegalArgumentException.class,()->rule.normalizeQuery("g",new EligibilityQuery(query)));
            assertThrows(IllegalArgumentException.class,()->rule.take("g",Map.of(EligibilityQuery.parse(Map.of("workerId",List.of("w"))),1)));
            verifyNoInteractions(client);
        }
    }
    @Test void invalidCompositionAndTargetsFailWithoutRedis() {
        var client=mock(RedisClient.class);
        try(var storage=new RedisRuleStorage(client,new RedisKeyspace("test_admission"))) {
            var handlers=Map.<String,RuleHandler>of("worker.default",new DefaultRuleHandler(storage,Map.of()),
                    "worker.country",new CountryRuleHandler(storage));
            assertThrows(IllegalArgumentException.class,()->new RedisWorkerMatchingCatalog(storage,handlers,Map.of("g",Set.of("unknown")),Map.of()));
            assertThrows(IllegalArgumentException.class,()->new RedisWorkerMatchingCatalog(storage,handlers,Map.of("g",Set.of("worker.country")),
                    Map.of("g",Map.of("worker.country",List.of(new RefillTarget(Map.of("workerId",List.of("w")),1))))));
            assertThrows(IllegalArgumentException.class,()->new RedisRuleStorage(client,new RedisKeyspace("test_admission"),
                    Map.of("custom",List.of(new RedisRuleStorage.IndexMutation("country","return function() end"))),System::currentTimeMillis));
            verifyNoInteractions(client);
        }
    }
}
