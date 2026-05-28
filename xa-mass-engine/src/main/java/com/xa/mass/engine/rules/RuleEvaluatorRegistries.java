package com.xa.mass.engine.rules;

import com.xa.mass.storage.rule.RuleType;

import java.util.Map;

public final class RuleEvaluatorRegistries {

    private RuleEvaluatorRegistries() {
    }

    public static RuleEvaluatorRegistry<Map<String, Object>> defaultRegistry() {
        InMemoryRuleEvaluatorRegistry<Map<String, Object>> registry = new InMemoryRuleEvaluatorRegistry<>();
        registry.registerEvaluator(RuleType.QL_EXPRESS, new QLExpressRuleEvaluator());
        return registry;
    }
}
