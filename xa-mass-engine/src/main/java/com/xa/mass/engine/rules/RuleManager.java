package com.xa.mass.engine.rules;

import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleEvaluator;
import com.xa.mass.storage.rule.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores rule definitions and delegates evaluation by rule type.
 */
public class RuleManager<T> {

    private static final Logger log = LoggerFactory.getLogger(RuleManager.class);

    private final RuleStorage ruleStorage;
    private final RuleEvaluatorRegistry<T> evaluatorRegistry;

    public RuleManager(RuleStorage ruleStorage, RuleEvaluatorRegistry<T> evaluatorRegistry) {
        this.ruleStorage = Objects.requireNonNull(ruleStorage, "ruleStorage");
        this.evaluatorRegistry = Objects.requireNonNull(evaluatorRegistry, "evaluatorRegistry");
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
        Optional<RuleEvaluator<T>> evaluatorOpt = evaluatorRegistry.evaluator(rule.getType());
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

    public void registerEvaluator(RuleType ruleType, RuleEvaluator<T> evaluator) {
        evaluatorRegistry.registerEvaluator(ruleType, evaluator);
    }

    public Optional<RuleEvaluator<T>> getEvaluator(RuleType ruleType) {
        return evaluatorRegistry.evaluator(ruleType);
    }

    public List<RuleType> getRegisteredEvaluatorTypes() {
        return evaluatorRegistry.registeredEvaluatorTypes();
    }

    public boolean removeEvaluator(RuleType ruleType) {
        return evaluatorRegistry.removeEvaluator(ruleType);
    }

    public void clear() {
        ruleStorage.clear();
    }
}
