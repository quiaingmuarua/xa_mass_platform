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
import com.xa.mass.base.jsondsl.util.GsonConfig;

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
    private static final Gson gson = GsonConfig.buildGson();

    // ==================== Generate 方法 ====================

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
        JsonObject root = JsonParser.parseString(jsonDsl).getAsJsonObject();
        
        // 检查是否有多个 MODEL（根级别）
        if (hasMultipleModels(root)) {
            throw new JsonDslException("generateList 方法只支持单一模型");
        }
        
        // 单个模型的情况
        int count = root.has(DslKeyword.COUNT.name()) ? root.get(DslKeyword.COUNT.name()).getAsInt() : 1;
        String modelName = root.has(DslKeyword.MODEL.name()) ? root.get(DslKeyword.MODEL.name()).getAsString() : "Root";
        
        List<T> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            DslContext context = new DslContext();
            context.setScopeName(modelName);
            context.setVariable("&" + modelName + ".index", i);
            Object obj = DslObjectBuilder.mockFromDsl(root, context);
            
            if (targetType.isInstance(obj)) {
                result.add((T) obj);
            } else {
                throw new JsonDslException("对象类型不匹配: 期望 " + targetType.getName() +
                    ", 实际 " + (obj != null ? obj.getClass().getName() : "null"));
            }
        }
        
        return result;
    }

    // ==================== Filter 方法 ====================

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