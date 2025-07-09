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
class DefaultValidateProcessor implements ValidateProcessor {
    
    @Override
    public <T> List<String> validate(T input, JsonDslDefinition definition, ProcessingContext context) {
        // 使用统一的参数校验
        ParameterValidator.validateValidateParams(input, definition, context);
        
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