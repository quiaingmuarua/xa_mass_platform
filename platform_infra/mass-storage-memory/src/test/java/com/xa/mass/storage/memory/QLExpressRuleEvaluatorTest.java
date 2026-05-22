package com.xa.mass.storage.memory;

import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QLExpressRuleEvaluatorTest {

    private final QLExpressRuleEvaluator evaluator = new QLExpressRuleEvaluator();

    @Test
    void evaluatesNestedAttributesMapAccess() throws Exception {
        RuleDefinition rule = new RuleDefinition();
        rule.setId("worker-attr-country");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("workerAttributes['country'] == 'us' && workerAttributes['pool'] == 'premium'");

        Map<String, Object> context = Map.of(
                "workerAttributes", Map.of("country", "us", "pool", "premium")
        );

        assertTrue(evaluator.evaluate(rule, context));
    }

    @Test
    void missingNestedAttributeDoesNotMatch() throws Exception {
        RuleDefinition rule = new RuleDefinition();
        rule.setId("worker-attr-country");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("workerAttributes['country'] == 'us'");

        assertFalse(evaluator.evaluate(rule, Map.of("workerAttributes", Map.of())));
    }
}
