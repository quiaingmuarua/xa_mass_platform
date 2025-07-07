package com.xa.mass.base.jsondsl.filter;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.eval.DslExprExecutor;
import com.xa.mass.base.jsondsl.util.GsonConfig;
import com.xa.mass.base.jsondsl.util.FieldRuleEvaluator;

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
            
            // 直接遍历 fieldDsl 进行字段条件判断
            if (!evaluateFieldDsl(context)) {
                return null; // 被过滤掉
            }
            
            // 应用字段过滤
            return applyFieldFilters(input, context);
            
        } catch (Exception e) {
            throw new JsonDslException("过滤对象失败: " + input, e);
        }
    }
    
    /**
     * 遍历 fieldDsl 进行字段条件判断
     */
    private boolean evaluateFieldDsl(Map<String, Object> context) {
        JsonElement fieldDslElem = filterConfig.get("fieldDsl");
        if (fieldDslElem == null || !fieldDslElem.isJsonObject()) return true;
        JsonObject fieldDsl = fieldDslElem.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : fieldDsl.entrySet()) {
            String field = entry.getKey();
            JsonElement cond = entry.getValue();
            
            // 处理 $EXPR 特殊字段
            if ("$EXPR".equals(field)) {
                try {
                    String expr = cond.getAsString();
                    Object result = DslExprExecutor.execute(expr, context);
                    boolean ok = false;
                    if (result instanceof Boolean) ok = (Boolean) result;
                    else if (result instanceof Number) ok = ((Number) result).doubleValue() != 0;
                    else if (result instanceof String) ok = !((String) result).isEmpty();
                    else ok = result != null;
                    if (!ok) {
                        return false;
                    }
                    continue; // 跳过后续处理
                } catch (Exception e) {
                    throw new JsonDslException("执行表达式失败: " + cond.getAsString(), e);
                }
            }
            
            Object fieldValue = context.get(field);
            if (cond.isJsonObject()) {
                Map rule = GSON.fromJson(cond, Map.class);
                if (!FieldRuleEvaluator.evaluate(fieldValue, rule)) {
                    return false;
                }
            } else if (cond.isJsonPrimitive() || cond.isJsonArray()) {
                // 直接等值判断
                if (fieldValue == null || !fieldValue.toString().equals(cond.getAsString())) {
                    return false;
                }
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