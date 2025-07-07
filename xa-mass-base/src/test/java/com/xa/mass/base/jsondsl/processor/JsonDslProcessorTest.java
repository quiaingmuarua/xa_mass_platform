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
    void testProcessorSupports() {
        // 测试支持的类型
        assertTrue(processor.supports(JsonDslDefinition.DslType.GENERATE));
        assertFalse(processor.supports(JsonDslDefinition.DslType.FILTER));
        assertFalse(processor.supports(JsonDslDefinition.DslType.TRANSFORM));
        assertFalse(processor.supports(JsonDslDefinition.DslType.VALIDATE));
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
    void testProcessorDefaultSupports() {
        // 测试默认的 supports 实现
        TestGenerateProcessor generateProcessor = new TestGenerateProcessor();
        assertTrue(generateProcessor.supports(JsonDslDefinition.DslType.GENERATE));
        assertFalse(generateProcessor.supports(JsonDslDefinition.DslType.FILTER));
    }
    
    /**
     * 测试用的基础处理器实现
     */
    private static class TestProcessor implements JsonDslProcessor {
        
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
    
    /**
     * 测试用的生成处理器实现
     */
    private static class TestGenerateProcessor implements GenerateProcessor<String> {
        
        @Override
        public java.util.List<String> generate(JsonDslDefinition definition, ProcessingContext context, Class<String> targetType) {
            if (definition == null) {
                throw new IllegalArgumentException("Definition cannot be null");
            }
            if (context == null) {
                throw new IllegalArgumentException("Context cannot be null");
            }
            if (targetType == null) {
                throw new IllegalArgumentException("Target type cannot be null");
            }
            return java.util.List.of("TestProcessor processed: " + definition.getUniqueId());
        }
        
        @Override
        public String getName() {
            return "TestGenerateProcessor";
        }
        
        @Override
        public int getPriority() {
            return 100;
        }
    }
} 