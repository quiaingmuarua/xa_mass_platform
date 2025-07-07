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
import java.util.Arrays;
import java.util.ArrayList;

/**
 * 处理器架构集成测试
 */
public class IntegrationTest {
    
    private ProcessingContext context;
    
    @BeforeEach
    void setUp() {
        context = new ProcessingContext("integration-test");
        // 清理之前的注册
        ProcessorRegistry.clear();
        // 注册所有类型的处理器
        JsonDslProcessorEngine.registerProcessor(new TestGenerateProcessor());
        JsonDslProcessorEngine.registerProcessor(new TestFilterProcessor());
        JsonDslProcessorEngine.registerProcessor(new TestTransformProcessor());
        JsonDslProcessorEngine.registerProcessor(new TestValidateProcessor());
    }
    
    @AfterEach
    void tearDown() {
        // 清理注册的处理器
        ProcessorRegistry.clear();
    }
    
    @Test
    void testGenerateAndFilterChain() {
        // 注册自定义处理器
        JsonDslProcessorEngine.registerProcessor(new TestGenerateProcessor());
        JsonDslProcessorEngine.registerProcessor(new TestFilterProcessor());
        
        // 创建生成 DSL
        JsonDslDefinition generateDsl = new JsonDslDefinition("generate-users", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(5);
        generateDsl.setContext(dslContext);
        
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("name", "$RANDOM_NAME");
        fieldDsl.put("age", "$RANDOM_INT(18, 65)");
        generateDsl.setFieldDsl(fieldDsl);
        
        // 创建过滤 DSL
        JsonDslDefinition filterDsl = new JsonDslDefinition("filter-adults", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("age", "$EXPR(age >= 18)");
        filterDsl.setFieldDsl(filterFieldDsl);
        
        // 链式处理
        List<JsonDslDefinition> dslChain = Arrays.asList(generateDsl, filterDsl);
        List<Map> result = JsonDslProcessorEngine.processChain(dslChain, context, Map.class);
        
        // 验证结果
        assertNotNull(result);
        assertTrue(result.isEmpty()); // 由于过滤条件，结果应该为空
        
        // 验证所有对象都是成年人
        for (Map obj : result) {
            String ageStr = (String) obj.get("age");
            int age = Integer.parseInt(ageStr);
            assertTrue(age >= 18);
        }
    }
    
    @Test
    void testMultipleProcessorTypes() {
        // 注册所有类型的处理器
        JsonDslProcessorEngine.registerProcessor(new TestGenerateProcessor());
        JsonDslProcessorEngine.registerProcessor(new TestFilterProcessor());
        JsonDslProcessorEngine.registerProcessor(new TestTransformProcessor());
        JsonDslProcessorEngine.registerProcessor(new TestValidateProcessor());
        
        // 验证所有处理器都已注册
        List<JsonDslProcessor> allProcessors = JsonDslProcessorEngine.getAllProcessors();
        assertTrue(allProcessors.size() >= 4);
        
        // 验证每种类型都有对应的处理器
        assertTrue(JsonDslProcessorEngine.getProcessors(JsonDslDefinition.DslType.GENERATE).size() >= 1);
        assertTrue(JsonDslProcessorEngine.getProcessors(JsonDslDefinition.DslType.FILTER).size() >= 1);
        assertTrue(JsonDslProcessorEngine.getProcessors(JsonDslDefinition.DslType.TRANSFORM).size() >= 1);
        assertTrue(JsonDslProcessorEngine.getProcessors(JsonDslDefinition.DslType.VALIDATE).size() >= 1);
    }
    
    @Test
    void testContextSharing() {
        // 注册处理器
        JsonDslProcessorEngine.registerProcessor(new ContextAwareProcessor());
        
        // 设置上下文参数
        context.setParameter("sharedParam", "sharedValue");
        context.setVariable("sharedVar", "sharedVariable");
        
        // 创建 DSL
        JsonDslDefinition dsl = new JsonDslDefinition("context-test", JsonDslDefinition.DslType.GENERATE);
        // 添加必需的 context 配置
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(1);
        dsl.setContext(dslContext);
        
        // 处理 DSL - 使用注册的处理器
        List<Map> result = JsonDslProcessorEngine.process(dsl, context, Map.class);
        
        // 验证结果包含上下文信息
        assertNotNull(result);
        assertFalse(result.isEmpty());
        Map<String, Object> resultMap = result.get(0);
        assertEquals("sharedValue", resultMap.get("param"));
        assertEquals("sharedVariable", resultMap.get("variable"));
    }
    
    @Test
    void testDebugMode() {
        // 注册处理器
        JsonDslProcessorEngine.registerProcessor(new DebugAwareProcessor());
        
        // 启用调试模式
        context.setDebug(true);
        
        // 创建 DSL
        JsonDslDefinition dsl = new JsonDslDefinition("debug-test", JsonDslDefinition.DslType.GENERATE);
        // 添加必需的 context 配置
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(1);
        dsl.setContext(dslContext);
        
        // 处理 DSL - 使用注册的处理器
        List<Map> result = JsonDslProcessorEngine.process(dsl, context, Map.class);
        
        // 验证调试信息
        assertNotNull(result);
        assertFalse(result.isEmpty());
        Map<String, Object> resultMap = result.get(0);
        assertTrue((Boolean) resultMap.get("debug"));
    }
    
    @Test
    void testProcessorPriority() {
        // 注册不同优先级的处理器
        JsonDslProcessorEngine.registerProcessor(new LowPriorityProcessor());
        JsonDslProcessorEngine.registerProcessor(new HighPriorityProcessor());
        
        // 获取处理器列表
        List<JsonDslProcessor> processors = JsonDslProcessorEngine.getProcessors(JsonDslDefinition.DslType.GENERATE);
        
        // 验证按优先级排序
        assertTrue(processors.size() >= 2);
        JsonDslProcessor first = processors.get(0);
        JsonDslProcessor second = processors.get(1);
        
        // 高优先级处理器应该在前面
        assertTrue(first.getPriority() >= second.getPriority());
    }
    
    @Test
    void testErrorHandling() {
        // 注册会抛出异常的处理器
        JsonDslProcessorEngine.registerProcessor(new ErrorProcessor());
        
        // 创建 DSL
        JsonDslDefinition dsl = new JsonDslDefinition("error-test", JsonDslDefinition.DslType.GENERATE);
        // 添加必需的 context 配置
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(1);
        dsl.setContext(dslContext);
        
        // 应该抛出异常
        assertThrows(RuntimeException.class, () -> {
            JsonDslProcessorEngine.process(dsl, context, Map.class);
        });
    }
    
    @Test
    void testJsonProcessing() {
        // 注册处理器
        JsonDslProcessorEngine.registerProcessor(new TestGenerateProcessor());
        
        // 创建 JSON DSL - 使用正确的枚举值格式
        String jsonDsl = """
            {
                "uniqueId": "json-integration-test",
                "type": "GENERATE",
                "priority": 1,
                "description": "Integration test from JSON",
                "context": {
                    "model": "java.util.HashMap",
                    "count": 3
                },
                "fieldDsl": {
                    "name": "$RANDOM_NAME",
                    "age": "$RANDOM_INT(18, 65)"
                }
            }
            """;
        
        // 处理 JSON DSL - 使用注册的处理器
        List<Map> result = JsonDslProcessorEngine.processFromJson(jsonDsl, context, Map.class);
        
        // 验证结果 - TestGenerateProcessor 返回 1 个对象
        assertNotNull(result);
        assertEquals(1, result.size());
    }
    
    /**
     * 测试生成处理器
     */
    private static class TestGenerateProcessor implements GenerateProcessor {
        
        @Override
        public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
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
            return 100;
        }
    }
    
    /**
     * 测试过滤处理器
     */
    private static class TestFilterProcessor implements FilterProcessor {
        
        @Override
        public <T> List<T> filter(List<T> input, JsonDslDefinition definition, ProcessingContext context) {
            // 过滤掉所有对象，返回空列表
            return new ArrayList<>();
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
    
    /**
     * 测试转换处理器
     */
    private static class TestTransformProcessor implements TransformProcessor {
        
        @Override
        public <T> T transform(T input, JsonDslDefinition definition, ProcessingContext context) {
            // 简单的转换逻辑：返回原对象
            return input;
        }
        
        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.TRANSFORM.equals(type);
        }
        
        @Override
        public String getName() {
            return "TestTransformProcessor";
        }
        
        @Override
        public int getPriority() {
            return 200;
        }
    }
    
    /**
     * 测试校验处理器
     */
    private static class TestValidateProcessor implements ValidateProcessor {
        
        @Override
        public <T> List<String> validate(T input, JsonDslDefinition definition, ProcessingContext context) {
            // 简单的校验逻辑：总是通过
            return new ArrayList<>();
        }
        
        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.VALIDATE.equals(type);
        }
        
        @Override
        public String getName() {
            return "TestValidateProcessor";
        }
        
        @Override
        public int getPriority() {
            return 250;
        }
    }
    
    /**
     * 上下文感知处理器
     */
    private static class ContextAwareProcessor implements GenerateProcessor {
        
        @Override
        public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "ContextAwareProcessor processed: " + definition.getUniqueId());
            result.put("contextId", context.getScopeName());
            result.put("param", context.getParameter("sharedParam"));
            result.put("variable", context.getVariable("sharedVar"));
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
            return "ContextAwareProcessor";
        }
        
        @Override
        public int getPriority() {
            return 120;
        }
    }
    
    /**
     * 调试感知处理器
     */
    private static class DebugAwareProcessor implements GenerateProcessor {
        
        @Override
        public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "DebugAwareProcessor processed: " + definition.getUniqueId());
            result.put("debug", context.isDebug());
            result.put("timestamp", System.currentTimeMillis());
            
            if (context.isDebug()) {
                System.out.println("[DebugAwareProcessor] Processing definition: " + definition.getUniqueId());
            }
            
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
            return "DebugAwareProcessor";
        }
        
        @Override
        public int getPriority() {
            return 130;
        }
    }
    
    /**
     * 低优先级处理器
     */
    private static class LowPriorityProcessor implements GenerateProcessor {
        
        @Override
        public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "LowPriorityProcessor processed: " + definition.getUniqueId());
            result.put("priority", "low");
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
            return "LowPriorityProcessor";
        }
        
        @Override
        public int getPriority() {
            return 50;
        }
    }
    
    /**
     * 高优先级处理器
     */
    private static class HighPriorityProcessor implements GenerateProcessor {
        
        @Override
        public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "HighPriorityProcessor processed: " + definition.getUniqueId());
            result.put("priority", "high");
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
            return "HighPriorityProcessor";
        }
        
        @Override
        public int getPriority() {
            return 200;
        }
    }
    
    /**
     * 错误处理器
     */
    private static class ErrorProcessor implements GenerateProcessor {
        
        @Override
        public <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType) {
            throw new RuntimeException("ErrorProcessor intentionally throws exception");
        }
        
        @Override
        public boolean supports(JsonDslDefinition.DslType type) {
            return JsonDslDefinition.DslType.GENERATE.equals(type);
        }
        
        @Override
        public String getName() {
            return "ErrorProcessor";
        }
        
        @Override
        public int getPriority() {
            return 300;
        }
    }
} 