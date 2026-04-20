package com.xa.mass.base.jsondsl.processor;

import com.google.gson.Gson;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.GsonConfig;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.builtin.TemplateValueResolver;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

final class ProcessorDslSupport {

    private static final Gson GSON = GsonConfig.buildGson();

    private ProcessorDslSupport() {
    }

    @SuppressWarnings("unchecked")
    static <T> Map<String, Object> toMap(T input) {
        try {
            if (input instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
            return GSON.fromJson(GSON.toJson(input), Map.class);
        } catch (Exception e) {
            throw new JsonDslException("Failed to convert object to map: " + input.getClass().getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T copyInput(T input) {
        if (input instanceof Map<?, ?> map) {
            return (T) new LinkedHashMap<>((Map<String, Object>) map);
        }
        return (T) GSON.fromJson(GSON.toJson(input), input.getClass());
    }

    static DslContext createDslContext(Map<String, Object> inputMap, JsonDslDefinition definition, ProcessingContext context) {
        DslContext dslContext = new DslContext();
        if (definition.getContext() != null) {
            String scopeName = definition.getContext().getScopeName();
            if (scopeName == null || scopeName.isBlank()) {
                scopeName = definition.getContext().getModel();
            }
            dslContext.setScopeName(scopeName);
            dslContext.setStrict(Boolean.TRUE.equals(definition.getContext().getStrict()));
            if (definition.getContext().getParameters() != null) {
                definition.getContext().getParameters().forEach(dslContext::setVariable);
            }
        }
        if (context != null) {
            context.getParameters().forEach(dslContext::setVariable);
            context.getVariables().forEach(dslContext::setVariable);
        }
        inputMap.forEach(dslContext::setVariable);
        return dslContext;
    }

    static Object evaluateRule(String fieldName, Object currentValue, Object rule, DslContext dslContext) {
        dslContext.setVariable(fieldName, currentValue);
        dslContext.setVariable("curFiledVal", currentValue);
        return TemplateValueResolver.resolve(rule, dslContext);
    }

    static boolean isTruthy(Object result) {
        if (result instanceof Boolean bool) {
            return bool;
        }
        if (result instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (result instanceof String string) {
            return !string.isEmpty();
        }
        return result != null;
    }

    static void writeField(Object target, String fieldName, Object value) {
        if (target instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).put(fieldName, value);
            return;
        }

        Class<?> targetClass = target.getClass();
        Method setter = findSetter(targetClass, fieldName);
        if (setter != null) {
            try {
                setter.invoke(target, coerceValue(setter.getParameterTypes()[0], value));
                return;
            } catch (Exception e) {
                throw new JsonDslException("Failed to invoke setter for field '" + fieldName + "'", e);
            }
        }

        Field field = findField(targetClass, fieldName);
        if (field == null) {
            throw new JsonDslException("Unknown field '" + fieldName + "' on " + targetClass.getName());
        }
        try {
            field.setAccessible(true);
            field.set(target, coerceValue(field.getType(), value));
        } catch (Exception e) {
            throw new JsonDslException("Failed to write field '" + fieldName + "'", e);
        }
    }

    private static Method findSetter(Class<?> targetClass, String fieldName) {
        String setterName = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        for (Method method : targetClass.getMethods()) {
            if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                return method;
            }
        }
        return null;
    }

    private static Field findField(Class<?> targetClass, String fieldName) {
        Class<?> current = targetClass;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerceValue(Class<?> targetType, Object value) {
        if (value == null) {
            if (targetType.isPrimitive()) {
                if (targetType == boolean.class) {
                    return false;
                }
                if (targetType == char.class) {
                    return '\0';
                }
                return 0;
            }
            return null;
        }

        if (targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) {
            return String.valueOf(value);
        }
        if (targetType == Integer.class || targetType == int.class) {
            return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
        }
        if (targetType == Long.class || targetType == long.class) {
            return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
        }
        if (targetType == Double.class || targetType == double.class) {
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        }
        if (targetType == Float.class || targetType == float.class) {
            return value instanceof Number n ? n.floatValue() : Float.parseFloat(String.valueOf(value));
        }
        if (targetType == Short.class || targetType == short.class) {
            return value instanceof Number n ? n.shortValue() : Short.parseShort(String.valueOf(value));
        }
        if (targetType == Byte.class || targetType == byte.class) {
            return value instanceof Number n ? n.byteValue() : Byte.parseByte(String.valueOf(value));
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value instanceof Number n) {
                return n.intValue() != 0;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }
        if (targetType.isEnum()) {
            return Enum.valueOf((Class<Enum>) targetType, String.valueOf(value));
        }

        return GSON.fromJson(GSON.toJson(value), targetType);
    }
}
