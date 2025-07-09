package com.xa.mass.base.jsondsl.generate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.base.jsondsl.builtin.*;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.*;

/**
 * DSL 对象构建器 - 主入口类
 *
 * 新标准提供更好的类型安全、验证和扩展性。
 * 按职责拆分为多个专门的构建器，提高代码的可维护性和扩展性。
 */
public class DslObjectBuilder {
    private static final Gson gson = GsonConfig.buildGson();

    /**
     * 泛型版本：类型安全地生成指定类型对象
     */
    public static <T> T mockFromDsl(JsonObject dsl, DslContext context, Class<T> targetType) {
        // 1. 解析和验证 DSL
        DslDefinitionParser parser = new DslDefinitionParser();
        DslDefinition definition = parser.parse(dsl);

        // 2. 创建对象实例
        ObjectInstanceBuilder instanceBuilder = new ObjectInstanceBuilder();
        Object obj = instanceBuilder.createInstance(definition.getModelClass());

        // 3. 设置字段值
        FieldValueBuilder fieldBuilder = new FieldValueBuilder();
        fieldBuilder.setFields(obj, definition.getFields(), context);

        // 4. 类型检查和转换
        TypeValidator validator = new TypeValidator();
        return validator.validateAndCast(obj, targetType);
    }

    /**
     * 生成字段值
     */
    public static Object mockFieldValue(Object rule, DslContext context) {
        return FieldValueGenerator.generate(rule, context);
    }

    /**
     * DSL 定义解析器 - 专门负责解析 DSL 配置
     */
    static class DslDefinitionParser {

        public DslDefinition parse(JsonObject dsl) {
            // 1. 解析 MODEL
            String modelName = parseModelName(dsl);
            Class<?> modelClass = resolveModelClass(modelName);

            // 2. 解析 FIELDS
            Map<String, Object> fields = parseFields(dsl);

            return new DslDefinition(modelClass, fields);
        }

        private String parseModelName(JsonObject dsl) {
            // 优先从根级别获取
            if (dsl.has(DslKeyword.MODEL.name())) {
                return dsl.get(DslKeyword.MODEL.name()).getAsString();
            }

            // 从 context 中获取
            if (dsl.has("context") && dsl.get("context").isJsonObject()) {
                JsonObject ctxObj = dsl.getAsJsonObject("context");
                if (ctxObj.has(DslKeyword.MODEL.name())) {
                    return ctxObj.get(DslKeyword.MODEL.name()).getAsString();
                }
            }

            throw new JsonDslException("DSL 缺少 MODEL 字段");
        }

        private Map<String, Object> parseFields(JsonObject dsl) {
            if (!dsl.has(DslKeyword.FIELDS.name())) {
                return Collections.emptyMap();
            }

            Type mapType = new TypeToken<Map<String, Object>>() {
            }.getType();
            return gson.fromJson(dsl.get(DslKeyword.FIELDS.name()), mapType);
        }

        private Class<?> resolveModelClass(String modelName) {
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

    /**
     * DSL 定义数据类
     */
    static class DslDefinition {
        private final Class<?> modelClass;
        private final Map<String, Object> fields;

        public DslDefinition(Class<?> modelClass, Map<String, Object> fields) {
            this.modelClass = modelClass;
            this.fields = fields;
        }

        public Class<?> getModelClass() {
            return modelClass;
        }

        public Map<String, Object> getFields() {
            return fields;
        }
    }

    /**
     * 对象实例构建器 - 专门负责创建对象实例
     */
    static class ObjectInstanceBuilder {

        public Object createInstance(Class<?> clazz) {
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new JsonDslException("无法实例化模型: " + clazz.getName(), e);
            }
        }
    }

    /**
     * 字段值构建器 - 专门负责设置字段值
     */
    static class FieldValueBuilder {

        public void setFields(Object obj, Map<String, Object> fields, DslContext context) {
            Class<?> clazz = obj.getClass();

            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                String fieldName = entry.getKey();
                Field field = FieldCacheUtil.getFieldFromCache(clazz, fieldName);

                if (field == null) {
                    continue; // JSON 有但类无此字段，跳过
                }

                Object value = FieldValueGenerator.generate(entry.getValue(), context);
                value = TypeAdapterUtil.adaptType(field.getType(), value);

                setFieldValue(obj, field, fieldName, value, clazz);
            }
        }

        private void setFieldValue(Object obj, Field field, String fieldName, Object value, Class<?> clazz) {
            // 优先尝试使用 setter 方法
            if (setFieldViaSetter(obj, fieldName, value, clazz)) {
                return;
            }

            // 兜底：直接设置字段
            setFieldDirectly(obj, field, value, clazz);
        }

        private boolean setFieldViaSetter(Object obj, String fieldName, Object value, Class<?> clazz) {
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

        private void setFieldDirectly(Object obj, Field field, Object value, Class<?> clazz) {
            try {
                field.set(obj, value);
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
                        return;
                    } catch (Exception ignore) {
                    }
                }
                throw new JsonDslException("无法设置字段: " + field.getName() + " in " + clazz.getName(), e);
            }
        }

        private boolean tryInvokeSetter(Object obj, Class<?> clazz, String setterName, Class<?> paramType, Object value) {
            try {
                java.lang.reflect.Method setter = clazz.getMethod(setterName, paramType);
                setter.invoke(obj, value);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private boolean tryInvokeAnySetter(Object obj, Class<?> clazz, String setterName, Object value) {
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

        private boolean trySetIntegerField(Object obj, String fieldName, Object value, Class<?> clazz) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
                if (field.getType() == Integer.class && value instanceof String s) {
                    field.setAccessible(true);
                    field.set(obj, Integer.parseInt(s));
                    return true;
                }
            } catch (Exception ignore) {
            }
            return false;
        }
    }

    /**
     * 字段值生成器 - 专门负责生成字段值
     */
    static class FieldValueGenerator {

        public static Object generate(Object rule, DslContext context) {
            if (isCollectionRule(rule)) {
                return CollectionRuleHandler.handle(rule, context, Object.class);
            }

            if (isNestedModelRule(rule)) {
                return NestedModelRuleHandler.handle(rule, context, Object.class);
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
    }

    /**
     * 集合规则处理器 - 专门处理集合类型规则
     */
    static class CollectionRuleHandler {

        public static <T> T handle(Object rule, DslContext context, Class<T> targetType) {
            Map<?, ?> ruleMap = (Map<?, ?>) rule;
            String type = String.valueOf(ruleMap.get(DslKeyword.TYPE.name())).toUpperCase();
            int count = ruleMap.containsKey(DslKeyword.COUNT.name()) ?
                    ((Number) ruleMap.get(DslKeyword.COUNT.name())).intValue() : 1;
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
                    subContext.setVariable("&" + context.getScopeName() + ".index",
                            context.getVariable("&" + context.getScopeName() + ".index"));
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
    }

    /**
     * 嵌套模型规则处理器 - 专门处理嵌套模型规则
     */
    static class NestedModelRuleHandler {

        public static <T> T handle(Object rule, DslContext context, Class<T> targetType) {
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
    }

    /**
     * 类型验证器 - 专门负责类型检查和转换
     */
    static class TypeValidator {

        public <T> T validateAndCast(Object obj, Class<T> targetType) {
            if (!targetType.isInstance(obj)) {
                throw new JsonDslException("生成对象类型不匹配: 期望 " + targetType.getName() +
                        ", 实际 " + (obj != null ? obj.getClass().getName() : "null"));
            }
            return targetType.cast(obj);
        }
    }
} 