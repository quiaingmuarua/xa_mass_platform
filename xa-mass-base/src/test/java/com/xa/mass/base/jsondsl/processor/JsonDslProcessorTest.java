package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonDslProcessor 接口测试
 */
public class JsonDslProcessorTest {
    
    private TestProcessor processor;
    private JsonDslDefinition definition;
    private ProcessingContext context;
    
    @BeforeEach
    void setUp() {
        processor = new TestProcessor();
        definition = new JsonDslDefinition("test-dsl", JsonDslDefinition.DslType.GENERATE);
        context = new ProcessingContext("test-context");
    }
    
    @Test
    void testProcessorBasicFunctionality() {
        // 测试基本功能
        Object result = processor.process(definition, context);
        assertNotNull(result);
        assertEquals("TestProcessor processed: test-dsl", result);
    }
    
    @Test
    void testProcessorSupports() {
        // 测试支持的类型
        assertTrue(processor.supports(JsonDslDefinition.DslType.GENERATE));
        assertFalse(processor.supports(JsonDslDefinition.DslType.FILTER));
    }
    
    @Test
    void testProcessorName() {
        // 测试处理器名称
        assertEquals("TestProcessor", processor.getName());
    }
    
    @Test
    void testProcessorPriority() {
        // 测试处理器优先级
        assertEquals(100, processor.getPriority());
    }
    
    @Test
    void testProcessorWithNullDefinition() {
        // 测试空定义
        assertThrows(IllegalArgumentException.class, () -> {
            processor.process(null, context);
        });
    }
    
    @Test
    void testProcessorWithNullContext() {
        // 测试空上下文
        assertThrows(IllegalArgumentException.class, () -> {
            processor.process(definition, null);
        });
    }
    
    /**
     * 测试用的处理器实现
     */
    private static class TestProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            if (definition == null) {
                throw new IllegalArgumentException("Definition cannot be null");
            }
            if (context == null) {
                throw new IllegalArgumentException("Context cannot be null");
            }
            return "TestProcessor processed: " + definition.getUniqueId();
        }
        
        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.GENERATE.equals(type);
        }
        
        @Override
        public String getName() {
            return "TestProcessor";
        }
        
        @Override
        public int getPriority() {
            return 100;
        }
    }
} 