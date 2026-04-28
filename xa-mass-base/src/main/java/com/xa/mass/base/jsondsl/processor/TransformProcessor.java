package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

/**
 * 转换处理器接口
 * <p>
 * 负责根据 DSL 定义转换单个对象
 * </p>
 */
public interface TransformProcessor extends JsonDslProcessor {

    /**
     * 转换单个对象
     *
     * @param input 输入对象
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 转换后的对象
     */
    <T> T transform(T input, JsonDslDefinition definition, ProcessingContext context);

    @Override
    default boolean supports(JsonDslDefinition.DslType type) {
        return JsonDslDefinition.DslType.TRANSFORM.equals(type);
    }

    @Override
    default String getName() {
        return "TransformProcessor";
    }

    @Override
    default int getPriority() {
        return 300; // 转换处理器优先级
    }
} 