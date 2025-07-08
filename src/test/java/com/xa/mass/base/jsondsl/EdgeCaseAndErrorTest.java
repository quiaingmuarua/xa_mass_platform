package com.xa.mass.base.jsondsl;

import com.xa.mass.base.jsondsl.eval.EvalException;
import com.xa.mass.base.jsondsl.generate.JsonDslEngine;
import com.xa.mass.base.jsondsl.model.DslContext;
import com.xa.mass.base.jsondsl.processor.BuiltinFunctions;
import com.xa.mass.base.jsondsl.processor.TemplateValueResolver;
import com.xa.mass.base.jsondsl.processor.TemplateValueResolverException;
import com.xa.mass.base.jsondsl.processor.VariableResolver;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class EdgeCaseAndErrorTest {
    @Test
    public void testNullInput() {
        JsonDslEngine engine = new JsonDslEngine();
        Object result = engine.generate(null);
        assertNull(result);
    }

    @Test
    public void testEmptyMap() {
        JsonDslEngine engine = new JsonDslEngine();
        Object result = engine.generate(Collections.emptyMap());
        assertTrue(result instanceof Map);
        assertTrue(((Map<?, ?>) result).isEmpty());
    }

    @Test
    public void testInvalidExprSyntax() {
        JsonDslEngine engine = new JsonDslEngine();
        Map<String, Object> dsl = new HashMap<>();
        dsl.put("a", Collections.singletonMap("$EXPR", "1+"));
        boolean exceptionThrown = false;
        try {
            engine.generate(dsl);
        } catch (Exception e) {
            exceptionThrown = true;
            // 允许不同实现抛出不同异常
        }
        assertTrue(exceptionThrown);
    }

    @Test
    public void testBuiltinFunctionReturnsNull() {
        // $RANGE(1,0) 应返回 null 或抛异常，均可接受
        Map<String, Object> dsl = new HashMap<>();
        dsl.put("a", Collections.singletonMap("$RANGE", new Object[]{1, 0}));
        JsonDslEngine engine = new JsonDslEngine();
        Object result = null;
        boolean exceptionThrown = false;
        try {
            result = engine.generate(dsl);
        } catch (Exception e) {
            exceptionThrown = true;
        }
        if (!exceptionThrown) {
            assertTrue(result instanceof Map);
            Object val = ((Map<?, ?>) result).get("a");
            assertNull(val);
        }
    }

    @Test
    public void testTemplateValueResolverInvalidFunction() {
        // TemplateValueResolver 处理未注册函数
        Map<String, Object> dsl = new HashMap<>();
        dsl.put("a", Collections.singletonMap("$NOT_A_FUNCTION", new Object[]{}));
        DslContext ctx = new DslContext();
        boolean exceptionThrown = false;
        try {
            TemplateValueResolver.resolve(dsl.get("a"), ctx, VariableResolver.EMPTY);
        } catch (TemplateValueResolverException e) {
            exceptionThrown = true;
        }
        assertTrue(exceptionThrown);
    }

    @Test
    public void testTemplateValueResolverInvalidExpr() {
        // TemplateValueResolver 处理非法表达式
        Map<String, Object> dsl = new HashMap<>();
        dsl.put("a", Collections.singletonMap("$EXPR", "1+"));
        DslContext ctx = new DslContext();
        boolean exceptionThrown = false;
        try {
            TemplateValueResolver.resolve(dsl.get("a"), ctx, VariableResolver.EMPTY);
        } catch (EvalException e) {
            exceptionThrown = true;
        }
        assertTrue(exceptionThrown);
    }

    @Test
    public void testBuiltinFunctionNullArgs() {
        // $RANGE(null, null) 应返回 null 或抛异常
        Object result = null;
        boolean exceptionThrown = false;
        try {
            result = BuiltinFunctions.range(null, null);
        } catch (Exception e) {
            exceptionThrown = true;
        }
        if (!exceptionThrown) {
            assertNull(result);
        }
    }
} 