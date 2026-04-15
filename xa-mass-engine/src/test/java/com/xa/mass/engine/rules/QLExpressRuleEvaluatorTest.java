package com.xa.mass.engine.rules;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QLExpressRuleEvaluatorTest {

    private final QLExpressRuleEvaluator evaluator = new QLExpressRuleEvaluator();

    @Test
    void evaluatesNestedAttributesMapAccess() throws Exception {
        RuleDefinition rule = new RuleDefinition();
        rule.setId("worker-context-attr-country");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("workerContextAttributes['country'] == 'us' && workerAttributes['pool'] == 'premium'");

        Map<String, Object> context = Map.of(
                "workerContextAttributes", Map.of("country", "us"),
                "workerAttributes", Map.of("pool", "premium")
        );

        assertTrue(evaluator.evaluate(rule, context));
    }

    @Test
    void missingNestedAttributeDoesNotMatch() throws Exception {
        RuleDefinition rule = new RuleDefinition();
        rule.setId("worker-context-attr-country");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("workerContextAttributes['country'] == 'us'");

        assertFalse(evaluator.evaluate(rule, Map.of("workerContextAttributes", Map.of())));
    }
}
