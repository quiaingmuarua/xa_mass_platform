package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理器注册表
 * <p>
 * 负责注册和管理所有 DSL 处理器，支持自动发现和链式调用
 * </p>
 */
public class ProcessorRegistry {
    
    private static final Map<String, JsonDslProcessor> processors = new ConcurrentHashMap<>();
    private static final List<JsonDslProcessor> processorChain = new ArrayList<>();
    
    static {
        // 注册默认处理器
        register(new GenerateProcessor());
        register(new FilterProcessor());
        register(new TransformProcessor());
        register(new ValidateProcessor());
    }
    
    /**
     * 注册处理器
     */
    public static void register(JsonDslProcessor processor) {
        processors.put(processor.getName(), processor);
        
        // 重新构建处理器链（按优先级排序）
        rebuildProcessorChain();
    }
    
    /**
     * 获取处理器
     */
    public static JsonDslProcessor get(String name) {
        return processors.get(name);
    }
    
    /**
     * 获取支持指定类型的处理器
     */
    public static JsonDslProcessor getProcessor(JsonDslDefinition.DslType type) {
        for (JsonDslProcessor processor : processorChain) {
            if (processor.supports(type)) {
                return processor;
            }
        }
        throw new IllegalArgumentException("未找到支持类型 " + type + " 的处理器");
    }
    
    /**
     * 获取所有处理器
     */
    public static List<JsonDslProcessor> getAllProcessors() {
        return new ArrayList<>(processorChain);
    }
    
    /**
     * 获取支持指定类型的处理器列表
     */
    public static List<JsonDslProcessor> getProcessors(JsonDslDefinition.DslType type) {
        return processorChain.stream()
            .filter(processor -> processor.supports(type))
            .toList();
    }
    
    /**
     * 链式处理 DSL
     */
    public static Object processChain(JsonDslDefinition definition, ProcessingContext context) {
        JsonDslProcessor processor = getProcessor(definition.getType());
        return processor.process(definition, context);
    }
    
    /**
     * 链式处理多个 DSL
     */
    public static Object processChain(List<JsonDslDefinition> definitions, ProcessingContext context) {
        Object result = null;
        
        for (JsonDslDefinition definition : definitions) {
            if (context.isDebug()) {
                System.out.println("[ProcessorRegistry] 处理 DSL: " + definition.getUniqueId() + 
                    " (类型: " + definition.getType() + ")");
            }
            
            result = processChain(definition, context);
            
            // 将结果传递给下一个处理器
            if (result instanceof List) {
                context.setParameter("objects", result);
            }
        }
        
        return result;
    }
    
    /**
     * 重新构建处理器链
     */
    private static void rebuildProcessorChain() {
        processorChain.clear();
        processorChain.addAll(processors.values());
        
        // 按优先级排序（优先级高的在前）
        processorChain.sort((p1, p2) -> Integer.compare(p2.getPriority(), p1.getPriority()));
    }
    
    /**
     * 清除所有处理器
     */
    public static void clear() {
        processors.clear();
        processorChain.clear();
    }
    
    /**
     * 移除处理器
     */
    public static void remove(String name) {
        processors.remove(name);
        rebuildProcessorChain();
    }
} 