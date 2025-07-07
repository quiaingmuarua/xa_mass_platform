package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;

import java.util.List;

/**
 * 默认生成处理器实现
 * <p>
 * 负责根据 DSL 定义生成指定类型的对象列表
 * </p>
 */
class DefaultGenerateProcessor implements GenerateProcessor {
    
    @Override
    public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
        // 参数验证
        if (definition == null) {
            throw new JsonDslException("Definition cannot be null");
        }
        if (context == null) {
            throw new JsonDslException("Context cannot be null");
        }
        if (targetType == null) {
            throw new JsonDslException("Target type cannot be null");
        }
        
        if (context.isDebug()) {
            System.out.println("[DefaultGenerateProcessor] 开始处理 DSL: " + definition.getUniqueId());
        }
        
        // 验证 DSL 定义
        definition.validate();
        
        // 转换为传统格式并生成数据
        String legacyFormat = JsonDslParser.toLegacyFormat(definition);
        
        // 获取生成数量
        int count = definition.getContext() != null && definition.getContext().getCount() != null 
            ? definition.getContext().getCount() : 1;
        
        // 获取模型类名
        String modelName = definition.getContext() != null ? definition.getContext().getModel() : null;
        if (modelName == null) {
            throw new IllegalArgumentException("generate 类型 DSL 必须指定 context.model");
        }
        
        // 生成数据
        List<T> result = JsonDslEngine.generateList(legacyFormat, targetType);
        
        if (context.isDebug()) {
            System.out.println("[DefaultGenerateProcessor] 生成完成，数量: " + result.size());
        }
        
        return result;
    }
    
    @Override
    public String getName() {
        return "DefaultGenerateProcessor";
    }
    
    @Override
    public int getPriority() {
        return 100; // 生成处理器优先级
    }
} 