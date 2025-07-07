package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;

import java.util.List;

/**
 * 过滤处理器接口
 * <p>
 * 负责根据 DSL 定义过滤对象列表
 * </p>
 */
public interface FilterProcessor extends JsonDslProcessor {
    
    /**
     * 过滤对象列表，返回结构化结果
     * @param input 输入对象列表
     * @param definition DSL 定义
     * @param context 处理上下文，可通过参数控制是否包含失败明细、统计等
     * @return 过滤结果
     */
    <T> FilterResult<T> filter(List<T> input, JsonDslDefinition definition, ProcessingContext context);
    
    @Override
    default boolean supports(JsonDslDefinition.DslType type) {
        return JsonDslDefinition.DslType.FILTER.equals(type);
    }
    
    @Override
    default String getName() {
        return "FilterProcessor";
    }
    
    @Override
    default int getPriority() {
        return 200; // 过滤处理器优先级
    }
} 