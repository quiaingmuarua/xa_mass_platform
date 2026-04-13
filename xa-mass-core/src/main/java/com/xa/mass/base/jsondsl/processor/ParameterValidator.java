package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.Collection;

/**
 * 参数校验工具类
 * <p>
 * 提供统一的参数校验方法，避免重复代码，统一异常类型
 * </p>
 */
public final class ParameterValidator {

    private ParameterValidator() {
        // 工具类，禁止实例化
    }

    /**
     * 校验对象不为空
     *
     * @param value 要校验的对象
     * @param paramName 参数名称
     * @throws JsonDslException 如果对象为空
     */
    public static void notNull(Object value, String paramName) {
        if (value == null) {
            throw new JsonDslException(paramName + " cannot be null");
        }
    }

    /**
     * 校验字符串不为空
     *
     * @param value 要校验的字符串
     * @param paramName 参数名称
     * @throws JsonDslException 如果字符串为空或空白
     */
    public static void notBlank(String value, String paramName) {
        notNull(value, paramName);
        if (value.trim().isEmpty()) {
            throw new JsonDslException(paramName + " cannot be blank");
        }
    }

    /**
     * 校验集合不为空
     *
     * @param collection 要校验的集合
     * @param paramName 参数名称
     * @throws JsonDslException 如果集合为空
     */
    public static void notEmpty(Collection<?> collection, String paramName) {
        notNull(collection, paramName);
        if (collection.isEmpty()) {
            throw new JsonDslException(paramName + " cannot be empty");
        }
    }


    /**
     * 校验数值大于等于指定值
     *
     * @param value 要校验的数值
     * @param minValue 最小值
     * @param paramName 参数名称
     * @throws JsonDslException 如果数值小于最小值
     */
    public static void greaterThanOrEqual(Number value, Number minValue, String paramName) {
        notNull(value, paramName);
        notNull(minValue, "minValue");
        if (value.doubleValue() < minValue.doubleValue()) {
            throw new JsonDslException(paramName + " must be greater than or equal to " + minValue);
        }
    }

    /**
     * 校验数值在指定范围内
     *
     * @param value 要校验的数值
     * @param minValue 最小值
     * @param maxValue 最大值
     * @param paramName 参数名称
     * @throws JsonDslException 如果数值不在范围内
     */
    public static void inRange(Number value, Number minValue, Number maxValue, String paramName) {
        notNull(value, paramName);
        notNull(minValue, "minValue");
        notNull(maxValue, "maxValue");
        double doubleValue = value.doubleValue();
        double min = minValue.doubleValue();
        double max = maxValue.doubleValue();
        if (doubleValue < min || doubleValue > max) {
            throw new JsonDslException(paramName + " must be between " + min + " and " + max);
        }
    }

    /**
     * 校验条件为真
     *
     * @param condition 条件
     * @param message 错误消息
     * @throws JsonDslException 如果条件为假
     */
    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new JsonDslException(message);
        }
    }

    /**
     * 校验 DSL 定义类型
     *
     * @param definition DSL 定义
     * @param expectedType 期望的类型
     * @throws JsonDslException 如果类型不匹配
     */
    public static void validateDslType(JsonDslDefinition definition, JsonDslDefinition.DslType expectedType) {
        notNull(definition, "definition");
        notNull(expectedType, "expectedType");
        if (definition.getType() != expectedType) {
            throw new JsonDslException("DSL type must be " + expectedType.getCode() + ", but got " +
                    (definition.getType() != null ? definition.getType().getCode() : "null"));
        }
    }

    /**
     * 校验 DSL 定义包含必需的字段
     *
     * @param definition DSL 定义
     * @param fieldName 字段名称
     * @throws JsonDslException 如果字段为空
     */
    public static void validateDslField(JsonDslDefinition definition, String fieldName) {
        notNull(definition, "definition");
        notBlank(fieldName, "fieldName");

        switch (fieldName) {
            case "fieldDsl":
                if (definition.getFieldDsl() == null || definition.getFieldDsl().isEmpty()) {
                    throw new JsonDslException("fieldDsl must not be empty");
                }
                break;
            case "context":
                if (definition.getContext() == null) {
                    throw new JsonDslException("context must not be null");
                }
                break;
            case "context.model":
                if (definition.getContext() == null ||
                        definition.getContext().getModel() == null ||
                        definition.getContext().getModel().trim().isEmpty()) {
                    throw new JsonDslException("context.model must not be empty");
                }
                break;
            default:
                throw new JsonDslException("Unsupported field validation: " + fieldName);
        }
    }

    public static void validateDslFieldOrCombine(JsonDslDefinition def) {
        if ((def.getFieldDsl() == null || def.getFieldDsl().isEmpty())
                && (def.getCombineDsl() == null || def.getCombineDsl().isEmpty())) {
            throw new JsonDslException("fieldDsl and combineDsl must not both be empty");
        }
    }

    /**
     * 校验处理器参数（通用方法）
     *
     * @param definition DSL 定义
     * @param context 处理上下文
     * @param processorName 处理器名称（用于日志）
     * @throws JsonDslException 如果参数无效
     */
    public static void validateProcessorParams(JsonDslDefinition definition, ProcessingContext context, String processorName) {
        notNull(definition, "definition");
        notNull(context, "context");

        if (context.isDebug()) {
            System.out.println("[" + processorName + "] 开始处理 DSL: " + definition.getUniqueId());
        }

        // 验证 DSL 定义
        definition.validate();
    }

    /**
     * 校验生成处理器参数
     *
     * @param definition DSL 定义
     * @param context 处理上下文
     * @param targetType 目标类型
     * @throws JsonDslException 如果参数无效
     */
    public static void validateGenerateParams(JsonDslDefinition definition, ProcessingContext context, Class<?> targetType) {
        validateProcessorParams(definition, context, "DefaultGenerateProcessor");
        notNull(targetType, "targetType");
        validateDslType(definition, JsonDslDefinition.DslType.GENERATE);
        validateDslField(definition, "context.model");
    }

    /**
     * 校验过滤处理器参数
     *
     * @param data 输入数据
     * @param definition DSL 定义
     * @param context 处理上下文
     * @throws JsonDslException 如果参数无效
     */
    public static void validateFilterParams(Collection<?> data, JsonDslDefinition definition, ProcessingContext context) {
        notNull(data, "input data");
        validateProcessorParams(definition, context, "DefaultFilterProcessor");
        validateDslType(definition, JsonDslDefinition.DslType.FILTER);
        validateDslField(definition, "fieldDsl");
    }

    /**
     * 校验转换处理器参数
     *
     * @param input 输入对象
     * @param definition DSL 定义
     * @param context 处理上下文
     * @throws JsonDslException 如果参数无效
     */
    public static void validateTransformParams(Object input, JsonDslDefinition definition, ProcessingContext context) {
        notNull(input, "input object");
        validateProcessorParams(definition, context, "DefaultTransformProcessor");
        validateDslType(definition, JsonDslDefinition.DslType.TRANSFORM);
    }

    /**
     * 校验验证处理器参数
     *
     * @param input 输入对象
     * @param definition DSL 定义
     * @param context 处理上下文
     * @throws JsonDslException 如果参数无效
     */
    public static void validateValidateParams(Object input, JsonDslDefinition definition, ProcessingContext context) {
        notNull(input, "input object");
        validateProcessorParams(definition, context, "DefaultValidateProcessor");
        validateDslType(definition, JsonDslDefinition.DslType.VALIDATE);
    }
} 