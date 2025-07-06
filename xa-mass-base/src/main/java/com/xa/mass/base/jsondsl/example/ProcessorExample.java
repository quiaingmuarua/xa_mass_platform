package com.xa.mass.base.jsondsl.example;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.processor.JsonDslProcessorEngine;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.jsondsl.processor.JsonDslProcessor;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;

import java.util.*;

/**
 * 新的处理器架构使用示例
 * <p>
 * 展示如何使用新的处理器模式进行 DSL 处理
 * </p>
 */
public class ProcessorExample {
    
    public static void main(String[] args) {
        // 示例1：单个 DSL 处理
        singleDslExample();
        
        // 示例2：链式 DSL 处理
        chainDslExample();
        
        // 示例3：自定义处理器
        customProcessorExample();
        
        // 示例4：调试模式
        debugModeExample();
    }
    
    /**
     * 示例1：单个 DSL 处理
     */
    public static void singleDslExample() {
        System.out.println("=== 示例1：单个 DSL 处理 ===");
        
        // 创建生成类型的 DSL
        JsonDslDefinition generateDsl = new JsonDslDefinition("user-generator", JsonDslDefinition.DslType.GENERATE);
        generateDsl.setDescription("生成用户数据");
        generateDsl.setPriority(1);
        
        JsonDslContext context = new JsonDslContext();
        context.setModel("com.xa.mass.base.model.User");
        context.setCount(3);
        generateDsl.setContext(context);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        fieldDsl.put("age", "$RANDOM_INT(18, 65)");
        fieldDsl.put("email", "$RANDOM_EMAIL");
        generateDsl.setFieldDsl(fieldDsl);
        
        // 处理 DSL
        try {
            Object result = JsonDslProcessorEngine.process(generateDsl);
            System.out.println("生成结果: " + result);
        } catch (Exception e) {
            System.out.println("处理失败: " + e.getMessage());
        }
    }
    
    /**
     * 示例2：链式 DSL 处理
     */
    public static void chainDslExample() {
        System.out.println("\n=== 示例2：链式 DSL 处理 ===");
        
        // 创建处理上下文
        ProcessingContext context = new ProcessingContext("chain-example");
        
        // 创建生成 DSL
        JsonDslDefinition generateDsl = new JsonDslDefinition("user-generator", JsonDslDefinition.DslType.GENERATE);
        generateDsl.setDescription("生成用户数据");
        
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.model.User");
        dslContext.setCount(5);
        generateDsl.setContext(dslContext);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        fieldDsl.put("age", "$RANDOM_INT(18, 65)");
        fieldDsl.put("email", "$RANDOM_EMAIL");
        generateDsl.setFieldDsl(fieldDsl);
        
        // 创建过滤 DSL
        JsonDslDefinition filterDsl = new JsonDslDefinition("age-filter", JsonDslDefinition.DslType.FILTER);
        filterDsl.setDescription("过滤年龄大于30的用户");
        filterDsl.setPriority(2);
        
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("age", "$EXPR(age > 30)");
        filterDsl.setFieldDsl(filterFieldDsl);
        
        // 链式处理
        try {
            List<JsonDslDefinition> dslChain = Arrays.asList(generateDsl, filterDsl);
            Object result = JsonDslProcessorEngine.processChain(dslChain, context);
            System.out.println("链式处理结果: " + result);
        } catch (Exception e) {
            System.out.println("链式处理失败: " + e.getMessage());
        }
    }
    
    /**
     * 示例3：自定义处理器
     */
    public static void customProcessorExample() {
        System.out.println("\n=== 示例3：自定义处理器 ===");
        
        // 注册自定义处理器
        JsonDslProcessorEngine.registerProcessor(new CustomProcessor());
        
        // 创建使用自定义类型的 DSL
        JsonDslDefinition customDsl = new JsonDslDefinition("custom-processor", JsonDslDefinition.DslType.TRANSFORM);
        customDsl.setDescription("使用自定义处理器");
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("customField", "$CUSTOM_FUNCTION");
        customDsl.setFieldDsl(fieldDsl);
        
        // 处理 DSL
        try {
            Object result = JsonDslProcessorEngine.process(customDsl);
            System.out.println("自定义处理器结果: " + result);
        } catch (Exception e) {
            System.out.println("自定义处理失败: " + e.getMessage());
        }
    }
    
    /**
     * 示例4：调试模式
     */
    public static void debugModeExample() {
        System.out.println("\n=== 示例4：调试模式 ===");
        
        // 创建调试上下文
        ProcessingContext debugContext = new ProcessingContext("debug-example");
        debugContext.setDebug(true);
        debugContext.setParameter("objects", Arrays.asList("test1", "test2", "test3"));
        
        // 创建过滤 DSL
        JsonDslDefinition filterDsl = new JsonDslDefinition("debug-filter", JsonDslDefinition.DslType.FILTER);
        filterDsl.setDescription("调试模式过滤");
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("value", "$EXPR(value.contains('test'))");
        filterDsl.setFieldDsl(fieldDsl);
        
        // 处理 DSL（会输出调试信息）
        try {
            Object result = JsonDslProcessorEngine.process(filterDsl, debugContext);
            System.out.println("调试模式结果: " + result);
        } catch (Exception e) {
            System.out.println("调试处理失败: " + e.getMessage());
        }
    }
    
    /**
     * 自定义处理器示例
     */
    public static class CustomProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            System.out.println("[CustomProcessor] 处理自定义 DSL: " + definition.getUniqueId());
            
            // 自定义处理逻辑
            Map<String, Object> result = new HashMap<>();
            result.put("processedBy", "CustomProcessor");
            result.put("dslId", definition.getUniqueId());
            result.put("timestamp", System.currentTimeMillis());
            
            return result;
        }
        
        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.TRANSFORM.equals(type);
        }
        
        @Override
        public String getName() {
            return "CustomProcessor";
        }
        
        @Override
        public int getPriority() {
            return 500; // 高优先级
        }
    }
} 