package com.xa.mass.base.jsondsl.generate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.DslKeyword;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.builtin.TemplateValueResolver;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.*;

/**
 * DSL 对象构建器，负责从 DSL 配置生成对象实例。
 *
 * 新标准提供更好的类型安全、验证和扩展性。
 */
public class DslObjectBuilder {
    private static final Gson gson = GsonConfig.buildGson();

    /**
     * 泛型版本：类型安全地生成指定类型对象
     */
    public static <T> T mockFromDsl(JsonObject dsl, DslContext context, Class<T> targetType) {
        // 1. 解析 MODEL
        Object modelName = dsl.has(DslKeyword.MODEL.name()) ? dsl.get(DslKeyword.MODEL.name()).getAsString() : null;
        if (modelName == null && dsl.has("context") && dsl.get("context").isJsonObject()) {
            var ctxObj = dsl.getAsJsonObject("context");
            if (ctxObj.has(DslKeyword.MODEL.name())) {
                modelName = ctxObj.get(DslKeyword.MODEL.name()).getAsString();
            }
        }
        if (modelName == null) {
            throw new JsonDslException("DSL 缺少 MODEL 字段");
        }
        Class<?> clazz = resolveModelClass(modelName.toString());
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
                            Integer intValue = null;
                            if (value != null) {
                                if (value instanceof Number n) {
                                    intValue = n.intValue();
                                } else {
                                    String str = value.toString().trim();
                                    if (!str.isEmpty()) {
                                        try {
                                            intValue = Integer.parseInt(str);
                                        } catch (Exception ignore) {
                                            // 保持 intValue = null
                                        }
                                    }
                                }
                            }
                            field.set(obj, intValue);
                            fallbackSet = true;
                        } catch (Exception ignore) {}
                    }
                    if (!fallbackSet) {
                        throw new JsonDslException("无法设置字段: " + field.getName() + " in " + clazz.getName(), e);
                    }
                }
            }
        }
        
        // 类型检查和转换
        if (!targetType.isInstance(obj)) {
            throw new JsonDslException("生成对象类型不匹配: 期望 " + targetType.getName() +
                ", 实际 " + (obj != null ? obj.getClass().getName() : "null"));
        }
        return targetType.cast(obj);
    }

    private static Object mockFieldValue(Field field, Object rule, DslContext context) {
        if (isCollectionRule(rule)) {
            return handleCollectionRule(rule, context, Object.class);
        }
        if (isNestedModelRule(rule)) {
            return handleNestedModelRule(rule, context, Object.class);
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

    /**
     * 处理集合类型规则，生成 List/Set 等集合对象。
     * <p>
     * 示例：
     * <pre>
     *     JsonObject dsl = ...;
     *     DslContext ctx = ...;
     *     List<User> users = handleCollectionRule(dsl, ctx, List.class);
     * </pre>
     * @param rule 集合类型的 DSL 规则对象（Map 或 JsonObject）
     * @param context 变量上下文
     * @param targetType 目标集合类型
     * @return 生成的集合对象
     */
    private static <T> T handleCollectionRule(Object rule, DslContext context, Class<T> targetType) {
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
            list.add(mockFromDsl(subDsl, subContext, Object.class));
        }
        Object result = type.equals("LIST") ? list : 
                       type.equals("SET") ? new HashSet<>(list) : 
                       null;
        if (result == null) {
            throw new JsonDslException("不支持的集合类型: " + type);
        }
        return targetType.cast(result);
    }

    /**
     * 处理嵌套模型规则，递归生成嵌套对象。
     * <p>
     * 示例：
     * <pre>
     *     JsonObject dsl = ...;
     *     DslContext ctx = ...;
     *     User user = handleNestedModelRule(dsl, ctx, User.class);
     * </pre>
     * @param rule 嵌套模型的 DSL 规则对象（Map 或 JsonObject）
     * @param context 变量上下文
     * @param targetType 目标类型
     * @return 生成的目标类型对象
     */
    private static <T> T handleNestedModelRule(Object rule, DslContext context, Class<T> targetType) {
        Map<?, ?> ruleMap = (Map<?, ?>) rule;
        String modelName = (String) ruleMap.get(DslKeyword.MODEL.name());
        DslContext subContext = new DslContext(context);
        subContext.setScopeName(modelName);
        JsonObject subDsl = new JsonObject();
        subDsl.addProperty(DslKeyword.MODEL.name(), modelName);
        if (ruleMap.containsKey(DslKeyword.FIELDS.name())) {
            subDsl.add(DslKeyword.FIELDS.name(), gson.toJsonTree(ruleMap.get(DslKeyword.FIELDS.name())));
        }
        return mockFromDsl(subDsl, subContext, targetType);
    }

    private static boolean setFieldViaSetter(Object obj, String fieldName, Object value, Class<?> clazz) {
        try {
            String setterName = "set" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
            
            // 尝试不同类型的 setter
            if (tryInvokeSetter(obj, clazz, setterName, String.class, value == null ? null : value.toString()) ||
                tryInvokeSetter(obj, clazz, setterName, value.getClass(), value) ||
                tryInvokeSetter(obj, clazz, setterName, Object.class, value) ||
                tryInvokeAnySetter(obj, clazz, setterName, value)) {
                return true;
            }
            
            // 兜底：如果字段类型为Integer且value为String，直接parseInt后set到字段
            return trySetIntegerField(obj, fieldName, value, clazz);
        } catch (Exception e) {
            return false;
        }
    }
    
    private static boolean tryInvokeSetter(Object obj, Class<?> clazz, String setterName, Class<?> paramType, Object value) {
        try {
            java.lang.reflect.Method setter = clazz.getMethod(setterName, paramType);
            setter.invoke(obj, value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    private static boolean tryInvokeAnySetter(Object obj, Class<?> clazz, String setterName, Object value) {
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
        return false;
    }
    
    private static boolean trySetIntegerField(Object obj, String fieldName, Object value, Class<?> clazz) {
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