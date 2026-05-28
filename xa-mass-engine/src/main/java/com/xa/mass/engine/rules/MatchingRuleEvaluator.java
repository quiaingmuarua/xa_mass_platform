package com.xa.mass.engine.rules;

import com.xa.mass.storage.rule.RuleDefinition;

/**
 * Narrow matching-time rule evaluator contract.
 */
public interface MatchingRuleEvaluator<C> {

    boolean evaluate(RuleDefinition rule, C context) throws Exception;
}
