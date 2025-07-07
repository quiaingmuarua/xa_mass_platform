package com.xa.mass.base.jsondsl.filter;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.eval.DslExprExecutor;
import com.xa.mass.base.jsondsl.util.GsonConfig;

import java.util.List;
import java.util.Map;

/**
 * JSON-DSL 过滤器实现
 * <p>
 * 支持基于 JSON 配置的过滤规则，可以使用表达式进行条件判断
 * </p>
 */
public class JsonDslFilter<T> implements DslFilter<T, T> {
    
    private static final Gson GSON = GsonConfig.buildGson();
    
    private final String name;
    private final String description;
    private final JsonObject filterConfig;
    public final DslExprExecutor exprExecutor;
    
    public JsonDslFilter(String name, String description, JsonObject filterConfig) {
        this.name = name;
        this.description = description;
        this.filterConfig = filterConfig;
        this.exprExecutor = new DslExprExecutor();
    }
    
    public JsonDslFilter(String name, String description, String filterConfigJson) {
        this(name, description, GSON.fromJson(filterConfigJson, JsonObject.class));
    }
    
    @Override
    public T filter(T input) {
        if (input == null) return null;
        
        try {
            // 将输入对象转换为 Map 以便表达式计算
            Map<String, Object> context = objectToMap(input);
            
            // 检查过滤条件
            if (!evaluateConditions(context)) {
                return null; // 被过滤掉
            }
            
            // 应用字段过滤
            return applyFieldFilters(input, context);
            
        } catch (Exception e) {
            throw new JsonDslException("过滤对象失败: " + input, e);
        }
    }
    
    /**
     * 评估过滤条件
     */
    private boolean evaluateConditions(Map<String, Object> context) throws Exception {
        JsonElement conditions = filterConfig.get("conditions");
        if (conditions == null || conditions.isJsonNull()) {
            return true; // 没有条件，默认通过
        }
        
        if (conditions.isJsonArray()) {
            // 多个条件，全部满足才通过
            for (JsonElement condition : conditions.getAsJsonArray()) {
                if (!evaluateSingleCondition(condition, context)) {
                    return false;
                }
            }
            return true;
        } else {
            // 单个条件
            return evaluateSingleCondition(conditions, context);
        }
    }
    
    /**
     * 评估单个条件
     */
    private boolean evaluateSingleCondition(JsonElement condition, Map<String, Object> context) throws Exception {
        if (condition.isJsonPrimitive()) {
            // 简单表达式
            String expr = condition.getAsString();
            Object result = exprExecutor.execute(expr, context);
            return isTrue(result);
        } else if (condition.isJsonObject()) {
            // 复杂条件对象
            JsonObject condObj = condition.getAsJsonObject();
            
            // 支持 and/or 逻辑
            if (condObj.has("and")) {
                for (JsonElement subCond : condObj.getAsJsonArray("and")) {
                    if (!evaluateSingleCondition(subCond, context)) {
                        return false;
                    }
                }
                return true;
            }
            
            if (condObj.has("or")) {
                for (JsonElement subCond : condObj.getAsJsonArray("or")) {
                    if (evaluateSingleCondition(subCond, context)) {
                        return true;
                    }
                }
                return false;
            }
            
            // 字段条件
            for (Map.Entry<String, JsonElement> entry : condObj.entrySet()) {
                String field = entry.getKey();
                JsonElement value = entry.getValue();
                
                Object fieldValue = context.get(field);
                if (!evaluateFieldCondition(fieldValue, value)) {
                    return false;
                }
            }
            return true;
        }
        
        return true;
    }
    
    /**
     * 评估字段条件
     */
    public boolean evaluateFieldCondition(Object fieldValue, JsonElement condition) {
        if (condition.isJsonPrimitive()) {
            return fieldValue != null && fieldValue.toString().equals(condition.getAsString());
        } else if (condition.isJsonObject()) {
            JsonObject condObj = condition.getAsJsonObject();
            
            // 支持比较操作符
            if (condObj.has("eq")) {
                return fieldValue != null && fieldValue.toString().equals(condObj.get("eq").getAsString());
            }
            if (condObj.has("ne")) {
                return fieldValue == null || !fieldValue.toString().equals(condObj.get("ne").getAsString());
            }
            if (condObj.has("gt")) {
                return compareNumbers(fieldValue, condObj.get("gt").getAsNumber()) > 0;
            }
            if (condObj.has("gte")) {
                return compareNumbers(fieldValue, condObj.get("gte").getAsNumber()) >= 0;
            }
            if (condObj.has("lt")) {
                return compareNumbers(fieldValue, condObj.get("lt").getAsNumber()) < 0;
            }
            if (condObj.has("lte")) {
                return compareNumbers(fieldValue, condObj.get("lte").getAsNumber()) <= 0;
            }
            if (condObj.has("in")) {
                List values = GSON.fromJson(condObj.get("in"), List.class);
                return fieldValue != null && values.contains(fieldValue.toString());
            }
            if (condObj.has("notIn")) {
                List values = GSON.fromJson(condObj.get("notIn"), List.class);
                return fieldValue == null || !values.contains(fieldValue.toString());
            }
        }
        
        return true;
    }
    
    /**
     * 应用字段过滤器
     */
    @SuppressWarnings("unchecked")
    private T applyFieldFilters(T input, Map<String, Object> context) {
        JsonElement fields = filterConfig.get("fields");
        if (fields == null || fields.isJsonNull()) {
            return input; // 没有字段过滤，返回原对象
        }
        
        // 这里可以实现字段过滤逻辑
        // 比如只保留指定字段，或者转换字段值
        // 当前简单返回原对象
        return input;
    }
    
    /**
     * 将对象转换为 Map
     */
    public Map<String, Object> objectToMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        String json = GSON.toJson(obj);
        return GSON.fromJson(json, Map.class);
    }
    
    /**
     * 判断值是否为真
     */
    private boolean isTrue(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        if (value instanceof String) return !((String) value).isEmpty();
        return true;
    }
    
    /**
     * 比较数字
     */
    private int compareNumbers(Object fieldValue, Number conditionValue) {
        if (fieldValue == null) return -1;
        if (fieldValue instanceof Number) {
            return Double.compare(((Number) fieldValue).doubleValue(), conditionValue.doubleValue());
        }
        try {
            double fieldNum = Double.parseDouble(fieldValue.toString());
            return Double.compare(fieldNum, conditionValue.doubleValue());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    /**
     * 获取过滤器配置
     */
    public JsonObject getFilterConfig() {
        return filterConfig;
    }
} 