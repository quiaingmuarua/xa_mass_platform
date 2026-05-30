package com.xa.mass.engine.rules;

import com.xa.mass.kernel.spi.rule.RuleDefinition;

/**
 * Narrow matching-time rule evaluator contract.
 */
public interface MatchingRuleEvaluator<C> {

    boolean evaluate(RuleDefinition rule, C context) throws Exception;
}
