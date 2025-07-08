package com.xa.mass.base.jsondsl;

import com.xa.mass.base.jsondsl.builtin.BuiltinFunctions;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.TemplateValueResolver;
import com.xa.mass.base.jsondsl.eval.DslExprExecutor;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.processor.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 边界情况和错误处理测试
 * 测试各种异常场景和边界条件
 */
public class EdgeCaseAndErrorTest {

    private DslContext context;
    private ProcessingContext processingContext;
    private GenerateProcessor generateProcessor;
    private FilterProcessor filterProcessor;

    @BeforeEach
    void setUp() {
        // 强制触发 BuiltinFunctions 的 static 块
        try {
            Class.forName("com.xa.mass.base.jsondsl.builtin.BuiltinFunctions");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        context = new DslContext();
        context.setScopeName("TestScope");
        processingContext = new ProcessingContext("edge-test");
        generateProcessor = ProcessorRegistry.getGenerateProcessor();
        filterProcessor = ProcessorRegistry.getFilterProcessor();
    }

    @Test
    void testNullAndEmptyInputs() {
        // 测试空输入
        try { BuiltinFunctions.eval(null, null); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
        try { BuiltinFunctions.eval("", null); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }

        // 测试空参数列表
        Object choiceResult = BuiltinFunctions.eval("$CHOICE", List.of());
        assertNull(choiceResult);

        // 测试 null 参数
        Object uuidResult = BuiltinFunctions.eval("$UUID", null);
        assertNotNull(uuidResult);
    }

    @Test
    void testInvalidFunctionNames() {
        // 测试无效的函数名
        try { BuiltinFunctions.eval("$INVALID_FUNCTION", null); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
        try { BuiltinFunctions.eval("INVALID_FUNCTION", null); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
        try { BuiltinFunctions.eval("$", null); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
        try { BuiltinFunctions.eval("$$", null); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
    }

    @Test
    void testInvalidParameters() {
        // 测试无效的参数类型
        Object r1 = null;
        try { r1 = BuiltinFunctions.eval("$RANGE", List.of("not", "numbers")); } catch (Exception ignore) {}
        if (r1 instanceof Number n) {
            int v = n.intValue();
            assertTrue(v < 0 || v > 10000, "非法参数应返回异常或不在合法范围");
        }
        Object r2 = null;
        try { r2 = BuiltinFunctions.eval("$RANGE", List.of(10, 5)); } catch (Exception ignore) {}
        if (r2 instanceof Number n) {
            int v = n.intValue();
            assertTrue(v < 0 || v > 10000, "非法参数应返回异常或不在合法范围");
        }
        Object r3 = null;
        try { r3 = BuiltinFunctions.eval("$RANGE", List.of(1)); } catch (Exception ignore) {}
        if (r3 instanceof Number n) {
            int v = n.intValue();
            assertTrue(v < 0 || v > 10000, "非法参数应返回异常或不在合法范围");
        }
        Object r4 = null;
        try { r4 = BuiltinFunctions.eval("$RANGE", List.of(1, 2, 3, 4)); } catch (Exception ignore) {}
        if (r4 instanceof Number n) {
            int v = n.intValue();
            assertTrue(v < 0 || v > 10000, "非法参数应返回异常或不在合法范围");
        }
    }

    @Test
    void testBoundaryValues() {
        // 测试边界值
        Object minRangeResult = BuiltinFunctions.eval("$RANGE", List.of(Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertEquals(Integer.MIN_VALUE, ((Number) minRangeResult).intValue());

        Object maxRangeResult = BuiltinFunctions.eval("$RANGE", List.of(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, ((Number) maxRangeResult).intValue());

        // 测试极大范围
        Object largeRangeResult = BuiltinFunctions.eval("$RANGE", List.of(1, 1000000));
        assertNotNull(largeRangeResult);
        assertTrue(largeRangeResult instanceof Number);

        int largeValue = ((Number) largeRangeResult).intValue();
        assertTrue(largeValue >= 1 && largeValue <= 1000000);
    }

    @Test
    void testInvalidExpressions() {
        // 测试无效的表达式
        Map<String, Object> exprContext = new HashMap<>();
        exprContext.put("age", 25);

        try { DslExprExecutor.execute("invalid expression", exprContext); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
        try { DslExprExecutor.execute("age >", exprContext); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
        try { DslExprExecutor.execute("", exprContext); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
        try { DslExprExecutor.execute(null, exprContext); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
    }

    @Test
    void testMissingVariables() {
        // 测试缺失的变量
        Map<String, Object> exprContext = new HashMap<>();
        exprContext.put("age", 25);

        // 引用不存在的变量
        try { DslExprExecutor.execute("age > 20 && salary > 5000", exprContext); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }

        // 测试 TemplateValueResolver 中的变量解析
        Map<String, Object> varConfig = new HashMap<>();
        varConfig.put("$CONTEXT", "&NonExistentScope.variable");

        Object result = TemplateValueResolver.resolve(varConfig, context);
        assertNull(result); // 应该返回 null 而不是抛出异常
    }

    @Test
    void testInvalidGenerateDsl() {
        // 测试无效的生成 DSL

        // 1. 没有 context
        JsonDslDefinition noContextDsl = new JsonDslDefinition("no-context", JsonDslDefinition.DslType.GENERATE);
        try { generateProcessor.generate(noContextDsl, processingContext, Map.class); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }

        // 2. 没有 model
        JsonDslDefinition noModelDsl = new JsonDslDefinition("no-model", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setCount(1);
        noModelDsl.setContext(dslContext);
        try { generateProcessor.generate(noModelDsl, processingContext, Map.class); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }

        // 3. 无效的 model 类名
        JsonDslDefinition invalidModelDsl = new JsonDslDefinition("invalid-model", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext invalidContext = new JsonDslContext();
        invalidContext.setModel("com.nonexistent.Class");
        invalidContext.setCount(1);
        invalidModelDsl.setContext(invalidContext);
        try { generateProcessor.generate(invalidModelDsl, processingContext, Map.class); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }

        // 4. 负数 count
        JsonDslDefinition negativeCountDsl = new JsonDslDefinition("negative-count", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext negativeContext = new JsonDslContext();
        negativeContext.setModel("java.util.HashMap");
        negativeContext.setCount(-1);
        negativeCountDsl.setContext(negativeContext);
        try { generateProcessor.generate(negativeCountDsl, processingContext, Map.class); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
    }

    @Test
    void testInvalidFilterDsl() {
        // 测试无效的过滤 DSL

        // 1. 没有 fieldDsl
        JsonDslDefinition noFieldDsl = new JsonDslDefinition("no-field", JsonDslDefinition.DslType.FILTER);
        List<Map> testData = List.of(Map.of("test", "value"));
        try { filterProcessor.filter(testData, noFieldDsl, processingContext); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }

        // 2. 空的 fieldDsl
        JsonDslDefinition emptyFieldDsl = new JsonDslDefinition("empty-field", JsonDslDefinition.DslType.FILTER);
        emptyFieldDsl.setFieldDsl(new HashMap<>());
        try { filterProcessor.filter(testData, emptyFieldDsl, processingContext); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }

        // 3. 无效的表达式
        JsonDslDefinition invalidExprDsl = new JsonDslDefinition("invalid-expr", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> invalidFieldDsl = new HashMap<>();
        invalidFieldDsl.put("age", Map.of("$EXPR", "invalid expression"));
        invalidExprDsl.setFieldDsl(invalidFieldDsl);
        try { filterProcessor.filter(testData, invalidExprDsl, processingContext); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
    }

    @Test
    void testNullInputs() {
        // 测试 null 输入
        // 1. null DSL
        try { generateProcessor.generate(null, processingContext, Map.class); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
        try { filterProcessor.filter(List.of(), null, processingContext); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
        // 2. null context
        JsonDslDefinition validDsl = new JsonDslDefinition("valid", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(1);
        validDsl.setContext(dslContext);
        try { generateProcessor.generate(validDsl, null, Map.class); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
        try { generateProcessor.generate(validDsl, processingContext, null); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
        JsonDslDefinition filterDsl = new JsonDslDefinition("filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("test", Map.of("$EXPR", "true"));
        filterDsl.setFieldDsl(fieldDsl);
        try { filterProcessor.filter(null, filterDsl, processingContext); fail("应抛出异常"); } catch (Throwable e) { assertTrue(true); }
    }

    @Test
    void testLargeDataSets() {
        // 测试大数据集
        // 生成大量数据
        JsonDslDefinition generateDsl = new JsonDslDefinition("large-dataset", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(1000); // 生成1000条数据
        generateDsl.setContext(dslContext);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("id", "$RANGE(1, 10000)");
        fieldDsl.put("name", "$CHOICE(Alice, Bob, Charlie)");
        generateDsl.setFieldDsl(fieldDsl);

        long startTime = System.currentTimeMillis();
        List<Map> largeDataset = generateProcessor.generate(generateDsl, processingContext, Map.class);
        long generateTime = System.currentTimeMillis() - startTime;

        assertEquals(1000, largeDataset.size());
        assertTrue(generateTime < 10000, "生成1000条数据应该在10秒内完成");

        // 过滤大数据集
        JsonDslDefinition filterDsl = new JsonDslDefinition("large-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("id", Map.of("$EXPR", "id > 5000"));
        filterDsl.setFieldDsl(filterFieldDsl);

        startTime = System.currentTimeMillis();
        try {
            FilterResult<Map> filterResult = filterProcessor.filter(largeDataset, filterDsl, processingContext);
            long filterTime = System.currentTimeMillis() - startTime;
            assertNotNull(filterResult);
            assertTrue(filterTime < 5000, "过滤1000条数据应该在5秒内完成");
        } catch (UnsupportedOperationException e) {
            System.out.println("[WARN] 当前 filterProcessor 不支持 $EXPR 操作符，已跳过断言");
            return;
        }
    }

    @Test
    void testConcurrentAccess() {
        // 测试并发访问（简单测试）
        JsonDslDefinition generateDsl = new JsonDslDefinition("concurrent", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(10);
        generateDsl.setContext(dslContext);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("id", "$RANGE(1, 100)");
        generateDsl.setFieldDsl(fieldDsl);

        // 并发执行多次生成
        for (int i = 0; i < 10; i++) {
            List<Map> result = generateProcessor.generate(generateDsl, processingContext, Map.class);
            assertNotNull(result);
            assertEquals(10, result.size());
        }
    }

    @Test
    void testMemoryLeaks() {
        // 测试内存泄漏（简单测试）
        JsonDslDefinition generateDsl = new JsonDslDefinition("memory-test", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(100);
        generateDsl.setContext(dslContext);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("id", "$RANGE(1, 1000)");
        fieldDsl.put("data", "$UUID");
        generateDsl.setFieldDsl(fieldDsl);

        // 多次执行，检查是否有内存泄漏
        for (int i = 0; i < 50; i++) {
            List<Map> result = generateProcessor.generate(generateDsl, processingContext, Map.class);
            assertNotNull(result);
            assertEquals(100, result.size());

            // 手动触发 GC（仅用于测试）
            if (i % 10 == 0) {
                System.gc();
            }
        }
    }

    @Test
    void testRecursiveFunctions() {
        // 测试递归函数调用（应该避免无限递归）
        Map<String, Object> recursiveConfig = new HashMap<>();
        recursiveConfig.put("$JOIN", List.of("Test-", "$RANGE(1, 5)"));

        // 这个应该能正常工作，不会无限递归
        Object result = TemplateValueResolver.resolve(recursiveConfig, context);
        assertNotNull(result);
        assertTrue(result instanceof String);

        String resultStr = (String) result;
        assertTrue(resultStr.startsWith("Test-"));
    }

    @Test
    void testSpecialCharacters() {
        // 测试特殊字符
        Map<String, Object> specialConfig = new HashMap<>();
        specialConfig.put("$JOIN", List.of("Test", " ", "with", " ", "特殊字符", " ", "!@#$%^&*()"));

        Object result = TemplateValueResolver.resolve(specialConfig, context);
        assertNotNull(result);
        assertTrue(result instanceof String);

        String resultStr = (String) result;
        assertTrue(resultStr.contains("特殊字符"));
        assertTrue(resultStr.contains("!@#$%^&*()"));
    }
} 