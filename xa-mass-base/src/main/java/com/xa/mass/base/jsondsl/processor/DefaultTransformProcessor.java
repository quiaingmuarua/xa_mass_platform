package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

/**
 * 默认转换处理器实现
 * <p>
 * 负责根据 DSL 定义转换单个对象
 * </p>
 */
class DefaultTransformProcessor implements TransformProcessor {
    
    @Override
    public <T> T transform(T input, JsonDslDefinition definition, ProcessingContext context) {
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
        
        // 当前实现：简单返回原对象（可以扩展为实际的转换逻辑）
        if (context.isDebug()) {
            System.out.println("[DefaultTransformProcessor] 转换完成");
        }
        
        return input;
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