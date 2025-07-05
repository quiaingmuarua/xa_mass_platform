package com.xa.mass.base.jsondsl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

/**
 * 测试 JsonDslEngine 独立的 generate 和 filter 方法
 */
public class JsonDslEngineIndependentTest {

    @Test
    public void testGenerateAndFilterIndependently() {
        // 1. 定义简单的 DSL（不指定 MODEL 类型，直接生成 Map）
        String userDsl = """
            {
                "COUNT": 5,
                "FIELDS": {
                    "id": {"$RANGE": [1, 100]},
                    "name": {"$CHOICE": ["Alice", "Bob", "Charlie", "Diana", "Eve"]},
                    "age": {"$RANGE": [18, 50]},
                    "score": {"$RANGE": [60, 100]}
                }
            }
            """;
        
        // 2. 独立生成数据
        List<Object> allUsers = JsonDslEngine.generateList(userDsl);
        assertEquals(5, allUsers.size());
        
        // 3. 独立过滤数据
        List<Object> filteredUsers = JsonDslEngine.filter(allUsers, "age", "gte", 25);
        assertTrue(filteredUsers.size() <= allUsers.size());
        
        // 4. 验证过滤结果
        for (Object user : filteredUsers) {
            Map<String, Object> userMap = (Map<String, Object>) user;
            Integer age = (Integer) userMap.get("age");
            assertTrue(age >= 25, "过滤后的用户年龄应该 >= 25");
        }
        
        System.out.println("原始用户数: " + allUsers.size());
        System.out.println("过滤后用户数: " + filteredUsers.size());
    }
    
    @Test
    public void testGenerateMapAndFilter() {
        // 1. 定义多模型 DSL（不指定 MODEL 类型）
        String multiModelDsl = """
            {
                "users": {
                    "COUNT": 3,
                    "FIELDS": {
                        "id": {"$RANGE": [1, 100]},
                        "name": {"$CHOICE": ["Alice", "Bob", "Charlie"]},
                        "age": {"$RANGE": [18, 50]}
                    }
                },
                "products": {
                    "COUNT": 2,
                    "FIELDS": {
                        "id": {"$RANGE": [1, 100]},
                        "name": {"$CHOICE": ["iPhone", "MacBook"]},
                        "price": {"$RANGE": [100, 1000]}
                    }
                }
            }
            """;
        
        // 2. 独立生成多模型数据
        Map<String, List<Object>> allModels = JsonDslEngine.generateMap(multiModelDsl);
        assertEquals(2, allModels.size());
        assertEquals(3, allModels.get("users").size());
        assertEquals(2, allModels.get("products").size());
        
        // 3. 独立过滤多模型数据
        Map<String, List<Object>> filteredModels = JsonDslEngine.filter(allModels, "age", "gte", 25);
        
        // 4. 验证过滤结果
        assertTrue(filteredModels.get("users").size() <= allModels.get("users").size());
        assertEquals(allModels.get("products").size(), filteredModels.get("products").size()); // 产品没有 age 字段，应该保持不变
        
        System.out.println("原始用户数: " + allModels.get("users").size());
        System.out.println("过滤后用户数: " + filteredModels.get("users").size());
    }
    
    @Test
    public void testChainFiltering() {
        // 1. 生成数据（不指定 MODEL 类型）
        String userDsl = """
            {
                "COUNT": 10,
                "FIELDS": {
                    "id": {"$RANGE": [1, 100]},
                    "name": {"$CHOICE": ["Alice", "Bob", "Charlie", "Diana", "Eve"]},
                    "age": {"$RANGE": [16, 70]},
                    "score": {"$RANGE": [30, 100]},
                    "status": {"$CHOICE": ["active", "inactive"]}
                }
            }
            """;
        
        List<Object> allUsers = JsonDslEngine.generateList(userDsl);
        assertEquals(10, allUsers.size());
        
        // 2. 链式过滤：年龄 -> 状态 -> 分数 -> 部门
        List<Object> step1 = JsonDslEngine.filter(allUsers, "age", "gte", 25);
        System.out.println("年龄>=25: " + step1.size());
        
        List<Object> step2 = JsonDslEngine.filter(step1, "status", "eq", "active");
        System.out.println("状态=active: " + step2.size());
        
        List<Object> step3 = JsonDslEngine.filter(step2, "score", "gte", 70);
        System.out.println("分数>=70: " + step3.size());
        
        List<Object> step4 = JsonDslEngine.filter(step3, "score", "lte", 100);
        System.out.println("分数<=100: " + step4.size());
        
        // 3. 验证链式过滤结果
        assertTrue(step4.size() <= step3.size());
        assertTrue(step3.size() <= step2.size());
        assertTrue(step2.size() <= step1.size());
        assertTrue(step1.size() <= allUsers.size());
        
        // 4. 验证最终结果
        for (Object user : step4) {
            Map<String, Object> userMap = (Map<String, Object>) user;
            Integer age = (Integer) userMap.get("age");
            Integer score = (Integer) userMap.get("score");
            String status = (String) userMap.get("status");
            
            assertTrue(age >= 25, "年龄应该 >= 25");
            assertTrue(score >= 70 && score <= 100, "分数应该在 70-100 之间");
            assertEquals("active", status, "状态应该是 active");
        }
        
        System.out.println("原始用户数: " + allUsers.size());
        System.out.println("年龄>=25: " + step1.size());
        System.out.println("状态=active: " + step2.size());
        System.out.println("分数>=70: " + step3.size());
        System.out.println("分数<=100: " + step4.size());
    }
} 