package com.xa.mass.base.jsondsl.processor;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 兼容性过滤测试
 *
 * 验证修复后的 FilterProcessor 接口保持向后兼容性
 */
public class CompatibleFilterTest {

    public static void main(String[] args) {
        // 创建过滤处理器
        FilterProcessor processor = new DefaultFilterProcessor();

        // 创建过滤条件：年龄大于20
        JsonDslDefinition filterDef = new JsonDslDefinition("age-filter", JsonDslDefinition.DslType.FILTER);
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("age", Map.of("$EXPR", "age > 20"));
        filterDef.setFieldDsl(fieldDsl);

        ProcessingContext context = new ProcessingContext("compatible-filter-demo");

        System.out.println("=== 兼容性过滤测试 ===\n");

        // 1. 单个对象过滤
        System.out.println("1. 单个对象过滤:");
        User alice = new User("Alice", 25, "active");
        FilterResult<User> singleResult = processor.filter(alice, filterDef, context);
        System.out.println("   Alice (25岁): " + (singleResult.getPassed().size() > 0 ? "通过" : "不通过"));
        System.out.println("   通过数量: " + singleResult.getPassed().size());
        System.out.println("   失败数量: " + (singleResult.getFailed() != null ? singleResult.getFailed().size() : 0));

        User bob = new User("Bob", 15, "active");
        FilterResult<User> singleResult2 = processor.filter(bob, filterDef, context);
        System.out.println("   Bob (15岁): " + (singleResult2.getPassed().size() > 0 ? "通过" : "不通过"));
        System.out.println("   通过数量: " + singleResult2.getPassed().size());
        System.out.println("   失败数量: " + (singleResult2.getFailed() != null ? singleResult2.getFailed().size() : 0));

        // 2. 列表对象过滤
        System.out.println("\n2. 列表对象过滤:");
        List<User> users = Arrays.asList(
                new User("Alice", 25, "active"),
                new User("Bob", 15, "active"),
                new User("Charlie", 35, "active"),
                new User("David", 18, "active")
        );

        FilterResult<Object> listResult = processor.filter(users, filterDef, context);
        System.out.println("   原始数量: " + users.size());
        System.out.println("   过滤后数量: " + listResult.getPassed().size());
        System.out.println("   失败数量: " + (listResult.getFailed() != null ? listResult.getFailed().size() : 0));
        System.out.println("   通过的用户: " + listResult.getPassed().stream()
                .map(obj -> ((User) obj).getName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("无"));

        // 3. Map对象过滤
        System.out.println("\n3. Map对象过滤:");
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("name", "Eve");
        userMap.put("age", 30);
        userMap.put("status", "active");

        FilterResult<Map<String, Object>> mapResult = processor.filter(userMap, filterDef, context);
        System.out.println("   Eve (30岁): " + (mapResult.getPassed().size() > 0 ? "通过" : "不通过"));
        System.out.println("   通过数量: " + mapResult.getPassed().size());
        System.out.println("   失败数量: " + (mapResult.getFailed() != null ? mapResult.getFailed().size() : 0));

        // 4. 接口兼容性验证
        System.out.println("\n4. 接口兼容性验证:");
        System.out.println("   单个对象返回类型: " + singleResult.getClass().getSimpleName());
        System.out.println("   列表对象返回类型: " + listResult.getClass().getSimpleName());
        System.out.println("   Map对象返回类型: " + mapResult.getClass().getSimpleName());
        System.out.println("   所有返回类型都是 FilterResult，保持接口一致性");

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
    }
} 