package com.xa.mass.base.jsondsl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.DslKeyword;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.filter.DslFilter;
import com.xa.mass.base.jsondsl.filter.DslFilterFactory;
import com.xa.mass.base.jsondsl.filter.JsonDslFilter;
import com.xa.mass.base.jsondsl.generate.DslObjectBuilder;

import java.util.*;

/**
 * 通用 JSON-DSL mock 主入口。
 * 支持通过 DSL 批量生成任意对象，支持 MODEL、FIELDS、COUNT、TYPE，递归嵌套、内置函数、注册表。
 * 提供独立的 generate 和 filter 方法，支持灵活组合使用。
 * 
 * @deprecated 建议使用新的标准化 DSL 结构，通过 {@link com.xa.mass.base.jsondsl.model.JsonDslDefinition} 
 * 和 {@link com.xa.mass.base.jsondsl.parser.JsonDslParser} 进行 DSL 定义和解析。
 * 新标准提供更好的类型安全、元数据管理和扩展性。
 */
@Deprecated(since = "2.0.0", forRemoval = true)
public class JsonDslEngine {
    private static final Gson gson = new Gson();

    // ==================== Generate 方法 ====================

    /**
     * 生成多模型映射。
     * key: 模型名称, value: 模型对象列表
     * @param jsonDsl JSON-DSL 字符串
     * @return 模型名称到对象列表的映射
     * 
     * @deprecated 建议使用新的标准化 DSL 结构，通过 {@link com.xa.mass.base.jsondsl.model.JsonDslDefinition} 
     * 定义 DSL，然后使用 {@link com.xa.mass.base.jsondsl.parser.JsonDslParser} 解析。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public static Map<String, List<Object>> generateMap(String jsonDsl) {
        JsonObject root = JsonParser.parseString(jsonDsl).getAsJsonObject();
        Map<String, List<Object>> result = new HashMap<>();
        
        // 检查是否有多个 MODEL（根级别）
        if (hasMultipleModels(root)) {
            // 多模型情况：遍历每个子对象
            for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                String key = entry.getKey();
                com.google.gson.JsonElement value = entry.getValue();
                
                if (value.isJsonObject()) {
                    JsonObject modelDsl = value.getAsJsonObject();
                    if (modelDsl.has(DslKeyword.MODEL.name())) {
                        // 这是一个模型定义，调用 generateList 处理
                        List<Object> modelList = generateList(value.toString());
                        result.put(key, modelList);
                    } else {
                        // 普通字段，包装为单元素列表
                        result.put(key, Collections.singletonList(gson.fromJson(value, Object.class)));
                    }
                } else {
                    // 普通值字段，包装为单元素列表
                    result.put(key, Collections.singletonList(gson.fromJson(value, Object.class)));
                }
            }
        } else {
            // 单模型情况：包装为单元素映射
            List<Object> modelList = generateList(jsonDsl);
            String modelName = root.has(DslKeyword.MODEL.name()) ? 
                root.get(DslKeyword.MODEL.name()).getAsString() : "default";
            result.put(modelName, modelList);
        }
        
        return result;
    }

    /**
     * 生成单模型对象列表（无类型转换）。
     * @param jsonDsl JSON-DSL 字符串（只处理单一模型）
     * @return 对象列表
     * 
     * @deprecated 建议使用新的标准化 DSL 结构，通过 {@link com.xa.mass.base.jsondsl.model.JsonDslDefinition} 
     * 定义 DSL，然后使用 {@link com.xa.mass.base.jsondsl.parser.JsonDslParser} 解析。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public static List<Object> generateList(String jsonDsl) {
        JsonObject root = JsonParser.parseString(jsonDsl).getAsJsonObject();
        
        // 检查是否有多个 MODEL（根级别）
        if (hasMultipleModels(root)) {
            throw new JsonDslException("generateList 方法只支持单一模型，请使用 generateMap 方法处理多模型");
        }
        
        // 单个模型的情况
        int count = root.has(DslKeyword.COUNT.name()) ? root.get(DslKeyword.COUNT.name()).getAsInt() : 1;
        String modelName = root.has(DslKeyword.MODEL.name()) ? root.get(DslKeyword.MODEL.name()).getAsString() : "Root";
        
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            DslContext context = new DslContext();
            context.setScopeName(modelName);
            context.setVariable("&" + modelName + ".index", i);
            result.add(DslObjectBuilder.mockFromDsl(root, context));
        }
        
        return result;
    }

    /**
     * 生成单模型对象列表。
     * @param jsonDsl JSON-DSL 字符串（只处理单一模型）
     * @param <T> 目标类型
     * @return 对象列表
     * 
     * @deprecated 建议使用新的标准化 DSL 结构，通过 {@link com.xa.mass.base.jsondsl.model.JsonDslDefinition} 
     * 定义 DSL，然后使用 {@link com.xa.mass.base.jsondsl.parser.JsonDslParser} 解析。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    @SuppressWarnings("unchecked")
    public static <T> List<T> generateList(String jsonDsl, Class<T> targetType) {
        List<Object> result = generateList(jsonDsl);
        List<T> typedResult = new ArrayList<>();
        
        for (Object obj : result) {
            if (targetType.isInstance(obj)) {
                typedResult.add((T) obj);
            } else {
                throw new JsonDslException("对象类型不匹配: 期望 " + targetType.getName() +
                    ", 实际 " + (obj != null ? obj.getClass().getName() : "null"));
            }
        }
        
        return typedResult;
    }

    // ==================== Filter 方法 ====================

    /**
     * 过滤对象列表（使用 JSON 配置）
     * @param objects 要过滤的对象列表
     * @param filterConfig 过滤器配置 JSON 字符串
     * @return 过滤后的对象列表
     * 
     * @deprecated 建议使用新的标准化 DSL 结构，通过 {@link com.xa.mass.base.jsondsl.model.JsonDslDefinition} 
     * 定义 filter 类型的 DSL，然后使用 {@link com.xa.mass.base.jsondsl.parser.JsonDslParser} 解析。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public static List<Object> filter(List<Object> objects, String filterConfig) {
        JsonDslFilter<Object> filter = DslFilterFactory.createJsonDslFilter(
            "autoFilter", "自动生成的过滤器", filterConfig
        );
        return filter.filterList(objects);
    }

    /**
     * 过滤对象列表（使用已注册的过滤器）
     * @param objects 要过滤的对象列表
     * @param filterName 已注册的过滤器名称
     * @return 过滤后的对象列表
     * 
     * @deprecated 建议使用新的标准化 DSL 结构，通过 {@link com.xa.mass.base.jsondsl.model.JsonDslDefinition} 
     * 定义 filter 类型的 DSL，然后使用 {@link com.xa.mass.base.jsondsl.parser.JsonDslParser} 解析。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public static List<Object> filter(List<Object> objects, String filterName, boolean isNamedFilter) {
        if (!isNamedFilter) {
            return filter(objects, filterName); // 作为 JSON 配置处理
        }
        DslFilter<Object, Object> filter = DslFilterFactory.getFilter(filterName);
        if (filter == null) {
            throw new JsonDslException("未找到已注册的过滤器: " + filterName);
        }
        return filter.filterList(objects);
    }

    /**
     * 过滤多模型映射（使用 JSON 配置）
     * @param modelMap 要过滤的模型映射
     * @param filterConfig 过滤器配置 JSON 字符串
     * @return 过滤后的模型映射
     */
    public static Map<String, List<Object>> filter(Map<String, List<Object>> modelMap, String filterConfig) {
        JsonDslFilter<Object> filter = DslFilterFactory.createJsonDslFilter(
            "autoFilter", "自动生成的过滤器", filterConfig
        );
        Map<String, List<Object>> filteredResult = new java.util.HashMap<>();
        for (Map.Entry<String, List<Object>> entry : modelMap.entrySet()) {
            filteredResult.put(entry.getKey(), filter.filterList(entry.getValue()));
        }
        return filteredResult;
    }

    /**
     * 过滤多模型映射（使用已注册的过滤器）
     * @param modelMap 要过滤的模型映射
     * @param filterName 已注册的过滤器名称
     * @return 过滤后的模型映射
     */
    public static Map<String, List<Object>> filter(Map<String, List<Object>> modelMap, String filterName, boolean isNamedFilter) {
        if (!isNamedFilter) {
            return filter(modelMap, filterName); // 作为 JSON 配置处理
        }
        DslFilter<Object, Object> filter = DslFilterFactory.getFilter(filterName);
        if (filter == null) {
            throw new JsonDslException("未找到已注册的过滤器: " + filterName);
        }
        Map<String, List<Object>> filteredResult = new java.util.HashMap<>();
        for (Map.Entry<String, List<Object>> entry : modelMap.entrySet()) {
            filteredResult.put(entry.getKey(), filter.filterList(entry.getValue()));
        }
        return filteredResult;
    }

    /**
     * 便捷过滤方法：直接使用字段、操作符和值
     * @param objects 要过滤的对象列表
     * @param field 字段名
     * @param operator 操作符
     * @param value 比较值
     * @return 过滤后的对象列表
     * 
     * @deprecated 建议使用新的标准化 DSL 结构，通过 {@link com.xa.mass.base.jsondsl.model.JsonDslDefinition} 
     * 定义 filter 类型的 DSL，然后使用 {@link com.xa.mass.base.jsondsl.parser.JsonDslParser} 解析。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public static List<Object> filter(List<Object> objects, String field, String operator, Object value) {
        JsonDslFilter<Object> filter = DslFilterFactory.createSimpleFilter("autoFilter", field, operator, value);
        return filter.filterList(objects);
    }

    /**
     * 便捷过滤方法：直接使用字段、操作符和值（多模型）
     * @param modelMap 要过滤的模型映射
     * @param field 字段名
     * @param operator 操作符
     * @param value 比较值
     * @return 过滤后的模型映射
     */
    public static Map<String, List<Object>> filter(Map<String, List<Object>> modelMap, String field, String operator, Object value) {
        JsonDslFilter<Object> filter = DslFilterFactory.createSimpleFilter("autoFilter", field, operator, value);
        Map<String, List<Object>> filteredResult = new java.util.HashMap<>();
        for (Map.Entry<String, List<Object>> entry : modelMap.entrySet()) {
            filteredResult.put(entry.getKey(), filter.filterList(entry.getValue()));
        }
        return filteredResult;
    }

    // ==================== 便捷方法 ====================

    /**
     * 创建简单过滤器的 JSON 配置
     * @param field 字段名
     * @param operator 操作符
     * @param value 比较值
     * @return 过滤器配置 JSON 字符串
     */
    public static String createFilterConfig(String field, String operator, Object value) {
        return String.format("{\"conditions\":{\"%s\":{\"%s\":%s}}}",
            field, operator, value instanceof String ? "\"" + value + "\"" : value);
    }

    /**
     * 创建范围过滤器的 JSON 配置
     * @param field 字段名
     * @param min 最小值
     * @param max 最大值
     * @return 过滤器配置 JSON 字符串
     */
    public static String createRangeFilterConfig(String field, Number min, Number max) {
        return String.format("{\"conditions\":{\"%s\":{\"gte\":%s,\"lte\":%s}}}",
            field, min, max);
    }

    /**
     * 创建表达式过滤器的 JSON 配置
     * @param expression 表达式字符串
     * @return 过滤器配置 JSON 字符串
     * 
     * @deprecated 建议使用新的标准化 DSL 结构，通过 {@link com.xa.mass.base.jsondsl.model.JsonDslDefinition} 
     * 定义 filter 类型的 DSL，然后使用 {@link com.xa.mass.base.jsondsl.parser.JsonDslParser} 解析。
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public static String createExpressionFilterConfig(String expression) {
        return String.format("{\"expression\":\"%s\"}", expression);
    }

    /**
     * 检查根 DSL 是否包含多个模型定义
     */
    private static boolean hasMultipleModels(JsonObject root) {
        // 检查是否有多个顶级 MODEL 字段
        int modelCount = 0;
        for (String key : root.keySet()) {
            if (key.equals(DslKeyword.MODEL.name())) {
                modelCount++;
            }
        }
        
        // 如果根级别有 MODEL 字段，说明是单个模型
        if (modelCount > 0) {
            return false;
        }
        
        // 检查是否有多个子对象，每个子对象都有自己的 MODEL 字段
        int subModelCount = 0;
        for (String key : root.keySet()) {
            com.google.gson.JsonElement value = root.get(key);
            if (value.isJsonObject()) {
                JsonObject subObj = value.getAsJsonObject();
                if (subObj.has(DslKeyword.MODEL.name())) {
                    subModelCount++;
                }
            }
        }
        
        return subModelCount > 1;
    }
} 