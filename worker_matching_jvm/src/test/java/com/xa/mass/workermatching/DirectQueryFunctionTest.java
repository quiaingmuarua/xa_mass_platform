package com.xa.mass.workermatching;

import com.xa.mass.workermatching.functions.PhoneQueryFunction;

import com.xa.mass.workermatching.functions.IdentityQueryFunction;

import com.xa.mass.workermatching.pool.CandidateBudget;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectQueryFunctionTest {
    final CandidateBudget budget = new CandidateBudget();
    @Test void identityNeedsNoFactsStockOrRedisAndRetainsInvocationLocalCorrelation() {

        {
            var catalog = catalog();
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

        }
    }

    @Test void localAdmissionIsStrictAndPhoneEnablementDoesNotReadRedis() {

        {
            var catalog = catalog();
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

        }
    }

    @Test void noPoolGroupRejectsAnyAndRetiredDefaultWithoutImplicitSupply() {

        {
            var catalog=new DefaultWorkerMatchingCatalog(budget, Map.of(), System::currentTimeMillis, Map.of(),
                    Map.of("workerId", new IdentityQueryFunction()), Map.of(), List.of(), Set.of("workerId"));
            assertEquals(List.of(),catalog.normalizeRefill("g",List.of()));
            assertEquals(Map.of(),catalog.observeRefillDeficits(Map.of("g",List.of())));
            for(String pool:List.of("any","default"))
                assertThrows(IllegalArgumentException.class,()->catalog.normalizeRefill("g",List.of(
                        new com.xa.mass.kernel.assignment.RefillTarget(pool,new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()),1))));
            for(String function:List.of("worker.any","worker.default"))
                assertThrows(IllegalArgumentException.class,()->catalog.normalizeQuery("g",new WorkerQuery(function,Map.of())));
            assertEquals(new WorkerCandidate("w",0),catalog.take("g",Map.of("m",new WorkerQuery("workerId","w"))).get("m"));

        }
    }

    private DefaultWorkerMatchingCatalog catalog() {
        return new DefaultWorkerMatchingCatalog(budget, Map.of(), System::currentTimeMillis, Map.of(), Map.of("workerId", new IdentityQueryFunction(), "worker.phone", new PhoneQueryFunction((group, values) -> { throw new AssertionError("admission must not read the index"); })), Map.of("g",new MatchingGroup(Set.of(),Set.of("worker.phone"))), List.of(), Set.of("workerId"));
    }
}
