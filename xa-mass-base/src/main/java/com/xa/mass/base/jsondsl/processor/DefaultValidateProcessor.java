package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认校验处理器实现
 * <p>
 * 负责根据 DSL 定义校验单个对象
 * </p>
 */
public class DefaultValidateProcessor implements ValidateProcessor {
    
    @Override
    public <T> List<String> validate(T input, JsonDslDefinition definition, ProcessingContext context) {
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
            System.out.println("[DefaultValidateProcessor] 开始处理 DSL: " + definition.getUniqueId());
        }
        
        // 验证 DSL 定义
        definition.validate();
        
        // 当前实现：简单返回空列表（表示校验通过）
        // 可以扩展为实际的校验逻辑
        List<String> errors = new ArrayList<>();
        
        if (context.isDebug()) {
            System.out.println("[DefaultValidateProcessor] 校验完成，错误数: " + errors.size());
        }
        
        return errors;
    }
    
    @Override
    public String getName() {
        return "DefaultValidateProcessor";
    }
    
    @Override
    public int getPriority() {
        return 400; // 校验处理器优先级
    }
} 