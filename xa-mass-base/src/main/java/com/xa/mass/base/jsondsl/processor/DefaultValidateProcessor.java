package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认校验处理器实现
 * <p>
 * 负责根据 DSL 定义校验对象
 * </p>
 * @param <T> 校验对象的类型
 */
public class DefaultValidateProcessor<T> implements ValidateProcessor<T> {
    
    @Override
    public List<String> validate(T obj, JsonDslDefinition definition, ProcessingContext context) {
        // 参数验证
        if (obj == null) {
            throw new IllegalArgumentException("Object to validate cannot be null");
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
        
        // 执行校验逻辑
        List<String> errors = validateObject(obj, definition, context);
        
        if (context.isDebug()) {
            System.out.println("[DefaultValidateProcessor] 校验完成，错误数量: " + errors.size());
        }
        
        return errors;
    }
    
    /**
     * 校验单个对象
     */
    private List<String> validateObject(T obj, JsonDslDefinition definition, ProcessingContext context) {
        List<String> errors = new ArrayList<>();
        
        // 这里实现具体的校验逻辑
        // 可以根据 fieldDsl 进行字段级校验
        // 可以根据 combine_dsl 进行复杂校验
        
        // 示例：简单校验（实际应该根据 DSL 规则进行校验）
        // 暂时返回空列表表示校验通过
        
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