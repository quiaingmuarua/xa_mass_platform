package com.xa.mass.engine.rules;

import com.xa.mass.storage.api.RuleStorage;

import java.util.Map;

public class RuleManagerFactory {
    public static RuleManager<Map<String, Object>> getDefaultRuleManager(RuleStorage ruleStorage) {
        RuleManager<Map<String, Object>> manager = new RuleManager<>(ruleStorage);
        manager.addDefaultRules(RuleConfig.getDefaultWorkerMatchRules());
        return manager;
    }

    public static RuleManager<Map<String, Object>> getProjectRuleManager(RuleStorage ruleStorage, String project) {
        RuleManager<Map<String, Object>> manager = new RuleManager<>(ruleStorage);
        manager.addDefaultRules(RuleConfig.getProjectSpecificRules(project));
        return manager;
    }

    public static RuleManager<Map<String, Object>> getLooseRuleManager(RuleStorage ruleStorage) {
        RuleManager<Map<String, Object>> manager = new RuleManager<>(ruleStorage);
        manager.addDefaultRules(RuleConfig.getLooseWorkerMatchRules());
        return manager;
    }
}
