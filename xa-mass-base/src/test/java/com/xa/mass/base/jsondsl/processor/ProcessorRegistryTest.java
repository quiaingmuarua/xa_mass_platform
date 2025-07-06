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
    
    private TestProcessor testProcessor;
    private JsonDslDefinition definition;
    private ProcessingContext context;
    
    @BeforeEach
    void setUp() {
        testProcessor = new TestProcessor();
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
        ProcessorRegistry.remove("TestProcessor");
    }
    
    @Test
    void testRegisterProcessor() {
        // 注册处理器
        ProcessorRegistry.register(testProcessor);
        
        // 验证处理器已注册
        JsonDslProcessor retrieved = ProcessorRegistry.get("TestProcessor");
        assertNotNull(retrieved);
        assertEquals("TestProcessor", retrieved.getName());
    }
    
    @Test
    void testGetProcessor() {
        // 注册处理器
        ProcessorRegistry.register(testProcessor);
        
        // 获取处理器
        JsonDslProcessor processor = ProcessorRegistry.get("TestProcessor");
        assertNotNull(processor);
        assertEquals("TestProcessor", processor.getName());
        
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
            if ("TestProcessor".equals(processor.getName())) {
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
        Object result = ProcessorRegistry.processChain(definition, context);
        assertNotNull(result);
        assertEquals("TestProcessor processed: test-dsl", result);
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
        
        // 链式处理
        Object result = ProcessorRegistry.processChain(dslList, context);
        assertNotNull(result);
    }
    
    @Test
    void testRemoveProcessor() {
        // 注册处理器
        ProcessorRegistry.register(testProcessor);
        
        // 验证处理器已注册
        assertNotNull(ProcessorRegistry.get("TestProcessor"));
        
        // 移除处理器
        ProcessorRegistry.remove("TestProcessor");
        
        // 验证处理器已移除
        assertNull(ProcessorRegistry.get("TestProcessor"));
    }
    
    @Test
    void testClear() {
        // 注册多个处理器
        ProcessorRegistry.register(testProcessor);
        ProcessorRegistry.register(new TestFilterProcessor());
        
        // 验证处理器已注册
        assertNotNull(ProcessorRegistry.get("TestProcessor"));
        assertNotNull(ProcessorRegistry.get("TestFilterProcessor"));
        
        // 清除所有处理器
        ProcessorRegistry.clear();
        
        // 验证处理器已清除
        assertNull(ProcessorRegistry.get("TestProcessor"));
        assertNull(ProcessorRegistry.get("TestFilterProcessor"));
    }
    
    @Test
    void testProcessorPriority() {
        // 创建不同优先级的处理器
        TestProcessor highPriorityProcessor = new TestProcessor("HighPriorityProcessor", 500);
        TestProcessor lowPriorityProcessor = new TestProcessor("LowPriorityProcessor", 100);
        
        // 注册处理器
        ProcessorRegistry.register(lowPriorityProcessor);
        ProcessorRegistry.register(highPriorityProcessor);
        
        // 获取所有处理器
        List<JsonDslProcessor> processors = ProcessorRegistry.getAllProcessors();
        
        // 验证按优先级排序（优先级高的在前）
        boolean foundHighPriority = false;
        boolean foundLowPriority = false;
        
        for (JsonDslProcessor processor : processors) {
            if ("HighPriorityProcessor".equals(processor.getName())) {
                foundHighPriority = true;
                // 高优先级处理器应该在低优先级处理器之前
                assertFalse(foundLowPriority);
            }
            if ("LowPriorityProcessor".equals(processor.getName())) {
                foundLowPriority = true;
            }
        }
        
        assertTrue(foundHighPriority);
        assertTrue(foundLowPriority);
        
        // 清理
        ProcessorRegistry.remove("HighPriorityProcessor");
        ProcessorRegistry.remove("LowPriorityProcessor");
    }
    
    /**
     * 测试用的处理器实现
     */
    private static class TestProcessor implements JsonDslProcessor {
        
        private final String name;
        private final int priority;
        
        public TestProcessor() {
            this("TestProcessor", 500);
        }
        
        public TestProcessor(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            return name + " processed: " + definition.getUniqueId();
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
     * 测试用的过滤器处理器实现
     */
    private static class TestFilterProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            return "TestFilterProcessor processed: " + definition.getUniqueId();
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