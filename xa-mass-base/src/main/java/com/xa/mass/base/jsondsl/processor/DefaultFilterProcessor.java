package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.filter.DslFilterFactory;
import com.xa.mass.base.jsondsl.filter.JsonDslFilter;

import java.util.List;

/**
 * 默认过滤处理器实现
 * <p>
 * 负责根据 DSL 定义过滤对象列表
 * </p>
 */
class DefaultFilterProcessor implements FilterProcessor {
    
    @Override
    public <T> List<T> filter(List<T> input, JsonDslDefinition definition, ProcessingContext context) {
        // 参数验证
        if (input == null) {
            throw new IllegalArgumentException("Input list cannot be null");
        }
        if (definition == null) {
            throw new IllegalArgumentException("Definition cannot be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        
        if (context.isDebug()) {
            System.out.println("[DefaultFilterProcessor] 开始处理 DSL: " + definition.getUniqueId());
        }
        
        // 验证 DSL 定义
        definition.validate();
        
        // 转换为传统格式
        String filterConfig = JsonDslParser.toLegacyFormat(definition);
        
        // 应用过滤器
        JsonDslFilter<Object> filter = DslFilterFactory.createJsonDslFilter(
            "autoFilter", "自动生成的过滤器", filterConfig
        );
        @SuppressWarnings("unchecked")
        List<T> result = (List<T>) filter.filterList((List<Object>) input);
        
        if (context.isDebug()) {
            System.out.println("[DefaultFilterProcessor] 过滤完成，原始数量: " + input.size() + ", 过滤后数量: " + result.size());
        }
        
        return result;
    }
    
    @Override
    public String getName() {
        return "DefaultFilterProcessor";
    }
    
    @Override
    public int getPriority() {
        return 200; // 过滤处理器优先级
    }
} 