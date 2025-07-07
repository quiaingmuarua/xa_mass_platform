package com.xa.mass.base.jsondsl.processor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;

import java.util.List;

/**
 * 生成处理器接口
 * <p>
 * 负责根据 DSL 定义生成指定类型的对象列表
 * </p>
 */
public interface GenerateProcessor extends JsonDslProcessor {
    
    /**
     * 生成对象列表
     * 
     * @param definition DSL 定义
     * @param context 处理上下文
     * @param targetType 目标类型
     * @return 生成的对象列表
     */
    <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType);
    
    @Override
    default boolean supports(JsonDslDefinition.DslType type) {
        return JsonDslDefinition.DslType.GENERATE.equals(type);
    }
    
    @Override
    default String getName() {
        return "GenerateProcessor";
    }
    
    @Override
    default int getPriority() {
        return 100; // 生成处理器优先级
    }
} 