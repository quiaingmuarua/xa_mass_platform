package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FilterProcessor 新接口使用示例
 *
 * 展示重构后的接口如何支持：
 * 1. 单个对象过滤
 * 2. 不同类型对象的统一处理
 * 3. 批量过滤的便利方法
 */
public class FilterProcessorUsageExample {

    @Test
    void demonstrateSingleObjectFiltering() {
        // 1. 单个JavaBean对象过滤
        User user = new User("Alice", 25, "active");

        JsonDslDefinition filterDef = new JsonDslDefinition("user-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        fieldDsl.put("status", Map.of("$EXPR", "status == 'active'"));
        filterDef.setFieldDsl(fieldDsl);

        ProcessingContext context = new ProcessingContext("user-filter-context");
        FilterProcessor processor = new DefaultFilterProcessor();

        // 直接过滤单个对象
        FilterResult result = processor.filter(user, filterDef, context);
//        assertTrue(result.getPassed()., "Alice应该通过过滤");

        // 2. 单个Map对象过滤
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("name", "Bob");
        userMap.put("age", 15);
        userMap.put("status", "inactive");

//        boolean mapPassed = processor.filter(userMap, filterDef, context);
//        assertFalse(mapPassed, "Bob不应该通过过滤");
    }

    @Test
    void demonstrateBatchFiltering() {
        // 创建测试数据
        List<User> users = Arrays.asList(
                new User("Alice", 25, "active"),
                new User("Bob", 15, "inactive"),
                new User("Charlie", 35, "active"),
                new User("David", 45, "inactive")
        );

        JsonDslDefinition filterDef = new JsonDslDefinition("batch-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        fieldDsl.put("status", Map.of("$EXPR", "status == 'active'"));
        filterDef.setFieldDsl(fieldDsl);

        ProcessingContext context = new ProcessingContext("batch-filter-context");

        // 使用便利方法进行批量过滤
        List<User> filtered = JsonDslProcessorEngine.filterBatch(users, filterDef, context, User.class);

        assertEquals(2, filtered.size(), "应该只有Alice和Charlie通过过滤");
        assertTrue(filtered.stream().anyMatch(u -> "Alice".equals(u.getName())));
        assertTrue(filtered.stream().anyMatch(u -> "Charlie".equals(u.getName())));
    }

    @Test
    void demonstrateBatchFilteringWithDetails() {
        // 创建测试数据
        List<User> users = Arrays.asList(
                new User("Alice", 25, "active"),
                new User("Bob", 15, "inactive"),
                new User("Charlie", 35, "active")
        );

        JsonDslDefinition filterDef = new JsonDslDefinition("detail-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        fieldDsl.put("status", Map.of("$EXPR", "status == 'active'"));
        filterDef.setFieldDsl(fieldDsl);

        ProcessingContext context = new ProcessingContext("detail-filter-context");

        // 获取详细的过滤结果
        FilterResult<User> result = JsonDslProcessorEngine.filterBatchWithDetails(users, filterDef, context, User.class);

        assertEquals(2, result.getPassed().size(), "应该有2个通过");
        assertEquals(1, result.getFailed().size(), "应该有1个失败");
        assertEquals(3, users.size(), "总数量应该是3");
    }

    @Test
    void demonstrateMixedTypeFiltering() {
        // 创建混合类型的测试数据
        List<Object> mixedData = Arrays.asList(
                new User("Alice", 25, "active"),
                Map.of("name", "Bob", "age", 35, "status", "active"),
                new User("Charlie", 15, "inactive")
        );

        JsonDslDefinition filterDef = new JsonDslDefinition("mixed-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        filterDef.setFieldDsl(fieldDsl);

        ProcessingContext context = new ProcessingContext("mixed-filter-context");

        // 统一处理不同类型的对象
        List<Object> filtered = JsonDslProcessorEngine.filterBatch(mixedData, filterDef, context, Object.class);

        assertEquals(2, filtered.size(), "应该有2个对象通过过滤");
    }

    @Test
    void demonstrateComplexFiltering() {
        User user = new User("Alice", 25, "active");
        user.setDepartment("Engineering");
        user.setSalary(80000);

        JsonDslDefinition filterDef = new JsonDslDefinition("complex-filter", JsonDslDefinition.DslType.FILTER);

        // 字段条件
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        fieldDsl.put("status", Map.of("$EXPR", "status == 'active'"));
        filterDef.setFieldDsl(fieldDsl);

        // 组合条件
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("seniorCheck", Map.of("$EXPR", "age > 30 || salary > 70000"));
        combineDsl.put("deptCheck", Map.of("$EXPR", "department == 'Engineering'"));
        filterDef.setCombineDsl(combineDsl);

        ProcessingContext context = new ProcessingContext("complex-filter-context");
        FilterProcessor processor = new DefaultFilterProcessor();

//        boolean passed = processor.filter(user, filterDef, context);
//        assertTrue(passed, "Alice应该通过复杂过滤条件");
    }

    /**
     * 测试用户类
     */
    public static class User {
        private String name;
        private Integer age;
        private String status;
        private String department;
        private Integer salary;

        public User(String name, Integer age, String status) {
            this.name = name;
            this.age = age;
            this.status = status;
        }

        // Getters and Setters
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

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
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
    }
} 