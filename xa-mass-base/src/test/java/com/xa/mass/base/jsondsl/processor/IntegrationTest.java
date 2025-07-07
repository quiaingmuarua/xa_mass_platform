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

/**
 * 处理器架构集成测试
 */
public class IntegrationTest {
    
    private ProcessingContext context;
    
    @BeforeEach
    void setUp() {
        context = new ProcessingContext("integration-test");
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
        Object result = JsonDslProcessorEngine.processChain(dslChain, context);
        
        // 验证结果
        assertNotNull(result);
        assertTrue(result instanceof List);
        List<?> filteredList = (List<?>) result;
        
        // 验证所有对象都是成年人
        for (Object obj : filteredList) {
            Map<String, Object> map = (Map<String, Object>) obj;
            String ageStr = (String) map.get("age");
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
        
        // 处理 DSL
        Object result = JsonDslProcessorEngine.process(dsl, context);
        
        // 验证结果包含上下文信息
        assertNotNull(result);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
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
        
        // 处理 DSL
        Object result = JsonDslProcessorEngine.process(dsl, context);
        
        // 验证调试信息
        assertNotNull(result);
        assertTrue(result instanceof Map);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertTrue((Boolean) resultMap.get("debugMode"));
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
        
        // 应该抛出异常
        assertThrows(RuntimeException.class, () -> {
            JsonDslProcessorEngine.process(dsl, context);
        });
    }
    
    @Test
    void testJsonProcessing() {
        // 注册处理器
        JsonDslProcessorEngine.registerProcessor(new TestGenerateProcessor());
        
        // 创建 JSON DSL
        String jsonDsl = """
            {
                "uniqueId": "json-integration-test",
                "type": "generate",
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
        
        // 处理 JSON DSL
        Object result = JsonDslProcessorEngine.processFromJson(jsonDsl, context);
        
        // 验证结果
        assertNotNull(result);
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(5, list.size());
    }
    
    /**
     * 测试用的生成处理器
     */
    private static class TestGenerateProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            List<Map<String, Object>> result = Arrays.asList(
                createTestUser("Alice", "25"),
                createTestUser("Bob", "35"),
                createTestUser("Charlie", "45"),
                createTestUser("David", "55"),
                createTestUser("Eve", "20")
            );
            return result;
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
            return 150; // 比默认处理器更高的优先级
        }
        
        private Map<String, Object> createTestUser(String name, String age) {
            Map<String, Object> user = new HashMap<>();
            user.put("name", name);
            user.put("age", age);
            return user;
        }
    }
    
    /**
     * 测试用的过滤处理器
     */
    private static class TestFilterProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            List<Object> objects = (List<Object>) context.getParameter("objects");
            if (objects == null) {
                return Arrays.asList();
            }
            
            // 简单的过滤逻辑：保留年龄大于等于18的对象
            return objects.stream()
                .filter(obj -> {
                    Map<String, Object> map = (Map<String, Object>) obj;
                    String ageStr = (String) map.get("age");
                    int age = Integer.parseInt(ageStr);
                    return age >= 18;
                })
                .toList();
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
            return 200;
        }
    }
    
    /**
     * 测试用的转换处理器
     */
    private static class TestTransformProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            List<Object> objects = (List<Object>) context.getParameter("objects");
            if (objects == null) {
                return Arrays.asList();
            }
            
            // 简单的转换逻辑：添加年龄组字段
            return objects.stream()
                .map(obj -> {
                    Map<String, Object> map = new HashMap<>((Map<String, Object>) obj);
                    String ageStr = (String) map.get("age");
                    int age = Integer.parseInt(ageStr);
                    String ageGroup = age < 30 ? "young" : age < 50 ? "middle" : "senior";
                    map.put("ageGroup", ageGroup);
                    return map;
                })
                .toList();
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
            return 300;
        }
    }
    
    /**
     * 测试用的校验处理器
     */
    private static class TestValidateProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            List<Object> objects = (List<Object>) context.getParameter("objects");
            if (objects == null) {
                return new ValidateProcessor.ValidationResult();
            }
            
            ValidateProcessor.ValidationResult result = new ValidateProcessor.ValidationResult();
            
            for (Object obj : objects) {
                Map<String, Object> map = (Map<String, Object>) obj;
                String ageStr = (String) map.get("age");
                int age = Integer.parseInt(ageStr);
                
                if (age >= 0 && age <= 150) {
                    result.addValidObject(obj);
                } else {
                    result.addInvalidObject(obj, "Invalid age: " + age);
                }
            }
            
            return result;
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
            return 400;
        }
    }
    
    /**
     * 上下文感知处理器
     */
    private static class ContextAwareProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            Map<String, Object> result = new HashMap<>();
            result.put("param", context.getParameter("sharedParam"));
            result.put("variable", context.getVariable("sharedVar"));
            return result;
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
            return 100;
        }
    }
    
    /**
     * 调试感知处理器
     */
    private static class DebugAwareProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            Map<String, Object> result = new HashMap<>();
            result.put("debugMode", context.isDebug());
            return result;
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
            return 100;
        }
    }
    
    /**
     * 低优先级处理器
     */
    private static class LowPriorityProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            return "LowPriority";
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
    private static class HighPriorityProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            return "HighPriority";
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
            return 500;
        }
    }
    
    /**
     * 错误处理器
     */
    private static class ErrorProcessor implements JsonDslProcessor {
        
        @Override
        public Object process(JsonDslDefinition definition, ProcessingContext context) {
            throw new RuntimeException("Test error");
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
            return 100;
        }
    }
} 