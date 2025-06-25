package com.xa.mass.engine.rules;

import java.util.HashMap;
import java.util.Map;

import java.util.*;

public class RuleManager<T> {

    private final Map<RuleType, RuleEvaluator> evaluatorMap = new HashMap<>();
    private final List<RuleDefinition> defaultRules = new ArrayList<>();
    private final Map<String, RuleDefinition> ruleMap = new HashMap<>(); // 可选，按ID存储

    public RuleManager() {
        // 注册已实现类型的 evaluator
        evaluatorMap.put(RuleType.QL_EXPRESS, new QLExpressRuleEvaluator());
//        evaluatorMap.put(RuleType.JSON_DSL, new JsonDslRuleEvaluator<>());
    }

    // 注册全局/默认规则
    public void addDefaultRule(RuleDefinition rule) {
        defaultRules.add(rule);
        ruleMap.put(rule.getId(), rule);
    }

    // 支持批量注册
    public void addDefaultRules(Collection<RuleDefinition> rules) {
        for (RuleDefinition rule : rules) addDefaultRule(rule);
    }

    // 支持移除规则
    public void removeDefaultRule(String ruleId) {
        defaultRules.removeIf(r -> r.getId().equals(ruleId));
        ruleMap.remove(ruleId);
    }

    // 获取默认规则
    public List<RuleDefinition> getDefaultRules() {
        return Collections.unmodifiableList(defaultRules);
    }

    // 单规则评估
    public boolean evaluate(RuleDefinition rule, T context) throws Exception {
        RuleEvaluator evaluator = evaluatorMap.get(rule.getType());
        if (evaluator == null)
            throw new IllegalArgumentException("不支持的规则类型:" + rule.getType());
        return evaluator.evaluate(rule, context);
    }

    // 批量评估，返回命中的规则ID
    public List<String> evaluateRules(Collection<RuleDefinition> rules, T context) {
        List<String> hitRules = new ArrayList<>();
        for (RuleDefinition rule : rules) {
            try {
                if (evaluate(rule, context)) hitRules.add(rule.getId());
            } catch (Exception e) {
                // 可记录异常归因
            }
        }
        return hitRules;
    }

    // 评估所有默认规则
    public List<String> evaluateDefaultRules(T context) {
        return evaluateRules(defaultRules, context);
    }
}
