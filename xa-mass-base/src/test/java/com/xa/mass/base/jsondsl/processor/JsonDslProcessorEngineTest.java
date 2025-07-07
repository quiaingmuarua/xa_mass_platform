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
    private TestGenerateProcessor testProcessor;
    
    @BeforeEach
    void setUp() {
        definition = new JsonDslDefinition("test-dsl", JsonDslDefinition.DslType.GENERATE);
        // 添加必要的 context 配置
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(1);
        definition.setContext(dslContext);
        
        context = new ProcessingContext("test-context");
        testProcessor = new TestGenerateProcessor();
        
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 注册一个测试 FilterProcessor
        JsonDslProcessorEngine.registerProcessor(new DefaultFilterProcessor());
        
        // 注册一个测试 TransformProcessor
        JsonDslProcessorEngine.registerProcessor(new DefaultTransformProcessor());
        
        // 注册一个测试 ValidateProcessor
        JsonDslProcessorEngine.registerProcessor(new DefaultValidateProcessor());
    }
    
    @AfterEach
    void tearDown() {
        // 清理注册的处理器
        ProcessorRegistry.remove("TestGenerateProcessor");
    }
    
    @Test
    void testProcessSingleDsl() {
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 处理单个 DSL
        List<Map> result = JsonDslProcessorEngine.process(definition, Map.class);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("TestGenerateProcessor processed: test-dsl", result.get(0).get("message"));
    }
    
    @Test
    void testProcessSingleDslWithContext() {
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 设置上下文参数
        context.setParameter("testParam", "testValue");
        
        // 处理单个 DSL
        List<Map> result = JsonDslProcessorEngine.process(definition, context, Map.class);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("TestGenerateProcessor processed: test-dsl", result.get(0).get("message"));
    }
    
    @Test
    void testProcessChain() {
        // 注册测试处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 创建 DSL 列表
        List<JsonDslDefinition> dslList = List.of(definition);
        
        // 链式处理
        List<Map> result = JsonDslProcessorEngine.processChain(dslList, Map.class);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("TestGenerateProcessor processed: test-dsl", result.get(0).get("message"));
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
        List<Map> result = JsonDslProcessorEngine.processChain(dslList, context, Map.class);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("TestGenerateProcessor processed: test-dsl", result.get(0).get("message"));
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
        List<Map> result = JsonDslProcessorEngine.processFromJson(jsonDsl, Map.class);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("TestGenerateProcessor processed: json-test-dsl", result.get(0).get("message"));
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
        List<Map> result = JsonDslProcessorEngine.processFromJson(jsonDsl, context, Map.class);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("TestGenerateProcessor processed: json-test-dsl", result.get(0).get("message"));
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
        List<Map> result = JsonDslProcessorEngine.processChainFromJson(jsonDslList, Map.class);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("TestGenerateProcessor processed: json-test-dsl-2", result.get(0).get("message"));
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
        List<Map> result = JsonDslProcessorEngine.processChainFromJson(jsonDslList, context, Map.class);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("TestGenerateProcessor processed: json-test-dsl-1", result.get(0).get("message"));
    }
    
    @Test
    void testRegisterProcessor() {
        // 注册处理器
        JsonDslProcessorEngine.registerProcessor(testProcessor);
        
        // 验证处理器已注册
        List<JsonDslProcessor> processors = JsonDslProcessorEngine.getAllProcessors();
        boolean found = false;
        for (JsonDslProcessor processor : processors) {
            if ("TestGenerateProcessor".equals(processor.getName())) {
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
            if ("DefaultGenerateProcessor".equals(processor.getName()) ||
                "DefaultFilterProcessor".equals(processor.getName()) ||
                "DefaultTransformProcessor".equals(processor.getName()) ||
                "DefaultValidateProcessor".equals(processor.getName())) {
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
    void testGetStrongTypedProcessors() {
        // 测试获取强类型处理器
        GenerateProcessor generateProcessor = JsonDslProcessorEngine.getGenerateProcessor();
        assertNotNull(generateProcessor);
        
        FilterProcessor filterProcessor = JsonDslProcessorEngine.getFilterProcessor();
        assertNotNull(filterProcessor);
        
        TransformProcessor transformProcessor = JsonDslProcessorEngine.getTransformProcessor();
        assertNotNull(transformProcessor);
        
        ValidateProcessor validateProcessor = JsonDslProcessorEngine.getValidateProcessor();
        assertNotNull(validateProcessor);
    }
    
    @Test
    void testProcessWithInvalidJson() {
        // 测试无效的 JSON
        String invalidJson = "{ invalid json }";
        
        assertThrows(Exception.class, () -> {
            JsonDslProcessorEngine.processFromJson(invalidJson, Map.class);
        });
    }
    
    @Test
    void testProcessWithUnsupportedDslType() {
        // 创建不支持的 DSL 类型
        JsonDslDefinition unsupportedDsl = new JsonDslDefinition("unsupported", JsonDslDefinition.DslType.FILTER);
        
        // 应该抛出异常（因为 FILTER 类型需要不同的处理方式）
        assertThrows(IllegalArgumentException.class, () -> {
            JsonDslProcessorEngine.process(unsupportedDsl, Map.class);
        });
    }
    
    @Test
    void testProcessWithNullDefinition() {
        assertThrows(NullPointerException.class, () -> {
            JsonDslProcessorEngine.process(null, Map.class);
        });
    }
    
    @Test
    void testProcessWithNullContext() {
        assertThrows(IllegalArgumentException.class, () -> {
            JsonDslProcessorEngine.process(definition, null, Map.class);
        });
    }
    
    @Test
    void testProcessChainWithNullList() {
        assertThrows(NullPointerException.class, () -> {
            JsonDslProcessorEngine.processChain(null, Map.class);
        });
    }
    
    @Test
    void testProcessChainWithEmptyList() {
        List<JsonDslDefinition> emptyList = List.of();
        List<Map> result = JsonDslProcessorEngine.processChain(emptyList, Map.class);
        assertNull(result);
    }
    
    /**
     * 测试用的强类型生成处理器实现
     */
    private static class TestGenerateProcessor implements GenerateProcessor {
        
        @Override
        public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
            if (definition == null) {
                throw new IllegalArgumentException("Definition cannot be null");
            }
            if (context == null) {
                throw new IllegalArgumentException("Context cannot be null");
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("message", "TestGenerateProcessor processed: " + definition.getUniqueId());
            result.put("timestamp", System.currentTimeMillis());
            
            @SuppressWarnings("unchecked")
            List<T> typedResult = (List<T>) List.of(result);
            return typedResult;
        }
        
        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.GENERATE.equals(type);
        }
        
        @Override
        public String getName() {
            return "TestGenerateProcessor";
        }
        
        @Override
        public int getPriority() {
            return 500;
        }
    }
} 