package com.xa.mass.base.jsondsl.generate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.DslKeyword;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.builtin.TemplateValueResolver;
import com.xa.mass.base.jsondsl.util.GsonConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.*;

/**
 * DSL 对象构建器，负责从 DSL 配置生成对象实例。
 * 
 * @deprecated 建议使用新的标准化 DSL 结构，通过 {@link com.xa.mass.base.jsondsl.model.JsonDslDefinition} 
 * 和 {@link com.xa.mass.base.jsondsl.parser.JsonDslParser} 进行 DSL 定义和解析。
 * 新标准提供更好的类型安全、验证和扩展性。
 */
@Deprecated(since = "2.0.0", forRemoval = true)
public class DslObjectBuilder {
    private static final Gson gson = GsonConfig.buildGson();

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
            
            // 优先尝试使用 setter 方法
            boolean setterUsed = setFieldViaSetter(obj, fieldName, value, clazz);
            if (!setterUsed) {
                boolean fallbackSet = false;
                try {
                    field.set(obj, value);
                    fallbackSet = true;
                } catch (Exception e) {
                    // 兜底：如果字段类型为Integer，支持Number、String、其他类型
                    if (field.getType() == Integer.class) {
                        try {
                            if (value == null) {
                                field.set(obj, null);
                                fallbackSet = true;
                            } else if (value instanceof Number n) {
                                field.set(obj, n.intValue());
                                fallbackSet = true;
                            } else if (value instanceof String s) {
                                if (s.isEmpty()) {
                                    field.set(obj, null);
                                    fallbackSet = true;
                                } else {
                                    try {
                                        field.set(obj, Integer.parseInt(s));
                                        fallbackSet = true;
                                    } catch (Exception ignore) {
                                        field.set(obj, null);
                                        fallbackSet = true;
                                    }
                                }
                            } else {
                                String str = value.toString();
                                if (str.isEmpty()) {
                                    field.set(obj, null);
                                    fallbackSet = true;
                                } else {
                                    try {
                                        field.set(obj, Integer.parseInt(str));
                                        fallbackSet = true;
                                    } catch (Exception ignore) {
                                        field.set(obj, null);
                                        fallbackSet = true;
                                    }
                                }
                            }
                        } catch (Exception ignore) {}
                    }
                    if (!fallbackSet) {
                        throw new JsonDslException("无法设置字段: " + field.getName() + " in " + clazz.getName(), e);
                    }
                }
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

    private static boolean setFieldViaSetter(Object obj, String fieldName, Object value, Class<?> clazz) {
        try {
            String setterName = "set" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
            // 1. 优先尝试String类型setter
            try {
                java.lang.reflect.Method setter = clazz.getMethod(setterName, String.class);
                setter.invoke(obj, value == null ? null : value.toString());
                return true;
            } catch (NoSuchMethodException e) {
                // 2. 再尝试与字段类型匹配的setter
                try {
                    java.lang.reflect.Method setter = clazz.getMethod(setterName, value.getClass());
                    setter.invoke(obj, value);
                    return true;
                } catch (NoSuchMethodException e2) {
                    // 3. 再尝试Object类型setter
                    try {
                        java.lang.reflect.Method setter = clazz.getMethod(setterName, Object.class);
                        setter.invoke(obj, value);
                        return true;
                    } catch (NoSuchMethodException e3) {
                        // 4. 最后遍历所有同名单参数setter
                        java.lang.reflect.Method[] methods = clazz.getMethods();
                        for (java.lang.reflect.Method method : methods) {
                            if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                                try {
                                    method.invoke(obj, value);
                                    return true;
                                } catch (Exception ex) {
                                    // 继续尝试下一个方法
                                }
                            }
                        }
                        // 5. 兜底：如果字段类型为Integer且value为String，直接parseInt后set到字段
                        try {
                            java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
                            if (field.getType() == Integer.class && value instanceof String s) {
                                field.setAccessible(true);
                                field.set(obj, Integer.parseInt(s));
                                return true;
                            }
                        } catch (Exception ignore) {}
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
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