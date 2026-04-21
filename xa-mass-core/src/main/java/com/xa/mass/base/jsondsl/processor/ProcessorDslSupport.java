package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.builtin.TemplateValueResolver;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ProcessorDslSupport {

    private static final ConcurrentHashMap<Class<?>, BeanAccessPlan> BEAN_ACCESS_CACHE = new ConcurrentHashMap<>();

    private ProcessorDslSupport() {
    }

    @SuppressWarnings("unchecked")
    static <T> Map<String, Object> toMap(T input) {
        if (input instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return beanPlan(input.getClass()).snapshot(input);
    }

    @SuppressWarnings("unchecked")
    static <T> T copyInput(T input) {
        if (input instanceof Map<?, ?> map) {
            return (T) new LinkedHashMap<>((Map<String, Object>) map);
        }

        BeanAccessPlan accessPlan = beanPlan(input.getClass());
        Object target = accessPlan.newInstance();
        accessPlan.snapshot(input).forEach((fieldName, value) -> accessPlan.write(target, fieldName, value));
        return (T) target;
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

    @SuppressWarnings("unchecked")
    static void writeField(Object target, String fieldName, Object value) {
        if (target instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).put(fieldName, value);
            return;
        }
        beanPlan(target.getClass()).write(target, fieldName, value);
    }

    private static BeanAccessPlan beanPlan(Class<?> beanType) {
        return BEAN_ACCESS_CACHE.computeIfAbsent(beanType, ProcessorDslSupport::buildPlan);
    }

    private static BeanAccessPlan buildPlan(Class<?> beanType) {
        try {
            BeanInfo beanInfo = Introspector.getBeanInfo(beanType, Object.class);
            Map<String, Method> getters = new LinkedHashMap<>();
            Map<String, List<Method>> setters = new LinkedHashMap<>();
            LinkedHashSet<String> readableNames = new LinkedHashSet<>();
            Map<String, Field> fields = collectFields(beanType);

            for (PropertyDescriptor descriptor : beanInfo.getPropertyDescriptors()) {
                Method readMethod = descriptor.getReadMethod();
                if (readMethod != null) {
                    readMethod.setAccessible(true);
                    getters.put(descriptor.getName(), readMethod);
                    readableNames.add(descriptor.getName());
                }

                Method writeMethod = descriptor.getWriteMethod();
                if (writeMethod != null) {
                    writeMethod.setAccessible(true);
                    setters.computeIfAbsent(descriptor.getName(), ignored -> new ArrayList<>()).add(writeMethod);
                }
            }

            readableNames.addAll(fields.keySet());
            Constructor<?> constructor = resolveNoArgsConstructor(beanType);
            return new BeanAccessPlan(beanType, constructor, readableNames, getters, setters, fields);
        } catch (IntrospectionException e) {
            throw new JsonDslException("Failed to inspect bean type: " + beanType.getName(), e);
        }
    }

    private static Map<String, Field> collectFields(Class<?> beanType) {
        LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
        Class<?> current = beanType;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                field.setAccessible(true);
                fields.putIfAbsent(field.getName(), field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static Constructor<?> resolveNoArgsConstructor(Class<?> beanType) {
        try {
            Constructor<?> constructor = beanType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Method findBestSetter(List<Method> candidates, Class<?> preferredType, Object value) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        Method bestMatch = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method method : candidates) {
            int score = scoreSetter(method.getParameterTypes()[0], preferredType, value);
            if (score < bestScore) {
                bestScore = score;
                bestMatch = method;
            }
        }
        return bestMatch;
    }

    private static int scoreSetter(Class<?> parameterType, Class<?> preferredType, Object value) {
        Class<?> wrappedParameterType = wrapPrimitive(parameterType);
        Class<?> wrappedPreferredType = wrapPrimitive(preferredType);

        if (wrappedPreferredType != null && wrappedParameterType.equals(wrappedPreferredType)) {
            return 0;
        }
        if (value != null) {
            Class<?> wrappedValueType = wrapPrimitive(value.getClass());
            if (wrappedParameterType.isAssignableFrom(wrappedValueType)) {
                return 1;
            }
            if (value instanceof Number && Number.class.isAssignableFrom(wrappedParameterType)) {
                return 2;
            }
            if (value instanceof Boolean && wrappedParameterType == Boolean.class) {
                return 2;
            }
            if (wrappedParameterType == String.class) {
                return 5;
            }
        }
        return 10;
    }

    private static Class<?> wrapPrimitive(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
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

        if (targetType.isInstance(value) || targetType == Object.class) {
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
        if (targetType.isAssignableFrom(value.getClass())) {
            return value;
        }

        throw new JsonDslException("Unsupported value coercion from " + value.getClass().getName()
                + " to " + targetType.getName());
    }

    private record BeanAccessPlan(
            Class<?> beanType,
            Constructor<?> constructor,
            LinkedHashSet<String> readableNames,
            Map<String, Method> getters,
            Map<String, List<Method>> setters,
            Map<String, Field> fields
    ) {

        Map<String, Object> snapshot(Object source) {
            LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
            for (String fieldName : readableNames) {
                snapshot.put(fieldName, read(source, fieldName));
            }
            return snapshot;
        }

        Object newInstance() {
            if (constructor == null) {
                throw new JsonDslException("Bean type requires a no-args constructor for transform copy: " + beanType.getName());
            }
            try {
                return constructor.newInstance();
            } catch (Exception e) {
                throw new JsonDslException("Failed to instantiate bean type: " + beanType.getName(), e);
            }
        }

        Object read(Object source, String fieldName) {
            Method getter = getters.get(fieldName);
            if (getter != null) {
                try {
                    return getter.invoke(source);
                } catch (Exception e) {
                    throw new JsonDslException("Failed to read field '" + fieldName + "' from " + beanType.getName(), e);
                }
            }

            Field field = fields.get(fieldName);
            if (field != null) {
                try {
                    return field.get(source);
                } catch (Exception e) {
                    throw new JsonDslException("Failed to read field '" + fieldName + "' from " + beanType.getName(), e);
                }
            }
            return null;
        }

        void write(Object target, String fieldName, Object value) {
            Field field = fields.get(fieldName);
            Method setter = findBestSetter(setters.get(fieldName), field != null ? field.getType() : null, value);
            if (setter != null) {
                try {
                    setter.invoke(target, coerceValue(setter.getParameterTypes()[0], value));
                    return;
                } catch (Exception e) {
                    throw new JsonDslException("Failed to invoke setter for field '" + fieldName + "'", e);
                }
            }

            if (field == null) {
                throw new JsonDslException("Unknown field '" + fieldName + "' on " + beanType.getName());
            }

            try {
                field.set(target, coerceValue(field.getType(), value));
            } catch (Exception e) {
                throw new JsonDslException("Failed to write field '" + fieldName + "'", e);
            }
        }
    }
}
