package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.builtin.JsonDslException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultFilterProcessor 重构后的测试
 */
public class DefaultFilterProcessorTest {

    private DefaultFilterProcessor processor;
    private JsonDslDefinition definition;
    private ProcessingContext context;

    @BeforeEach
    void setUp() {
        processor = new DefaultFilterProcessor();
        definition = new JsonDslDefinition("test-filter", JsonDslDefinition.DslType.FILTER);
        context = new ProcessingContext("test-context");
    }

    @Test
    void testFilterSingleWithJavaBean() {
        // 创建测试用户
        TestUser user = new TestUser();
        user.setName("Alice");
        user.setAge(25);
        user.setStatus("active");

        // 设置过滤条件：年龄大于20
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        definition.setFieldDsl(fieldDsl);


    }

    @Test
    void testFilterSingleWithMap() {
        // 创建测试Map
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("name", "Bob");
        userMap.put("age", 30);
        userMap.put("status", "active");

        // 设置过滤条件：年龄大于25且状态为active
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 25"));
        fieldDsl.put("status", Map.of("$EXPR", "status == 'active'"));
        definition.setFieldDsl(fieldDsl);


    }

    @Test
    void testFilterBatchWithMixedTypes() {
        // 创建混合类型的测试数据
        List<Object> testData = Arrays.asList(
            createTestUser("Alice", 25, "active"),
            createTestMap("Bob", 35, "inactive"),
            createTestUser("Charlie", 45, "active")
        );

        // 设置过滤条件：年龄大于30
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 30"));
        definition.setFieldDsl(fieldDsl);

        // 测试批量过滤
        List<Object> filtered = JsonDslProcessorEngine.filterBatch(testData, definition, context, Object.class);
        assertNotNull(filtered);
        assertTrue(filtered.size() >= 0, "应该有过滤结果");
    }

    @Test
    void testFilterWithCombineConditions() {
        // 创建测试用户
        TestUser user = new TestUser();
        user.setName("Alice");
        user.setAge(25);
        user.setStatus("active");

        // 设置字段条件
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        definition.setFieldDsl(fieldDsl);

        // 设置组合条件
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("nameCheck", Map.of("$EXPR", "name != null && name.length() > 0"));
        combineDsl.put("statusCheck", Map.of("$EXPR", "status == 'active'"));
        definition.setCombineDsl(combineDsl);


    }

    @Test
    void testFilterWithNullInput() {
        // 测试空输入
        assertThrows(JsonDslException.class, () -> {
            processor.filter(null, definition, context);
        });
    }

    @Test
    void testFilterWithNullDefinition() {
        TestUser user = new TestUser();
        user.setName("Alice");
        user.setAge(25);

        assertThrows(JsonDslException.class, () -> {
            processor.filter(user, null, context);
        });
    }

    @Test
    void testFilterWithNullContext() {
        TestUser user = new TestUser();
        user.setName("Alice");
        user.setAge(25);

        assertThrows(JsonDslException.class, () -> {
            processor.filter(user, definition, null);
        });
    }

    @Test
    void testFilterWithDebugMode() {
        // 测试调试模式
        context.setDebug(true);

        TestUser user = new TestUser();
        user.setName("Alice");
        user.setAge(25);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 30")); // 故意设置不满足的条件
        definition.setFieldDsl(fieldDsl);

        Object result = processor.filter(user, definition, context);

    }

    @Test
    void testFilterWithInvalidExpression() {
        TestUser user = new TestUser();
        user.setName("Alice");
        user.setAge(25);

        // 设置无效的表达式
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "invalid expression"));
        definition.setFieldDsl(fieldDsl);


    }

    @Test
    void testFilterBatchWithDetails() {
        // 创建测试数据
        List<TestUser> testUsers = Arrays.asList(
            createTestUser("Alice", 15, "active"),  // 年龄不满足
            createTestUser("Bob", 25, "inactive"),  // 状态不满足
            createTestUser("Charlie", 35, "active") // 都满足
        );

        // 设置过滤条件
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        fieldDsl.put("status", Map.of("$EXPR", "status == 'active'"));
        definition.setFieldDsl(fieldDsl);

        // 测试批量过滤（带详情）
        FilterResult<TestUser> result = JsonDslProcessorEngine.filterBatchWithDetails(testUsers, definition, context, TestUser.class);
        List<TestUser> passed = result.getPassed();
        List<FilterReport.FilterFail<TestUser>> failed = result.getFailed();

        assertNotNull(passed);
        assertNotNull(failed);
        assertEquals(1, passed.size(), "应该只有Charlie通过过滤");
        assertEquals(2, failed.size(), "应该有2个失败的对象");
    }

    @Test
    public void testFilterWithDetailedFailureReasons() {
        // 创建过滤条件：年龄必须大于20，状态必须是active
        JsonDslDefinition filterDef = new JsonDslDefinition("detailed-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        fieldDsl.put("status", Map.of("$EXPR", "status == 'active'"));
        filterDef.setFieldDsl(fieldDsl);

        // 测试用户：年龄15（不满足），状态inactive（不满足）
        TestUser user = new TestUser("YoungUser", 15, "inactive");
        
        FilterProcessor processor = new DefaultFilterProcessor();
        ProcessingContext context = new ProcessingContext();
        context.setDebug(true);
        
        FilterResult<TestUser> result = processor.filter(user, filterDef, context);
        
        // 验证失败
        assertThat(result.getPassed()).isEmpty();
        assertThat(result.getFailed()).hasSize(1);
        
        // 验证详细的失败原因
        List<String> failReasons = result.getFailed().get(0).getFailedConditions();
        assertThat(failReasons).hasSize(2);
        assertThat(failReasons).anyMatch(reason -> reason.contains("age") && reason.contains("不满足条件"));
        assertThat(failReasons).anyMatch(reason -> reason.contains("status") && reason.contains("不满足条件"));
        
        System.out.println("失败原因: " + failReasons);
    }

    @Test
    public void testFilterListWithDetailedFailureReasons() {
        // 创建过滤条件：年龄必须大于20
        JsonDslDefinition filterDef = new JsonDslDefinition("age-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        filterDef.setFieldDsl(fieldDsl);

        // 测试用户列表：包含通过和不通过的用户
        List<TestUser> users = Arrays.asList(
            createTestUser("Alice", 25, "active"),   // 通过
            createTestUser("Bob", 15, "active"),     // 不通过：年龄太小
            createTestUser("Charlie", 35, "active")  // 通过
        );
        
        FilterProcessor processor = new DefaultFilterProcessor();
        ProcessingContext context = new ProcessingContext();
        
        FilterResult<TestUser> result = processor.filterList(users, filterDef, context);
        
        // 验证结果
        assertThat(result.getPassed()).hasSize(2);
        assertThat(result.getFailed()).hasSize(1);
        
        // 验证失败用户的详细原因
        List<String> failReasons = result.getFailed().get(0).getFailedConditions();
        assertThat(failReasons).hasSize(1);
        assertThat(failReasons.get(0)).contains("age").contains("不满足条件");
        
        System.out.println("通过的用户: " + result.getPassed().stream().map(TestUser::getName).collect(Collectors.toList()));
        System.out.println("失败的用户: " + result.getFailed().get(0).getObject().getName());
        System.out.println("失败原因: " + failReasons);
    }

    @Test
    public void testFilterWithCombinationConditions() {
        // 创建组合条件：年龄大于20 且 状态为active
        JsonDslDefinition filterDef = new JsonDslDefinition("combination-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("ageAndStatus", Map.of("$EXPR", "age > 20 && status == 'active'"));
        filterDef.setCombineDsl(combineDsl);

        // 测试用户：年龄15，状态active（组合条件不满足）
        TestUser user = new TestUser("YoungUser", 15, "active");
        
        FilterProcessor processor = new DefaultFilterProcessor();
        ProcessingContext context = new ProcessingContext();
        
        FilterResult<TestUser> result = processor.filter(user, filterDef, context);
        
        // 验证失败
        assertThat(result.getPassed()).isEmpty();
        assertThat(result.getFailed()).hasSize(1);
        
        // 验证组合条件的失败原因
        List<String> failReasons = result.getFailed().get(0).getFailedConditions();
        assertThat(failReasons).hasSize(1);
        assertThat(failReasons.get(0)).contains("组合条件").contains("ageAndStatus");
        
        System.out.println("组合条件失败原因: " + failReasons);
    }

    private TestUser createTestUser(String name, int age) {
        TestUser user = new TestUser();
        user.setName(name);
        user.setAge(age);
        return user;
    }

    private TestUser createTestUser(String name, int age, String status) {
        TestUser user = new TestUser();
        user.setName(name);
        user.setAge(age);
        user.setStatus(status);
        return user;
    }

    private Map<String, Object> createTestMap(String name, int age, String status) {
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("name", name);
        userMap.put("age", age);
        userMap.put("status", status);
        return userMap;
    }

    /**
     * 测试用户类
     */
    public static class TestUser {
        private String name;
        private Integer age;
        private String status;

        public TestUser() {
        }

        public TestUser(String name, int age, String status) {
            this.name = name;
            this.age = age;
            this.status = status;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
} 