package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.List;

/**
 * 过滤处理器接口
 * <p>
 * 负责根据 DSL 定义过滤对象，明确区分单对象和列表的泛型类型
 * </p>
 */
public interface FilterProcessor extends JsonDslProcessor {

    /**
     * 过滤单个对象
     *
     * @param data 要过滤的单个对象
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 过滤结果
     */
    <T> FilterResult<T> filter(T data, JsonDslDefinition definition, ProcessingContext context);

    /**
     * 过滤对象列表
     *
     * @param dataList 要过滤的对象列表
     * @param definition DSL 定义
     * @param context 处理上下文
     * @return 过滤结果
     */
    <T> FilterResult<T> filterList(List<T> dataList, JsonDslDefinition definition, ProcessingContext context);
} 