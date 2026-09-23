package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.*;
import com.xa.mass.kernel.assignment.WorkerMatching.*;
import com.xa.mass.workermatching.pool.CandidateBudget;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryFunctionTest {
    final CandidateBudget budget = new CandidateBudget();

    DefaultWorkerMatchingCatalog catalog(Map<String, QueryFunction> functions) {
        return new DefaultWorkerMatchingCatalog(budget, Map.of(), System::currentTimeMillis, Map.of(),
                functions, Map.of("g", new MatchingGroup(Set.of(), functions.keySet(), null)), List.of(), Set.of());
    }

    @Test void globalAvailabilityIsCompositionDataAndAllowsUnknownGroups() {
        {
            var catalog = new DefaultWorkerMatchingCatalog(budget, Map.of(), System::currentTimeMillis,
                     Map.of(), Map.of("renamed", new com.xa.mass.workermatching.functions.IdentityQueryFunction()),
                     Map.of(), List.of(), Set.of("renamed"));
            assertEquals("w", catalog.take("not-configured", Map.of("m", new WorkerQuery("renamed", "w"))).get("m").workerId());
            assertThrows(IllegalArgumentException.class, () -> catalog.normalizeQuery("g", new WorkerQuery("workerId", "w")));
        }
    }

    @Test void scalarStrategiesNeedNeitherRefillPolicyNorPoolAndKeepCallLocalOrder() {

        var calls = new ArrayList<String>();
        {
            QueryFunction strings = new QueryFunction() {
                public Object normalizeInput(String group, Object input) {
                    calls.add("admit-text");
                    if (!(input instanceof String text)) throw new IllegalArgumentException();
                    return text.toUpperCase(Locale.ROOT);
                }
                public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
                    calls.add("text");
                    var result = new LinkedHashMap<String, WorkerCandidate>();
                    inputs.forEach((id, input) -> result.put(id, new WorkerCandidate("text-" + input, 0)));
                    assertThrows(UnsupportedOperationException.class, inputs::clear);
                    return Collections.unmodifiableMap(result);
                }
            };
            QueryFunction numbers = new QueryFunction() {
                public Object normalizeInput(String group, Object input) {
                    calls.add("admit-number");
                    if (!(input instanceof Integer)) throw new IllegalArgumentException();
                    return input;
                }
                public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
                    calls.add("number");
                    var result = new LinkedHashMap<String, WorkerCandidate>();
                    inputs.forEach((id, input) -> result.put(id, new WorkerCandidate("number-" + input, 31)));
                    return Collections.unmodifiableMap(result);
                }
            };
            var supplied = new LinkedHashMap<String, QueryFunction>();
            supplied.put("text", strings); supplied.put("number", numbers);
            {
            var catalog = catalog(supplied);
                supplied.clear();
                var requests = new LinkedHashMap<String, WorkerQuery>();
                requests.put("a", new WorkerQuery("text", "a"));
                requests.put("b", new WorkerQuery("number", 7));
                requests.put("c", new WorkerQuery("text", "c"));
                var result = catalog.take("g", requests);
                assertEquals(List.of("admit-text", "admit-number", "admit-text", "text", "number"), calls);
                assertEquals(List.of("a", "b", "c"), List.copyOf(result.keySet()));
                assertEquals(List.of("text-A", "number-7", "text-C"), result.values().stream().map(WorkerCandidate::workerId).toList());
                assertEquals(0, result.get("a").expectedScore()); assertEquals(31, result.get("b").expectedScore());
                assertEquals(result, catalog.take("g", requests));
                assertThrows(UnsupportedOperationException.class, result::clear);
                assertThrows(IllegalArgumentException.class, () -> catalog.take("other", requests));

            }
        }
    }

    @Test void lateAdmissionFailureConsumesNothingAndLaterExecutionFailureDoesNotRollBack() {
        var consumed = new AtomicInteger();
        {
            var first = new QueryFunction() {
                public Object normalizeInput(String g, Object i) { return i; }
                public Map<String, WorkerCandidate> apply(String g, Map<String, Object> inputs) {
                    consumed.incrementAndGet();
                    return Map.of(inputs.keySet().iterator().next(), new WorkerCandidate("w", 12));
                }
            };
            var last = new QueryFunction() {
                public Object normalizeInput(String g, Object i) {
                    if (!i.equals(true)) throw new IllegalArgumentException();
                    return i;
                }
                public Map<String, WorkerCandidate> apply(String g, Map<String, Object> inputs) {
                    throw new IllegalStateException("execution failure");
                }
            };
            {
            var catalog = catalog(Map.of("first", first, "last", last));
                var requests = new LinkedHashMap<String, WorkerQuery>();
                requests.put("first", new WorkerQuery("first", Map.of()));
                requests.put("last", new WorkerQuery("last", false));
                assertThrows(IllegalArgumentException.class, () -> catalog.take("g", requests));
                assertEquals(0, consumed.get());
                requests.put("last", new WorkerQuery("last", true));
                assertThrows(IllegalStateException.class, () -> catalog.take("g", requests));
                assertEquals(1, consumed.get());
            }
        }
    }

    @Test void duplicateIdentityAcrossFunctionsKeepsFirstAssociationWithoutAnotherExecution() {
        var calls = new AtomicInteger();
        var fn = new QueryFunction() {
            public Object normalizeInput(String g, Object i) { return i; }
            public Map<String, WorkerCandidate> apply(String g, Map<String, Object> inputs) {
                calls.incrementAndGet();
                var result = new LinkedHashMap<String, WorkerCandidate>();
                inputs.keySet().forEach(id -> result.put(id, new WorkerCandidate("same", 19)));
                return Collections.unmodifiableMap(result);
            }
        };
        {
            var catalog = catalog(Map.of("first", fn, "second", fn));
            var requests = new LinkedHashMap<String, WorkerQuery>();
            requests.put("first", new WorkerQuery("first", Map.of()));
            requests.put("second", new WorkerQuery("second", Map.of()));
            assertEquals(Set.of("first"), catalog.take("g", requests).keySet());
            assertEquals(2, calls.get());
        }
    }

    @Test void independentMapStockCanShareRefillAndNamedConsumptionWithoutUsingPoolResource() {
        var held = new LinkedHashMap<String, Long>();
        PoolRefillPolicy rule = new PoolRefillPolicy() {
            public TargetBatching targetBatching() { return TargetBatching.PAGED; }
            public EligibilityQuery normalizeQuery(String g, EligibilityQuery q) {
                if (!q.query().isEmpty()) throw new IllegalArgumentException(); return q;
            }
            public synchronized Map<EligibilityQuery, Integer> deficits(String g, Map<EligibilityQuery, Integer> targets) {
                var result = new LinkedHashMap<EligibilityQuery, Integer>();
                targets.forEach((q, n) -> result.put(q, Math.max(0, n - held.size()))); return Map.copyOf(result);
            }
            public synchronized List<String> refill(String g, Map<EligibilityQuery, Integer> targets, Map<String, Long> offered, int limit) {
                var result = new ArrayList<String>();
                for (var entry : offered.entrySet()) if (result.size() < limit && held.putIfAbsent(entry.getKey(), entry.getValue()) == null) result.add(entry.getKey());
                return List.copyOf(result);
            }
        };
        var function = new QueryFunction() {
            public Object normalizeInput(String g, Object i) {
                if (!i.equals(Map.of())) throw new IllegalArgumentException(); return i;
            }
            public Map<String, WorkerCandidate> apply(String g, Map<String, Object> inputs) {
                var result = new LinkedHashMap<String, WorkerCandidate>();
                synchronized (rule) {
                    var iterator = held.entrySet().iterator();
                    for (String id : inputs.keySet()) if (iterator.hasNext()) {
                        var candidate = iterator.next(); iterator.remove();
                        result.put(id, new WorkerCandidate(candidate.getKey(), candidate.getValue()));
                    }
                }
                return Collections.unmodifiableMap(result);
            }
        };

        {
            var catalog = new DefaultWorkerMatchingCatalog(budget, Map.of(), System::currentTimeMillis,
                        Map.of("map", rule), Map.of("map", function), Map.of("g", new MatchingGroup(Set.of("map"), Set.of("map"), null)), List.of("map"), Set.of());
            var targets = List.of(new RefillTarget("map", new EligibilityQuery(Map.of()), 1));
            assertEquals(Map.of("g",1), catalog.observeRefillDeficits(Map.of("g", targets)));
            assertEquals(1, catalog.refill("g", targets, Map.ofEntries(Map.entry("w", (long) (44)))));
            assertEquals(new WorkerCandidate("w", 44), catalog.take("g", Map.of("m", new WorkerQuery("map", Map.of()))).get("m"));

        }
    }
}
