package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理器注册表
 * <p>
 * 负责注册和管理自定义 DSL 处理器，支持自动发现和链式调用
 * 默认处理器由 ProcessorManager 统一管理
 * </p>
 */
public class ProcessorRegistry {
    
    private static final Map<String, JsonDslProcessor> customProcessors = new ConcurrentHashMap<>();
    private static final List<JsonDslProcessor> processorChain = new ArrayList<>();
    
    /**
     * 注册自定义处理器
     */
    public static void register(JsonDslProcessor processor) {
        customProcessors.put(processor.getName(), processor);
        
        // 重新构建处理器链（按优先级排序）
        rebuildProcessorChain();
    }
    
    /**
     * 获取自定义处理器
     */
    public static JsonDslProcessor get(String name) {
        return customProcessors.get(name);
    }
    
    /**
     * 获取支持指定类型的处理器（优先使用自定义处理器，否则使用默认处理器）
     */
    public static JsonDslProcessor getProcessor(JsonDslDefinition.DslType type) {
        // 先查找自定义处理器
        for (JsonDslProcessor processor : processorChain) {
            if (processor.supports(type)) {
                return processor;
            }
        }
        
        // 如果没有自定义处理器，尝试使用默认处理器
        JsonDslProcessor defaultProcessor = ProcessorManager.getProcessor(type);
        if (defaultProcessor != null) {
            return defaultProcessor;
        }
        
        // 如果连默认处理器都没有，抛出异常
        throw new IllegalArgumentException("未找到支持类型 " + type + " 的处理器");
    }
    
    /**
     * 获取所有处理器（包括自定义和默认处理器）
     */
    public static List<JsonDslProcessor> getAllProcessors() {
        List<JsonDslProcessor> allProcessors = new ArrayList<>(processorChain);
        
        // 添加默认处理器（如果还没有被自定义处理器覆盖）
        for (JsonDslDefinition.DslType type : JsonDslDefinition.DslType.values()) {
            boolean hasCustomProcessor = allProcessors.stream()
                .anyMatch(p -> p.supports(type));
            
            if (!hasCustomProcessor) {
                allProcessors.add(ProcessorManager.getProcessor(type));
            }
        }
        
        return allProcessors;
    }
    
    /**
     * 获取支持指定类型的处理器列表
     */
    public static List<JsonDslProcessor> getProcessors(JsonDslDefinition.DslType type) {
        List<JsonDslProcessor> result = new ArrayList<>();
        
        // 添加自定义处理器
        for (JsonDslProcessor processor : processorChain) {
            if (processor.supports(type)) {
                result.add(processor);
            }
        }
        
        // 如果没有自定义处理器，添加默认处理器
        if (result.isEmpty()) {
            result.add(ProcessorManager.getProcessor(type));
        }
        
        return result;
    }
    
    /**
     * 获取强类型生成处理器
     */
    public static GenerateProcessor getGenerateProcessor() {
        JsonDslProcessor processor = getProcessor(JsonDslDefinition.DslType.GENERATE);
        if (processor instanceof GenerateProcessor) {
            return (GenerateProcessor) processor;
        }
        throw new IllegalArgumentException("未找到生成处理器");
    }
    
    /**
     * 获取强类型过滤处理器
     */
    public static FilterProcessor getFilterProcessor() {
        JsonDslProcessor processor = getProcessor(JsonDslDefinition.DslType.FILTER);
        if (processor instanceof FilterProcessor) {
            return (FilterProcessor) processor;
        }
        throw new IllegalArgumentException("未找到过滤处理器");
    }
    
    /**
     * 获取强类型转换处理器
     */
    public static TransformProcessor getTransformProcessor() {
        JsonDslProcessor processor = getProcessor(JsonDslDefinition.DslType.TRANSFORM);
        if (processor instanceof TransformProcessor) {
            return (TransformProcessor) processor;
        }
        throw new IllegalArgumentException("未找到转换处理器");
    }
    
    /**
     * 获取强类型校验处理器
     */
    public static ValidateProcessor getValidateProcessor() {
        JsonDslProcessor processor = getProcessor(JsonDslDefinition.DslType.VALIDATE);
        if (processor instanceof ValidateProcessor) {
            return (ValidateProcessor) processor;
        }
        throw new IllegalArgumentException("未找到校验处理器");
    }
    
    /**
     * 链式处理多个 DSL（使用强类型处理器）
     */
    public static <T> List<T> processGenerateChain(List<JsonDslDefinition> definitions, ProcessingContext context, Class<T> targetType) {
        return ProcessorManager.processGenerateChain(definitions, context, targetType);
    }
    
    /**
     * 重新构建处理器链
     */
    private static void rebuildProcessorChain() {
        processorChain.clear();
        processorChain.addAll(customProcessors.values());
        
        // 按优先级排序（优先级高的在前）
        processorChain.sort((p1, p2) -> Integer.compare(p2.getPriority(), p1.getPriority()));
    }
    
    /**
     * 清除所有自定义处理器
     */
    public static void clear() {
        customProcessors.clear();
        processorChain.clear();
    }
    
    /**
     * 移除自定义处理器
     */
    public static JsonDslProcessor remove(String name) {
        JsonDslProcessor removed = customProcessors.remove(name);
        if (removed != null) {
            rebuildProcessorChain();
        }
        return removed;
    }
} 