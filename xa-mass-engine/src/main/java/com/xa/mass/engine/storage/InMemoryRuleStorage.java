package com.xa.mass.engine.storage;

import com.xa.mass.engine.rules.QLExpressRuleEvaluator;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleEvaluator;
import com.xa.mass.engine.rules.RuleType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存规则存储实现
 * 使用ConcurrentHashMap保证线程安全
 */
public class InMemoryRuleStorage implements RuleStorage {

    private final Map<String, RuleDefinition> ruleMap = new ConcurrentHashMap<>();
    private final Map<RuleType, RuleEvaluator> evaluatorMap = new ConcurrentHashMap<>();

    public InMemoryRuleStorage() {
        // 注册默认的评估器
        registerEvaluator(RuleType.QL_EXPRESS, new QLExpressRuleEvaluator());
    }

    @Override
    public void addRule(RuleDefinition rule) {
        ruleMap.put(rule.getId(), rule);
    }

    @Override
    public Optional<RuleDefinition> getRule(String ruleId) {
        return Optional.ofNullable(ruleMap.get(ruleId));
    }

    @Override
    public boolean updateRule(RuleDefinition rule) {
        if (rule.getId() == null || !ruleMap.containsKey(rule.getId())) {
            return false;
        }
        ruleMap.put(rule.getId(), rule);
        return true;
    }

    @Override
    public boolean deleteRule(String ruleId) {
        RuleDefinition removed = ruleMap.remove(ruleId);
        return removed != null;
    }

    @Override
    public List<RuleDefinition> getAllRules() {
        return new ArrayList<>(ruleMap.values());
    }

    @Override
    public List<RuleDefinition> getRulesByType(RuleType ruleType) {
        return ruleMap.values().stream()
                .filter(rule -> rule.getType() == ruleType)
                .collect(Collectors.toList());
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
    public void registerEvaluator(RuleType ruleType, RuleEvaluator evaluator) {
        evaluatorMap.put(ruleType, evaluator);
    }

    @Override
    public Optional<RuleEvaluator> getEvaluator(RuleType ruleType) {
        return Optional.ofNullable(evaluatorMap.get(ruleType));
    }

    @Override
    public List<RuleType> getRegisteredEvaluatorTypes() {
        return new ArrayList<>(evaluatorMap.keySet());
    }

    @Override
    public boolean removeEvaluator(RuleType ruleType) {
        RuleEvaluator removed = evaluatorMap.remove(ruleType);
        return removed != null;
    }

    @Override
    public void clear() {
        ruleMap.clear();
        evaluatorMap.clear();
        // 重新注册默认评估器
        registerEvaluator(RuleType.QL_EXPRESS, new QLExpressRuleEvaluator());
    }
} 