package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.List;
import java.util.Map;

/**
 * 校验类型 DSL 处理器
 * <p>
 * 负责处理 validate 类型的 DSL，校验对象有效性
 * </p>
 */
public class ValidateProcessor implements JsonDslProcessor {
    
    @Override
    public Object process(JsonDslDefinition definition, ProcessingContext context) {
        if (context.isDebug()) {
            System.out.println("[ValidateProcessor] 开始处理 DSL: " + definition.getUniqueId());
        }
        
        // 验证 DSL 定义
        definition.validate();
        
        // 获取要校验的对象列表
        List<Object> objects = (List<Object>) context.getParameter("objects");
        if (objects == null) {
            throw new IllegalArgumentException("validate 类型 DSL 需要在 context 中提供 'objects' 参数");
        }
        
        // 执行校验逻辑
        ValidationResult result = validateObjects(objects, definition, context);
        
        if (context.isDebug()) {
            System.out.println("[ValidateProcessor] 校验完成，总数: " + objects.size() + 
                ", 通过: " + result.getValidCount() + ", 失败: " + result.getInvalidCount());
        }
        
        return result;
    }
    
    /**
     * 校验对象列表
     */
    private ValidationResult validateObjects(List<Object> objects, JsonDslDefinition definition, ProcessingContext context) {
        ValidationResult result = new ValidationResult();
        
        for (Object obj : objects) {
            ValidationResult itemResult = validateObject(obj, definition, context);
            result.merge(itemResult);
        }
        
        return result;
    }
    
    /**
     * 校验单个对象
     */
    private ValidationResult validateObject(Object obj, JsonDslDefinition definition, ProcessingContext context) {
        ValidationResult result = new ValidationResult();
        
        // 这里实现具体的校验逻辑
        // 可以根据 fieldDsl 进行字段级校验
        // 可以根据 combine_dsl 进行复杂校验
        
        // 示例：简单校验（实际应该根据 DSL 规则进行校验）
        boolean isValid = true;
        String errorMessage = null;
        
        // 这里应该根据 definition 的规则进行实际校验
        // 暂时返回校验通过
        result.addValidObject(obj);
        
        return result;
    }
    
    @Override
    public boolean supports(JsonDslDefinition.DslType type) {
        return JsonDslDefinition.DslType.VALIDATE.equals(type);
    }
    
    @Override
    public String getName() {
        return "ValidateProcessor";
    }
    
    @Override
    public int getPriority() {
        return 400; // 校验处理器优先级
    }
    
    /**
     * 校验结果
     */
    public static class ValidationResult {
        private final List<Object> validObjects = new java.util.ArrayList<>();
        private final List<Object> invalidObjects = new java.util.ArrayList<>();
        private final List<String> errorMessages = new java.util.ArrayList<>();
        
        public void addValidObject(Object obj) {
            validObjects.add(obj);
        }
        
        public void addInvalidObject(Object obj, String errorMessage) {
            invalidObjects.add(obj);
            errorMessages.add(errorMessage);
        }
        
        public void merge(ValidationResult other) {
            validObjects.addAll(other.validObjects);
            invalidObjects.addAll(other.invalidObjects);
            errorMessages.addAll(other.errorMessages);
        }
        
        public List<Object> getValidObjects() {
            return validObjects;
        }
        
        public List<Object> getInvalidObjects() {
            return invalidObjects;
        }
        
        public List<String> getErrorMessages() {
            return errorMessages;
        }
        
        public int getValidCount() {
            return validObjects.size();
        }
        
        public int getInvalidCount() {
            return invalidObjects.size();
        }
        
        public boolean isAllValid() {
            return invalidObjects.isEmpty();
        }
    }
} 