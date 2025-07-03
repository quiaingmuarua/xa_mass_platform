package com.xa.mass.base.jsondsl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 通用 JSON-DSL mock 主入口。
 * 支持通过 DSL 批量生成任意对象，支持 MODEL、FIELDS、COUNT、TYPE，递归嵌套、内置函数、注册表。
 */
public class JsonDslEngine {
    private static final Gson gson = new Gson();

    // Field 缓存：Class -> (fieldName -> Field)
    private static final ConcurrentHashMap<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    // 类型适配器注册表
    private static final Map<Class<?>, Function<Object, Object>> TYPE_ADAPTERS = new HashMap<>();
    static {
        TYPE_ADAPTERS.put(LocalDateTime.class, v -> {
            if (v == null) return null;
            if (v instanceof LocalDateTime ldt) return ldt;
            if (v instanceof String str) {
                String[] formats = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "HH:mm:ss", "HH:mm"};
                for (String fmt : formats) {
                    try {
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(fmt);
                        if (fmt.length() == str.length()) {
                            return LocalDateTime.parse(str, formatter);
                        }
                    } catch (Exception ignored) {}
                }
            }
            return null;
        });
        TYPE_ADAPTERS.put(Date.class, v -> {
            if (v == null) return null;
            if (v instanceof Date d) return d;
            if (v instanceof LocalDateTime ldt) return java.sql.Timestamp.valueOf(ldt);
            if (v instanceof String str) {
                String[] formats = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "HH:mm:ss", "HH:mm"};
                for (String fmt : formats) {
                    try {
                        SimpleDateFormat sdf = new SimpleDateFormat(fmt);
                        if (fmt.length() == str.length()) {
                            return sdf.parse(str);
                        }
                    } catch (Exception ignored) {}
                }
            }
            return null;
        });
        TYPE_ADAPTERS.put(String.class, v -> {
            if (v == null) return null;
            if (v instanceof Date date) return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
            if (v instanceof LocalDateTime ldt) return ldt.toString();
            return v.toString();
        });
        // 其他类型可按需扩展
    }

    private static Field getFieldFromCache(Class<?> clazz, String fieldName) {
        Map<String, Field> map = FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> m = new HashMap<>();
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    m.put(f.getName(), f);
                }
            }
            return m;
        });
        return map.get(fieldName);
    }

    /**
     * 根据 JSON-DSL 生成 mock 对象列表。
     * @param jsonDsl JSON-DSL 字符串
     * @return mock 对象列表（每个对象为实例）
     */
    public static List<Object> generate(String jsonDsl) {
        JsonObject root = JsonParser.parseString(jsonDsl).getAsJsonObject();
        int count = root.has(DslKeyword.COUNT.name()) ? root.get(DslKeyword.COUNT.name()).getAsInt() : 1;
        List<Object> result = new ArrayList<>();
        String modelName = root.has(DslKeyword.MODEL.name()) ? root.get(DslKeyword.MODEL.name()).getAsString() : "Root";
        for (int i = 0; i < count; i++) {
            DslContext context = new DslContext();
            context.setScopeName(modelName);
            context.setVariable("&" + modelName + ".index", i);
            result.add(mockFromDsl(root, context));
        }
        return result;
    }

    private static Object mockFromDsl(JsonObject dsl, DslContext context) {
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
            Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
            fields = gson.fromJson(dsl.get(DslKeyword.FIELDS.name()), mapType);
        }
        if (fields == null) fields = Collections.emptyMap();
        // 新：遍历 JSON FIELDS 字段
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            Field field = getFieldFromCache(clazz, fieldName);
            if (field == null) continue; // JSON 有但类无此字段，跳过
            Object value = mockFieldValue(field, entry.getValue(), context);
            value = adaptType(field.getType(), value);
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

    private static Object adaptType(Class<?> fieldType, Object value) {
        if (value == null) {
            if (fieldType.isPrimitive()) return getPrimitiveDefaultValue(fieldType);
            return null;
        }
        // 枚举类型适配
        if (fieldType.isEnum()) {
            if (value instanceof String strVal) {
                return Enum.valueOf((Class<Enum>) fieldType, strVal);
            }
            // 兼容直接传枚举对象
            if (fieldType.isInstance(value)) {
                return value;
            }
            // 兼容数字下标
            if (value instanceof Number num) {
                Object[] enumConstants = fieldType.getEnumConstants();
                int idx = num.intValue();
                if (idx >= 0 && idx < enumConstants.length) {
                    return enumConstants[idx];
                }
            }
            throw new JsonDslException("无法将 " + value + " 转为枚举 " + fieldType.getName());
        }
        // 类型适配器
        Function<Object, Object> adapter = TYPE_ADAPTERS.get(fieldType);
        if (adapter != null) {
            Object adapted = adapter.apply(value);
            if (adapted != null) return adapted;
        }
        return value;
    }
} 