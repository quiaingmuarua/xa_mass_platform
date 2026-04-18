package com.xa.mass.engine.rules;

import com.xa.mass.engine.storage.RuleStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 规则管理器
 * 负责规则的CRUD操作和规则评估
 */
public class RuleManager<T> {

    private static final Logger log = LoggerFactory.getLogger(RuleManager.class);

    private final RuleStorage ruleStorage;

    public RuleManager() {
        this(TaskStorageFactory.createDefaultRuleStorage());
    }

    public RuleManager(RuleStorage ruleStorage) {
        this.ruleStorage = ruleStorage;
    }

    /**
     * 注册全局/默认规则
     */
    public void addDefaultRule(RuleDefinition rule) {
        ruleStorage.addRule(rule);
    }

    /**
     * 支持批量注册
     */
    public void addDefaultRules(Collection<RuleDefinition> rules) {
        ruleStorage.addRules(rules);
    }

    /**
     * 支持移除规则
     */
    public void removeDefaultRule(String ruleId) {
        ruleStorage.deleteRule(ruleId);
    }

    /**
     * 获取默认规则
     */
    public List<RuleDefinition> getDefaultRules() {
        return ruleStorage.getAllRules();
    }

    /**
     * 单规则评估
     */
    public boolean evaluate(RuleDefinition rule, T context) throws Exception {
        Optional<RuleEvaluator> evaluatorOpt = ruleStorage.getEvaluator(rule.getType());
        if (evaluatorOpt.isEmpty()) {
            throw new IllegalArgumentException("不支持的规则类型:" + rule.getType());
        }
        return evaluatorOpt.get().evaluate(rule, context);
    }

    /**
     * 批量评估，返回命中的规则ID
     */
    public List<String> evaluateRules(Collection<RuleDefinition> rules, T context) {
        List<String> hitRules = new java.util.ArrayList<>();
        for (RuleDefinition rule : rules) {
            try {
                if (evaluate(rule, context)) {
                    hitRules.add(rule.getId());
                }
            } catch (Exception e) {
                log.warn("Rule evaluation failed [ruleId={}, ruleType={}]: {}", rule.getId(), rule.getType(), e.getMessage());
            }
        }
        return hitRules;
    }

    /**
     * 评估所有默认规则
     */
    public List<String> evaluateDefaultRules(T context) {
        return evaluateRules(getDefaultRules(), context);
    }

    /**
     * 根据ID获取规则
     */
    public Optional<RuleDefinition> getRule(String ruleId) {
        return ruleStorage.getRule(ruleId);
    }

    /**
     * 更新规则
     */
    public boolean updateRule(RuleDefinition rule) {
        return ruleStorage.updateRule(rule);
    }

    /**
     * 删除规则
     */
    public boolean deleteRule(String ruleId) {
        return ruleStorage.deleteRule(ruleId);
    }

    /**
     * 根据类型获取规则
     */
    public List<RuleDefinition> getRulesByType(RuleType ruleType) {
        return ruleStorage.getRulesByType(ruleType);
    }

    /**
     * 注册规则评估器
     */
    public void registerEvaluator(RuleType ruleType, RuleEvaluator evaluator) {
        ruleStorage.registerEvaluator(ruleType, evaluator);
    }

    /**
     * 获取规则评估器
     */
    public Optional<RuleEvaluator> getEvaluator(RuleType ruleType) {
        return ruleStorage.getEvaluator(ruleType);
    }

    /**
     * 获取所有已注册的评估器类型
     */
    public List<RuleType> getRegisteredEvaluatorTypes() {
        return ruleStorage.getRegisteredEvaluatorTypes();
    }

    /**
     * 移除规则评估器
     */
    public boolean removeEvaluator(RuleType ruleType) {
        return ruleStorage.removeEvaluator(ruleType);
    }

    /**
     * 清空所有规则和评估器
     */
    public void clear() {
        ruleStorage.clear();
    }
}
