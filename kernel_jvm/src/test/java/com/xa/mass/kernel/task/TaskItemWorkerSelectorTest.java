package com.xa.mass.kernel.task;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TaskItemWorkerSelectorTest {
    @Test
    void capturesOneExpressionAndDerivesOnlyMechanicalIdentities() {
        var any = TaskItemWorkerSelector.parse(Map.of());
        assertTrue(any.isAny());
        assertFalse(any.hasExplicitWorkerIds());
        var single = TaskItemWorkerSelector.parse(Map.of("workerId", List.of("worker-a")));
        var multiple = TaskItemWorkerSelector.parse(Map.of("workerId", List.of("worker-b", "worker-a")));
        assertEquals(List.of("worker-a"), single.targetWorkerIds());
        assertEquals(List.of("worker-b", "worker-a"), multiple.targetWorkerIds());
        assertEquals(Map.of("workerId", List.of("worker-a")), single.expression());
        assertTrue(single.hasExplicitWorkerIds());
    }

    @Test
    void parameterMeaningBelongsToMatchingNotKernel() {
        for (Map<?, ?> input : List.of(
                Map.of("worker.country", List.of("cn")),
                Map.of("worker.country", List.of("CN", "US")),
                Map.of("worker.test.region", List.of("east", "west")),
                Map.of("worker.future", List.of("", "$eq", "", "value")))) {
            var selector = TaskItemWorkerSelector.parse(input);
            assertEquals(input, selector.expression());
            assertFalse(selector.isAny());
            assertFalse(selector.hasExplicitWorkerIds());
            assertThrows(IllegalStateException.class, selector::targetWorkerIds);
        }
    }

    @Test
    void rejectsMalformedStructureAndInvalidExplicitIdentitySelection() {
        var nullValue = new HashMap<String, Object>();
        nullValue.put("worker.future", null);
        var nullKey = new HashMap<String, Object>();
        nullKey.put(null, List.of("value"));
        List<Map<?, ?>> invalid = List.of(
                Map.of("a", List.of("x"), "b", List.of("y")),
                Map.of("", List.of("x")), Map.of(" ", List.of("x")), Map.of(1, List.of("x")),
                Map.of("worker.future", "x"), Map.of("worker.future", 1),
                Map.of("worker.future", Map.of()), Map.of("worker.future", List.of()),
                Map.of("worker.future", List.of(1)), Map.of("worker.future", List.of(true)),
                Map.of("worker.future", List.of(List.of("x"))),
                Map.of("worker.future", Arrays.asList("x", null)), nullValue, nullKey,
                Map.of("workerId", List.of(" ")), Map.of("workerId", List.of("id", "id")));
        invalid.forEach(input -> assertThrows(IllegalArgumentException.class,
                () -> TaskItemWorkerSelector.parse(input), input.toString()));
        assertThrows(IllegalArgumentException.class, () -> TaskItemWorkerSelector.parse(null));
        List<String> oversized = IntStream.range(0, 101).mapToObj(i -> "id-" + i).toList();
        for (String binding : List.of("workerId", "worker.future")) {
            assertThrows(IllegalArgumentException.class,
                    () -> TaskItemWorkerSelector.parse(Map.of(binding, oversized)));
        }
    }

    @Test
    void capturesMapAndParameterListsAndHasStableValueEquality() {
        var ids = new ArrayList<>(List.of("worker-b", "worker-a"));
        var input = new HashMap<String, List<String>>(Map.of("workerId", ids));
        var selector = new TaskItemWorkerSelector(input);
        var expected = TaskItemWorkerSelector.parse(Map.of("workerId", List.copyOf(ids)));
        int hash = selector.hashCode();
        ids.clear();
        input.clear();
        assertEquals(expected, selector);
        assertEquals(hash, selector.hashCode());
        assertEquals(List.of("worker-b", "worker-a"), selector.targetWorkerIds());
        assertThrows(UnsupportedOperationException.class, () -> selector.expression().clear());
        assertThrows(UnsupportedOperationException.class, () -> selector.expression().get("workerId").clear());
    }
}
