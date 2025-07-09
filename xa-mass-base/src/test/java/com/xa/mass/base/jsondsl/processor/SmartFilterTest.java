package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;

/**
 * 智能过滤功能测试
 * 
 * 验证 FilterProcessor 能够智能识别输入类型：
 * 1. 单个对象 -> 返回 boolean
 * 2. List 对象 -> 返回过滤后的 List
 */
public class SmartFilterTest {

    private DefaultFilterProcessor processor;
    private JsonDslDefinition definition;
    private ProcessingContext context;

    @BeforeEach
    void setUp() {
        processor = new DefaultFilterProcessor();
        definition = new JsonDslDefinition("smart-filter", JsonDslDefinition.DslType.FILTER);
        context = new ProcessingContext("smart-filter-context");
        
        // 设置过滤条件：年龄大于20
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        definition.setFieldDsl(fieldDsl);
    }

    @Test
    void testFilterSmartWithSingleObject() {
        // 测试单个对象
        TestUser user = new TestUser("Alice", 25, "active");
        
        Object result = processor.filterSmart(user, definition, context);
        
        assertTrue(result instanceof Boolean, "单个对象应该返回Boolean");
        assertTrue((Boolean) result, "Alice年龄25应该通过过滤");
        
        // 测试不满足条件的单个对象
        TestUser youngUser = new TestUser("Bob", 15, "active");
        Object failResult = processor.filterSmart(youngUser, definition, context);
        
        assertTrue(failResult instanceof Boolean, "单个对象应该返回Boolean");
        assertFalse((Boolean) failResult, "Bob年龄15不应该通过过滤");
    }

    @Test
    void testFilterSmartWithList() {
        // 测试列表对象
        List<TestUser> users = Arrays.asList(
            new TestUser("Alice", 25, "active"),
            new TestUser("Bob", 15, "active"),
            new TestUser("Charlie", 35, "active")
        );
        
        Object result = processor.filterSmart(users, definition, context);
        
        assertTrue(result instanceof List, "列表对象应该返回List");
        @SuppressWarnings("unchecked")
        List<TestUser> filtered = (List<TestUser>) result;
        
        assertEquals(2, filtered.size(), "应该有2个用户通过过滤");
        assertTrue(filtered.stream().anyMatch(u -> "Alice".equals(u.getName())));
        assertTrue(filtered.stream().anyMatch(u -> "Charlie".equals(u.getName())));
        assertFalse(filtered.stream().anyMatch(u -> "Bob".equals(u.getName())));
    }

    @Test
    void testFilterSmartWithMap() {
        // 测试Map对象
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("name", "David");
        userMap.put("age", 30);
        userMap.put("status", "active");
        
        Object result = processor.filterSmart(userMap, definition, context);
        
        assertTrue(result instanceof Boolean, "Map对象应该返回Boolean");
        assertTrue((Boolean) result, "David年龄30应该通过过滤");
    }

    @Test
    void testFilterSmartWithMixedTypes() {
        // 测试不同类型的输入
        TestUser user = new TestUser("Alice", 25, "active");
        List<TestUser> users = Arrays.asList(
            new TestUser("Bob", 15, "active"),
            new TestUser("Charlie", 35, "active")
        );
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("name", "David");
        userMap.put("age", 18);
        userMap.put("status", "active");
        
        // 单个对象
        Object singleResult = processor.filterSmart(user, definition, context);
        assertTrue(singleResult instanceof Boolean);
        assertTrue((Boolean) singleResult);
        
        // 列表对象
        Object listResult = processor.filterSmart(users, definition, context);
        assertTrue(listResult instanceof List);
        @SuppressWarnings("unchecked")
        List<TestUser> filtered = (List<TestUser>) listResult;
        assertEquals(1, filtered.size()); // 只有Charlie通过
        
        // Map对象
        Object mapResult = processor.filterSmart(userMap, definition, context);
        assertTrue(mapResult instanceof Boolean);
        assertFalse((Boolean) mapResult); // David年龄18不通过
    }

    @Test
    void testFilterSmartWithEmptyList() {
        // 测试空列表
        List<TestUser> emptyList = Arrays.asList();
        
        Object result = processor.filterSmart(emptyList, definition, context);
        
        assertTrue(result instanceof List, "空列表应该返回List");
        @SuppressWarnings("unchecked")
        List<TestUser> filtered = (List<TestUser>) result;
        assertTrue(filtered.isEmpty(), "空列表过滤后应该还是空的");
    }

    @Test
    void testFilterSmartWithNullInput() {
        // 测试空输入
        assertThrows(Exception.class, () -> {
            processor.filterSmart(null, definition, context);
        });
    }

    @Test
    void testFilterSmartWithComplexConditions() {
        // 测试复杂条件
        TestUser user = new TestUser("Alice", 25, "active");
        user.setDepartment("Engineering");
        user.setSalary(80000);
        
        // 设置复杂过滤条件
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        fieldDsl.put("status", Map.of("$EXPR", "status == 'active'"));
        definition.setFieldDsl(fieldDsl);
        
        Map<String, Object> combineDsl = new HashMap<>();
        combineDsl.put("seniorCheck", Map.of("$EXPR", "age > 30 || salary > 70000"));
        combineDsl.put("deptCheck", Map.of("$EXPR", "department == 'Engineering'"));
        definition.setCombineDsl(combineDsl);
        
        Object result = processor.filterSmart(user, definition, context);
        
        assertTrue(result instanceof Boolean);
        assertTrue((Boolean) result, "Alice应该通过复杂过滤条件");
    }

    @Test
    void testFilterSmartWithDebugMode() {
        // 测试调试模式
        context.setDebug(true);
        
        List<TestUser> users = Arrays.asList(
            new TestUser("Alice", 25, "active"),
            new TestUser("Bob", 15, "active")
        );
        
        Object result = processor.filterSmart(users, definition, context);
        
        assertTrue(result instanceof List);
        @SuppressWarnings("unchecked")
        List<TestUser> filtered = (List<TestUser>) result;
        assertEquals(1, filtered.size(), "调试模式下应该正确处理过滤");
    }

    /**
     * 测试用户类
     */
    public static class TestUser {
        private String name;
        private Integer age;
        private String status;
        private String department;
        private Integer salary;
        
        public TestUser(String name, Integer age, String status) {
            this.name = name;
            this.age = age;
            this.status = status;
        }
        
        // Getters and Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public String getDepartment() { return department; }
        public void setDepartment(String department) { this.department = department; }
        
        public Integer getSalary() { return salary; }
        public void setSalary(Integer salary) { this.salary = salary; }
    }
} 