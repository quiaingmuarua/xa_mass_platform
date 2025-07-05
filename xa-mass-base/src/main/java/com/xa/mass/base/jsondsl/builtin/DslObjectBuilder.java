package com.xa.mass.base.jsondsl.builtin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.*;

public class DslObjectBuilder {
    private static final Gson gson = new Gson();

    public static Object mockFromDsl(JsonObject dsl, DslContext context) {
        // 1. 解析 MODEL
        String modelName = dsl.has(DslKeyword.MODEL.name()) ? dsl.get(DslKeyword.MODEL.name()).getAsString() : null;
        if (modelName == null) {
            throw new JsonDslException("DSL 缺少 MODEL 字段");
        }
        Class<?> clazz = resolveModelClass(modelName);
        Object obj;
        try {
            obj = clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new JsonDslException("无法实例化模型: " + modelName, e);
        }
        // 2. 处理 FIELDS
        Map<String, Object> fields = null;
        if (dsl.has(DslKeyword.FIELDS.name())) {
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            fields = gson.fromJson(dsl.get(DslKeyword.FIELDS.name()), mapType);
        }
        if (fields == null) fields = Collections.emptyMap();
        // 新：遍历 JSON FIELDS 字段
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            Field field = FieldCacheUtil.getFieldFromCache(clazz, fieldName);
            if (field == null) continue; // JSON 有但类无此字段，跳过
            Object value = mockFieldValue(field, entry.getValue(), context);
            value = TypeAdapterUtil.adaptType(field.getType(), value);
            try {
                field.set(obj, value);
            } catch (Exception e) {
                throw new JsonDslException("无法设置字段: " + field.getName() + " in " + clazz.getName(), e);
            }
        }
        return obj;
    }

    private static Object mockFieldValue(Field field, Object rule, DslContext context) {
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

    private static Object handleCollectionRule(Object rule, DslContext context) {
        Map<?, ?> ruleMap = (Map<?, ?>) rule;
        String type = String.valueOf(ruleMap.get(DslKeyword.TYPE.name())).toUpperCase();
        int count = ruleMap.containsKey(DslKeyword.COUNT.name()) ? ((Number) ruleMap.get(DslKeyword.COUNT.name())).intValue() : 1;
        String modelName = (String) ruleMap.get(DslKeyword.MODEL.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> subFields = (Map<String, Object>) ruleMap.get(DslKeyword.FIELDS.name());
        List<Object> list = new ArrayList<>();
        for (int j = 0; j < count; j++) {
            DslContext subContext = new DslContext(context);
            subContext.setScopeName(modelName);
            subContext.setVariable("&" + modelName + ".index", j);
            if (context.getScopeName() != null) {
                // 继承父作用域的 index 变量（可选）
                subContext.setVariable("&" + context.getScopeName() + ".index", context.getVariable("&" + context.getScopeName() + ".index"));
            }
            JsonObject subDsl = new JsonObject();
            subDsl.addProperty(DslKeyword.MODEL.name(), modelName);
            if (subFields != null) {
                subDsl.add(DslKeyword.FIELDS.name(), gson.toJsonTree(subFields));
            }
            list.add(mockFromDsl(subDsl, subContext));
        }
        if (type.equals("LIST")) return list;
        if (type.equals("SET")) return new HashSet<>(list);
        throw new JsonDslException("不支持的集合类型: " + type);
    }

    private static Object handleNestedModelRule(Object rule, DslContext context) {
        Map<?, ?> ruleMap = (Map<?, ?>) rule;
        String modelName = (String) ruleMap.get(DslKeyword.MODEL.name());
        DslContext subContext = new DslContext(context);
        subContext.setScopeName(modelName);
        JsonObject subDsl = new JsonObject();
        subDsl.addProperty(DslKeyword.MODEL.name(), modelName);
        if (ruleMap.containsKey(DslKeyword.FIELDS.name())) {
            subDsl.add(DslKeyword.FIELDS.name(), gson.toJsonTree(ruleMap.get(DslKeyword.FIELDS.name())));
        }
        return mockFromDsl(subDsl, subContext);
    }

    private static Class<?> resolveModelClass(String modelName) {
        // 先查注册表，再尝试全类名
        String className = TypeRegistry.getClassName(modelName);
        if (className != null) {
            try {
                return Class.forName(className);
            } catch (Exception e) {
                throw new JsonDslException("注册表中的类无法加载: " + className, e);
            }
        }
        // 尝试直接当作全类名
        try {
            return Class.forName(modelName);
        } catch (Exception e) {
            throw new JsonDslException("未注册类型: " + modelName + "，请先注册或填写全类名", e);
        }
    }
} 