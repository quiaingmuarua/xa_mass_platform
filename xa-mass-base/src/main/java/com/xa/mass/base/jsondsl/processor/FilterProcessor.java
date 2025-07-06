package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;

import java.util.List;

/**
 * 过滤类型 DSL 处理器
 * <p>
 * 负责处理 filter 类型的 DSL，过滤对象列表
 * </p>
 */
public class FilterProcessor implements JsonDslProcessor {
    
    @Override
    public Object process(JsonDslDefinition definition, ProcessingContext context) {
        if (context.isDebug()) {
            System.out.println("[FilterProcessor] 开始处理 DSL: " + definition.getUniqueId());
        }
        
        // 验证 DSL 定义
        definition.validate();
        
        // 获取要过滤的对象列表
        List<Object> objects = (List<Object>) context.getParameter("objects");
        if (objects == null) {
            throw new IllegalArgumentException("filter 类型 DSL 需要在 context 中提供 'objects' 参数");
        }
        
        // 转换为传统格式
        String filterConfig = JsonDslParser.toLegacyFormat(definition);
        
        // 应用过滤器
        List<Object> result = com.xa.mass.base.jsondsl.JsonDslEngine.filter(objects, filterConfig);
        
        if (context.isDebug()) {
            System.out.println("[FilterProcessor] 过滤完成，原始数量: " + objects.size() + ", 过滤后数量: " + result.size());
        }
        
        return result;
    }
    
    @Override
    public boolean supports(JsonDslDefinition.DslType type) {
        return JsonDslDefinition.DslType.FILTER.equals(type);
    }
    
    @Override
    public String getName() {
        return "FilterProcessor";
    }
    
    @Override
    public int getPriority() {
        return 200; // 过滤处理器优先级
    }
} 