package com.xa.mass.base.jsondsl.parser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.Map;

/**
 * JSON-DSL 解析器
 * <p>
 * 负责解析标准化的 DSL 结构，支持向后兼容和扩展
 * </p>
 */
public class JsonDslParser {

    private static final Gson gson = GsonConfig.buildGson();

    /**
     * 解析 JSON 字符串为标准 DSL 定义
     *
     * @param jsonDsl JSON 字符串
     * @return 标准化的 DSL 定义
     */
    public static JsonDslDefinition parse(String jsonDsl) {
        try {
            JsonObject root = JsonParser.parseString(jsonDsl).getAsJsonObject();
            // 只支持标准 DSL 结构
            return parseStandardDsl(root);
        } catch (Exception e) {
            throw new JsonDslException("解析 DSL 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析标准化 DSL 结构
     */
    private static JsonDslDefinition parseStandardDsl(JsonObject root) {
        JsonDslDefinition definition = new JsonDslDefinition();

        // 解析核心字段（支持下划线和驼峰命名）
        definition.setUniqueId(getString(root, "unique_id", getString(root, "uniqueId")));
        definition.setType(JsonDslDefinition.DslType.fromCode(getString(root, "type")));
        definition.setPriority(getInteger(root, "priority", 1));
        definition.setDescription(getString(root, "desc", getString(root, "description")));
        definition.setVersion(getString(root, "version", "1.0"));

        // 解析时间戳（支持下划线和驼峰命名）
        Long createTime = getLong(root, "create_time");
        if (createTime == null) {
            createTime = getLong(root, "createTime");
        }
        if (createTime != null) {
            definition.setCreateTime(createTime);
        }

        Long updateTime = getLong(root, "update_time");
        if (updateTime == null) {
            updateTime = getLong(root, "updateTime");
        }
        if (updateTime != null) {
            definition.setUpdateTime(updateTime);
        }

        // 解析上下文配置
        if (root.has("context")) {
            definition.setContext(parseContext(root.getAsJsonObject("context")));
        }

        // 解析字段 DSL
        if (root.has("fieldDsl")) {
            definition.setFieldDsl(gson.fromJson(root.get("fieldDsl"), Map.class));
        }

        // 解析组合 DSL
        if (root.has("combine_dsl")) {
            definition.setCombineDsl(gson.fromJson(root.get("combine_dsl"), Map.class));
        }

        // 解析扩展配置
        if (root.has("extensions")) {
            definition.setExtensions(gson.fromJson(root.get("extensions"), Map.class));
        }

        // 解析元数据
        if (root.has("tags")) {
            definition.setTags(gson.fromJson(root.get("tags"), String[].class));
        }
        definition.setAuthor(getString(root, "author"));
        definition.setEnabled(getBoolean(root, "enabled", true));
        definition.setCacheable(getBoolean(root, "cacheable", false));
        definition.setCacheExpireSeconds(getInteger(root, "cache_expire_seconds", 300));

        // 验证 DSL 定义
        definition.validate();

        return definition;
    }

    /**
     * 解析上下文配置
     */
    private static JsonDslContext parseContext(JsonObject contextObj) {
        JsonDslContext context = new JsonDslContext();

        // 支持大写和小写
        if (contextObj.has("MODEL")) {
            context.setModel(contextObj.get("MODEL").getAsString());
        } else if (contextObj.has("model")) {
            context.setModel(contextObj.get("model").getAsString());
        }
        if (contextObj.has("COUNT")) {
            context.setCount(contextObj.get("COUNT").getAsInt());
        } else if (contextObj.has("count")) {
            context.setCount(contextObj.get("count").getAsInt());
        }
        if (contextObj.has("TYPE")) {
            context.setType(contextObj.get("TYPE").getAsString());
        } else if (contextObj.has("type")) {
            context.setType(contextObj.get("type").getAsString());
        }
        if (contextObj.has("scope_name")) {
            context.setScopeName(contextObj.get("scope_name").getAsString());
        } else if (contextObj.has("scopeName")) {
            context.setScopeName(contextObj.get("scopeName").getAsString());
        }
        if (contextObj.has("parent_scope")) {
            context.setParentScope(contextObj.get("parent_scope").getAsString());
        } else if (contextObj.has("parentScope")) {
            context.setParentScope(contextObj.get("parentScope").getAsString());
        }
        if (contextObj.has("parameters")) {
            context.setParameters(gson.fromJson(contextObj.get("parameters"), Map.class));
        }
        context.setDebug(getBoolean(contextObj, "debug", false));
        context.setStrict(getBoolean(contextObj, "strict", false));

        context.validate();
        return context;
    }

    /**
     * 将标准 DSL 定义转换为 JSON 字符串
     */
    public static String toJson(JsonDslDefinition definition) {
        return gson.toJson(definition);
    }

    // ==================== 工具方法 ====================

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : null;
    }

    private static String getString(JsonObject obj, String key, String defaultValue) {
        return obj.has(key) ? obj.get(key).getAsString() : defaultValue;
    }

    private static Integer getInteger(JsonObject obj, String key, Integer defaultValue) {
        return obj.has(key) ? obj.get(key).getAsInt() : defaultValue;
    }

    private static Long getLong(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsLong() : null;
    }

    private static Long getLong(JsonObject obj, String key, Long defaultValue) {
        return obj.has(key) ? obj.get(key).getAsLong() : defaultValue;
    }

    private static Boolean getBoolean(JsonObject obj, String key, Boolean defaultValue) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : defaultValue;
    }
} 