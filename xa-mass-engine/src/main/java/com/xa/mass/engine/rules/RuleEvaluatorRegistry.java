package com.xa.mass.engine.rules;

import com.xa.mass.kernel.spi.rule.RuleEvaluator;
import com.xa.mass.kernel.spi.rule.RuleType;

import java.util.List;
import java.util.Optional;

/**
 * Process-local rule evaluator registry assembled outside durable rule storage.
 */
public interface RuleEvaluatorRegistry<C> {

    void registerEvaluator(RuleType ruleType, RuleEvaluator<C> evaluator);

    Optional<RuleEvaluator<C>> evaluator(RuleType ruleType);

    List<RuleType> registeredEvaluatorTypes();

    boolean removeEvaluator(RuleType ruleType);
}
