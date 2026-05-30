package com.xa.mass.storage.api;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;

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

    void clear();
}
