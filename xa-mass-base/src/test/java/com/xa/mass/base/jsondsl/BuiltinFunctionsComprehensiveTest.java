package com.xa.mass.base.jsondsl;

import com.xa.mass.base.jsondsl.builtin.BuiltinFunctions;
import com.xa.mass.base.jsondsl.builtin.DslContext;
import com.xa.mass.base.jsondsl.builtin.TemplateValueResolver;
import com.xa.mass.base.jsondsl.eval.DslExprExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置函数全面测试
 * 测试所有内置函数和表达式引擎的各种用法
 */
public class BuiltinFunctionsComprehensiveTest {
    
    private DslContext context;
    
    @BeforeEach
    void setUp() {
        // 强制触发 BuiltinFunctions 的 static 块，确保所有内置函数注册
        try {
            Class.forName("com.xa.mass.base.jsondsl.builtin.BuiltinFunctions");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        
        context = new DslContext();
        context.setScopeName("TestScope");
        context.setVariable("&TestScope.index", 1);
        context.setVariable("&TestScope.name", "TestUser");
        context.setVariable("&TestScope.age", 25);
    }
    
    @Test
    void testChoiceFunction() {
        // 测试 $CHOICE 函数
        Map<String, Object> choiceConfig = new HashMap<>();
        choiceConfig.put("$CHOICE", List.of("Alice", "Bob", "Charlie"));
        
        Object result = TemplateValueResolver.resolve(choiceConfig, context);
        assertNotNull(result);
        assertTrue(result instanceof String);
        assertTrue(List.of("Alice", "Bob", "Charlie").contains(result));
        
        // 测试空列表
        Map<String, Object> emptyChoiceConfig = new HashMap<>();
        emptyChoiceConfig.put("$CHOICE", List.of());
        
        Object emptyResult = TemplateValueResolver.resolve(emptyChoiceConfig, context);
        assertNull(emptyResult);
    }
    
    @Test
    void testRangeFunction() {
        // 测试 $RANGE 函数
        Map<String, Object> rangeConfig = new HashMap<>();
        rangeConfig.put("$RANGE", List.of(10, 20));
        
        Object result = TemplateValueResolver.resolve(rangeConfig, context);
        assertNotNull(result);
        assertTrue(result instanceof Number);
        
        int value = ((Number) result).intValue();
        assertTrue(value >= 10 && value <= 20);
        
        // 测试边界情况
        Map<String, Object> singleValueConfig = new HashMap<>();
        singleValueConfig.put("$RANGE", List.of(5, 5));
        
        Object singleResult = TemplateValueResolver.resolve(singleValueConfig, context);
        assertEquals(5, ((Number) singleResult).intValue());
    }
    
    @Test
    void testJoinFunction() {
        // 测试 $JOIN 函数
        Map<String, Object> joinConfig = new HashMap<>();
        joinConfig.put("$JOIN", List.of("Hello", " ", "World"));
        
        Object result = TemplateValueResolver.resolve(joinConfig, context);
        assertNotNull(result);
        assertTrue(result instanceof String);
        assertEquals("Hello World", result);
        
        // 测试包含变量的 JOIN
        Map<String, Object> joinWithVarConfig = new HashMap<>();
        joinWithVarConfig.put("$JOIN", List.of("User-", "&TestScope.index"));
        
        Object varResult = TemplateValueResolver.resolve(joinWithVarConfig, context);
        assertNotNull(varResult);
        assertTrue(varResult instanceof String);
        assertEquals("User-1", varResult);
    }
    
    @Test
    void testUuidFunction() {
        // 测试 $UUID 函数
        Map<String, Object> uuidConfig = new HashMap<>();
        uuidConfig.put("$UUID", null);
        
        Object result = TemplateValueResolver.resolve(uuidConfig, context);
        assertNotNull(result);
        assertTrue(result instanceof String);
        
        String uuid = (String) result;
        assertTrue(uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }
    
    @Test
    void testRandomFunction() {
        // 测试 $RANDOM 函数
        Map<String, Object> randomConfig = new HashMap<>();
        randomConfig.put("$RANDOM", null);
        
        Object result = TemplateValueResolver.resolve(randomConfig, context);
        assertNotNull(result);
        assertTrue(result instanceof Number);
        
        int randomValue = ((Number) result).intValue();
        // 随机值应该在合理范围内
        assertTrue(randomValue >= Integer.MIN_VALUE && randomValue <= Integer.MAX_VALUE);
    }
    
    @Test
    void testContextFunction() {
        // 测试 $CONTEXT 函数
        Map<String, Object> contextConfig = new HashMap<>();
        contextConfig.put("$CONTEXT", "&TestScope.name");
        
        Object result = TemplateValueResolver.resolve(contextConfig, context);
        assertNotNull(result);
        assertEquals("TestUser", result);
        
        // 测试获取年龄
        Map<String, Object> ageConfig = new HashMap<>();
        ageConfig.put("$CONTEXT", "&TestScope.age");
        
        Object ageResult = TemplateValueResolver.resolve(ageConfig, context);
        assertNotNull(ageResult);
        assertEquals(25, ageResult);
    }
    
    @Test
    void testNowFunction() {
        // 测试 $NOW 函数
        Map<String, Object> nowConfig = new HashMap<>();
        nowConfig.put("$NOW", "yyyy-MM-dd");
        
        Object result = TemplateValueResolver.resolve(nowConfig, context);
        assertNotNull(result);
        assertTrue(result instanceof String);
        
        String dateStr = (String) result;
        assertTrue(dateStr.matches("\\d{4}-\\d{2}-\\d{2}"));
        
        // 测试不带格式的 NOW
        Map<String, Object> nowWithoutFormatConfig = new HashMap<>();
        nowWithoutFormatConfig.put("$NOW", null);
        
        Object nowResult = TemplateValueResolver.resolve(nowWithoutFormatConfig, context);
        assertNotNull(nowResult);
        // 应该返回 LocalDateTime 对象
        assertTrue(nowResult.toString().contains("T"));
    }
    
    @Test
    void testTimeRangeFunction() {
        // 测试 $TIME_RANGE 函数
        Map<String, Object> timeRangeConfig = new HashMap<>();
        timeRangeConfig.put("$TIME_RANGE", List.of("2024-01-01 00:00:00", "2024-01-02 00:00:00", "HOURS", "yyyy-MM-dd HH:mm:ss"));
        
        Object result = TemplateValueResolver.resolve(timeRangeConfig, context);
        assertNotNull(result);
        assertTrue(result instanceof String);
        
        String timeStr = (String) result;
        assertTrue(timeStr.matches("2024-01-0[12] \\d{2}:\\d{2}:\\d{2}"));
        
        // 测试相对时间
        Map<String, Object> relativeTimeConfig = new HashMap<>();
        relativeTimeConfig.put("$TIME_RANGE", List.of("now-1h", "now", "MINUTES"));
        
        Object relativeResult = TemplateValueResolver.resolve(relativeTimeConfig, context);
        assertNotNull(relativeResult);
        // 应该返回 LocalDateTime 对象
        assertTrue(relativeResult.toString().contains("T"));
    }
    
    @Test
    void testExpressionEngine() {
        // 测试 $EXPR 表达式引擎
        Map<String, Object> exprConfig = new HashMap<>();
        exprConfig.put("$EXPR", "age > 20 && name == 'TestUser'");
        
        // 设置表达式上下文
        Map<String, Object> exprContext = new HashMap<>();
        exprContext.put("age", 25);
        exprContext.put("name", "TestUser");
        
        try {
            Object result = DslExprExecutor.execute(exprConfig.get("$EXPR"), exprContext);
            assertNotNull(result);
            assertTrue(result instanceof Boolean);
            assertTrue((Boolean) result);
        } catch (Exception e) {
            fail("表达式执行失败: " + e.getMessage());
        }
    }
    
    @Test
    void testComplexExpression() {
        // 测试复杂表达式
        Map<String, Object> exprContext = new HashMap<>();
        exprContext.put("age", 30);
        exprContext.put("score", 85);
        exprContext.put("status", "active");
        exprContext.put("department", "IT");
        
        try {
            // 复杂条件表达式
            String complexExpr = "age >= 25 && score >= 80 && status == 'active' && (department == 'IT' || department == 'Finance')";
            Object result = DslExprExecutor.execute(complexExpr, exprContext);
            assertNotNull(result);
            assertTrue(result instanceof Boolean);
            assertTrue((Boolean) result);
            
            // 使用内置函数的表达式
            String builtinExpr = "range(1, 10) > 5";
            Object builtinResult = DslExprExecutor.execute(builtinExpr, exprContext);
            assertNotNull(builtinResult);
            assertTrue(builtinResult instanceof Boolean);
            
        } catch (Exception e) {
            fail("复杂表达式执行失败: " + e.getMessage());
        }
    }
    
    @Test
    void testNestedFunctions() {
        // 测试嵌套函数调用
        Map<String, Object> nestedConfig = new HashMap<>();
        nestedConfig.put("$JOIN", List.of("User-", "&TestScope.index", "-", "$RANGE(100, 999)"));
        
        Object result = TemplateValueResolver.resolve(nestedConfig, context);
        assertNotNull(result);
        assertTrue(result instanceof String);
        
        String resultStr = (String) result;
        assertTrue(resultStr.startsWith("User-1-"));
        assertTrue(resultStr.length() > 8); // 至少包含数字部分
    }
    
    @Test
    void testVariableResolution() {
        // 测试变量解析
        Map<String, Object> varConfig = new HashMap<>();
        varConfig.put("$JOIN", List.of("&TestScope.name", " is ", "&TestScope.age", " years old"));
        
        Object result = TemplateValueResolver.resolve(varConfig, context);
        assertNotNull(result);
        assertTrue(result instanceof String);
        assertEquals("TestUser is 25 years old", result);
    }
    
    @Test
    void testBuiltinFunctionsEval() {
        // 测试 BuiltinFunctions.eval 方法
        Object choiceResult = BuiltinFunctions.eval("$CHOICE", List.of("A", "B", "C"));
        assertNotNull(choiceResult);
        assertTrue(List.of("A", "B", "C").contains(choiceResult));
        
        Object rangeResult = BuiltinFunctions.eval("$RANGE", List.of(1, 5));
        assertNotNull(rangeResult);
        assertTrue(rangeResult instanceof Number);
        
        int rangeValue = ((Number) rangeResult).intValue();
        assertTrue(rangeValue >= 1 && rangeValue <= 5);
        
        Object uuidResult = BuiltinFunctions.eval("$UUID", null);
        assertNotNull(uuidResult);
        assertTrue(uuidResult instanceof String);
    }
    
    @Test
    void testErrorHandling() {
        // 测试错误处理
        
        // 1. 无效的函数名
        assertThrows(Exception.class, () -> {
            BuiltinFunctions.eval("$INVALID_FUNCTION", null);
        });
        
        // 2. 无效的参数
        assertThrows(Exception.class, () -> {
            BuiltinFunctions.eval("$RANGE", List.of("invalid", "parameters"));
        });
        
        // 3. 空函数名
        assertThrows(Exception.class, () -> {
            BuiltinFunctions.eval(null, null);
        });
    }
    
    @Test
    void testPerformance() {
        // 测试性能（生成大量数据）
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < 1000; i++) {
            Map<String, Object> config = new HashMap<>();
            config.put("$RANGE", List.of(1, 100));
            
            Object result = TemplateValueResolver.resolve(config, context);
            assertNotNull(result);
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        System.out.println("生成1000个随机数耗时: " + duration + "ms");
        assertTrue(duration < 5000, "性能测试应该在5秒内完成");
    }
    
    @Test
    void testFunctionAliases() {
        // 测试函数别名
        Object choiceResult1 = BuiltinFunctions.eval("$CHOICE", List.of("A", "B"));
        Object choiceResult2 = BuiltinFunctions.eval("choice", List.of("A", "B"));
        
        assertNotNull(choiceResult1);
        assertNotNull(choiceResult2);
        assertTrue(List.of("A", "B").contains(choiceResult1));
        assertTrue(List.of("A", "B").contains(choiceResult2));
        
        Object rangeResult1 = BuiltinFunctions.eval("$RANGE", List.of(1, 3));
        Object rangeResult2 = BuiltinFunctions.eval("range", List.of(1, 3));
        
        assertNotNull(rangeResult1);
        assertNotNull(rangeResult2);
        assertTrue(rangeResult1 instanceof Number);
        assertTrue(rangeResult2 instanceof Number);
    }
} 