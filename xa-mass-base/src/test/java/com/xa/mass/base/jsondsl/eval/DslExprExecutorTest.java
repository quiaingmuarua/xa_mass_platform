package com.xa.mass.base.jsondsl.eval;

import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.TemplateValueResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DslExprExecutorTest {

    private QLExpressEngine qlEngine;

    @BeforeEach
    void setUp() {
        qlEngine = (QLExpressEngine) ExpressionEngineRegistry.get("ql");
        qlEngine.resetCompileCount();
        DslExprExecutor.clearCompiledCache();
    }

    @Test
    void shouldReuseCompiledExpressionForSameExpr() throws Exception {
        Map<String, Object> context = Map.of("age", 25);

        Object first = DslExprExecutor.execute("age >= 18", context);
        Object second = DslExprExecutor.execute(" age >= 18 ", context);

        assertEquals(true, first);
        assertEquals(true, second);
        assertEquals(1, qlEngine.getCompileCount());
        assertEquals(1, DslExprExecutor.getCompiledCacheSize());
    }

    @Test
    void shouldNotShareCacheAcrossDifferentExpressions() throws Exception {
        Map<String, Object> context = Map.of("age", 25);

        DslExprExecutor.execute("age >= 18", context);
        DslExprExecutor.execute("age >= 21", context);

        assertEquals(2, qlEngine.getCompileCount());
        assertEquals(2, DslExprExecutor.getCompiledCacheSize());
    }

    @Test
    void shouldNotCacheCompileFailures() {
        Map<String, Object> context = Map.of("age", 25);

        assertThrows(Exception.class, () -> DslExprExecutor.execute("age >>>", context));
        assertThrows(Exception.class, () -> DslExprExecutor.execute("age >>>", context));

        assertTrue(qlEngine.getCompileCount() >= 2);
        assertEquals(0, DslExprExecutor.getCompiledCacheSize());
    }

    @Test
    void shouldKeepExprShorthandAsCompatibilityOnlyPath() {
        DslContext context = new DslContext();
        context.setVariable("age", 25);

        Object result = TemplateValueResolver.resolve("$EXPR(age >= 18)", context);

        assertEquals(true, result);
        assertEquals(1, qlEngine.getCompileCount());
    }
}
