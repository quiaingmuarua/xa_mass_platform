package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.JsonDslEngine;
import com.xa.mass.base.jsondsl.filter.DslFilterFactory;
import com.xa.mass.base.jsondsl.filter.JsonDslFilter;

import java.util.List;
import java.util.Map;

/**
 * JsonDslEngine 独立 generate 和 filter 方法使用示例
 * <p>
 * 展示如何独立使用 generate 和 filter 方法，让使用者可以自由组合
 * </p>
 */
public class JsonDslEngineWithFilterExample {

    public static void main(String[] args) {
        System.out.println("=== JsonDslEngine 独立 generate + filter 示例 ===\n");
        
        // 示例1: 生成用户数据并过滤
        generateAndFilterUsers();
        
        // 示例2: 生成产品数据并过滤
        generateAndFilterProducts();
        
        // 示例3: 多模型生成和过滤
        generateAndFilterMultipleModels();
        
        // 示例4: 使用已注册的过滤器
        useRegisteredFilters();
        
        // 示例5: 链式过滤
        chainFiltering();
    }
    
    /**
     * 示例1: 生成用户数据并过滤
     */
    public static void generateAndFilterUsers() {
        System.out.println("--- 示例1: 生成用户数据并过滤 ---");
        
        // 1. 定义用户生成 DSL
        String userDsl = """
            {
                "MODEL": "User",
                "COUNT": 10,
                "FIELDS": {
                    "id": {"$RANGE": [1, 1000]},
                    "name": {"$CHOICE": ["Alice", "Bob", "Charlie", "Diana", "Eve"]},
                    "age": {"$RANGE": [16, 65]},
                    "score": {"$RANGE": [50, 100]},
                    "status": {"$CHOICE": ["active", "inactive", "pending"]}
                }
            }
            """;
        
        // 2. 生成数据
        List<Object> allUsers = JsonDslEngine.generateList(userDsl);
        System.out.println("生成的总用户数: " + allUsers.size());
        
        // 3. 定义过滤器配置：只保留成年且活跃的高分用户
        String filterConfig = """
            {
                "conditions": {
                    "age": {"gte": 18},
                    "status": {"eq": "active"},
                    "score": {"gte": 80}
                }
            }
            """;
        
        // 4. 独立过滤
        List<Object> filteredUsers = JsonDslEngine.filter(allUsers, filterConfig);
        
        System.out.println("过滤后的用户数: " + filteredUsers.size());
        System.out.println("过滤率: " + String.format("%.1f%%", (1.0 - (double)filteredUsers.size() / allUsers.size()) * 100));
        
        // 5. 显示过滤后的用户信息
        System.out.println("符合条件的用户:");
        filteredUsers.forEach(user -> {
            Map<String, Object> userMap = (Map<String, Object>) user;
            System.out.println("  - " + userMap.get("name") + 
                             " (年龄:" + userMap.get("age") + 
                             ", 分数:" + userMap.get("score") + 
                             ", 状态:" + userMap.get("status") + ")");
        });
        System.out.println();
    }
    
    /**
     * 示例2: 生成产品数据并过滤
     */
    public static void generateAndFilterProducts() {
        System.out.println("--- 示例2: 生成产品数据并过滤 ---");
        
        // 1. 定义产品生成 DSL
        String productDsl = """
            {
                "MODEL": "Product",
                "COUNT": 15,
                "FIELDS": {
                    "id": {"$UUID": null},
                    "name": {"$CHOICE": ["iPhone", "MacBook", "iPad", "AirPods", "Watch", "Coffee", "Book", "Pen"]},
                    "price": {"$RANGE": [5, 2000]},
                    "category": {"$CHOICE": ["electronics", "books", "food", "clothing"]},
                    "stock": {"$RANGE": [0, 100]},
                    "rating": {"$RANGE": [1, 5]}
                }
            }
            """;
        
        // 2. 生成数据
        List<Object> allProducts = JsonDslEngine.generateList(productDsl);
        System.out.println("生成的总产品数: " + allProducts.size());
        
        // 3. 使用便捷方法过滤：只保留有库存的产品
        List<Object> inStockProducts = JsonDslEngine.filter(allProducts, "stock", "gt", 0);
        System.out.println("有库存的产品数: " + inStockProducts.size());
        
        // 4. 继续过滤：价格范围
        List<Object> priceFilteredProducts = JsonDslEngine.filter(inStockProducts, "price", "gte", 10);
        List<Object> finalProducts = JsonDslEngine.filter(priceFilteredProducts, "price", "lte", 1000);
        System.out.println("价格合理的产品数: " + finalProducts.size());
        
        // 5. 继续过滤：类别
        List<Object> electronicsProducts = JsonDslEngine.filter(finalProducts, "category", "eq", "electronics");
        System.out.println("最终电子产品数: " + electronicsProducts.size());
        
        // 6. 显示最终结果
        System.out.println("符合条件的产品:");
        electronicsProducts.forEach(product -> {
            Map<String, Object> productMap = (Map<String, Object>) product;
            System.out.println("  - " + productMap.get("name") + 
                             " (价格:$" + productMap.get("price") + 
                             ", 库存:" + productMap.get("stock") + 
                             ", 评分:" + productMap.get("rating") + ")");
        });
        System.out.println();
    }
    
    /**
     * 示例3: 多模型生成和过滤
     */
    public static void generateAndFilterMultipleModels() {
        System.out.println("--- 示例3: 多模型生成和过滤 ---");
        
        // 1. 定义多模型 DSL
        String multiModelDsl = """
            {
                "users": {
                    "MODEL": "User",
                    "COUNT": 5,
                    "FIELDS": {
                        "id": {"$RANGE": [1, 100]},
                        "name": {"$CHOICE": ["Alice", "Bob", "Charlie"]},
                        "age": {"$RANGE": [18, 50]},
                        "role": {"$CHOICE": ["admin", "user", "guest"]}
                    }
                },
                "orders": {
                    "MODEL": "Order",
                    "COUNT": 8,
                    "FIELDS": {
                        "id": {"$UUID": null},
                        "userId": {"$RANGE": [1, 100]},
                        "amount": {"$RANGE": [10, 500]},
                        "status": {"$CHOICE": ["pending", "completed", "cancelled"]}
                    }
                }
            }
            """;
        
        // 2. 生成多模型数据
        Map<String, List<Object>> allModels = JsonDslEngine.generateMap(multiModelDsl);
        System.out.println("原始数据:");
        allModels.forEach((modelName, objects) -> 
            System.out.println("  " + modelName + ": " + objects.size() + " 个对象")
        );
        
        // 3. 定义过滤器配置
        String filterConfig = """
            {
                "conditions": {
                    "age": {"gte": 25},
                    "role": {"ne": "guest"},
                    "amount": {"gte": 50},
                    "status": {"eq": "completed"}
                }
            }
            """;
        
        // 4. 独立过滤多模型数据
        Map<String, List<Object>> filteredModels = JsonDslEngine.filter(allModels, filterConfig);
        
        System.out.println("过滤后数据:");
        filteredModels.forEach((modelName, objects) -> 
            System.out.println("  " + modelName + ": " + objects.size() + " 个对象")
        );
        System.out.println();
    }
    
    /**
     * 示例4: 使用已注册的过滤器
     */
    public static void useRegisteredFilters() {
        System.out.println("--- 示例4: 使用已注册的过滤器 ---");
        
        // 1. 创建并注册过滤器
        JsonDslFilter<Object> ageFilter = DslFilterFactory.createSimpleFilter(
            "adultFilter", "age", "gte", 18
        );
        
        JsonDslFilter<Object> scoreFilter = DslFilterFactory.createRangeFilter(
            "highScoreFilter", "score", 80, 100
        );
        
        DslFilterFactory.registerFilter("adultFilter", ageFilter);
        DslFilterFactory.registerFilter("highScoreFilter", scoreFilter);
        
        // 2. 定义学生生成 DSL
        String studentDsl = """
            {
                "MODEL": "Student",
                "COUNT": 12,
                "FIELDS": {
                    "id": {"$RANGE": [1, 100]},
                    "name": {"$CHOICE": ["Alice", "Bob", "Charlie", "Diana", "Eve", "Frank"]},
                    "age": {"$RANGE": [15, 25]},
                    "score": {"$RANGE": [60, 100]},
                    "grade": {"$CHOICE": ["A", "B", "C", "D"]}
                }
            }
            """;
        
        // 3. 生成数据
        List<Object> allStudents = JsonDslEngine.generateList(studentDsl);
        System.out.println("总学生数: " + allStudents.size());
        
        // 4. 使用已注册的过滤器
        List<Object> adults = JsonDslEngine.filter(allStudents, "adultFilter", true);
        List<Object> highScores = JsonDslEngine.filter(allStudents, "highScoreFilter", true);
        
        System.out.println("成年学生数: " + adults.size());
        System.out.println("高分学生数: " + highScores.size());
        
        // 5. 显示高分学生信息
        System.out.println("高分学生:");
        highScores.forEach(student -> {
            Map<String, Object> studentMap = (Map<String, Object>) student;
            System.out.println("  - " + studentMap.get("name") + 
                             " (年龄:" + studentMap.get("age") + 
                             ", 分数:" + studentMap.get("score") + 
                             ", 等级:" + studentMap.get("grade") + ")");
        });
        System.out.println();
    }
    
    /**
     * 示例5: 链式过滤
     */
    public static void chainFiltering() {
        System.out.println("--- 示例5: 链式过滤 ---");
        
        // 1. 生成数据
        String userDsl = """
            {
                "MODEL": "User",
                "COUNT": 20,
                "FIELDS": {
                    "id": {"$RANGE": [1, 1000]},
                    "name": {"$CHOICE": ["Alice", "Bob", "Charlie", "Diana", "Eve", "Frank", "Grace", "Henry"]},
                    "age": {"$RANGE": [16, 70]},
                    "score": {"$RANGE": [30, 100]},
                    "status": {"$CHOICE": ["active", "inactive", "pending", "suspended"]},
                    "department": {"$CHOICE": ["IT", "HR", "Sales", "Marketing", "Finance"]}
                }
            }
            """;
        
        List<Object> allUsers = JsonDslEngine.generateList(userDsl);
        System.out.println("原始用户数: " + allUsers.size());
        
        // 2. 链式过滤：年龄 -> 状态 -> 分数 -> 部门
        List<Object> step1 = JsonDslEngine.filter(allUsers, "age", "gte", 25);
        System.out.println("年龄>=25: " + step1.size());
        
        List<Object> step2 = JsonDslEngine.filter(step1, "status", "eq", "active");
        System.out.println("状态=active: " + step2.size());
        
        List<Object> step3 = JsonDslEngine.filter(step2, "score", "gte", 70);
        System.out.println("分数>=70: " + step3.size());
        
        List<Object> step4 = JsonDslEngine.filter(step3, "score", "lte", 100);
        System.out.println("分数<=100: " + step4.size());
        
        // 3. 显示最终结果
        System.out.println("最终符合条件的用户:");
        step4.forEach(user -> {
            Map<String, Object> userMap = (Map<String, Object>) user;
            System.out.println("  - " + userMap.get("name") + 
                             " (年龄:" + userMap.get("age") + 
                             ", 分数:" + userMap.get("score") + 
                             ", 部门:" + userMap.get("department") + ")");
        });
        System.out.println();
    }
} 