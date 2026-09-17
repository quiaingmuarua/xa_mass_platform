package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.RefillTarget;
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
        var methods=Arrays.stream(PoolRefillPolicy.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("normalizeQuery","deficits","refill"),methods);
        assertEquals(0,PoolRefillPolicy.class.getDeclaredClasses().length);
        for(var method:PoolRefillPolicy.class.getDeclaredMethods())for(var type:method.getParameterTypes())
            assertFalse(type.getName().contains("Redis") || type.getName().contains("Lease")
                    || type.getName().contains("TaskDescriptor"));
    }
    @Test void admissionIsLocalAndNamedRulesRejectIds() {
        var client=mock(RedisClient.class);
        try(var storage=new MatchingStorage(client,new RedisKeyspace("test_admission"))) {
            var stock=new CandidatePool(storage);
            var rule=new CountryPoolPolicy(storage,stock);
            assertDoesNotThrow(()->rule.normalizeQuery("g",new EligibilityQuery(Map.of("worker.country",List.of("CN")))));
            assertThrows(IllegalArgumentException.class,()->EligibilityQuery.parse(Map.of("worker.country",Map.of("op","in","values",List.of("CN")))));
            assertDoesNotThrow(()->rule.normalizeQuery("g",EligibilityQuery.parse(Map.of("worker.country",List.of("CN")))));
            for(var query:List.of(Map.of("workerId",List.of("w")),Map.of("workerid",List.of("w")),
                    Map.of("country",List.of("CN")),Map.of("worker.country",List.of("cn"))))
                assertThrows(IllegalArgumentException.class,()->rule.normalizeQuery("g",new EligibilityQuery(query)));
            assertThrows(IllegalArgumentException.class,()->PoolQueryFunctions.country(stock).execute().apply("g",Map.of("m",Map.of("workerId",List.of("w")))));
            verifyNoInteractions(client);
        }
    }
    @Test void invalidCompositionAndTargetsFailWithoutRedis() {
        var client=mock(RedisClient.class);
        try(var storage=new MatchingStorage(client,new RedisKeyspace("test_admission"))) {
            assertThrows(IllegalArgumentException.class,()->MatchingComposition.create(storage,
                    Map.of("g",new MatchingGroup(Set.of("unknown"),Set.of()))));
            assertThrows(IllegalArgumentException.class,()->MatchingComposition.create(storage,
                    Map.of("g",new MatchingGroup(Set.of(),Set.of("worker.country")))));
            try(var catalog=MatchingComposition.create(storage,Map.of("g",new MatchingGroup(Set.of("country"),Set.of())))) {
                assertThrows(IllegalArgumentException.class,()->catalog.normalizeRefill("g",List.of(
                        new RefillTarget("country",new EligibilityQuery(Map.of("workerId",List.of("w"))),1))));
            }
            verifyNoInteractions(client);
        }
    }
}
