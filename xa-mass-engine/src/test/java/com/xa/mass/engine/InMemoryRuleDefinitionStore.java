package com.xa.mass.engine;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.storage.api.RuleStorage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine test fixture for matching rule definitions.
 */
public final class InMemoryRuleDefinitionStore implements RuleStorage {

    private final Map<String, RuleDefinition> rulesById = new ConcurrentHashMap<>();

    @Override
    public void addRule(RuleDefinition rule) {
        rulesById.put(rule.getId(), rule);
    }

    @Override
    public Optional<RuleDefinition> getRule(String ruleId) {
        return Optional.ofNullable(rulesById.get(ruleId));
    }

    @Override
    public boolean updateRule(RuleDefinition rule) {
        if (rule.getId() == null || !rulesById.containsKey(rule.getId())) {
            return false;
        }
        rulesById.put(rule.getId(), rule);
        return true;
    }

    @Override
    public boolean deleteRule(String ruleId) {
        return rulesById.remove(ruleId) != null;
    }

    @Override
    public List<RuleDefinition> getAllRules() {
        return new ArrayList<>(rulesById.values());
    }

    @Override
    public List<RuleDefinition> getRulesByType(RuleType ruleType) {
        return rulesById.values().stream()
                .filter(rule -> rule.getType() == ruleType)
                .toList();
    }

    @Override
    public void addRules(Collection<RuleDefinition> rules) {
        for (RuleDefinition rule : rules) {
            addRule(rule);
        }
    }

    @Override
    public void deleteRules(Collection<String> ruleIds) {
        for (String ruleId : ruleIds) {
            deleteRule(ruleId);
        }
    }

    @Override
    public void clear() {
        rulesById.clear();
    }
}
