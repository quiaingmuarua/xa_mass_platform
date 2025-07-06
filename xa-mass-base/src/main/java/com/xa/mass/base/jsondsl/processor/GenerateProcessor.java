package com.xa.mass.base.jsondsl.processor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.parser.JsonDslParser;

import java.util.List;

/**
 * 生成类型 DSL 处理器
 * <p>
 * 负责处理 generate 类型的 DSL，生成对象实例
 * </p>
 */
public class GenerateProcessor implements JsonDslProcessor {
    
    @Override
    public Object process(JsonDslDefinition definition, ProcessingContext context) {
        // 参数验证
        if (definition == null) {
            throw new IllegalArgumentException("Definition cannot be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        
        if (context.isDebug()) {
            System.out.println("[GenerateProcessor] 开始处理 DSL: " + definition.getUniqueId());
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
        
        // 尝试解析为指定类型
        try {
            Class<?> modelClass = Class.forName(modelName);
            List<?> result = com.xa.mass.base.jsondsl.JsonDslEngine.generateList(legacyFormat, modelClass);
            
            if (context.isDebug()) {
                System.out.println("[GenerateProcessor] 生成完成，数量: " + result.size());
            }
            
            return result;
        } catch (ClassNotFoundException e) {
            // 如果无法解析为指定类型，返回 Object 列表
            List<Object> result = com.xa.mass.base.jsondsl.JsonDslEngine.generateList(legacyFormat);
            
            if (context.isDebug()) {
                System.out.println("[GenerateProcessor] 生成完成（Object 类型），数量: " + result.size());
            }
            
            return result;
        }
    }
    
    @Override
    public boolean supports(JsonDslDefinition.DslType type) {
        return JsonDslDefinition.DslType.GENERATE.equals(type);
    }
    
    @Override
    public String getName() {
        return "GenerateProcessor";
    }
    
    @Override
    public int getPriority() {
        return 100; // 生成处理器优先级
    }
} 