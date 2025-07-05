package com.xa.mass.base.jsondsl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.DslKeyword;
import com.xa.mass.base.jsondsl.builtin.DslObjectBuilder;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;

import java.util.*;

/**
 * 通用 JSON-DSL mock 主入口。
 * 支持通过 DSL 批量生成任意对象，支持 MODEL、FIELDS、COUNT、TYPE，递归嵌套、内置函数、注册表。
 */
public class JsonDslEngine {
    private static final Gson gson = new Gson();

    /**
     * 生成多模型映射。
     * key: 模型名称, value: 模型对象列表
     * @param jsonDsl JSON-DSL 字符串
     * @return 模型名称到对象列表的映射
     */
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
     * 生成单模型对象列表。
     * @param jsonDsl JSON-DSL 字符串（只处理单一模型）
     * @param <T> 目标类型
     * @return 对象列表
     */
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

    /**
     * 生成单模型对象列表（无类型转换）。
     * @param jsonDsl JSON-DSL 字符串（只处理单一模型）
     * @return 对象列表
     */
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