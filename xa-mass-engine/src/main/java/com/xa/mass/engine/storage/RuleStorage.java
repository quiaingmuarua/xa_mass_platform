package com.xa.mass.engine.storage;

import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleEvaluator;
import com.xa.mass.engine.rules.RuleType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 规则存储接口
 * 提供规则定义和规则评估器的存储抽象能力
 */
public interface RuleStorage {

    /**
     * 添加规则定义
     */
    void addRule(RuleDefinition rule);

    /**
     * 根据ID获取规则定义
     */
    Optional<RuleDefinition> getRule(String ruleId);

    /**
     * 更新规则定义
     */
    boolean updateRule(RuleDefinition rule);

    /**
     * 删除规则定义
     */
    boolean deleteRule(String ruleId);

    /**
     * 获取所有规则定义
     */
    List<RuleDefinition> getAllRules();

    /**
     * 根据类型获取规则定义
     */
    List<RuleDefinition> getRulesByType(RuleType ruleType);

    /**
     * 批量添加规则定义
     */
    void addRules(Collection<RuleDefinition> rules);

    /**
     * 批量删除规则定义
     */
    void deleteRules(Collection<String> ruleIds);

    /**
     * 注册规则评估器
     */
    void registerEvaluator(RuleType ruleType, RuleEvaluator evaluator);

    /**
     * 根据类型获取规则评估器
     */
    Optional<RuleEvaluator> getEvaluator(RuleType ruleType);

    /**
     * 获取所有已注册的评估器类型
     */
    List<RuleType> getRegisteredEvaluatorTypes();

    /**
     * 移除规则评估器
     */
    boolean removeEvaluator(RuleType ruleType);

    /**
     * 清空所有规则和评估器
     */
    void clear();
} 