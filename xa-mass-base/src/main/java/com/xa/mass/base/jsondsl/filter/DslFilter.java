package com.xa.mass.base.jsondsl.filter;

import java.util.List;

/**
 * DSL 过滤器接口
 * <p>
 * 用于对 DSL 生成的对象进行过滤和转换
 * </p>
 * 
 * @param <T> 输入对象类型
 * @param <R> 输出对象类型
 */
public interface DslFilter<T, R> {
    
    /**
     * 过滤单个对象
     * 
     * @param input 输入对象
     * @return 过滤后的对象，如果被过滤掉则返回 null
     */
    R filter(T input);
    
    /**
     * 批量过滤对象列表
     * 
     * @param inputs 输入对象列表
     * @return 过滤后的对象列表
     */
    default List<R> filterList(List<T> inputs) {
        if (inputs == null) return null;
        return inputs.stream()
                .map(this::filter)
                .filter(result -> result != null)
                .toList();
    }
    
    /**
     * 获取过滤器名称
     * 
     * @return 过滤器名称
     */
    String getName();
    
    /**
     * 获取过滤器描述
     * 
     * @return 过滤器描述
     */
    String getDescription();
} 