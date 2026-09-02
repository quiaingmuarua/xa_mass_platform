package com.xa.mass.workermatching;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintEvaluatorTest {

    private final ConstraintEvaluator evaluator = new ConstraintEvaluator();

    @Test
    void evaluatesTheFiniteDslAcrossWorkerAndPlatformFacts() {
        LinkedHashMap<String, Object> rule = new LinkedHashMap<>();
        rule.put("worker.eq", Map.of("$eq", 10));
        rule.put("worker.ne", Map.of("$ne", 9));
        rule.put("worker.gt", Map.of("$gt", 9));
        rule.put("worker.gte", Map.of("$gte", 10));
        rule.put("worker.lt", Map.of("$lt", 11));
        rule.put("worker.lte", Map.of("$lte", 10));
        rule.put("worker.in", Map.of("$in", List.of(8, 10)));
        rule.put("worker.battery", Map.of("$range", List.of(50, 100)));
        rule.put("platform.region", Map.of("$eq", "cn"));
        rule.put("worker.present", Map.of("$exists", true));
        rule.put("platform.missing", Map.of("$exists", false));
        rule.put("workerId", Map.of("$eq", "worker-1"));

        LinkedHashMap<String, Object> worker = new LinkedHashMap<>();
        for (String property : List.of(
                "eq", "ne", "gt", "gte", "lt", "lte", "in"
        )) {
            worker.put(property, 10L);
        }
        worker.put("battery", 50L);
        worker.put("present", null);
        var conditions = evaluator.normalize(rule);

        assertTrue(evaluator.matches(
                conditions,
                "worker-1",
                worker,
                Map.of("region", "cn")
        ));
        worker.put("battery", 100.0);
        assertTrue(evaluator.matches(
                conditions,
                "worker-1",
                worker,
                Map.of("region", "cn")
        ));
        worker.put("battery", 101);
        assertFalse(evaluator.matches(
                conditions,
                "worker-1",
                worker,
                Map.of("region", "cn")
        ));
    }

    @Test
    void preservesMissingNullAndFlatPropertyNames() {
        LinkedHashMap<String, Object> rule = new LinkedHashMap<>();
        LinkedHashMap<String, Object> nullOperator = new LinkedHashMap<>();
        nullOperator.put("$equal", null);
        rule.put("platform.value", nullOperator);
        rule.put("worker.device.region", Map.of("$eq", "cn"));
        rule.put("worker.optional", Map.of("$exists", false));
        var conditions = evaluator.normalize(rule);

        assertTrue(evaluator.matches(
                conditions,
                "worker-1",
                Map.of("device.region", "cn"),
                java.util.Collections.singletonMap("value", null)
        ));
        assertFalse(evaluator.matches(
                conditions,
                "worker-1",
                Map.of("device", Map.of("region", "cn")),
                java.util.Collections.singletonMap("value", null)
        ));
        assertFalse(evaluator.matches(
                evaluator.normalize(Map.of(
                        "worker.value",
                        Map.of("$gt", 1)
                )),
                "worker-1",
                Map.of("value", "text"),
                Map.of()
        ));
    }

    @Test
    void rejectsUnsupportedMalformedAndInvalidRangeRules() {
        assertThrows(IllegalArgumentException.class, () ->
                evaluator.normalize(Map.of(
                        "worker.region",
                        Map.of("$regex", "cn")
                )));
        assertThrows(IllegalArgumentException.class, () ->
                evaluator.normalize(Map.of(
                        "worker.region",
                        Map.of("$in", "cn")
                )));
        assertThrows(IllegalArgumentException.class, () ->
                evaluator.normalize(Map.of(
                        "unknown",
                        Map.of("$eq", "cn")
                )));
        assertThrows(IllegalArgumentException.class, () ->
                evaluator.normalize(Map.of(
                        "worker.battery",
                        Map.of("$range", List.of(100, 50))
                )));
        List<Object> nullBound = new ArrayList<>();
        nullBound.add(null);
        nullBound.add(100);
        assertThrows(IllegalArgumentException.class, () ->
                evaluator.normalize(Map.of(
                        "worker.battery",
                        Map.of("$range", nullBound)
                )));
    }
}
