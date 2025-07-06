package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * GenerateProcessor 测试
 */
public class GenerateProcessorTest {
    
    private GenerateProcessor processor;
    private JsonDslDefinition definition;
    private ProcessingContext context;
    
    @BeforeEach
    void setUp() {
        processor = new GenerateProcessor();
        definition = new JsonDslDefinition("test-generator", JsonDslDefinition.DslType.GENERATE);
        context = new ProcessingContext("test-context");
    }
    
    @Test
    void testSupportsGenerateType() {
        assertTrue(processor.supports(JsonDslDefinition.DslType.GENERATE));
        assertFalse(processor.supports(JsonDslDefinition.DslType.FILTER));
        assertFalse(processor.supports(JsonDslDefinition.DslType.TRANSFORM));
        assertFalse(processor.supports(JsonDslDefinition.DslType.VALIDATE));
    }
    
    @Test
    void testProcessorName() {
        assertEquals("GenerateProcessor", processor.getName());
    }
    
    @Test
    void testProcessorPriority() {
        assertEquals(100, processor.getPriority());
    }
    
    @Test
    void testProcessWithValidDefinition() {
        // 设置有效的 DSL 定义
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.processor.TestUser");
        dslContext.setCount(3);
        definition.setContext(dslContext);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        fieldDsl.put("email", "$RANDOM_EMAIL");
        definition.setFieldDsl(fieldDsl);
        
        // 处理 DSL
        Object result = processor.process(definition, context);
        
        // 验证结果
        assertNotNull(result);
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(3, list.size());
        
        // 验证每个对象都是 TestUser 类型
        for (Object obj : list) {
            assertTrue(obj instanceof TestUser);
            TestUser user = (TestUser) obj;
            assertNotNull(user.getName());
            assertNotNull(user.getEmail());
        }
    }
    
    @Test
    void testProcessWithInvalidDefinition() {
        // 测试没有 context 的 DSL
        assertThrows(com.xa.mass.base.jsondsl.builtin.JsonDslException.class, () -> {
            processor.process(definition, context);
        });
    }
    
    @Test
    void testProcessWithNullModel() {
        // 设置没有 model 的 context
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setCount(3);
        definition.setContext(dslContext);
        
        assertThrows(com.xa.mass.base.jsondsl.builtin.JsonDslException.class, () -> {
            processor.process(definition, context);
        });
    }
    
    @Test
    void testProcessWithInvalidModel() {
        // 设置无效的 model
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.nonexistent.Class");
        dslContext.setCount(1);
        definition.setContext(dslContext);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("value", "$RANDOM_STRING(5)");
        definition.setFieldDsl(fieldDsl);
        
        // 应该回退到 Object 类型
        Object result = processor.process(definition, context);
        assertNotNull(result);
        assertTrue(result instanceof List);
    }
    
    @Test
    void testProcessWithDefaultCount() {
        // 测试默认数量（不设置 count）
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.processor.TestUser");
        definition.setContext(dslContext);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        definition.setFieldDsl(fieldDsl);
        
        Object result = processor.process(definition, context);
        assertNotNull(result);
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(1, list.size()); // 默认数量为 1
    }
    
    @Test
    void testProcessWithDebugMode() {
        // 测试调试模式
        context.setDebug(true);
        
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.processor.TestUser");
        dslContext.setCount(2);
        definition.setContext(dslContext);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        definition.setFieldDsl(fieldDsl);
        
        Object result = processor.process(definition, context);
        assertNotNull(result);
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(2, list.size());
    }
    
    @Test
    void testProcessWithComplexFieldDsl() {
        // 测试复杂的字段 DSL
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.processor.TestUser");
        dslContext.setCount(1);
        definition.setContext(dslContext);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        fieldDsl.put("age", "$RANDOM_INT(18, 65)");
        fieldDsl.put("email", "$RANDOM_EMAIL");
        definition.setFieldDsl(fieldDsl);
        
        Object result = processor.process(definition, context);
        assertNotNull(result);
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(1, list.size());
    }
    
    @Test
    void testProcessWithNullDefinition() {
        assertThrows(IllegalArgumentException.class, () -> {
            processor.process(null, context);
        });
    }
    
    @Test
    void testProcessWithNullContext() {
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.processor.TestUser");
        dslContext.setCount(1);
        definition.setContext(dslContext);
        
        assertThrows(IllegalArgumentException.class, () -> {
            processor.process(definition, null);
        });
    }
    
    @Test
    void testProcessWithInvalidDslType() {
        // 测试错误的 DSL 类型
        definition.setType(JsonDslDefinition.DslType.FILTER);
        
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.processor.TestUser");
        dslContext.setCount(1);
        definition.setContext(dslContext);
        
        // 虽然类型不匹配，但处理器应该仍然能处理（因为实际处理时会验证）
        assertThrows(com.xa.mass.base.jsondsl.builtin.JsonDslException.class, () -> {
            processor.process(definition, context);
        });
    }
} 