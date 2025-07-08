package com.xa.mass.base.jsondsl.filter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DSL 过滤器工厂
 * <p>
 * 用于创建和管理不同类型的过滤器
 * </p>
 */
public class DslFilterFactory {
    
    private static final Gson GSON = GsonConfig.buildGson();
    private static final Map<String, DslFilter<?, ?>> FILTER_REGISTRY = new ConcurrentHashMap<>();
    
    /**
     * 创建 JSON-DSL 过滤器
     * 
     * @param name 过滤器名称
     * @param description 过滤器描述
     * @param filterConfigJson 过滤器配置 JSON 字符串
     * @return JSON-DSL 过滤器实例
     */
    public static <T> JsonDslFilter<T> createJsonDslFilter(String name, String description, String filterConfigJson) {
        try {
            JsonObject config = GSON.fromJson(filterConfigJson, JsonObject.class);
            return new JsonDslFilter<>(name, description, config);
        } catch (Exception e) {
            throw new JsonDslException("创建 JSON-DSL 过滤器失败: " + name, e);
        }
    }
    
    /**
     * 创建 JSON-DSL 过滤器
     * 
     * @param name 过滤器名称
     * @param description 过滤器描述
     * @param filterConfig 过滤器配置对象
     * @return JSON-DSL 过滤器实例
     */
    public static <T> JsonDslFilter<T> createJsonDslFilter(String name, String description, JsonObject filterConfig) {
        return new JsonDslFilter<>(name, description, filterConfig);
    }
    
    /**
     * 注册过滤器
     * 
     * @param name 过滤器名称
     * @param filter 过滤器实例
     */
    public static void registerFilter(String name, DslFilter<?, ?> filter) {
        FILTER_REGISTRY.put(name, filter);
    }
    
    /**
     * 获取已注册的过滤器
     * 
     * @param name 过滤器名称
     * @return 过滤器实例，如果不存在则返回 null
     */
    @SuppressWarnings("unchecked")
    public static <T, R> DslFilter<T, R> getFilter(String name) {
        return (DslFilter<T, R>) FILTER_REGISTRY.get(name);
    }
    
    /**
     * 检查过滤器是否存在
     * 
     * @param name 过滤器名称
     * @return 是否存在
     */
    public static boolean hasFilter(String name) {
        return FILTER_REGISTRY.containsKey(name);
    }
    
    /**
     * 移除过滤器
     * 
     * @param name 过滤器名称
     * @return 被移除的过滤器实例
     */
    public static DslFilter<?, ?> removeFilter(String name) {
        return FILTER_REGISTRY.remove(name);
    }
    
    /**
     * 获取所有已注册的过滤器名称
     * 
     * @return 过滤器名称集合
     */
    public static java.util.Set<String> getFilterNames() {
        return FILTER_REGISTRY.keySet();
    }
    
    /**
     * 清空所有过滤器
     */
    public static void clearFilters() {
        FILTER_REGISTRY.clear();
    }
    
    /**
     * 创建简单的条件过滤器
     * 
     * @param name 过滤器名称
     * @param field 字段名
     * @param operator 操作符 (eq, ne, gt, gte, lt, lte, in, notIn)
     * @param value 比较值
     * @return JSON-DSL 过滤器实例
     */
    public static <T> JsonDslFilter<T> createSimpleFilter(String name, String field, String operator, Object value) {
        JsonObject fieldDsl = new JsonObject();
        JsonObject fieldRule = new JsonObject();
        fieldRule.add("$" + operator, GSON.toJsonTree(value));
        fieldDsl.add(field, fieldRule);
        JsonObject config = new JsonObject();
        config.add("fieldDsl", fieldDsl);
        return new JsonDslFilter<>(name, "简单条件过滤器: " + field + " $" + operator + " " + value, config);
    }
    
    /**
     * 创建范围过滤器
     * 
     * @param name 过滤器名称
     * @param field 字段名
     * @param min 最小值
     * @param max 最大值
     * @return JSON-DSL 过滤器实例
     */
    public static <T> JsonDslFilter<T> createRangeFilter(String name, String field, Number min, Number max) {
        JsonObject fieldDsl = new JsonObject();
        JsonObject fieldRule = new JsonObject();
        if (min != null) {
            fieldRule.add("$gte", GSON.toJsonTree(min));
        }
        if (max != null) {
            fieldRule.add("$lte", GSON.toJsonTree(max));
        }
        fieldDsl.add(field, fieldRule);
        JsonObject config = new JsonObject();
        config.add("fieldDsl", fieldDsl);
        return new JsonDslFilter<>(name, "范围过滤器: " + field + " in [" + min + ", " + max + "]", config);
    }
    
    /**
     * 创建表达式过滤器
     * 
     * @param name 过滤器名称
     * @param expression 表达式字符串
     * @return JSON-DSL 过滤器实例
     */
    public static <T> JsonDslFilter<T> createExpressionFilter(String name, String expression) {
        JsonObject fieldDsl = new JsonObject();
        fieldDsl.addProperty("$EXPR", expression);
        JsonObject config = new JsonObject();
        config.add("fieldDsl", fieldDsl);
        return new JsonDslFilter<>(name, "表达式过滤器: " + expression, config);
    }
} 