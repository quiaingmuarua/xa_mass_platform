package com.xa.mass.base.jsondsl;

import com.xa.mass.base.jsondsl.model.JsonDslContext;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.processor.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generate 和 Filter 集成测试
 * 测试各种复杂的生成和过滤场景
 */
public class GenerateFilterIntegrationTest {

    private ProcessingContext context;
    private GenerateProcessor generateProcessor;
    private FilterProcessor filterProcessor;

    @BeforeEach
    void setUp() {
        // 强制触发 BuiltinFunctions 的 static 块，确保所有内置函数注册
        try {
            Class.forName("com.xa.mass.base.jsondsl.builtin.BuiltinFunctions");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        // 注册测试用的内部类到 TypeRegistry
        com.xa.mass.base.jsondsl.generate.TypeRegistry.register("TestUser", TestUser.class);

        context = new ProcessingContext("integration-test");
        generateProcessor = ProcessorRegistry.getGenerateProcessor();
        filterProcessor = ProcessorRegistry.getFilterProcessor();
    }

    @Test
    void testComplexGenerateWithNestedObjects() {
        // 测试生成包含嵌套对象的复杂数据结构
        com.xa.mass.base.jsondsl.generate.TypeRegistry.register("TestUser", TestUser.class);
        JsonDslDefinition generateDsl = new JsonDslDefinition("complex-generate", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("TestUser");
        dslContext.setCount(3);
        generateDsl.setContext(dslContext);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("id", Map.of("$EXPR", "range(1, 1000)"));
        fieldDsl.put("name", "$choice('active', 'inactive', 'pending')");
        fieldDsl.put("age", "$RANGE(18, 65)");
        // 简化 email 生成，使用简单的字符串拼接
        fieldDsl.put("email", "$join('alice', '@', 'example.com')");
        fieldDsl.put("score", "$RANGE(60, 100)");
        fieldDsl.put("status", "$choice('active', 'inactive', 'pending')");
        fieldDsl.put("createdTime", "$NOW('yyyy-MM-dd HH:mm:ss')");
        generateDsl.setFieldDsl(fieldDsl);

        List<TestUser> users = generateProcessor.generate(generateDsl, context, TestUser.class);

        assertNotNull(users);
        assertEquals(3, users.size());

        for (TestUser user : users) {
            assertNotNull(user.getId());
            assertNotNull(user.getName());
            assertNotNull(user.getAge());
            assertNotNull(user.getEmail());
            assertNotNull(user.getScore());
            assertNotNull(user.getStatus());
            assertNotNull(user.getCreatedTime());

            // 验证数值范围
            assertTrue(user.getAge() >= 18 && user.getAge() <= 65);
            assertTrue(user.getScore() >= 60 && user.getScore() <= 100);
        }
    }

    @Test
    void testMultiConditionFilter() {
        // 首先生成测试数据
        JsonDslDefinition generateDsl = new JsonDslDefinition("test-data", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.GenerateFilterIntegrationTest$TestUser");
        dslContext.setCount(10);
        generateDsl.setContext(dslContext);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("id", "$RANGE(1, 100)");
        fieldDsl.put("name", "$CHOICE(Alice, Bob, Charlie, Diana, Eve)");
        fieldDsl.put("age", "$RANGE(16, 70)");
        fieldDsl.put("score", "$RANGE(50, 100)");
        fieldDsl.put("status", "$CHOICE(active, inactive, pending)");
        fieldDsl.put("department", "$CHOICE(IT, HR, Finance, Marketing)");
        generateDsl.setFieldDsl(fieldDsl);

        List<TestUser> allUsers = generateProcessor.generate(generateDsl, context, TestUser.class);
        assertEquals(10, allUsers.size());

        // 创建多条件过滤 DSL
        JsonDslDefinition filterDsl = new JsonDslDefinition("multi-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("age", Map.of("$EXPR", "age >= 25"));
        filterFieldDsl.put("score", Map.of("$EXPR", "score >= 80"));
        filterFieldDsl.put("status", Map.of("$EXPR", "status == 'active'"));
        filterFieldDsl.put("department", Map.of("$EXPR", "department == 'IT' || department == 'Finance'"));
        filterDsl.setFieldDsl(filterFieldDsl);

        // 执行过滤
        FilterResult<TestUser> filterResult = filterProcessor.filterList(allUsers, filterDsl, context);
        List<TestUser> filteredUsers = filterResult.getPassed();

        assertNotNull(filteredUsers);
        assertTrue(filteredUsers.size() <= allUsers.size());

        // 验证过滤结果
        for (TestUser user : filteredUsers) {
            assertTrue(user.getAge() >= 25, "年龄应该 >= 25");
            assertTrue(user.getScore() >= 80, "分数应该 >= 80");
            assertEquals("active", user.getStatus(), "状态应该是 active");
            assertTrue("IT".equals(user.getDepartment()) || "Finance".equals(user.getDepartment()),
                    "部门应该是 IT 或 Finance");
        }
    }

    @Test
    void testExpressionBasedFilter() {
        // 生成测试数据
        JsonDslDefinition generateDsl = new JsonDslDefinition("expression-test", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.GenerateFilterIntegrationTest$TestUser");
        dslContext.setCount(5);
        generateDsl.setContext(dslContext);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("id", "$RANGE(1, 100)");
        fieldDsl.put("name", "$CHOICE('Alice', 'Bob', 'Charlie')");
        fieldDsl.put("age", "$RANGE(20, 60)");
        fieldDsl.put("salary", "$RANGE(3000, 15000)");
        fieldDsl.put("experience", "$RANGE(1, 20)");
        generateDsl.setFieldDsl(fieldDsl);

        List<TestUser> employees = generateProcessor.generate(generateDsl, context, TestUser.class);
        assertEquals(5, employees.size());

        // 使用复杂表达式过滤
        JsonDslDefinition filterDsl = new JsonDslDefinition("expression-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        // 复杂条件：年龄在25-50之间，且经验>=5年，且薪资>=5000
        filterFieldDsl.put("match", Map.of("$EXPR", "age >= 25 && age <= 50 && experience >= 5 && salary >= 5000"));
        filterDsl.setFieldDsl(filterFieldDsl);

        FilterResult<TestUser> filterResult = filterProcessor.filterList(employees, filterDsl, context);
        List<TestUser> filteredEmployees = filterResult.getPassed();

        assertNotNull(filteredEmployees);

        // 验证过滤结果
        for (TestUser employee : filteredEmployees) {
            assertTrue(employee.getAge() >= 25 && employee.getAge() <= 50, "年龄应该在25-50之间");
            assertTrue(employee.getExperience() >= 5, "经验应该 >= 5年");
            assertTrue(employee.getSalary() >= 5000, "薪资应该 >= 5000");
        }
    }

    @Test
    void testChainProcessing() {
        // 测试链式处理：生成 -> 过滤 -> 再次过滤
        JsonDslDefinition generateDsl = new JsonDslDefinition("chain-generate", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.GenerateFilterIntegrationTest$TestUser");
        dslContext.setCount(20);
        generateDsl.setContext(dslContext);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("id", "$RANGE(1, 1000)");
        fieldDsl.put("name", "$CHOICE(Alice, Bob, Charlie, Diana, Eve, Frank, Grace)");
        fieldDsl.put("age", "$RANGE(18, 70)");
        fieldDsl.put("score", "$RANGE(40, 100)");
        fieldDsl.put("status", "$CHOICE(active, inactive, pending)");
        generateDsl.setFieldDsl(fieldDsl);

        List<TestUser> allUsers = generateProcessor.generate(generateDsl, context, TestUser.class);
        assertEquals(20, allUsers.size());

        // 第一步过滤：年龄 >= 25
        JsonDslDefinition filter1 = new JsonDslDefinition("age-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filter1Dsl = new HashMap<>();
        filter1Dsl.put("age", Map.of("$EXPR", "age >= 25"));
        filter1.setFieldDsl(filter1Dsl);

        FilterResult<TestUser> result1 = filterProcessor.filterList(allUsers, filter1, context);
        List<TestUser> step1 = result1.getPassed();
        assertNotNull(step1);
        assertTrue(step1.size() <= allUsers.size());

        // 第二步过滤：分数 >= 80
        JsonDslDefinition filter2 = new JsonDslDefinition("score-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filter2Dsl = new HashMap<>();
        filter2Dsl.put("score", Map.of("$EXPR", "score >= 80"));
        filter2.setFieldDsl(filter2Dsl);

        FilterResult<TestUser> result2 = filterProcessor.filterList(step1, filter2, context);
        List<TestUser> step2 = result2.getPassed();
        assertNotNull(step2);
        assertTrue(step2.size() <= step1.size());

        // 第三步过滤：状态为 active
        JsonDslDefinition filter3 = new JsonDslDefinition("status-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filter3Dsl = new HashMap<>();
        filter3Dsl.put("status", Map.of("$EXPR", "status == 'active'"));
        filter3.setFieldDsl(filter3Dsl);

        FilterResult<TestUser> result3 = filterProcessor.filterList(step2, filter3, context);
        List<TestUser> finalResult = result3.getPassed();
        assertNotNull(finalResult);
        assertTrue(finalResult.size() <= step2.size());

        // 验证最终结果
        for (TestUser user : finalResult) {
            assertTrue(user.getAge() >= 25, "年龄应该 >= 25");
            assertTrue(user.getScore() >= 80, "分数应该 >= 80");
            assertEquals("active", user.getStatus(), "状态应该是 active");
        }

        System.out.println("链式过滤结果：");
        System.out.println("原始数据: " + allUsers.size() + " 条");
        System.out.println("年龄过滤后: " + step1.size() + " 条");
        System.out.println("分数过滤后: " + step2.size() + " 条");
        System.out.println("状态过滤后: " + finalResult.size() + " 条");
    }

    @Test
    void testErrorHandling() {
        // 测试错误处理场景

        // 1. 测试无效的生成 DSL
        JsonDslDefinition invalidGenerateDsl = new JsonDslDefinition("invalid", JsonDslDefinition.DslType.GENERATE);
        // 不设置 context，应该抛出异常
        assertThrows(Exception.class, () -> {
            generateProcessor.generate(invalidGenerateDsl, context, TestUser.class);
        });

        // 2. 测试无效的过滤 DSL
        JsonDslDefinition invalidFilterDsl = new JsonDslDefinition("invalid", JsonDslDefinition.DslType.FILTER);
        // 不设置 fieldDsl，应该抛出异常
        List<TestUser> testData = List.of(new TestUser());
        assertThrows(Exception.class, () -> {
            filterProcessor.filter(testData, invalidFilterDsl, context);
        });

        // 3. 测试空数据过滤
        JsonDslDefinition validFilterDsl = new JsonDslDefinition("valid", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("age", Map.of("$EXPR", "age > 0"));
        validFilterDsl.setFieldDsl(filterFieldDsl);

        List<TestUser> emptyData = List.of();
        FilterResult<TestUser> emptyResult = filterProcessor.filterList(emptyData, validFilterDsl, context);
        assertNotNull(emptyResult);
        assertEquals(0, emptyResult.getPassed().size());
    }

    @Test
    void testDebugMode() {
        // 测试调试模式
        context.setDebug(true);

        JsonDslDefinition generateDsl = new JsonDslDefinition("debug-test", JsonDslDefinition.DslType.GENERATE);
        JsonDslContext dslContext = new JsonDslContext();
        dslContext.setModel("com.xa.mass.base.jsondsl.GenerateFilterIntegrationTest$TestUser");
        dslContext.setCount(2);
        generateDsl.setContext(dslContext);

        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("id", "$RANGE(1, 10)");
        fieldDsl.put("name", "$CHOICE(Alice, Bob)");
        generateDsl.setFieldDsl(fieldDsl);

        List<TestUser> users = generateProcessor.generate(generateDsl, context, TestUser.class);
        assertNotNull(users);
        assertEquals(2, users.size());

        // 测试过滤的调试模式
        JsonDslDefinition filterDsl = new JsonDslDefinition("debug-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> filterFieldDsl = new HashMap<>();
        filterFieldDsl.put("id", Map.of("$EXPR", "id > 5"));
        filterDsl.setFieldDsl(filterFieldDsl);

        FilterResult<TestUser> filterResult = filterProcessor.filterList(users, filterDsl, context);
        assertNotNull(filterResult);
    }

    /**
     * 测试用户 Bean 类
     */
    public static class TestUser {
        private Integer id;
        private String name;
        private Integer age;
        private String email;
        private Integer score;
        private String status;
        private String createdTime;
        private String department;
        private Integer salary;
        private Integer experience;

        // Getters and Setters
        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public Integer getScore() {
            return score;
        }

        public void setScore(Integer score) {
            this.score = score;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getCreatedTime() {
            return createdTime;
        }

        public void setCreatedTime(String createdTime) {
            this.createdTime = createdTime;
        }

        public String getDepartment() {
            return department;
        }

        public void setDepartment(String department) {
            this.department = department;
        }

        public Integer getSalary() {
            return salary;
        }

        public void setSalary(Integer salary) {
            this.salary = salary;
        }

        public Integer getExperience() {
            return experience;
        }

        public void setExperience(Integer experience) {
            this.experience = experience;
        }

        @Override
        public String toString() {
            return "TestUser{id=" + id + ", name='" + name + "', age=" + age +
                    ", email='" + email + "', score=" + score + ", status='" + status + "'}";
        }
    }
} 
