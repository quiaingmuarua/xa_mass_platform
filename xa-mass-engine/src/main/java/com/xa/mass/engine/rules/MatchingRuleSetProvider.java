package com.xa.mass.engine.rules;

import com.xa.mass.storage.rule.RuleDefinition;

import java.util.List;

/**
 * Read-only rule set provider for worker matching.
 */
public interface MatchingRuleSetProvider {

    List<RuleDefinition> activeWorkerMatchingRules();
}
