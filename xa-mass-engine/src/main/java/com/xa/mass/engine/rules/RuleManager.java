package com.xa.mass.engine.rules;

import com.xa.mass.engine.storage.RuleStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Stores rule definitions and delegates evaluation by rule type.
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

    public void addDefaultRule(RuleDefinition rule) {
        ruleStorage.addRule(rule);
    }

    public void addDefaultRules(Collection<RuleDefinition> rules) {
        ruleStorage.addRules(rules);
    }

    public void removeDefaultRule(String ruleId) {
        ruleStorage.deleteRule(ruleId);
    }

    public List<RuleDefinition> getDefaultRules() {
        return ruleStorage.getAllRules();
    }

    public boolean evaluate(RuleDefinition rule, T context) throws Exception {
        Optional<RuleEvaluator> evaluatorOpt = ruleStorage.getEvaluator(rule.getType());
        if (evaluatorOpt.isEmpty()) {
            throw new IllegalArgumentException("Unsupported rule type: " + rule.getType());
        }
        return evaluatorOpt.get().evaluate(rule, context);
    }

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

    public List<String> evaluateDefaultRules(T context) {
        return evaluateRules(getDefaultRules(), context);
    }

    public Optional<RuleDefinition> getRule(String ruleId) {
        return ruleStorage.getRule(ruleId);
    }

    public boolean updateRule(RuleDefinition rule) {
        return ruleStorage.updateRule(rule);
    }

    public boolean deleteRule(String ruleId) {
        return ruleStorage.deleteRule(ruleId);
    }

    public List<RuleDefinition> getRulesByType(RuleType ruleType) {
        return ruleStorage.getRulesByType(ruleType);
    }

    public void registerEvaluator(RuleType ruleType, RuleEvaluator evaluator) {
        ruleStorage.registerEvaluator(ruleType, evaluator);
    }

    public Optional<RuleEvaluator> getEvaluator(RuleType ruleType) {
        return ruleStorage.getEvaluator(ruleType);
    }

    public List<RuleType> getRegisteredEvaluatorTypes() {
        return ruleStorage.getRegisteredEvaluatorTypes();
    }

    public boolean removeEvaluator(RuleType ruleType) {
        return ruleStorage.removeEvaluator(ruleType);
    }

    public void clear() {
        ruleStorage.clear();
    }
}
