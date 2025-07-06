package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.List;
import java.util.Map;

/**
 * 转换类型 DSL 处理器
 * <p>
 * 负责处理 transform 类型的 DSL，转换对象格式
 * </p>
 */
public class TransformProcessor implements JsonDslProcessor {
    
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
            System.out.println("[TransformProcessor] 开始处理 DSL: " + definition.getUniqueId());
        }
        
        // 验证 DSL 定义
        definition.validate();
        
        // 获取要转换的对象列表
        List<Object> objects = (List<Object>) context.getParameter("objects");
        if (objects == null) {
            throw new IllegalArgumentException("transform 类型 DSL 需要在 context 中提供 'objects' 参数");
        }
        
        // 执行转换逻辑
        List<Object> result = transformObjects(objects, definition, context);
        
        if (context.isDebug()) {
            System.out.println("[TransformProcessor] 转换完成，数量: " + result.size());
        }
        
        return result;
    }
    
    /**
     * 转换对象列表
     */
    private List<Object> transformObjects(List<Object> objects, JsonDslDefinition definition, ProcessingContext context) {
        // 这里实现具体的转换逻辑
        // 可以根据 fieldDsl 和 combine_dsl 进行字段映射、格式转换等
        
        // 示例：简单的字段映射转换
        return objects.stream()
            .map(obj -> transformObject(obj, definition, context))
            .toList();
    }
    
    /**
     * 转换单个对象
     */
    private Object transformObject(Object obj, JsonDslDefinition definition, ProcessingContext context) {
        // 这里实现具体的对象转换逻辑
        // 可以根据 fieldDsl 进行字段映射
        // 可以根据 combine_dsl 进行复杂转换
        
        // 示例：返回原对象（实际应该根据 DSL 规则进行转换）
        return obj;
    }
    
    @Override
    public boolean supports(JsonDslDefinition.DslType type) {
        return JsonDslDefinition.DslType.TRANSFORM.equals(type);
    }
    
    @Override
    public String getName() {
        return "TransformProcessor";
    }
    
    @Override
    public int getPriority() {
        return 300; // 转换处理器优先级
    }
} 