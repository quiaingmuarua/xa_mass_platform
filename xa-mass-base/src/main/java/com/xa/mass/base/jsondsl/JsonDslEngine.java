package com.xa.mass.base.jsondsl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.*;

/**
 * 通用 JSON-DSL mock 主入口。
 * 支持通过 DSL 批量生成任意对象，支持 MODEL、FIELDS、COUNT、TYPE，递归嵌套、内置函数、注册表。
 */
public class JsonDslEngine {
    private static final Gson gson = new Gson();

    // 类型适配和字段缓存已迁移到工具类

    /**
     * 返回类型枚举
     */
    public enum ReturnType {
        /**
         * 自动判断：单个对象返回 Object，多个对象返回 List，多个模型返回 Map
         */
        AUTO,
        
        /**
         * 强制返回单个对象（如果 DSL 定义了多个对象，则返回第一个）
         */
        SINGLE,
        
        /**
         * 强制返回对象列表（如果 DSL 只定义了一个对象，则包装为单元素列表）
         */
        LIST,
        
        /**
         * 强制返回模型映射（如果 DSL 只定义了一个模型，则包装为单元素映射）
         */
        MAP
    }



    /**
     * 根据 JSON-DSL 生成 mock 对象（默认返回列表）。
     * @param jsonDsl JSON-DSL 字符串
     * @return mock 对象列表
     */
    public static List<Object> generate(String jsonDsl) {
        return generate(jsonDsl, ReturnType.LIST);
    }

    /**
     * 根据 JSON-DSL 生成 mock 对象，指定返回类型。
     * @param jsonDsl JSON-DSL 字符串
     * @param returnType 返回类型
     * @return 根据 returnType 返回相应类型的对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T generate(String jsonDsl, ReturnType returnType) {
        JsonObject root = JsonParser.parseString(jsonDsl).getAsJsonObject();
        
        // 检查是否有多个 MODEL（根级别）
        if (hasMultipleModels(root)) {
            Map<String, Object> result = generateMultipleModels(root);
            return (T) convertToReturnType(result, returnType);
        }
        
        // 单个模型的情况
        int count = root.has(DslKeyword.COUNT.name()) ? root.get(DslKeyword.COUNT.name()).getAsInt() : 1;
        String modelName = root.has(DslKeyword.MODEL.name()) ? root.get(DslKeyword.MODEL.name()).getAsString() : "Root";
        
        if (count == 1) {
            // 生成单个对象
            DslContext context = new DslContext();
            context.setScopeName(modelName);
            context.setVariable("&" + modelName + ".index", 0);
            Object singleObject = DslObjectBuilder.mockFromDsl(root, context);
            return (T) convertToReturnType(singleObject, returnType);
        } else {
            // 生成对象列表
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                DslContext context = new DslContext();
                context.setScopeName(modelName);
                context.setVariable("&" + modelName + ".index", i);
                result.add(DslObjectBuilder.mockFromDsl(root, context));
            }
            return (T) convertToReturnType(result, returnType);
        }
    }

    /**
     * 根据 JSON-DSL 生成单个 mock 对象。
     * @param jsonDsl JSON-DSL 字符串
     * @return 单个 mock 对象
     */
    public static Object generateSingle(String jsonDsl) {
        return generate(jsonDsl, ReturnType.SINGLE);
    }

    /**
     * 根据 JSON-DSL 生成 mock 对象列表。
     * @param jsonDsl JSON-DSL 字符串
     * @return mock 对象列表
     */
    public static List<Object> generateList(String jsonDsl) {
        return generate(jsonDsl, ReturnType.LIST);
    }

    /**
     * 根据 JSON-DSL 生成模型映射。
     * @param jsonDsl JSON-DSL 字符串
     * @param modelName 模型名称（当只有一个模型时使用）
     * @return 模型名称到对象的映射
     */
    public static Map<String, Object> generateMap(String jsonDsl, String modelName) {
        Object result = generate(jsonDsl, ReturnType.MAP);
        if (result instanceof Map) {
            return (Map<String, Object>) result;
        } else {
            Map<String, Object> map = new HashMap<>();
            map.put(modelName, result);
            return map;
        }
    }

    /**
     * 带类型转换的生成方法。
     * @param jsonDsl JSON-DSL 字符串
     * @param targetType 目标类型
     * @param <T> 泛型类型
     * @return 转换后的对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T generateTyped(String jsonDsl, Class<T> targetType) {
        if (targetType == List.class) {
            return (T) generateList(jsonDsl);
        } else if (targetType == Map.class) {
            return (T) generateMap(jsonDsl, "result");
        } else {
            Object result = generateSingle(jsonDsl);
            if (targetType.isInstance(result)) {
                return (T) result;
            }
            throw new JsonDslException("无法将结果转换为类型: " + targetType.getName());
        }
    }

    /**
     * 根据 JSON-DSL 生成 mock 对象列表（保持向后兼容）。
     * @param jsonDsl JSON-DSL 字符串
     * @return mock 对象列表
     * @deprecated 建议使用 generate(String, ReturnType.LIST) 方法
     */
    @Deprecated
    public static List<Object> generateListOld(String jsonDsl) {
        return generateList(jsonDsl);
    }

    /**
     * 将结果转换为指定的返回类型
     */
    @SuppressWarnings("unchecked")
    private static <T> T convertToReturnType(Object result, ReturnType returnType) {
        switch (returnType) {
            case AUTO:
                return (T) result;
            case SINGLE:
                if (result instanceof List) {
                    List<?> list = (List<?>) result;
                    return (T) (list.isEmpty() ? null : list.get(0));
                } else if (result instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) result;
                    return (T) (map.isEmpty() ? null : map.values().iterator().next());
                } else {
                    return (T) result;
                }
            case LIST:
                if (result instanceof List) {
                    return (T) result;
                } else {
                    return (T) Collections.singletonList(result);
                }
            case MAP:
                if (result instanceof Map) {
                    return (T) result;
                } else {
                    Map<String, Object> map = new HashMap<>();
                    map.put("result", result);
                    return (T) map;
                }
            default:
                throw new JsonDslException("不支持的返回类型: " + returnType);
        }
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

    /**
     * 生成多个模型的情况
     */
    private static Map<String, Object> generateMultipleModels(JsonObject root) {
        Map<String, Object> result = new HashMap<>();
        
        for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            com.google.gson.JsonElement value = entry.getValue();
            
            if (value.isJsonObject()) {
                JsonObject modelDsl = value.getAsJsonObject();
                if (modelDsl.has(DslKeyword.MODEL.name())) {
                    // 这是一个模型定义
                    Object modelResult = generate(value.toString());
                    result.put(key, modelResult);
                } else {
                    // 普通字段，直接解析
                    result.put(key, gson.fromJson(value, Object.class));
                }
            } else {
                // 普通值字段
                result.put(key, gson.fromJson(value, Object.class));
            }
        }
        
        return result;
    }

    // DSL 解析和对象构建逻辑已迁移到 DslObjectBuilder 工具类
} 