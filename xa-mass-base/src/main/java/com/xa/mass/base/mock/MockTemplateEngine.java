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
        int count = root.has(DslKeyword.COUNT.name()) ? root.get(DslKeyword.COUNT.name()).getAsInt() : 1;
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
        String modelName = dsl.has(DslKeyword.MODEL.name()) ? dsl.get(DslKeyword.MODEL.name()).getAsString() : null;
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
        if (dsl.has(DslKeyword.FIELDS.name())) {
            Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
            fields = gson.fromJson(dsl.get(DslKeyword.FIELDS.name()), mapType);
        }
        if (fields == null) fields = Collections.emptyMap();
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = null;
            if (fields.containsKey(field.getName())) {
                value = mockFieldValue(field, fields.get(field.getName()), context);
            }
            // 自动支持枚举类型赋值
            if (field.getType().isEnum() && value instanceof String strVal) {
                value = Enum.valueOf((Class<Enum>) field.getType(), strVal);
            }
            // 基本类型未指定时赋默认值
            if (value == null && field.getType().isPrimitive()) {
                value = getPrimitiveDefaultValue(field.getType());
            }
            try {
                field.set(obj, value);
            } catch (Exception e) {
                throw new MockTemplateException("无法设置字段: " + field.getName() + " in " + clazz.getName(), e);
            }
        }
        return obj;
    }

    private static Object mockFieldValue(Field field, Object rule, Map<String, Object> context) {
        if (isCollectionRule(rule)) {
            return handleCollectionRule(rule, context);
        }
        if (isNestedModelRule(rule)) {
            return handleNestedModelRule(rule, context);
        }
        // 其余情况交给 TemplateValueResolver（包括内置函数、普通值、变量）
        return TemplateValueResolver.resolve(rule, context);
    }

    private static boolean isCollectionRule(Object rule) {
        if (!(rule instanceof Map<?, ?> map)) return false;
        return map.containsKey(DslKeyword.TYPE.name());
    }

    private static boolean isNestedModelRule(Object rule) {
        if (!(rule instanceof Map<?, ?> map)) return false;
        return map.containsKey(DslKeyword.MODEL.name());
    }

    private static Object handleCollectionRule(Object rule, Map<String, Object> context) {
        Map<?, ?> ruleMap = (Map<?, ?>) rule;
        String type = String.valueOf(ruleMap.get(DslKeyword.TYPE.name())).toUpperCase();
        int count = ruleMap.containsKey(DslKeyword.COUNT.name()) ? ((Number) ruleMap.get(DslKeyword.COUNT.name())).intValue() : 1;
        String modelName = (String) ruleMap.get(DslKeyword.MODEL.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> subFields = (Map<String, Object>) ruleMap.get(DslKeyword.FIELDS.name());
        List<Object> list = new ArrayList<>();
        for (int j = 0; j < count; j++) {
            Map<String, Object> subContext = new HashMap<>(context);
            subContext.put("j", j);
            JsonObject subDsl = new JsonObject();
            subDsl.addProperty(DslKeyword.MODEL.name(), modelName);
            if (subFields != null) {
                subDsl.add(DslKeyword.FIELDS.name(), gson.toJsonTree(subFields));
            }
            list.add(mockFromDsl(subDsl, subContext));
        }
        if (type.equals("LIST")) return list;
        if (type.equals("SET")) return new HashSet<>(list);
        throw new MockTemplateException("不支持的集合类型: " + type);
    }

    private static Object handleNestedModelRule(Object rule, Map<String, Object> context) {
        Map<?, ?> ruleMap = (Map<?, ?>) rule;
        JsonObject subDsl = new JsonObject();
        subDsl.addProperty(DslKeyword.MODEL.name(), (String) ruleMap.get(DslKeyword.MODEL.name()));
        if (ruleMap.containsKey(DslKeyword.FIELDS.name())) {
            subDsl.add(DslKeyword.FIELDS.name(), gson.toJsonTree(ruleMap.get(DslKeyword.FIELDS.name())));
        }
        return mockFromDsl(subDsl, context);
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

    private static Object getPrimitiveDefaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }
} 