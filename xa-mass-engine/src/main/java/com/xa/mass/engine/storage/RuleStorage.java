package com.xa.mass.engine.storage;

import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleEvaluator;
import com.xa.mass.engine.rules.RuleType;

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
