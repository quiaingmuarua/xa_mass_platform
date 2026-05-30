package com.xa.mass.engine.rules;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleEvaluator;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RegistryBackedMatchingRuleEvaluator implements MatchingRuleEvaluator<Map<String, Object>> {

    private final RuleEvaluatorRegistry<Map<String, Object>> evaluatorRegistry;

    public RegistryBackedMatchingRuleEvaluator(RuleEvaluatorRegistry<Map<String, Object>> evaluatorRegistry) {
        this.evaluatorRegistry = Objects.requireNonNull(evaluatorRegistry, "evaluatorRegistry");
    }

    @Override
    public boolean evaluate(RuleDefinition rule, Map<String, Object> context) throws Exception {
        Optional<RuleEvaluator<Map<String, Object>>> evaluator = evaluatorRegistry.evaluator(rule.getType());
        if (evaluator.isEmpty()) {
            throw new IllegalArgumentException("Unsupported rule type: " + rule.getType());
        }
        return evaluator.get().evaluate(rule, context);
    }
}
