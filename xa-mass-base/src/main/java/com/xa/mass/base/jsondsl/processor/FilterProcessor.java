package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.List;

/**
 * 过滤处理器接口
 * <p>
 * 负责根据 DSL 定义过滤对象，支持单个对象和列表的智能处理
 * </p>
 */
public interface FilterProcessor extends JsonDslProcessor {
    
    /**
     * 智能过滤 - 自动识别输入类型并返回统一的结果格式
     * 
     * @param data 要过滤的数据（单个对象或列表）
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 过滤结果
     */
    <T> FilterResult<T> filter(T data, JsonDslDefinition definition, ProcessingContext context);
} 