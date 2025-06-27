package com.xa.mass.engine.storage;

import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 规则存储使用示例
 * 展示如何使用不同的存储实现
 */
public class RuleStorageExample {
    
    private static final Logger log = LoggerFactory.getLogger(RuleStorageExample.class);
    
    public static void main(String[] args) {
        // 示例1：使用默认内存存储
        log.info("=== 使用默认内存存储 ===");
        RuleManager<Map<String, Object>> memoryManager = new RuleManager<>(); // 自动使用内存存储
        demonstrateRuleManager(memoryManager);
        
        // 示例2：使用自定义内存存储
        log.info("\n=== 使用自定义内存存储 ===");
        RuleStorage memoryStorage = TaskStorageFactory.createDefaultRuleStorage();
        RuleManager<Map<String, Object>> customMemoryManager = new RuleManager<>(memoryStorage);
        demonstrateRuleManager(customMemoryManager);
        
        // 示例3：使用Redis存储（需要Redis依赖）
        log.info("\n=== 使用Redis存储 ===");
        try {
            RuleStorage redisStorage = TaskStorageFactory.createRuleStorage(TaskStorageFactory.StorageType.REDIS);
            RuleManager<Map<String, Object>> redisManager = new RuleManager<>(redisStorage);
            demonstrateRuleManager(redisManager);
        } catch (UnsupportedOperationException e) {
            log.warn("Redis存储未完全实现: {}", e.getMessage());
        }
        
        // 示例4：通过字符串配置创建存储
        log.info("\n=== 通过配置创建存储 ===");
        try {
            RuleStorage configStorage = TaskStorageFactory.createRuleStorage("memory");
            RuleManager<Map<String, Object>> configManager = new RuleManager<>(configStorage);
            demonstrateRuleManager(configManager);
        } catch (Exception e) {
            log.error("配置创建存储失败: {}", e.getMessage());
        }
    }
    
    private static void demonstrateRuleManager(RuleManager<Map<String, Object>> manager) {
        try {
            // 创建示例规则
            RuleDefinition rule1 = new RuleDefinition();
            rule1.setId("rule_001");
            rule1.setType(RuleType.QL_EXPRESS);
            rule1.setContent("age > 18 && country == 'US'");
            rule1.setDesc("年龄大于18且国家为美国的规则");
            
            RuleDefinition rule2 = new RuleDefinition();
            rule2.setId("rule_002");
            rule2.setType(RuleType.QL_EXPRESS);
            rule2.setContent("score >= 80");
            rule2.setDesc("分数大于等于80的规则");
            
            // 演示规则操作
            log.info("存储类型: {}", manager.getClass().getSimpleName());
            
            // 添加规则
            manager.addDefaultRule(rule1);
            manager.addDefaultRule(rule2);
            log.info("规则添加成功");
            
            // 获取规则
            var rule1Retrieved = manager.getRule("rule_001");
            log.info("规则获取成功: {}", rule1Retrieved.isPresent());
            
            // 获取所有规则
            var allRules = manager.getDefaultRules();
            log.info("所有规则数量: {}", allRules.size());
            
            // 根据类型获取规则
            var qlRules = manager.getRulesByType(RuleType.QL_EXPRESS);
            log.info("QL_EXPRESS类型规则数量: {}", qlRules.size());
            
            // 创建测试上下文
            Map<String, Object> context1 = new HashMap<>();
            context1.put("age", 20);
            context1.put("country", "US");
            context1.put("score", 85);
            
            Map<String, Object> context2 = new HashMap<>();
            context2.put("age", 16);
            context2.put("country", "CN");
            context2.put("score", 75);
            
            // 评估单个规则
            try {
                boolean result1 = manager.evaluate(rule1, context1);
                log.info("规则1评估结果 (context1): {}", result1);
                
                boolean result2 = manager.evaluate(rule1, context2);
                log.info("规则1评估结果 (context2): {}", result2);
            } catch (Exception e) {
                log.error("规则评估失败: {}", e.getMessage());
            }
            
            // 批量评估规则
            try {
                var hitRules = manager.evaluateRules(Arrays.asList(rule1, rule2), context1);
                log.info("批量评估命中规则: {}", hitRules);
            } catch (Exception e) {
                log.error("批量评估失败: {}", e.getMessage());
            }
            
            // 评估所有默认规则
            try {
                var defaultHitRules = manager.evaluateDefaultRules(context1);
                log.info("默认规则评估命中: {}", defaultHitRules);
            } catch (Exception e) {
                log.error("默认规则评估失败: {}", e.getMessage());
            }
            
            // 获取已注册的评估器类型
            var evaluatorTypes = manager.getRegisteredEvaluatorTypes();
            log.info("已注册评估器类型: {}", evaluatorTypes);
            
            // 删除规则
            manager.removeDefaultRule("rule_001");
            log.info("规则删除成功");
            
            // 验证删除
            var remainingRules = manager.getDefaultRules();
            log.info("剩余规则数量: {}", remainingRules.size());
            
            log.info("规则存储操作演示完成");
            
        } catch (Exception e) {
            log.error("规则存储操作失败: {}", e.getMessage());
        }
    }
} 