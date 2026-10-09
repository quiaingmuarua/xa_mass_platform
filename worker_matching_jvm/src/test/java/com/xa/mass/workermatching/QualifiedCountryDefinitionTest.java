package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QualifiedCountryDefinitionTest {
    private static final QualifiedCountryDefinition ALPHA = new QualifiedCountryDefinition(
            "alpha", "sample.alpha", "sample.alpha.phone", "can.a", "yes", "nation");
    private static final QualifiedCountryDefinition BETA = new QualifiedCountryDefinition(
            "beta", "sample.beta", "sample.beta.phone", "can.b", "ok", "territory");
    private static final RedisKeyspace SCOPE = new RedisKeyspace("test_qualified_country");

    @Test void qualificationUsesLiteralKeysAndExactStringValuesWithoutCoercion() {
        var rule = new QualifiedCountryEligibility(ALPHA);
        assertEquals("CN", rule.country(Map.of("can.a", "yes", "nation", "CN")));
        assertNull(rule.country(null));
        for (var facts : List.<Map<String, Object>>of(Map.of(), Map.of("can.a", "yes"),
                Map.of("can", Map.of("a", "yes"), "nation", "CN"),
                Map.of("can.a", true, "nation", "CN"), Map.of("can.a", "YES", "nation", "CN"),
                Map.of("can.a", "yes", "nation", "cn"), Map.of("can.a", "yes", "nation", "CHN"),
                Map.of("can.a", "yes", "nation", 86), Map.of("can.a", "yes", "country", "CN")))
            assertNull(rule.country(facts));
    }

    @Test void declaredFieldsPoolsAndGroupsStayIndependentAndConsumptionUsesOnlyStock() {
        var client = mock(RedisClient.class);
        var store = spy(new FactsIndexStore(client, SCOPE, Map.of()));
        var facts = new HashMap<String, Map<String, Object>>();
        facts.put("a", Map.of("can.a", "yes", "nation", "CN"));
        facts.put("b", Map.of("can.b", "ok", "territory", "US"));
        facts.put("both", Map.of("can.a", "yes", "nation", "GB", "can.b", "ok", "territory", "US"));
        doAnswer(call -> {
            var selected = new LinkedHashMap<String, Map<String, Object>>();
            for (String id : call.<List<String>>getArgument(1))
                if (facts.containsKey(id)) selected.put(id, facts.get(id));
            return selected;
        }).when(store).readWorkerFacts(anyString(), anyList());
        var group = new MatchingGroup(Set.of("alpha", "beta", "any"), Set.of("sample.alpha", "sample.beta", "worker.any"));
        try (var composition = new MatchingComposition(store, Map.of("g", group, "h", group), () -> 1_000L,
                List.of(), List.of(BETA, ALPHA))) {
            assertEquals(List.of("any", "alpha", "beta"), composition.poolOrder());
            assertEquals(Map.of("g", Set.of(), "h", Set.of()), MatchingComposition.indexedProperties(
                    Map.of("g", group, "h", group), List.of(ALPHA, BETA)));
            var offered = new LinkedHashMap<String, Long>();
            offered.put("a", 11L); offered.put("b", 12L); offered.put("both", 13L); offered.put("missing", 14L);
            assertEquals(3, composition.catalog().refill("g", List.of(target("alpha"), target("beta")), offered));
            verify(store).readWorkerFacts("g", List.of("a", "b", "both", "missing"));
            verify(store).readWorkerFacts("g", List.of("b", "missing"));
            assertTrue(composition.catalog().take("h", Map.of("m", query("sample.alpha", Map.of()))).isEmpty());
            assertTrue(composition.catalog().take("g", Map.of("m", query("worker.any", Map.of()))).isEmpty());
            // Property changes without invalidation do not change already-admitted snapshot evidence.
            facts.clear();
            var requests = new LinkedHashMap<String, WorkerQuery>();
            requests.put("a", query("sample.alpha", Map.of("country", List.of("CN"))));
            requests.put("b", query("sample.beta", Map.of()));
            requests.put("both", query("sample.alpha", Map.of()));
            assertEquals(Map.of("a", new WorkerCandidate("a", 11), "b", new WorkerCandidate("b", 12),
                    "both", new WorkerCandidate("both", 13)), composition.catalog().take("g", requests));
            verify(store, times(2)).readWorkerFacts(anyString(), anyList());
            assertEquals(10_000, composition.budget().available());
        }
        verifyNoInteractions(client);
    }

    @Test void qualifiedPhoneFunctionsIndependentlyShareTheFixedIndexAndAllocateNoPool() {
        var client = mock(RedisClient.class);
        var groups = Map.of("g", new MatchingGroup(Set.of(), Set.of(ALPHA.phoneFunctionName(), BETA.phoneFunctionName())));
        assertEquals(Map.of("g", Set.of("phone")), MatchingComposition.indexedProperties(groups, List.of(ALPHA, BETA)));
        try (var composition = MatchingComposition.create(client, SCOPE, groups, List.of(), List.of(ALPHA, BETA))) {
            assertTrue(composition.pools().isEmpty());
            assertTrue(composition.policies().isEmpty());
            assertEquals(Set.of("workerId", ALPHA.phoneFunctionName(), BETA.phoneFunctionName()), composition.functions().keySet());
            assertEquals(query(ALPHA.phoneFunctionName(), Map.of("phone", "123", "country", List.of("CN"))),
                    composition.catalog().normalizeQuery("g", query(ALPHA.phoneFunctionName(), Map.of("phone", "123", "country", List.of("CN")))));
            assertTrue(composition.catalog().take("g", Map.of()).isEmpty());
        }
        verifyNoInteractions(client);
    }

    @Test void allDefinitionKindsHaveOneNameSpaceAndDeterministicResourceOrder() {
        var client = mock(RedisClient.class);
        var window = new FixedWindowPoolDefinition("window", "sample.window", "at", "count",
                Map.of("g", new FixedWindowPoolDefinition.WindowLimit(60_000, 10)));
        var group = new MatchingGroup(Set.of("proof-facts", "country", "window", "any", "alpha", "beta"),
                Set.of(ALPHA.poolFunctionName(), BETA.poolFunctionName(), window.functionName()));
        try (var composition = MatchingComposition.create(client, SCOPE, Map.of("g", group), List.of(window), List.of(BETA, ALPHA))) {
            assertEquals(List.of("proof-facts", "country", "window", "any", "alpha", "beta"), composition.poolOrder());
        }
        for (var invalid : List.of(List.of(ALPHA, ALPHA),
                List.of(new QualifiedCountryDefinition("any", "x", "y", "enabled", "yes", "country")),
                List.of(new QualifiedCountryDefinition("window", "x", "y", "enabled", "yes", "country")),
                List.of(new QualifiedCountryDefinition("a", "worker.any", "y", "enabled", "yes", "country")),
                List.of(new QualifiedCountryDefinition("a", "x", "sample.window", "enabled", "yes", "country")),
                List.of(ALPHA, new QualifiedCountryDefinition("a", "x", ALPHA.poolFunctionName(), "enabled", "yes", "country"))))
            assertThrows(IllegalArgumentException.class, () -> MatchingComposition.create(client, SCOPE,
                    Map.of("g", new MatchingGroup(Set.of("window"), Set.of())), List.of(window), invalid));
        for (var missing : List.of(new MatchingGroup(Set.of("alpha"), Set.of()),
                new MatchingGroup(Set.of(), Set.of(ALPHA.phoneFunctionName()))))
            assertThrows(IllegalArgumentException.class, () -> MatchingComposition.create(client, SCOPE, Map.of("g", missing), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> MatchingComposition.create(client, SCOPE,
                Map.of("g", new MatchingGroup(Set.of(), Set.of(ALPHA.poolFunctionName()))), List.of(), List.of(ALPHA)));
        try (var composition = MatchingComposition.create(client, SCOPE, Map.of(), List.of(), List.of())) {
            assertTrue(composition.pools().isEmpty());
            assertEquals(Set.of("workerId"), composition.functions().keySet());
        }
        verifyNoInteractions(client);
    }

    @Test void invalidDeclarationValuesAreRejectedBeforeAssembly() {
        for (int field = 0; field < 6; field++) {
            for (String invalid : Arrays.asList(null, "", " ")) {
                String[] values = {"pool", "take", "phone", "enabled", "yes", "country"};
                values[field] = invalid;
                assertThrows(IllegalArgumentException.class, () -> new QualifiedCountryDefinition(
                        values[0], values[1], values[2], values[3], values[4], values[5]));
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> new QualifiedCountryDefinition("pool", "same", "same", "enabled", "yes", "country"));
    }

    private static RefillTarget target(String pool) { return RefillTarget.of(pool, new EligibilityQuery(Map.of()), 100); }
    private static WorkerQuery query(String function, Object input) { return new WorkerQuery(function, input); }
}
