package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * JsonDslProcessorEngine 测试
 */
public class JsonDslProcessorEngineTest {
    
    private JsonDslDefinition definition;
    private ProcessingContext context;
    private TestProcessor testProcessor;
    
    @BeforeEach
    void setUp() {
        definition = new JsonDslDefinition("test-dsl", JsonDslDefinition.DslType.GENERATE);
        // 添加必要的 context 配置
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(1);
        definition.setContext(dslContext);
        
        context = new ProcessingContext("test-context");
        testProcessor = new TestProcessor();
    }
    
    @AfterEach
    void tearDown() {
        // 清理注册的处理器
        ProcessorRegistry.remove("TestProcessor");
    }
    
    @Test
    void testProcessSingleDsl() {
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 处理单个 DSL
        Object result = JsonDslProcessorEngine.process(definition);
        assertNotNull(result);
        assertEquals("TestProcessor processed: test-dsl", result);
    }
    
    @Test
    void testProcessSingleDslWithContext() {
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 设置上下文参数
        context.setParameter("testParam", "testValue");
        
        // 处理单个 DSL
        Object result = JsonDslProcessorEngine.process(definition, context);
        assertNotNull(result);
        assertEquals("TestProcessor processed: test-dsl", result);
    }
    
    @Test
    void testProcessChain() {
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 创建 DSL 列表
        List<JsonDslDefinition> dslList = List.of(definition);
        
        // 链式处理
        Object result = JsonDslProcessorEngine.processChain(dslList);
        assertNotNull(result);
        assertEquals("TestProcessor processed: test-dsl", result);
    }
    
    @Test
    void testProcessChainWithContext() {
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 创建 DSL 列表
        List<JsonDslDefinition> dslList = List.of(definition);
        
        // 设置上下文参数
        context.setParameter("testParam", "testValue");
        
        // 链式处理
        Object result = JsonDslProcessorEngine.processChain(dslList, context);
        assertNotNull(result);
        assertEquals("TestProcessor processed: test-dsl", result);
    }
    
    @Test
    void testProcessFromJson() {
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 创建 JSON DSL
        String jsonDsl = """
            {
                "uniqueId": "json-test-dsl",
                "type": "generate",
                "priority": 1,
                "description": "Test DSL from JSON",
                "context": {
                    "model": "java.util.HashMap",
                    "count": 1
                }
            }
            """;
        
        // 从 JSON 处理
        Object result = JsonDslProcessorEngine.processFromJson(jsonDsl);
        assertNotNull(result);
        assertEquals("TestProcessor processed: json-test-dsl", result);
    }
    
    @Test
    void testProcessFromJsonWithContext() {
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 创建 JSON DSL
        String jsonDsl = """
            {
                "uniqueId": "json-test-dsl",
                "type": "generate",
                "priority": 1,
                "description": "Test DSL from JSON",
                "context": {
                    "model": "java.util.HashMap",
                    "count": 1
                }
            }
            """;
        
        // 设置上下文参数
        context.setParameter("testParam", "testValue");
        
        // 从 JSON 处理
        Object result = JsonDslProcessorEngine.processFromJson(jsonDsl, context);
        assertNotNull(result);
        assertEquals("TestProcessor processed: json-test-dsl", result);
    }
    
    @Test
    void testProcessChainFromJson() {
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 创建 JSON DSL 列表
        List<String> jsonDslList = List.of(
            """
            {
                "uniqueId": "json-test-dsl-1",
                "type": "generate",
                "priority": 1,
                "description": "Test DSL 1 from JSON",
                "context": {
                    "model": "java.util.HashMap",
                    "count": 1
                }
            }
            """,
            """
            {
                "uniqueId": "json-test-dsl-2",
                "type": "generate",
                "priority": 2,
                "description": "Test DSL 2 from JSON",
                "context": {
                    "model": "java.util.HashMap",
                    "count": 1
                }
            }
            """
        );
        
        // 链式处理 JSON
        Object result = JsonDslProcessorEngine.processChainFromJson(jsonDslList);
        assertNotNull(result);
        assertEquals("TestProcessor processed: json-test-dsl-2", result);
    }
    
    @Test
    void testProcessChainFromJsonWithContext() {
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 创建 JSON DSL 列表
        List<String> jsonDslList = List.of(
            """
            {
                "uniqueId": "json-test-dsl-1",
                "type": "generate",
                "priority": 1,
                "description": "Test DSL 1 from JSON",
                "context": {
                    "model": "java.util.HashMap",
                    "count": 1
                }
            }
            """
        );
        
        // 设置上下文参数
        context.setParameter("testParam", "testValue");
        
        // 链式处理 JSON
        Object result = JsonDslProcessorEngine.processChainFromJson(jsonDslList, context);
        assertNotNull(result);
        assertEquals("TestProcessor processed: json-test-dsl-1", result);
    }
    
    @Test
    void testRegisterProcessor() {
        // 注册处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 验证处理器已注册
        List<JsonDslProcessor> processors = JsonDslProcessorEngine.getAllProcessors();
        boolean found = false;
        for (JsonDslProcessor processor : processors) {
            if ("TestProcessor".equals(processor.getName())) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }
    
    @Test
    void testGetAllProcessors() {
        // 注册处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 获取所有处理器
        List<JsonDslProcessor> processors = JsonDslProcessorEngine.getAllProcessors();
        assertNotNull(processors);
        assertTrue(processors.size() >= 1);
        
        // 验证包含默认处理器
        boolean foundDefaultProcessor = false;
        for (JsonDslProcessor processor : processors) {
            if ("GenerateProcessor".equals(processor.getName()) ||
                "FilterProcessor".equals(processor.getName()) ||
                "TransformProcessor".equals(processor.getName()) ||
                "ValidateProcessor".equals(processor.getName())) {
                foundDefaultProcessor = true;
                break;
            }
        }
        assertTrue(foundDefaultProcessor);
    }
    
    @Test
    void testGetProcessorsByType() {
        // 注册处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 获取支持 GENERATE 类型的处理器
        List<JsonDslProcessor> generateProcessors = JsonDslProcessorEngine.getProcessors(JsonDslDefinition.DslType.GENERATE);
        assertNotNull(generateProcessors);
        assertTrue(generateProcessors.size() >= 1);
        
        // 验证所有处理器都支持 GENERATE 类型
        for (JsonDslProcessor processor : generateProcessors) {
            assertTrue(processor.supports(JsonDslDefinition.DslType.GENERATE));
        }
        
        // 获取支持 FILTER 类型的处理器
        List<JsonDslProcessor> filterProcessors = JsonDslProcessorEngine.getProcessors(JsonDslDefinition.DslType.FILTER);
        assertNotNull(filterProcessors);
        assertTrue(filterProcessors.size() >= 1);
        
        // 验证所有处理器都支持 FILTER 类型
        for (JsonDslProcessor processor : filterProcessors) {
            assertTrue(processor.supports(JsonDslDefinition.DslType.FILTER));
        }
    }
    
    @Test
    void testProcessWithInvalidJson() {
        // 测试无效的 JSON
        String invalidJson = "{ invalid json }";
        
        assertThrows(Exception.class, () -> {
            JsonDslProcessorEngine.processFromJson(invalidJson);
        });
    }
    
    @Test
    void testProcessWithUnsupportedDslType() {
        // 创建不支持的 DSL 类型
        JsonDslDefinition unsupportedDsl = new JsonDslDefinition("unsupported", JsonDslDefinition.DslType.FILTER);
        
        // 应该抛出异常（因为 FILTER 类型缺少 fieldDsl 配置）
        assertThrows(JsonDslException.class, () -> {
            JsonDslProcessorEngine.process(unsupportedDsl);
        });
    }
    
    @Test
    void testProcessWithNullDefinition() {
        assertThrows(NullPointerException.class, () -> {
            JsonDslProcessorEngine.process(null);
        });
    }
    
    @Test
    void testProcessWithNullContext() {
        assertThrows(IllegalArgumentException.class, () -> {
            JsonDslProcessorEngine.process(definition, null);
        });
    }
    
    @Test
    void testProcessChainWithNullList() {
        assertThrows(NullPointerException.class, () -> {
            JsonDslProcessorEngine.processChain(null);
        });
    }
    
    @Test
    void testProcessChainWithEmptyList() {
        List<JsonDslDefinition> emptyList = List.of();
        Object result = JsonDslProcessorEngine.processChain(emptyList);
        assertNull(result);
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
            return 500;
        }
    }
} 