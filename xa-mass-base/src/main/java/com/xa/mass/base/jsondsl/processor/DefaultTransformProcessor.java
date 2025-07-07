package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

/**
 * 默认转换处理器实现
 * <p>
 * 负责根据 DSL 定义转换对象
 * </p>
 * @param <T> 转换对象的类型
 */
public class DefaultTransformProcessor<T> implements TransformProcessor<T> {
    
    @Override
    public T transform(T input, JsonDslDefinition definition, ProcessingContext context) {
        // 参数验证
        if (input == null) {
            throw new IllegalArgumentException("Input object cannot be null");
        }
        if (definition == null) {
            throw new IllegalArgumentException("Definition cannot be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        
        if (context.isDebug()) {
            System.out.println("[DefaultTransformProcessor] 开始处理 DSL: " + definition.getUniqueId());
        }
        
        // 验证 DSL 定义
        definition.validate();
        
        // 这里实现具体的转换逻辑
        // 可以根据 fieldDsl 进行字段映射
        // 可以根据 combine_dsl 进行复杂转换
        
        // 示例：返回原对象（实际应该根据 DSL 规则进行转换）
        T result = transformObject(input, definition, context);
        
        if (context.isDebug()) {
            System.out.println("[DefaultTransformProcessor] 转换完成");
        }
        
        return result;
    }
    
    /**
     * 转换单个对象
     */
    private T transformObject(T obj, JsonDslDefinition definition, ProcessingContext context) {
        // 这里实现具体的对象转换逻辑
        // 可以根据 fieldDsl 进行字段映射
        // 可以根据 combine_dsl 进行复杂转换
        
        // 示例：返回原对象（实际应该根据 DSL 规则进行转换）
        return obj;
    }
    
    @Override
    public String getName() {
        return "DefaultTransformProcessor";
    }
    
    @Override
    public int getPriority() {
        return 300; // 转换处理器优先级
    }
} 