package com.xa.mass.engine.rules;

import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.rule.RuleDefinition;

import java.util.List;
import java.util.Objects;

public final class StorageBackedMatchingRuleSetProvider implements MatchingRuleSetProvider {

    private final RuleStorage ruleStorage;

    public StorageBackedMatchingRuleSetProvider(RuleStorage ruleStorage) {
        this.ruleStorage = Objects.requireNonNull(ruleStorage, "ruleStorage");
    }

    @Override
    public List<RuleDefinition> activeWorkerMatchingRules() {
        return ruleStorage.getAllRules();
    }
}
