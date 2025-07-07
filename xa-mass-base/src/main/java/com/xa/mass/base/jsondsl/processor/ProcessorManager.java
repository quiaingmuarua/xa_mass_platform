package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理器管理器
 * <p>
 * 统一管理所有处理器的创建和获取，隐藏默认处理器的具体实现
 * 提供工厂模式和策略模式的结合，支持自定义处理器扩展
 * </p>
 */
public class ProcessorManager {
    
    private static final Map<JsonDslDefinition.DslType, ProcessorFactory> factories = new ConcurrentHashMap<>();
    private static final Map<JsonDslDefinition.DslType, JsonDslProcessor> defaultProcessors = new ConcurrentHashMap<>();
    
    static {
        // 注册默认处理器工厂
        registerDefaultFactories();
        // 初始化默认处理器
        initializeDefaultProcessors();
    }
    
    /**
     * 处理器工厂接口
     */
    public interface ProcessorFactory {
        JsonDslProcessor createProcessor();
        boolean isSingleton();
    }
    
    /**
     * 注册处理器工厂
     */
    public static void registerFactory(JsonDslDefinition.DslType type, ProcessorFactory factory) {
        factories.put(type, factory);
        // 如果工厂是单例模式，立即创建并缓存处理器
        if (factory.isSingleton()) {
            defaultProcessors.put(type, factory.createProcessor());
        }
    }
    
    /**
     * 获取处理器（优先使用缓存的单例，否则创建新实例）
     */
    public static JsonDslProcessor getProcessor(JsonDslDefinition.DslType type) {
        // 先检查是否有缓存的单例处理器
        JsonDslProcessor cached = defaultProcessors.get(type);
        if (cached != null) {
            return cached;
        }
        
        // 查找工厂并创建新实例
        ProcessorFactory factory = factories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("未找到类型 " + type + " 的处理器工厂");
        }
        
        return factory.createProcessor();
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
        List<T> result = null;
        
        for (JsonDslDefinition definition : definitions) {
            if (context.isDebug()) {
                System.out.println("[ProcessorManager] 处理 DSL: " + definition.getUniqueId() + 
                    " (类型: " + definition.getType() + ")");
            }
            
            if (JsonDslDefinition.DslType.GENERATE.equals(definition.getType())) {
                GenerateProcessor processor = getGenerateProcessor();
                result = processor.generate(definition, context, targetType);
            } else if (JsonDslDefinition.DslType.FILTER.equals(definition.getType())) {
                FilterProcessor processor = getFilterProcessor();
                if (result == null) {
                    throw new IllegalArgumentException("过滤处理器需要前置的生成结果");
                }
                FilterResult<T> filterResult = ((FilterProcessor) processor).filter(result, definition, context);
                result = filterResult.getPassed();
            } else if (JsonDslDefinition.DslType.TRANSFORM.equals(definition.getType())) {
                TransformProcessor processor = getTransformProcessor();
                if (result == null || result.isEmpty()) {
                    throw new IllegalArgumentException("转换处理器需要前置的生成结果");
                }
                List<T> transformed = result.stream()
                    .map(obj -> processor.transform(obj, definition, context))
                    .toList();
                result = transformed;
            } else if (JsonDslDefinition.DslType.VALIDATE.equals(definition.getType())) {
                ValidateProcessor processor = getValidateProcessor();
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
     * 注册默认处理器工厂
     */
    private static void registerDefaultFactories() {
        // 生成处理器工厂
        registerFactory(JsonDslDefinition.DslType.GENERATE, new ProcessorFactory() {
            @Override
            public JsonDslProcessor createProcessor() {
                return new DefaultGenerateProcessor();
            }
            
            @Override
            public boolean isSingleton() {
                return true; // 生成处理器使用单例模式
            }
        });
        
        // 过滤处理器工厂
        registerFactory(JsonDslDefinition.DslType.FILTER, new ProcessorFactory() {
            @Override
            public JsonDslProcessor createProcessor() {
                return new DefaultFilterProcessor();
            }
            
            @Override
            public boolean isSingleton() {
                return true; // 过滤处理器使用单例模式
            }
        });
        
        // 转换处理器工厂
        registerFactory(JsonDslDefinition.DslType.TRANSFORM, new ProcessorFactory() {
            @Override
            public JsonDslProcessor createProcessor() {
                return new DefaultTransformProcessor();
            }
            
            @Override
            public boolean isSingleton() {
                return true; // 转换处理器使用单例模式
            }
        });
        
        // 校验处理器工厂
        registerFactory(JsonDslDefinition.DslType.VALIDATE, new ProcessorFactory() {
            @Override
            public JsonDslProcessor createProcessor() {
                return new DefaultValidateProcessor();
            }
            
            @Override
            public boolean isSingleton() {
                return true; // 校验处理器使用单例模式
            }
        });
    }
    
    /**
     * 初始化默认处理器
     */
    private static void initializeDefaultProcessors() {
        // 预创建所有默认处理器（单例模式）
        for (JsonDslDefinition.DslType type : JsonDslDefinition.DslType.values()) {
            if (factories.containsKey(type)) {
                ProcessorFactory factory = factories.get(type);
                if (factory.isSingleton()) {
                    defaultProcessors.put(type, factory.createProcessor());
                }
            }
        }
    }
    
    /**
     * 清除所有处理器和工厂
     */
    public static void clear() {
        factories.clear();
        defaultProcessors.clear();
    }
    
    /**
     * 重置为默认配置
     */
    public static void reset() {
        clear();
        registerDefaultFactories();
        initializeDefaultProcessors();
    }
} 