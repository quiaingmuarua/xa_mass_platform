package com.xa.mass.kernel.assignment;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EligibilityQueryTest {
    @Test void capturesStructureWithoutInterpretingFieldsOrDeduplicating() {
        for (var input : List.of(Map.of(), Map.of("worker.country", List.of("cn")),
                Map.of("workerId", List.of("b", "a", "a"), "custom", List.of("value")))) {
            assertEquals(input, EligibilityQuery.parse(input).query());
        }
    }
    @Test void rejectsMalformedStructureBeforeCapture() {
        var nullValue = new HashMap<String, Object>(); nullValue.put("a", null);
        var nullKey = new HashMap<String, Object>(); nullKey.put(null, List.of("value"));
        for (Map<?, ?> invalid : List.of(Map.of("", List.of("x")), Map.of(1, List.of("x")),
                Map.of("a", Arrays.asList("x", null)), nullValue, nullKey, Map.of("a", List.of(" ")),
                Map.of("a", List.of()), Map.of("a", List.of(1)), Map.of("a", "x"),
                Map.of("a", Map.of("op", "eq", "values", List.of("CN"))))) {
            assertThrows(IllegalArgumentException.class, () -> EligibilityQuery.parse(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> EligibilityQuery.parse(null));
        var oversized = IntStream.range(0, 101).mapToObj(i -> "v" + i).toList();
        assertThrows(IllegalArgumentException.class, () -> EligibilityQuery.parse(Map.of("a", oversized)));
        var fields = new LinkedHashMap<String, List<String>>();
        oversized.forEach(key -> fields.put(key, List.of("v")));
        assertThrows(IllegalArgumentException.class, () -> EligibilityQuery.parse(fields));
    }
    @Test void capturesDeepImmutableValuesAndStableEquality() {
        var values = new ArrayList<>(List.of("b", "a", "b"));
        var input = new HashMap<>(Map.of("custom", values));
        var query = EligibilityQuery.parse(input);
        int hash = query.hashCode(); values.clear(); input.clear();
        assertEquals(EligibilityQuery.parse(Map.of("custom", List.of("b", "a", "b"))), query);
        assertEquals(hash, query.hashCode());
        assertThrows(UnsupportedOperationException.class, () -> query.query().clear());
        assertThrows(UnsupportedOperationException.class, () -> query.query().get("custom").clear());
    }
    @Test void jsonIsTheDirectMapAndNeverCoercesValues() {
        var mapper = JsonMapper.builder().build();
        var query = EligibilityQuery.parse(Map.of("country", List.of("CN", "US")));
        assertEquals("{\"country\":[\"CN\",\"US\"]}", mapper.writeValueAsString(query));
        assertEquals(query, mapper.readValue(mapper.writeValueAsString(query), EligibilityQuery.class));
        for (String invalid : List.of("{\"a\":[1]}", "{\"a\":[true]}", "{\"a\":[null]}",
                "{\"a\":\"value\"}", "{\"a\":{\"op\":\"in\",\"values\":[\"CN\"]}}",
                "{\"query\":{\"a\":[\"CN\"]}}")) {
            assertThrows(RuntimeException.class, () -> mapper.readValue(invalid, EligibilityQuery.class), invalid);
        }
    }
}
