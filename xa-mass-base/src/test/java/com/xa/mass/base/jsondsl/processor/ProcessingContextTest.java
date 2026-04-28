package com.xa.mass.base.jsondsl.processor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProcessingContext 测试
 */
public class ProcessingContextTest {

    private ProcessingContext context;

    @BeforeEach
    void setUp() {
        context = new ProcessingContext("test-context");
    }

    @Test
    void testDefaultConstructor() {
        ProcessingContext defaultContext = new ProcessingContext();
        assertNotNull(defaultContext);
        assertNull(defaultContext.getScopeName());
        assertFalse(defaultContext.isDebug());
    }

    @Test
    void testParameterOperations() {
        // 测试参数设置和获取
        context.setParameter("key1", "value1");
        context.setParameter("key2", 123);

        assertEquals("value1", context.getParameter("key1"));
        assertEquals(123, context.getParameter("key2"));
        assertNull(context.getParameter("nonexistent"));

        // 测试默认值
        assertEquals("default", context.getParameter("nonexistent", "default"));
        assertEquals(456, context.getParameter("nonexistent", 456));
    }

    @Test
    void testVariableOperations() {
        // 测试变量设置和获取
        context.setVariable("var1", "value1");
        context.setVariable("var2", 123);

        assertEquals("value1", context.getVariable("var1"));
        assertEquals(123, context.getVariable("var2"));
        assertNull(context.getVariable("nonexistent"));

        // 测试默认值
        assertEquals("default", context.getVariable("nonexistent", "default"));
        assertEquals(456, context.getVariable("nonexistent", 456));
    }

    @Test
    void testHasParameter() {
        context.setParameter("key1", "value1");

        assertTrue(context.hasParameter("key1"));
        assertFalse(context.hasParameter("nonexistent"));
    }

    @Test
    void testHasVariable() {
        context.setVariable("var1", "value1");

        assertTrue(context.hasVariable("var1"));
        assertFalse(context.hasVariable("nonexistent"));
    }

    @Test
    void testGetAllParameters() {
        context.setParameter("key1", "value1");
        context.setParameter("key2", "value2");

        Map<String, Object> params = context.getParameters();
        assertEquals(2, params.size());
        assertEquals("value1", params.get("key1"));
        assertEquals("value2", params.get("key2"));

        // 测试返回的是副本，不是原引用
        params.put("key3", "value3");
        assertFalse(context.hasParameter("key3"));
    }

    @Test
    void testGetAllVariables() {
        context.setVariable("var1", "value1");
        context.setVariable("var2", "value2");

        Map<String, Object> vars = context.getVariables();
        assertEquals(2, vars.size());
        assertEquals("value1", vars.get("var1"));
        assertEquals("value2", vars.get("var2"));

        // 测试返回的是副本，不是原引用
        vars.put("var3", "value3");
        assertFalse(context.hasVariable("var3"));
    }

    @Test
    void testDebugMode() {
        assertFalse(context.isDebug());

        context.setDebug(true);
        assertTrue(context.isDebug());

        context.setDebug(false);
        assertFalse(context.isDebug());
    }

    @Test
    void testScopeName() {
        assertEquals("test-context", context.getScopeName());

        context.setScopeName("new-scope");
        assertEquals("new-scope", context.getScopeName());
    }

    @Test
    void testMerge() {
        // 创建另一个上下文
        ProcessingContext other = new ProcessingContext("other-context");
        other.setParameter("key1", "value1");
        other.setVariable("var1", "value1");
        other.setDebug(true);

        // 合并
        context.merge(other);

        // 验证参数和变量被合并
        assertEquals("value1", context.getParameter("key1"));
        assertEquals("value1", context.getVariable("var1"));

        // 验证作用域名称被覆盖
        assertEquals("other-context", context.getScopeName());

        // 验证调试模式被覆盖
        assertTrue(context.isDebug());
    }

    @Test
    void testMergeNull() {
        // 测试合并空上下文
        context.setParameter("key1", "value1");
        context.setVariable("var1", "value1");

        context.merge(null);

        // 验证原有数据不受影响
        assertEquals("value1", context.getParameter("key1"));
        assertEquals("value1", context.getVariable("var1"));
    }

    @Test
    void testCreateChild() {
        context.setParameter("key1", "value1");
        context.setVariable("var1", "value1");
        context.setDebug(true);

        ProcessingContext child = context.createChild("child-context");

        // 验证子上下文继承了父上下文的数据
        assertEquals("value1", child.getParameter("key1"));
        assertEquals("value1", child.getVariable("var1"));
        assertTrue(child.isDebug());

        // 验证作用域名称被设置
        assertEquals("child-context", child.getScopeName());

        // 验证子上下文的修改不影响父上下文
        child.setParameter("key2", "value2");
        assertFalse(context.hasParameter("key2"));
    }

    @Test
    void testCreateChildWithNullScopeName() {
        ProcessingContext child = context.createChild(null);
        assertNull(child.getScopeName());
    }
} 