package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.builtin.GsonConfig;
import com.xa.mass.base.jsondsl.generate.DslObjectBuilder;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.builtin.DslContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.xa.mass.base.jsondsl.generate.DslObjectBuilder.mockFieldValue;

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
        
        // 获取生成数量
        int count = definition.getContext() != null && definition.getContext().getCount() != null 
            ? definition.getContext().getCount() : 1;
        
        // 获取模型类名
        String modelName = definition.getContext() != null ? definition.getContext().getModel() : null;
        if (modelName == null) {
            throw new IllegalArgumentException("generate 类型 DSL 必须指定 context.model");
        }
        
        // 直接使用 DslObjectBuilder 生成数据
        List<T> result = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            DslContext dslContext = new DslContext();
            dslContext.setScopeName(modelName);
            dslContext.setVariable("&" + modelName + ".index", i);
            
            // 构造 JsonObject
            com.google.gson.JsonObject dsl = new com.google.gson.JsonObject();
            dsl.addProperty("MODEL", modelName);
            if (definition.getFieldDsl() != null) {
                dsl.add("FIELDS", GsonConfig.buildGson().toJsonTree(definition.getFieldDsl()));
            }
            Map<String,Object> resultMap = new HashMap<String, Object>();
            if(Map.class.isAssignableFrom(targetType)){
                for (Map.Entry<String, Object> entry : definition.getFieldDsl().entrySet()) {
                    Object value = DslObjectBuilder.mockFieldValue( entry.getValue(), dslContext);
                    if(value!=null){
                        resultMap.put(entry.getKey(), value);
                    }

                }
                result.add((T)resultMap);

            }else {
                T obj = DslObjectBuilder.mockFromDsl(dsl, dslContext, targetType);
                result.add(obj);
            }


        }
        
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