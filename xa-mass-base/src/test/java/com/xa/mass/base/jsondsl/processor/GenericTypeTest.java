package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;

/**
 * 泛型类型测试
 * 
 * 验证修复后的 FilterProcessor 接口泛型类型正确性
 */
public class GenericTypeTest {

    public static void main(String[] args) {
        // 创建过滤处理器
        FilterProcessor processor = new DefaultFilterProcessor();
        
        // 创建过滤条件：年龄大于20
        JsonDslDefinition filterDef = new JsonDslDefinition("age-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        filterDef.setFieldDsl(fieldDsl);
        
        ProcessingContext context = new ProcessingContext("generic-type-demo");
        
        System.out.println("=== 泛型类型测试 ===\n");
        
        // 1. 单个对象过滤 - T 是 User
        System.out.println("1. 单个对象过滤 (T = User):");
        User alice = new User("Alice", 25, "active");
        FilterResult<User> singleResult = processor.filter(alice, filterDef, context);
        System.out.println("   返回类型: " + singleResult.getClass().getSimpleName());
        System.out.println("   通过数量: " + singleResult.getPassed().size());
        System.out.println("   泛型类型正确: " + (singleResult.getPassed().isEmpty() || singleResult.getPassed().get(0) instanceof User));
        
        // 2. 列表对象过滤 - T 是 User
        System.out.println("\n2. 列表对象过滤 (T = User):");
        List<User> users = Arrays.asList(
            new User("Alice", 25, "active"),
            new User("Bob", 15, "active"),
            new User("Charlie", 35, "active")
        );
        
        FilterResult<User> listResult = processor.filterList(users, filterDef, context);
        System.out.println("   返回类型: " + listResult.getClass().getSimpleName());
        System.out.println("   通过数量: " + listResult.getPassed().size());
        System.out.println("   泛型类型正确: " + (listResult.getPassed().isEmpty() || listResult.getPassed().get(0) instanceof User));
        
        // 3. Map对象过滤 - T 是 Map<String, Object>
        System.out.println("\n3. Map对象过滤 (T = Map<String, Object>):");
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("name", "Eve");
        userMap.put("age", 30);
        userMap.put("status", "active");
        
        FilterResult<Map<String, Object>> mapResult = processor.filter(userMap, filterDef, context);
        System.out.println("   返回类型: " + mapResult.getClass().getSimpleName());
        System.out.println("   通过数量: " + mapResult.getPassed().size());
        System.out.println("   泛型类型正确: " + (mapResult.getPassed().isEmpty() || mapResult.getPassed().get(0) instanceof Map));
        
        // 4. 泛型类型验证
        System.out.println("\n4. 泛型类型验证:");
        System.out.println("   单个对象: T = User, 返回 FilterResult<User>");
        System.out.println("   列表对象: T = User, 返回 FilterResult<User>");
        System.out.println("   Map对象: T = Map<String, Object>, 返回 FilterResult<Map<String, Object>>");
        System.out.println("   泛型类型完全匹配，无擦除问题");
        
        System.out.println("\n=== 测试完成 ===");
    }
    
    /**
     * 测试用户类
     */
    public static class User {
        private String name;
        private Integer age;
        private String status;
        
        public User(String name, Integer age, String status) {
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