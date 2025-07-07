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
        register(new DefaultGenerateProcessor<>());
        register(new DefaultFilterProcessor<>());
        register(new DefaultTransformProcessor<>());
        register(new DefaultValidateProcessor<>());
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
     * 获取强类型生成处理器
     */
    @SuppressWarnings("unchecked")
    public static <T> GenerateProcessor<T> getGenerateProcessor() {
        JsonDslProcessor processor = getProcessor(JsonDslDefinition.DslType.GENERATE);
        if (processor instanceof GenerateProcessor) {
            return (GenerateProcessor<T>) processor;
        }
        throw new IllegalArgumentException("未找到生成处理器");
    }
    
    /**
     * 获取强类型过滤处理器
     */
    @SuppressWarnings("unchecked")
    public static <T> FilterProcessor<T> getFilterProcessor() {
        JsonDslProcessor processor = getProcessor(JsonDslDefinition.DslType.FILTER);
        if (processor instanceof FilterProcessor) {
            return (FilterProcessor<T>) processor;
        }
        throw new IllegalArgumentException("未找到过滤处理器");
    }
    
    /**
     * 获取强类型转换处理器
     */
    @SuppressWarnings("unchecked")
    public static <T> TransformProcessor<T> getTransformProcessor() {
        JsonDslProcessor processor = getProcessor(JsonDslDefinition.DslType.TRANSFORM);
        if (processor instanceof TransformProcessor) {
            return (TransformProcessor<T>) processor;
        }
        throw new IllegalArgumentException("未找到转换处理器");
    }
    
    /**
     * 获取强类型校验处理器
     */
    @SuppressWarnings("unchecked")
    public static <T> ValidateProcessor<T> getValidateProcessor() {
        JsonDslProcessor processor = getProcessor(JsonDslDefinition.DslType.VALIDATE);
        if (processor instanceof ValidateProcessor) {
            return (ValidateProcessor<T>) processor;
        }
        throw new IllegalArgumentException("未找到校验处理器");
    }
    
    /**
     * 链式处理多个 DSL（使用强类型处理器）
     */
    public static <T> List<T> processGenerateChain(List<JsonDslDefinition> definitions, ProcessingContext context, Class<T> targetType) {
        List<T> result = null;
        
        for (JsonDslDefinition definition : definitions) {
            if (context.isDebug()) {
                System.out.println("[ProcessorRegistry] 处理 DSL: " + definition.getUniqueId() + 
                    " (类型: " + definition.getType() + ")");
            }
            
            if (JsonDslDefinition.DslType.GENERATE.equals(definition.getType())) {
                GenerateProcessor<T> processor = getGenerateProcessor();
                result = processor.generate(definition, context, targetType);
            } else if (JsonDslDefinition.DslType.FILTER.equals(definition.getType())) {
                FilterProcessor<T> processor = getFilterProcessor();
                if (result == null) {
                    throw new IllegalArgumentException("过滤处理器需要前置的生成结果");
                }
                result = processor.filter(result, definition, context);
            } else if (JsonDslDefinition.DslType.TRANSFORM.equals(definition.getType())) {
                TransformProcessor<T> processor = getTransformProcessor();
                if (result == null || result.isEmpty()) {
                    throw new IllegalArgumentException("转换处理器需要前置的生成结果");
                }
                List<T> transformed = result.stream()
                    .map(obj -> processor.transform(obj, definition, context))
                    .toList();
                result = transformed;
            } else if (JsonDslDefinition.DslType.VALIDATE.equals(definition.getType())) {
                ValidateProcessor<T> processor = getValidateProcessor();
                if (result == null || result.isEmpty()) {
                    throw new IllegalArgumentException("校验处理器需要前置的生成结果");
                }
                // 校验所有对象
                for (T obj : result) {
                    List<String> errors = processor.validate(obj, definition, context);
                    if (!errors.isEmpty()) {
                        throw new IllegalArgumentException("校验失败: " + String.join(", ", errors));
                    }
                }
            } else {
                throw new IllegalArgumentException("不支持的 DSL 类型: " + definition.getType());
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