package com.xa.mass.starter.config;

import com.xa.mass.engine.rules.MatchingRuleSetProvider;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.storage.api.RuleStorage;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

final class StorageBackedMatchingRuleSetProvider implements MatchingRuleSetProvider {

    private final RuleStorage ruleStorage;

    StorageBackedMatchingRuleSetProvider(RuleStorage ruleStorage) {
        this.ruleStorage = Objects.requireNonNull(ruleStorage, "ruleStorage");
    }

    @Override
    public List<RuleDefinition> activeWorkerMatchingRules() {
        return ruleStorage.getAllRules().stream()
                .filter(RuleDefinition::isEnabled)
                .sorted(Comparator.comparingInt(RuleDefinition::getPriority))
                .toList();
    }
}
