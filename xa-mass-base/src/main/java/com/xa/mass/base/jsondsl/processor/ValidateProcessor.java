package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.List;

/**
 * 校验处理器接口
 * <p>
 * 负责根据 DSL 定义校验单个对象
 * </p>
 */
public interface ValidateProcessor extends JsonDslProcessor {

    /**
     * 校验单个对象
     *
     * @param input 输入对象
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 校验错误列表，如果为空则表示校验通过
     */
    <T> List<String> validate(T input, JsonDslDefinition definition, ProcessingContext context);

    @Override
    default boolean supports(JsonDslDefinition.DslType type) {
        return JsonDslDefinition.DslType.VALIDATE.equals(type);
    }

    @Override
    default String getName() {
        return "ValidateProcessor";
    }

    @Override
    default int getPriority() {
        return 400; // 校验处理器优先级
    }
} 