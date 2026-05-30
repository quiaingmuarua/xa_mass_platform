package com.xa.mass.engine.rules;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QLExpressRuleEvaluatorTest {

    private final QLExpressRuleEvaluator evaluator = new QLExpressRuleEvaluator();

    @Test
    void evaluatesBooleanExpression() throws Exception {
        RuleDefinition rule = rule("available", "isWorkerAvailable == true && appCount < 10");

        assertTrue(evaluator.evaluate(rule, Map.of("isWorkerAvailable", true, "appCount", 3)));
        assertFalse(evaluator.evaluate(rule, Map.of("isWorkerAvailable", true, "appCount", 12)));
    }

    private RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        return rule;
    }
}
