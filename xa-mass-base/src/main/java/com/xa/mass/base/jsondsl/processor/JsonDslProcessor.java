package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.List;
import java.util.Map;

/**
 * JSON-DSL 处理器接口
 * <p>
 * 定义 DSL 处理的标准接口，支持不同类型 DSL 的处理逻辑
 * </p>
 */
public interface JsonDslProcessor {
    
    /**
     * 处理 DSL 定义
     * 
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 处理结果
     */
    Object process(JsonDslDefinition definition, ProcessingContext context);
    
    /**
     * 检查是否支持指定的 DSL 类型
     * 
     * @param type DSL 类型
     * @return 是否支持
     */
    boolean supports(JsonDslDefinition.DslType type);
    
    /**
     * 获取处理器名称
     * 
     * @return 处理器名称
     */
    String getName();
    
    /**
     * 获取处理器优先级（用于链式调用时的排序）
     * 
     * @return 优先级，数值越大优先级越高
     */
    int getPriority();
} 