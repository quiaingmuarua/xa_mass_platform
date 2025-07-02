package com.xa.mass.base.mock;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;

/**
 * 通用 JSON-DSL mock 主入口。
 * 支持通过 DSL 批量生成任意对象，支持 MODEL、FIELDS、COUNT、TYPE，递归嵌套、内置函数、注册表。
 */
public class MockTemplateEngine {
    private static final Gson gson = new Gson();

    /**
     * 根据 JSON-DSL 生成 mock 对象列表。
     * @param jsonDsl JSON-DSL 字符串
     * @return mock 对象列表（每个对象为实例）
     */
    public static List<Object> generate(String jsonDsl) {
        JsonObject root = JsonParser.parseString(jsonDsl).getAsJsonObject();
        int count = root.has("COUNT") ? root.get("COUNT").getAsInt() : 1;
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> context = new HashMap<>();
            context.put("i", i);
            result.add(mockFromDsl(root, context));
        }
        return result;
    }

    private static Object mockFromDsl(JsonObject dsl, Map<String, Object> context) {
        // 1. 解析 MODEL
        String modelName = dsl.has("MODEL") ? dsl.get("MODEL").getAsString() : null;
        if (modelName == null) {
            throw new MockTemplateException("DSL 缺少 MODEL 字段");
        }
        Class<?> clazz = resolveModelClass(modelName);
        Object obj;
        try {
            obj = clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new MockTemplateException("无法实例化模型: " + modelName, e);
        }
        // 2. 处理 FIELDS
        Map<String, Object> fields = null;
        if (dsl.has("FIELDS")) {
            Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
            fields = gson.fromJson(dsl.get("FIELDS"), mapType);
        }
        if (fields == null) fields = Collections.emptyMap();
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = null;
            if (fields.containsKey(field.getName())) {
                value = mockFieldValue(field, fields.get(field.getName()), context);
            } // else value 保持 null
            try {
                field.set(obj, value);
            } catch (Exception e) {
                throw new MockTemplateException("无法设置字段: " + field.getName() + " in " + clazz.getName(), e);
            }
        }
        return obj;
    }

    private static Object mockFieldValue(Field field, Object rule, Map<String, Object> context) {
        // 1. 集合类型
        if (rule instanceof Map) {
            Map<?, ?> ruleMap = (Map<?, ?>) rule;
            if (ruleMap.containsKey("TYPE")) {
                String type = String.valueOf(ruleMap.get("TYPE")).toUpperCase();
                int count = ruleMap.containsKey("COUNT") ? ((Number) ruleMap.get("COUNT")).intValue() : 1;
                String modelName = (String) ruleMap.get("MODEL");
                Map<String, Object> subFields = (Map<String, Object>) ruleMap.get("FIELDS");
                List<Object> list = new ArrayList<>();
                for (int j = 0; j < count; j++) {
                    Map<String, Object> subContext = new HashMap<>(context);
                    subContext.put("j", j);
                    JsonObject subDsl = new JsonObject();
                    subDsl.addProperty("MODEL", modelName);
                    if (subFields != null) {
                        subDsl.add("FIELDS", gson.toJsonTree(subFields));
                    }
                    list.add(mockFromDsl(subDsl, subContext));
                }
                if (type.equals("LIST")) return list;
                if (type.equals("SET")) return new HashSet<>(list);
                throw new MockTemplateException("不支持的集合类型: " + type);
            }
            // 2. 嵌套对象
            if (ruleMap.containsKey("MODEL")) {
                JsonObject subDsl = new JsonObject();
                subDsl.addProperty("MODEL", (String) ruleMap.get("MODEL"));
                if (ruleMap.containsKey("FIELDS")) {
                    subDsl.add("FIELDS", gson.toJsonTree(ruleMap.get("FIELDS")));
                }
                return mockFromDsl(subDsl, context);
            }
            // 3. 内置函数表达式
            return TemplateValueResolver.resolve(rule, context);
        } else if (rule instanceof List) {
            // 直接返回 List，递归解析每个元素
            List<?> list = (List<?>) rule;
            return list.stream().map(v -> TemplateValueResolver.resolve(v, context)).toList();
        } else {
            // 4. 普通值/内置函数表达式/变量
            return TemplateValueResolver.resolve(rule, context);
        }
    }

    private static Class<?> resolveModelClass(String modelName) {
        // 先查注册表，再尝试全类名
        String className = MockTypeRegistry.getClassName(modelName);
        if (className != null) {
            try {
                return Class.forName(className);
            } catch (Exception e) {
                throw new MockTemplateException("注册表中的类无法加载: " + className, e);
            }
        }
        // 尝试直接当作全类名
        try {
            return Class.forName(modelName);
        } catch (Exception e) {
            throw new MockTemplateException("未注册类型: " + modelName + "，请先注册或填写全类名", e);
        }
    }
} 