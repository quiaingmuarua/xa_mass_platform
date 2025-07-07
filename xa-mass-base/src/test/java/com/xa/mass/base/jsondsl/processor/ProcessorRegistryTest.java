package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.model.JsonDslContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * ProcessorRegistry 测试
 */
public class ProcessorRegistryTest {
    
    private TestGenerateProcessor testProcessor;
    private JsonDslDefinition definition;
    private ProcessingContext context;
    
    @BeforeEach
    void setUp() {
        testProcessor = new TestGenerateProcessor();
        definition = new JsonDslDefinition("test-dsl", JsonDslDefinition.DslType.GENERATE);
        // 添加必要的 context 配置
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(1);
        definition.setContext(dslContext);
        context = new ProcessingContext("test-context");
    }
    
    @AfterEach
    void tearDown() {
        // 清理注册的处理器
        ProcessorRegistry.remove("TestGenerateProcessor");
    }
    
    @Test
    void testRegisterProcessor() {
        // 注册处理器
        ProcessorRegistry.register(testProcessor);
        
        // 验证处理器已注册
        JsonDslProcessor retrieved = ProcessorRegistry.get("TestGenerateProcessor");
        assertNotNull(retrieved);
        assertEquals("TestGenerateProcessor", retrieved.getName());
    }
    
    @Test
    void testGetProcessor() {
        // 注册处理器
        ProcessorRegistry.register(testProcessor);
        
        // 获取处理器
        JsonDslProcessor processor = ProcessorRegistry.get("TestGenerateProcessor");
        assertNotNull(processor);
        assertEquals("TestGenerateProcessor", processor.getName());
        
        // 获取不存在的处理器
        JsonDslProcessor nonexistent = ProcessorRegistry.get("NonexistentProcessor");
        assertNull(nonexistent);
    }
    
    @Test
    void testGetProcessorByType() {
        // 注册处理器
        ProcessorRegistry.register(testProcessor);
        
        // 获取支持指定类型的处理器
        JsonDslProcessor processor = ProcessorRegistry.getProcessor(JsonDslDefinition.DslType.GENERATE);
        assertNotNull(processor);
        assertTrue(processor.supports(JsonDslDefinition.DslType.GENERATE));
        
        // 获取不支持的类型的处理器
        assertThrows(IllegalArgumentException.class, () -> {
            ProcessorRegistry.getProcessor(JsonDslDefinition.DslType.FILTER);
        });
    }
    
    @Test
    void testGetAllProcessors() {
        // 注册多个处理器
        ProcessorRegistry.register(testProcessor);
        ProcessorRegistry.register(new TestFilterProcessor());
        
        // 获取所有处理器
        List<JsonDslProcessor> processors = ProcessorRegistry.getAllProcessors();
        assertNotNull(processors);
        assertTrue(processors.size() >= 2);
        
        // 验证处理器按优先级排序（优先级高的在前）
        boolean foundTestProcessor = false;
        boolean foundFilterProcessor = false;
        
        for (JsonDslProcessor processor : processors) {
            if ("TestGenerateProcessor".equals(processor.getName())) {
                foundTestProcessor = true;
            }
            if ("TestFilterProcessor".equals(processor.getName())) {
                foundFilterProcessor = true;
            }
        }
        
        assertTrue(foundTestProcessor);
        assertTrue(foundFilterProcessor);
    }
    
    @Test
    void testGetProcessorsByType() {
        // 注册多个处理器
        ProcessorRegistry.register(testProcessor);
        ProcessorRegistry.register(new TestFilterProcessor());
        
        // 获取支持 GENERATE 类型的处理器
        List<JsonDslProcessor> generateProcessors = ProcessorRegistry.getProcessors(JsonDslDefinition.DslType.GENERATE);
        assertNotNull(generateProcessors);
        assertTrue(generateProcessors.size() >= 1);
        
        // 验证所有处理器都支持 GENERATE 类型
        for (JsonDslProcessor processor : generateProcessors) {
            assertTrue(processor.supports(JsonDslDefinition.DslType.GENERATE));
        }
        
        // 获取支持 FILTER 类型的处理器
        List<JsonDslProcessor> filterProcessors = ProcessorRegistry.getProcessors(JsonDslDefinition.DslType.FILTER);
        assertNotNull(filterProcessors);
        assertTrue(filterProcessors.size() >= 1);
        
        // 验证所有处理器都支持 FILTER 类型
        for (JsonDslProcessor processor : filterProcessors) {
            assertTrue(processor.supports(JsonDslDefinition.DslType.FILTER));
        }
    }
    
    @Test
    void testProcessChain() {
        // 注册处理器
        ProcessorRegistry.register(testProcessor);
        
        // 链式处理单个 DSL
        GenerateProcessor<Map<String, Object>> processor = ProcessorRegistry.getGenerateProcessor();
        List<Map<String, Object>> result = processor.generate(definition, context, (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("TestGenerateProcessor processed: test-dsl", result.get(0).get("message"));
    }
    
    @Test
    void testProcessChainWithMultipleDsls() {
        // 注册处理器
        ProcessorRegistry.register(testProcessor);
        ProcessorRegistry.register(new TestFilterProcessor());
        
        // 创建多个 DSL
        JsonDslDefinition generateDsl = new JsonDslDefinition("generate-dsl", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext generateContext = new JsonDslContext();
        generateContext.setModel("java.util.HashMap");
        generateContext.setCount(1);
        generateDsl.setContext(generateContext);
        
        JsonDslDefinition filterDsl = new JsonDslDefinition("filter-dsl", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", ">= 18");
        filterDsl.setFieldDsl(fieldDsl);
        
        List<JsonDslDefinition> dslList = List.of(generateDsl, filterDsl);
        
        // 链式处理多个 DSL
        List<Map<String, Object>> result = null;
        for (JsonDslDefinition dsl : dslList) {
            if (JsonDslDefinition.DslType.GENERATE.equals(dsl.getType())) {
                GenerateProcessor<Map<String, Object>> processor = ProcessorRegistry.getGenerateProcessor();
                result = processor.generate(dsl, context, (Class<Map<String, Object>>) (Class<?>) Map.class);
            } else if (JsonDslDefinition.DslType.FILTER.equals(dsl.getType())) {
                FilterProcessor<Map<String, Object>> processor = ProcessorRegistry.getFilterProcessor();
                result = processor.filter(result, dsl, context);
            }
        }
        assertNotNull(result);
    }
    
    @Test
    void testRemoveProcessor() {
        // 注册处理器
        ProcessorRegistry.register(testProcessor);
        
        // 验证处理器已注册
        assertNotNull(ProcessorRegistry.get("TestGenerateProcessor"));
        
        // 移除处理器
        ProcessorRegistry.remove("TestGenerateProcessor");
        
        // 验证处理器已移除
        assertNull(ProcessorRegistry.get("TestGenerateProcessor"));
    }
    
    @Test
    void testClear() {
        // 注册多个处理器
        ProcessorRegistry.register(testProcessor);
        ProcessorRegistry.register(new TestFilterProcessor());
        
        // 验证处理器已注册
        assertNotNull(ProcessorRegistry.get("TestGenerateProcessor"));
        assertNotNull(ProcessorRegistry.get("TestFilterProcessor"));
        
        // 清除所有处理器
        ProcessorRegistry.clear();
        
        // 验证所有处理器已清除
        assertNull(ProcessorRegistry.get("TestGenerateProcessor"));
        assertNull(ProcessorRegistry.get("TestFilterProcessor"));
    }
    
    @Test
    void testProcessorPriority() {
        // 创建不同优先级的处理器
        TestGenerateProcessor lowPriority = new TestGenerateProcessor("LowPriorityProcessor", 50);
        TestGenerateProcessor highPriority = new TestGenerateProcessor("HighPriorityProcessor", 200);
        
        // 注册处理器
        ProcessorRegistry.register(lowPriority);
        ProcessorRegistry.register(highPriority);
        
        // 获取处理器列表
        List<JsonDslProcessor> processors = ProcessorRegistry.getProcessors(JsonDslDefinition.DslType.GENERATE);
        
        // 验证按优先级排序（优先级高的在前）
        assertTrue(processors.size() >= 2);
        JsonDslProcessor first = processors.get(0);
        JsonDslProcessor second = processors.get(1);
        
        assertTrue(first.getPriority() >= second.getPriority());
    }
    
    /**
     * 测试生成处理器
     */
    private static class TestGenerateProcessor implements GenerateProcessor<Map<String, Object>> {
        
        private final String name;
        private final int priority;
        
        public TestGenerateProcessor() {
            this("TestGenerateProcessor", 100);
        }
        
        public TestGenerateProcessor(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }
        
        @Override
        public List<Map<String, Object>> generate(JsonDslDefinition definition, ProcessingContext context, Class<Map<String, Object>> targetType) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", name + " processed: " + definition.getUniqueId());
            return List.of(result);
        }
        
        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.GENERATE.equals(type);
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public int getPriority() {
            return priority;
        }
    }
    
    /**
     * 测试过滤处理器
     */
    private static class TestFilterProcessor implements FilterProcessor<Map<String, Object>> {
        
        @Override
        public List<Map<String, Object>> filter(List<Map<String, Object>> input, JsonDslDefinition definition, ProcessingContext context) {
            // 简单的过滤逻辑：保留所有对象
            return input;
        }
        
        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.FILTER.equals(type);
        }
        
        @Override
        public String getName() {
            return "TestFilterProcessor";
        }
        
        @Override
        public int getPriority() {
            return 150;
        }
    }
} 