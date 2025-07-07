package com.xa.mass.base.example;

import com.xa.mass.base.jsondsl.filter.DslFilter;
import com.xa.mass.base.jsondsl.filter.DslFilterFactory;
import com.xa.mass.base.jsondsl.filter.JsonDslFilter;

import java.util.List;
import java.util.Map;

/**
 * 过滤器使用示例
 * <p>
 * 展示如何在实际场景中使用 DSL 过滤器
 * </p>
 */
public class FilterExample {

    public static void main(String[] args) {
        // 示例1: 过滤用户数据
        filterUserData();
        
        // 示例2: 过滤产品数据
        filterProductData();
        
        // 示例3: 使用注册的过滤器
        useRegisteredFilters();
    }
    
    /**
     * 示例1: 过滤用户数据
     */
    public static void filterUserData() {
        System.out.println("=== 过滤用户数据 ===");
        
        // 创建用户数据
        List<Map<String, Object>> users = List.of(
            Map.of("id", 1, "name", "Alice", "age", 25, "status", "active", "score", 85),
            Map.of("id", 2, "name", "Bob", "age", 17, "status", "active", "score", 90),
            Map.of("id", 3, "name", "Charlie", "age", 30, "status", "inactive", "score", 75),
            Map.of("id", 4, "name", "Diana", "age", 22, "status", "active", "score", 95)
        );
        
        // 创建复合过滤器：年龄>=18 且 状态为active 且 分数>=80
        String filterConfig = """
            {
                "conditions": {
                    "age": {"gte": 18},
                    "status": {"eq": "active"},
                    "score": {"gte": 80}
                }
            }
            """;
        
        JsonDslFilter<Map<String, Object>> filter = DslFilterFactory.createJsonDslFilter(
            "userFilter", "用户复合过滤器", filterConfig
        );
        
        // 过滤用户
        List<Map<String, Object>> filteredUsers = filter.filterList(users);
        
        System.out.println("原始用户数量: " + users.size());
        System.out.println("过滤后用户数量: " + filteredUsers.size());
        System.out.println("符合条件的用户:");
        filteredUsers.forEach(user -> 
            System.out.println("  - " + user.get("name") + " (年龄:" + user.get("age") + 
                             ", 分数:" + user.get("score") + ")")
        );
        System.out.println();
    }
    
    /**
     * 示例2: 过滤产品数据
     */
    public static void filterProductData() {
        System.out.println("=== 过滤产品数据 ===");
        
        // 创建产品数据
        List<Map<String, Object>> products = List.of(
            Map.of("id", "P001", "name", "iPhone", "price", 999.99, "category", "electronics", "stock", 50),
            Map.of("id", "P002", "name", "MacBook", "price", 1999.99, "category", "electronics", "stock", 20),
            Map.of("id", "P003", "name", "Coffee", "price", 5.99, "category", "food", "stock", 100),
            Map.of("id", "P004", "name", "Book", "price", 29.99, "category", "books", "stock", 0)
        );
        
        // 创建价格范围过滤器
        JsonDslFilter<Map<String, Object>> priceFilter = DslFilterFactory.createRangeFilter(
            "priceFilter", "price", 10.0, 1000.0
        );
        
        // 创建库存过滤器
        JsonDslFilter<Map<String, Object>> stockFilter = DslFilterFactory.createSimpleFilter(
            "stockFilter", "stock", "gt", 0
        );
        
        // 创建类别过滤器
        JsonDslFilter<Map<String, Object>> categoryFilter = DslFilterFactory.createSimpleFilter(
            "categoryFilter", "category", "in", List.of("electronics", "books")
        );
        
        // 组合过滤
        List<Map<String, Object>> step1 = priceFilter.filterList(products);
        List<Map<String, Object>> step2 = stockFilter.filterList(step1);
        List<Map<String, Object>> step3 = categoryFilter.filterList(step2);
        
        System.out.println("原始产品数量: " + products.size());
        System.out.println("价格过滤后: " + step1.size());
        System.out.println("库存过滤后: " + step2.size());
        System.out.println("类别过滤后: " + step3.size());
        System.out.println("最终符合条件的产品:");
        step3.forEach(product -> 
            System.out.println("  - " + product.get("name") + " (价格:" + product.get("price") + 
                             ", 库存:" + product.get("stock") + ")")
        );
        System.out.println();
    }
    
    /**
     * 示例3: 使用注册的过滤器
     */
    public static void useRegisteredFilters() {
        System.out.println("=== 使用注册的过滤器 ===");
        
        // 创建并注册过滤器
        JsonDslFilter<Map<String, Object>> ageFilter = DslFilterFactory.createSimpleFilter(
            "adultFilter", "age", "gte", 18
        );
        
        JsonDslFilter<Map<String, Object>> scoreFilter = DslFilterFactory.createRangeFilter(
            "highScoreFilter", "score", 80, 100
        );
        
        DslFilterFactory.registerFilter("adultFilter", ageFilter);
        DslFilterFactory.registerFilter("highScoreFilter", scoreFilter);
        
        // 创建测试数据
        List<Map<String, Object>> students = List.of(
            Map.of("name", "Alice", "age", 20, "score", 85),
            Map.of("name", "Bob", "age", 16, "score", 90),
            Map.of("name", "Charlie", "age", 25, "score", 75)
        );
        
        // 使用注册的过滤器
        DslFilter registeredAgeFilter = DslFilterFactory.getFilter("adultFilter");
        DslFilter registeredScoreFilter = DslFilterFactory.getFilter("highScoreFilter");
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> adults = registeredAgeFilter.filterList(students);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> highScores = registeredScoreFilter.filterList(students);
        
        System.out.println("成年学生: " + adults.size());
        System.out.println("高分学生: " + highScores.size());
        System.out.println("已注册的过滤器: " + DslFilterFactory.getFilterNames());
        System.out.println();
    }
} 