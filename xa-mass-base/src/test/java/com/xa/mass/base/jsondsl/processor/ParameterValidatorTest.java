package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ParameterValidator 测试类
 */
public class ParameterValidatorTest {

    private JsonDslDefinition generateDsl;
    private JsonDslDefinition filterDsl;
    private JsonDslDefinition transformDsl;
    private JsonDslDefinition validateDsl;
    private ProcessingContext context;

    @BeforeEach
    void setUp() {
        context = new ProcessingContext("test");

        // 创建生成 DSL
        generateDsl = new JsonDslDefinition("test-generate", JsonDslDefinition.DslType.GENERATE);
        generateDsl.setContext(new JsonDslContext("com.xa.mass.base.model.UserRef", 1));
        generateDsl.setFieldDsl(Collections.singletonMap("name", "test"));

        // 创建过滤 DSL
        filterDsl = new JsonDslDefinition("test-filter", JsonDslDefinition.DslType.FILTER);
        filterDsl.setFieldDsl(Collections.singletonMap("status", "active"));

        // 创建转换 DSL
        transformDsl = new JsonDslDefinition("test-transform", JsonDslDefinition.DslType.TRANSFORM);
        transformDsl.setFieldDsl(Collections.singletonMap("name", "transformed"));

        // 创建校验 DSL
        validateDsl = new JsonDslDefinition("test-validate", JsonDslDefinition.DslType.VALIDATE);
        validateDsl.setFieldDsl(Collections.singletonMap("age", "> 18"));
    }

    @Test
    void testNotNull() {
        // 正常情况
        ParameterValidator.notNull("test", "testParam");

        // 异常情况
        JsonDslException exception = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.notNull(null, "nullParam");
        });
        assertEquals("nullParam cannot be null", exception.getMessage());
    }

    @Test
    void testNotBlank() {
        // 正常情况
        ParameterValidator.notBlank("test", "testParam");

        // 异常情况 - null
        JsonDslException exception1 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.notBlank(null, "nullParam");
        });
        assertEquals("nullParam cannot be null", exception1.getMessage());

        // 异常情况 - 空字符串
        JsonDslException exception2 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.notBlank("", "emptyParam");
        });
        assertEquals("emptyParam cannot be blank", exception2.getMessage());

        // 异常情况 - 空白字符串
        JsonDslException exception3 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.notBlank("   ", "blankParam");
        });
        assertEquals("blankParam cannot be blank", exception3.getMessage());
    }

    @Test
    void testNotEmpty() {
        List<String> list = Arrays.asList("a", "b", "c");

        // 正常情况
        ParameterValidator.notEmpty(list, "testList");

        // 异常情况 - null
        JsonDslException exception1 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.notEmpty(null, "nullList");
        });
        assertEquals("nullList cannot be null", exception1.getMessage());

        // 异常情况 - 空列表
        JsonDslException exception2 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.notEmpty(Collections.<String>emptyList(), "emptyList");
        });
        assertEquals("emptyList cannot be empty", exception2.getMessage());
    }

    @Test
    void testValidateDslType() {
        // 正常情况
        ParameterValidator.validateDslType(generateDsl, JsonDslDefinition.DslType.GENERATE);

        // 异常情况 - 类型不匹配
        JsonDslException exception = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateDslType(generateDsl, JsonDslDefinition.DslType.FILTER);
        });
        assertTrue(exception.getMessage().contains("DSL type must be filter"));
    }

    @Test
    void testValidateDslField() {
        // 正常情况
        ParameterValidator.validateDslField(filterDsl, "fieldDsl");
        ParameterValidator.validateDslField(generateDsl, "context");
        ParameterValidator.validateDslField(generateDsl, "context.model");

        // 异常情况 - fieldDsl 为空
        JsonDslDefinition emptyFieldDsl = new JsonDslDefinition("test", JsonDslDefinition.DslType.FILTER);
        JsonDslException exception1 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateDslField(emptyFieldDsl, "fieldDsl");
        });
        assertEquals("fieldDsl must not be empty", exception1.getMessage());

        // 异常情况 - context 为空
        JsonDslDefinition emptyContext = new JsonDslDefinition("test", JsonDslDefinition.DslType.GENERATE);
        JsonDslException exception2 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateDslField(emptyContext, "context");
        });
        assertEquals("context must not be null", exception2.getMessage());

        // 异常情况 - context.model 为空
        JsonDslDefinition emptyModel = new JsonDslDefinition("test", JsonDslDefinition.DslType.GENERATE);
        emptyModel.setContext(new JsonDslContext());
        JsonDslException exception3 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateDslField(emptyModel, "context.model");
        });
        assertEquals("context.model must not be empty", exception3.getMessage());
    }

    @Test
    void testValidateGenerateParams() {
        // 正常情况
        ParameterValidator.validateGenerateParams(generateDsl, context, String.class);

        // 异常情况 - definition 为空
        JsonDslException exception1 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateGenerateParams(null, context, String.class);
        });
        assertEquals("definition cannot be null", exception1.getMessage());

        // 异常情况 - context 为空
        JsonDslException exception2 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateGenerateParams(generateDsl, null, String.class);
        });
        assertEquals("context cannot be null", exception2.getMessage());

        // 异常情况 - targetType 为空
        JsonDslException exception3 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateGenerateParams(generateDsl, context, null);
        });
        assertEquals("targetType cannot be null", exception3.getMessage());

        // 异常情况 - 类型不匹配
        JsonDslException exception4 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateGenerateParams(filterDsl, context, String.class);
        });
        assertTrue(exception4.getMessage().contains("DSL type must be generate"));
    }

    @Test
    void testValidateFilterParams() {
        List<String> data = Arrays.asList("a", "b", "c");

        // 正常情况
        ParameterValidator.validateFilterParams(data, filterDsl, context);

        // 异常情况 - data 为空
        JsonDslException exception1 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateFilterParams(null, filterDsl, context);
        });
        assertEquals("input data cannot be null", exception1.getMessage());

        // 异常情况 - 类型不匹配
        JsonDslException exception2 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateFilterParams(data, generateDsl, context);
        });
        assertTrue(exception2.getMessage().contains("DSL type must be filter"));
    }

    @Test
    void testValidateFilterParamsWithCombineDslOnly() {
        List<String> data = Arrays.asList("a", "b", "c");
        JsonDslDefinition combineOnlyFilterDsl = new JsonDslDefinition("combine-only", JsonDslDefinition.DslType.FILTER);
        combineOnlyFilterDsl.setCombineDsl(Collections.singletonMap("logic", "age > 18"));

        ParameterValidator.validateFilterParams(data, combineOnlyFilterDsl, context);
    }

    @Test
    void testValidateTransformParams() {
        // 正常情况
        ParameterValidator.validateTransformParams("test", transformDsl, context);

        // 异常情况 - input 为空
        JsonDslException exception1 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateTransformParams(null, transformDsl, context);
        });
        assertEquals("input object cannot be null", exception1.getMessage());

        // 异常情况 - 类型不匹配
        JsonDslException exception2 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateTransformParams("test", generateDsl, context);
        });
        assertTrue(exception2.getMessage().contains("DSL type must be transform"));
    }

    @Test
    void testValidateValidateParams() {
        // 正常情况
        ParameterValidator.validateValidateParams("test", validateDsl, context);

        // 异常情况 - input 为空
        JsonDslException exception1 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateValidateParams(null, validateDsl, context);
        });
        assertEquals("input object cannot be null", exception1.getMessage());

        // 异常情况 - 类型不匹配
        JsonDslException exception2 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.validateValidateParams("test", generateDsl, context);
        });
        assertTrue(exception2.getMessage().contains("DSL type must be validate"));
    }

    @Test
    void testIsTrue() {
        // 正常情况
        ParameterValidator.isTrue(true, "should not throw");

        // 异常情况
        JsonDslException exception = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.isTrue(false, "test message");
        });
        assertEquals("test message", exception.getMessage());
    }

    @Test
    void testGreaterThanOrEqual() {
        // 正常情况
        ParameterValidator.greaterThanOrEqual(10, 5, "test");
        ParameterValidator.greaterThanOrEqual(5, 5, "test");

        // 异常情况
        JsonDslException exception = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.greaterThanOrEqual(3, 5, "test");
        });
        assertEquals("test must be greater than or equal to 5", exception.getMessage());
    }

    @Test
    void testInRange() {
        // 正常情况
        ParameterValidator.inRange(5, 1, 10, "test");
        ParameterValidator.inRange(1, 1, 10, "test");
        ParameterValidator.inRange(10, 1, 10, "test");

        // 异常情况 - 小于最小值
        JsonDslException exception1 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.inRange(0, 1, 10, "test");
        });
        assertEquals("test must be between 1.0 and 10.0", exception1.getMessage());

        // 异常情况 - 大于最大值
        JsonDslException exception2 = assertThrows(JsonDslException.class, () -> {
            ParameterValidator.inRange(11, 1, 10, "test");
        });
        assertEquals("test must be between 1.0 and 10.0", exception2.getMessage());
    }
} 
