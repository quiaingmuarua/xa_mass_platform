package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.List;

/**
 * 校验处理器接口
 * <p>
 * 负责根据 DSL 定义校验对象
 * </p>
 * @param <T> 校验对象的类型
 */
public interface ValidateProcessor<T> extends JsonDslProcessor {
    
    /**
     * 校验对象
     * 
     * @param obj 待校验对象
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 校验错误信息列表，空列表表示校验通过
     */
    List<String> validate(T obj, JsonDslDefinition definition, ProcessingContext context);
    
    @Override
    default boolean supports(JsonDslDefinition.DslType type) {
        return JsonDslDefinition.DslType.VALIDATE.equals(type);
    }
} 