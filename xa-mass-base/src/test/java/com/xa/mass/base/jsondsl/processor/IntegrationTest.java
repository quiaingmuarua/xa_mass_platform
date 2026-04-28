package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实处理器集成测试 - 测试完整的DSL功能
 */
public class IntegrationTest {

    private ProcessingContext context;

    @BeforeEach
    void setUp() {
        context = new ProcessingContext("integration-test");
        // 清理之前的注册，使用系统默认的真实处理器
        ProcessorRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        // 清理注册的处理器
        ProcessorRegistry.clear();
    }

    @Test
    void testRealGenerateAndFilterChain() {
        // 创建真实的生成 DSL - 生成用户数据
        JsonDslDefinition generateDsl = new JsonDslDefinition("generate-users", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(10);
        generateDsl.setContext(dslContext);

        Map<String, Object> fieldDsl = new HashMap<>();
        Map<String, Object> nameRule = new HashMap<>();
        nameRule.put("$RANDOM_NAME", null);
        fieldDsl.put("name", nameRule);
        Map<String, Object> ageRule = new HashMap<>();
        ageRule.put("$RANDOM_INT", Arrays.asList(18, 65));
        fieldDsl.put("age", ageRule);
        Map<String, Object> statusRule = new HashMap<>();
        statusRule.put("$CHOICE", Arrays.asList("active", "inactive"));
        fieldDsl.put("status", statusRule);
        generateDsl.setFieldDsl(fieldDsl);

        // 生成数据
        List<Map> generatedUsers = JsonDslProcessorEngine.process(generateDsl, context, Map.class);

        // 验证生成的数据
        assertNotNull(generatedUsers);
        assertEquals(10, generatedUsers.size());

        for (Map user : generatedUsers) {
            assertNotNull(user.get("name"));
            assertNotNull(user.get("age"));
            assertNotNull(user.get("status"));
            assertTrue(user.get("age") instanceof Number);
            int age = ((Number) user.get("age")).intValue();
            assertTrue(age >= 18 && age <= 65);
        }

        // 创建真实的过滤 DSL - 过滤成年人
        JsonDslDefinition filterDsl = new JsonDslDefinition("filter-adults", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("age", Map.of("$EXPR", "age >= 18"));
        filterFieldDsl.put("status", Map.of("$EXPR", "status == 'active'"));
        filterDsl.setFieldDsl(filterFieldDsl);

        // 过滤数据
        FilterResult<Map> filterResult = JsonDslProcessorEngine.filterBatchWithDetails(generatedUsers, filterDsl, context, Map.class);

        // 验证过滤结果
        assertNotNull(filterResult);
        assertTrue(filterResult.getPassedCount() <= generatedUsers.size());

        // 验证通过过滤的用户都是成年人且状态为active
        for (Map user : filterResult.getPassed()) {
            int age = ((Number) user.get("age")).intValue();
            assertEquals("active", user.get("status"));
            assertTrue(age >= 18);
        }

        // 验证失败的用户有详细的失败原因
        if (!filterResult.getFailed().isEmpty()) {
            for (FilterResult.FilterFailure<Map> failure : filterResult.getFailed()) {
                assertFalse(failure.getReasons().isEmpty());
                System.out.println("用户 " + failure.getData().get("name") + " 过滤失败: " + failure.getReasons());
            }
        }
    }

    @Test
    void testRealLifecycleChainWithDefaultProcessors() {
        JsonDslDefinition generateDsl = new JsonDslDefinition("generate-lifecycle-users", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext generateContext = new JsonDslContext();
        generateContext.setModel("java.util.HashMap");
        generateContext.setCount(3);
        generateDsl.setContext(generateContext);
        Map<String, Object> generateFieldDsl = new HashMap<>();
        Map<String, Object> ageRule = new HashMap<>();
        ageRule.put("$CONTEXT", null);
        generateFieldDsl.put("age", ageRule);
        generateFieldDsl.put("name", "user");
        generateFieldDsl.put("status", "raw");
        generateDsl.setFieldDsl(generateFieldDsl);

        JsonDslDefinition filterDsl = new JsonDslDefinition("filter-lifecycle-users", JsonDslDefinition.DslType.FILTER);
        filterDsl.setFieldDsl(Map.of("age", Map.of("$EXPR", "age >= 1")));

        JsonDslDefinition transformDsl = new JsonDslDefinition("transform-lifecycle-users", JsonDslDefinition.DslType.TRANSFORM);
        transformDsl.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "'user-' + age"),
                "status", "READY"
        ));

        JsonDslDefinition validateDsl = new JsonDslDefinition("validate-lifecycle-users", JsonDslDefinition.DslType.VALIDATE);
        validateDsl.setFieldDsl(Map.of(
                "name", Map.of("$EXPR", "name != null && name.length() > 0"),
                "status", Map.of("$EXPR", "'READY'.equals(status)")
        ));

        List<Map> result = JsonDslProcessorEngine.processChain(
                List.of(generateDsl, filterDsl, transformDsl, validateDsl),
                context,
                Map.class
        );

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("user-1", result.get(0).get("name"));
        assertEquals("READY", result.get(0).get("status"));
        assertEquals("user-2", result.get(1).get("name"));
        assertEquals("READY", result.get(1).get("status"));
    }


    @Test
    void testRealPerformance() {
        // 测试大量数据处理的性能
        JsonDslDefinition generateDsl = new JsonDslDefinition("performance-test", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("java.util.HashMap");
        dslContext.setCount(1000);
        generateDsl.setContext(dslContext);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("id", Map.of("$RANGE", Arrays.asList(1, 1000)));
        fieldDsl.put("value", Map.of("$RANDOM_INT", Arrays.asList(1, 100)));
        generateDsl.setFieldDsl(fieldDsl);

        long startTime = System.currentTimeMillis();
        List<Map> data = JsonDslProcessorEngine.process(generateDsl, context, Map.class);
        long endTime = System.currentTimeMillis();

        assertEquals(1000, data.size());
        System.out.println("生成1000条数据耗时: " + (endTime - startTime) + "ms");

        // 测试过滤性能
        JsonDslDefinition filterDsl = new JsonDslDefinition("performance-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("value", Map.of("$EXPR", "value > 50"));
        filterDsl.setFieldDsl(filterFieldDsl);

        startTime = System.currentTimeMillis();
        FilterResult<Map> filterResult = JsonDslProcessorEngine.filterBatchWithDetails(data, filterDsl, context, Map.class);
        endTime = System.currentTimeMillis();

        System.out.println("过滤1000条数据耗时: " + (endTime - startTime) + "ms");
        System.out.println("过滤通过数量: " + filterResult.getPassedCount());
    }
} 
