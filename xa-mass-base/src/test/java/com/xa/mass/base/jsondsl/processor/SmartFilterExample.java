package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;

/**
 * 智能过滤功能使用示例
 * 
 * 演示 FilterProcessor 如何智能识别输入类型：
 * 1. 单个对象 -> 返回 boolean
 * 2. List 对象 -> 返回过滤后的 List
 */
public class SmartFilterExample {

    public static void main(String[] args) {
        // 创建过滤处理器
        FilterProcessor processor = new DefaultFilterProcessor();
        
        // 创建过滤条件：年龄大于20
        JsonDslDefinition filterDef = new JsonDslDefinition("age-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        filterDef.setFieldDsl(fieldDsl);
        
        ProcessingContext context = new ProcessingContext("smart-filter-demo");
        
        System.out.println("=== 智能过滤功能演示 ===\n");
        
        // 1. 单个对象过滤
        System.out.println("1. 单个对象过滤:");
        User alice = new User("Alice", 25, "active");
        Object singleResult = processor.filterSmart(alice, filterDef, context);
        System.out.println("   Alice (25岁): " + (singleResult instanceof Boolean ? 
            ((Boolean) singleResult ? "通过" : "不通过") : "类型错误"));
        
        User bob = new User("Bob", 15, "active");
        Object singleResult2 = processor.filterSmart(bob, filterDef, context);
        System.out.println("   Bob (15岁): " + (singleResult2 instanceof Boolean ? 
            ((Boolean) singleResult2 ? "通过" : "不通过") : "类型错误"));
        
        // 2. 列表对象过滤
        System.out.println("\n2. 列表对象过滤:");
        List<User> users = Arrays.asList(
            new User("Alice", 25, "active"),
            new User("Bob", 15, "active"),
            new User("Charlie", 35, "active"),
            new User("David", 18, "active")
        );
        
        Object listResult = processor.filterSmart(users, filterDef, context);
        if (listResult instanceof List) {
            @SuppressWarnings("unchecked")
            List<User> filtered = (List<User>) listResult;
            System.out.println("   原始数量: " + users.size());
            System.out.println("   过滤后数量: " + filtered.size());
            System.out.println("   通过的用户: " + filtered.stream()
                .map(User::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("无"));
        }
        
        // 3. Map对象过滤
        System.out.println("\n3. Map对象过滤:");
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("name", "Eve");
        userMap.put("age", 30);
        userMap.put("status", "active");
        
        Object mapResult = processor.filterSmart(userMap, filterDef, context);
        System.out.println("   Eve (30岁): " + (mapResult instanceof Boolean ? 
            ((Boolean) mapResult ? "通过" : "不通过") : "类型错误"));
        
        // 4. 混合类型演示
        System.out.println("\n4. 混合类型处理演示:");
        System.out.println("   单个对象返回类型: " + singleResult.getClass().getSimpleName());
        System.out.println("   列表对象返回类型: " + listResult.getClass().getSimpleName());
        System.out.println("   Map对象返回类型: " + mapResult.getClass().getSimpleName());
        
        System.out.println("\n=== 演示完成 ===");
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