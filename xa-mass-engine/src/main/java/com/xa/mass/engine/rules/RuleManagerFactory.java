package com.xa.mass.engine.rules;

import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.rule.RuleType;

import java.util.Map;

public class RuleManagerFactory {
    public static RuleManager<Map<String, Object>> getDefaultRuleManager(RuleStorage ruleStorage) {
        RuleManager<Map<String, Object>> manager = new RuleManager<>(ruleStorage, defaultEvaluatorRegistry());
        manager.addDefaultRules(RuleConfig.getDefaultWorkerMatchRules());
        return manager;
    }

    public static RuleManager<Map<String, Object>> getProjectRuleManager(RuleStorage ruleStorage, String project) {
        RuleManager<Map<String, Object>> manager = new RuleManager<>(ruleStorage, defaultEvaluatorRegistry());
        manager.addDefaultRules(RuleConfig.getProjectSpecificRules(project));
        return manager;
    }

    public static RuleManager<Map<String, Object>> getLooseRuleManager(RuleStorage ruleStorage) {
        RuleManager<Map<String, Object>> manager = new RuleManager<>(ruleStorage, defaultEvaluatorRegistry());
        manager.addDefaultRules(RuleConfig.getLooseWorkerMatchRules());
        return manager;
    }

    public static RuleEvaluatorRegistry<Map<String, Object>> defaultEvaluatorRegistry() {
        InMemoryRuleEvaluatorRegistry<Map<String, Object>> registry = new InMemoryRuleEvaluatorRegistry<>();
        registry.registerEvaluator(RuleType.QL_EXPRESS, new QLExpressRuleEvaluator());
        return registry;
    }
}
