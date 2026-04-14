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
        rule.setId("token-attr-country");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("tokenAttributes['country'] == 'us' && deviceAttributes['pool'] == 'premium'");

        Map<String, Object> context = Map.of(
                "tokenAttributes", Map.of("country", "us"),
                "deviceAttributes", Map.of("pool", "premium")
        );

        assertTrue(evaluator.evaluate(rule, context));
    }

    @Test
    void missingNestedAttributeDoesNotMatch() throws Exception {
        RuleDefinition rule = new RuleDefinition();
        rule.setId("token-attr-country");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("tokenAttributes['country'] == 'us'");

        assertFalse(evaluator.evaluate(rule, Map.of("tokenAttributes", Map.of())));
    }
}
