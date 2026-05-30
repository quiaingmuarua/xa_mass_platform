package com.xa.mass.engine.rules;

import com.xa.mass.kernel.spi.rule.RuleEvaluator;
import com.xa.mass.kernel.spi.rule.RuleType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Simple process-local evaluator registry.
 */
public final class InMemoryRuleEvaluatorRegistry<C> implements RuleEvaluatorRegistry<C> {

    private final ConcurrentMap<RuleType, RuleEvaluator<C>> evaluators = new ConcurrentHashMap<>();

    @Override
    public void registerEvaluator(RuleType ruleType, RuleEvaluator<C> evaluator) {
        evaluators.put(
                Objects.requireNonNull(ruleType, "ruleType"),
                Objects.requireNonNull(evaluator, "evaluator")
        );
    }

    @Override
    public Optional<RuleEvaluator<C>> evaluator(RuleType ruleType) {
        return Optional.ofNullable(evaluators.get(ruleType));
    }

    @Override
    public List<RuleType> registeredEvaluatorTypes() {
        return evaluators.keySet().stream().sorted().toList();
    }

    @Override
    public boolean removeEvaluator(RuleType ruleType) {
        return evaluators.remove(ruleType) != null;
    }
}
