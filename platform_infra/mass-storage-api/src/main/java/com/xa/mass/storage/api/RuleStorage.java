package com.xa.mass.storage.api;

import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleEvaluator;
import com.xa.mass.storage.rule.RuleType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction for rule definitions and registered evaluators.
 */
public interface RuleStorage {

    void addRule(RuleDefinition rule);

    Optional<RuleDefinition> getRule(String ruleId);

    boolean updateRule(RuleDefinition rule);

    boolean deleteRule(String ruleId);

    List<RuleDefinition> getAllRules();

    List<RuleDefinition> getRulesByType(RuleType ruleType);

    void addRules(Collection<RuleDefinition> rules);

    void deleteRules(Collection<String> ruleIds);

    void registerEvaluator(RuleType ruleType, RuleEvaluator evaluator);

    Optional<RuleEvaluator> getEvaluator(RuleType ruleType);

    List<RuleType> getRegisteredEvaluatorTypes();

    boolean removeEvaluator(RuleType ruleType);

    void clear();
}
