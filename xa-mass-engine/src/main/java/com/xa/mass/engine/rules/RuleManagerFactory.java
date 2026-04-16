package com.xa.mass.engine.rules;

import java.util.Map;

public class RuleManagerFactory {
    public static RuleManager<Map<String, Object>> getDefaultRuleManager() {
        RuleManager<Map<String, Object>> manager = new RuleManager<>();
        manager.addDefaultRules(RuleConfig.getDefaultWorkerMatchRules());
        return manager;
    }

    public static RuleManager<Map<String, Object>> getProjectRuleManager(String project) {
        RuleManager<Map<String, Object>> manager = new RuleManager<>();
        manager.addDefaultRules(RuleConfig.getProjectSpecificRules(project));
        return manager;
    }

    public static RuleManager<Map<String, Object>> getLooseRuleManager() {
        RuleManager<Map<String, Object>> manager = new RuleManager<>();
        manager.addDefaultRules(RuleConfig.getLooseWorkerMatchRules());
        return manager;
    }
}
