package com.xa.mass.engine.rules;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryBackedMatchingRuleEvaluatorTest {

    @Test
    void evaluatesRegisteredRuleType() throws Exception {
        RegistryBackedMatchingRuleEvaluator evaluator =
                new RegistryBackedMatchingRuleEvaluator(RuleEvaluatorRegistries.defaultRegistry());

        assertTrue(evaluator.evaluate(rule("r1", RuleType.QL_EXPRESS, "value > 3"), Map.of("value", 5)));
        assertFalse(evaluator.evaluate(rule("r2", RuleType.QL_EXPRESS, "value > 3"), Map.of("value", 1)));
    }

    @Test
    void rejectsUnsupportedRuleType() {
        RegistryBackedMatchingRuleEvaluator evaluator =
                new RegistryBackedMatchingRuleEvaluator(RuleEvaluatorRegistries.defaultRegistry());

        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(rule("json-dsl", RuleType.JSON_DSL, "{}"), Map.of()));
    }

    @Test
    void registryReportsAndRemovesEvaluatorTypes() {
        RuleEvaluatorRegistry<Map<String, Object>> registry = RuleEvaluatorRegistries.defaultRegistry();

        assertTrue(registry.registeredEvaluatorTypes().contains(RuleType.QL_EXPRESS));
        assertTrue(registry.removeEvaluator(RuleType.QL_EXPRESS));
        assertTrue(registry.registeredEvaluatorTypes().isEmpty());
    }

    private RuleDefinition rule(String id, RuleType type, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setType(type);
        rule.setContent(content);
        return rule;
    }
}
