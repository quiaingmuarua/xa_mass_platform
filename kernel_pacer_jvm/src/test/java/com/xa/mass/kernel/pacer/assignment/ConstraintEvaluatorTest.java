package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstraintEvaluatorTest {

    @Test
    void supportsTheFiniteDslAcrossWorkerAndPlatformProperties() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("worker.region", Map.of("$in", List.of("cn", "us")));
        rule.put("worker.battery", Map.of("$gte", 50));
        rule.put("platform.pool", Map.of("$ne", "blocked"));
        rule.put("platform.optional", Map.of("$exists", false));

        var compiled = ConstraintEvaluator.compileMatchRules(rule);
        assertTrue(ConstraintEvaluator.evaluateMatchRules(
                Map.of(
                        "worker", Map.of("region", "cn", "battery", 80),
                        "platform", Map.of("pool", "default")
                ),
                compiled
        ));
        assertFalse(ConstraintEvaluator.evaluateMatchRules(
                Map.of(
                        "worker", Map.of("region", "cn", "battery", 20),
                        "platform", Map.of("pool", "default")
                ),
                compiled
        ));
    }

    @Test
    void preservesNullOperandsAndFailsClosedOnIncomparableValues() {
        Map<String, Object> nullable = new LinkedHashMap<>();
        nullable.put("worker.value", Map.of("$exists", true));
        LinkedHashMap<String, Object> nullOperator = new LinkedHashMap<>();
        nullOperator.put("$eq", null);
        nullable.put("platform.value", nullOperator);
        var compiled = ConstraintEvaluator.compileMatchRules(nullable);

        assertTrue(ConstraintEvaluator.evaluateMatchRules(
                Map.of(
                        "worker", Map.of("value", "present"),
                        "platform", java.util.Collections.singletonMap(
                                "value",
                                null
                        )
                ),
                compiled
        ));
        assertFalse(ConstraintEvaluator.evaluateMatchRules(
                Map.of("worker", Map.of("value", "text")),
                ConstraintEvaluator.compileMatchRules(Map.of(
                        "worker.value",
                        Map.of("$gt", 1)
                ))
        ));
    }

    @Test
    void rejectsUnsupportedOrMalformedRules() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConstraintEvaluator.compileMatchRules(Map.of(
                        "worker.region",
                        Map.of("$regex", "cn")
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ConstraintEvaluator.compileMatchRules(Map.of(
                        "worker.region",
                        Map.of("$in", "cn")
                ))
        );
    }
}
