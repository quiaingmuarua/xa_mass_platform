package com.xa.mass.base.jsondsl.filter;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class FilterPackageTest {

    @Test
    public void testSimpleFilter() {
        // 创建简单条件过滤器
        JsonDslFilter<Map<String, Object>> filter = DslFilterFactory.createSimpleFilter(
            "ageFilter", "age", "gte", 18
        );
        
        // 测试数据
        Map<String, Object> person1 = Map.of("name", "Alice", "age", 20);
        Map<String, Object> person2 = Map.of("name", "Bob", "age", 16);
        
        // 过滤
        Map<String, Object> result1 = filter.filter(person1);
        Map<String, Object> result2 = filter.filter(person2);
        
        // 验证
        assertThat(result1).isNotNull();
        assertThat(result2).isNull(); // 年龄小于18，被过滤掉
    }
    
    @Test
    public void testRangeFilter() {
        // 创建范围过滤器
        JsonDslFilter<Map<String, Object>> filter = DslFilterFactory.createRangeFilter(
            "scoreFilter", "score", 60, 100
        );
        
        // 测试数据
        Map<String, Object> student1 = Map.of("name", "Alice", "score", 85);
        Map<String, Object> student2 = Map.of("name", "Bob", "score", 55);
        Map<String, Object> student3 = Map.of("name", "Charlie", "score", 95);
        
        // 过滤
        Map<String, Object> result1 = filter.filter(student1);
        Map<String, Object> result2 = filter.filter(student2);
        Map<String, Object> result3 = filter.filter(student3);
        
        // 验证
        assertThat(result1).isNotNull();
        assertThat(result2).isNull(); // 分数低于60，被过滤掉
        assertThat(result3).isNotNull();
    }
    
    @Test
    public void testExpressionFilter() {
        // 创建表达式过滤器
        JsonDslFilter<Map<String, Object>> filter = DslFilterFactory.createExpressionFilter(
            "complexFilter", "age >= 18 and score >= 80"
        );
        
        // 测试数据
        Map<String, Object> person1 = Map.of("name", "Alice", "age", 20, "score", 85);
        Map<String, Object> person2 = Map.of("name", "Bob", "age", 16, "score", 90);
        Map<String, Object> person3 = Map.of("name", "Charlie", "age", 25, "score", 75);
        
        // 过滤
        Map<String, Object> result1 = filter.filter(person1);
        Map<String, Object> result2 = filter.filter(person2);
        Map<String, Object> result3 = filter.filter(person3);
        
        // 验证
        assertThat(result1).isNotNull(); // 年龄>=18 且 分数>=80
        assertThat(result2).isNull(); // 年龄<18
        assertThat(result3).isNull(); // 分数<80
    }
    
    @Test
    public void testJsonDslFilter() {
        // 创建复杂的 JSON-DSL 过滤器（使用新标准 fieldDsl 格式）
        String filterConfigJson = """
            {
                "fieldDsl": {
                    "age": {"$gte": 18},
                    "status": {"$in": ["active", "pending"]},
                    "score": {"$gte": 60, "$lte": 100}
                }
            }
            """;
        
        JsonDslFilter<Map<String, Object>> filter = DslFilterFactory.createJsonDslFilter(
            "complexJsonFilter", "复杂JSON过滤器", filterConfigJson
        );
        
        // 测试数据
        Map<String, Object> person1 = Map.of("name", "Alice", "age", 20, "status", "active", "score", 85);
        Map<String, Object> person2 = Map.of("name", "Bob", "age", 16, "status", "active", "score", 90);
        Map<String, Object> person3 = Map.of("name", "Charlie", "age", 25, "status", "inactive", "score", 75);
        
        // 过滤
        Map<String, Object> result1 = filter.filter(person1);
        Map<String, Object> result2 = filter.filter(person2);
        Map<String, Object> result3 = filter.filter(person3);
        
        // 验证
        assertThat(result1).isNotNull(); // 满足所有条件
        assertThat(result2).isNull(); // 年龄<18
        assertThat(result3).isNull(); // 状态不在允许列表中
    }
    
    @Test
    public void testFilterRegistry() {
        // 创建过滤器
        JsonDslFilter<Map<String, Object>> filter = DslFilterFactory.createSimpleFilter(
            "testFilter", "value", "eq", "test"
        );
        
        // 注册过滤器
        DslFilterFactory.registerFilter("testFilter", filter);
        
        // 验证注册
        assertThat(DslFilterFactory.hasFilter("testFilter")).isTrue();
        assertThat(DslFilterFactory.getFilter("testFilter")).isNotNull();
        assertThat(DslFilterFactory.getFilterNames()).contains("testFilter");
        
        // 移除过滤器
        DslFilter<?, ?> removedFilter = DslFilterFactory.removeFilter("testFilter");
        assertThat(removedFilter).isNotNull();
        assertThat(DslFilterFactory.hasFilter("testFilter")).isFalse();
    }
    
    @Test
    public void testBatchFilter() {
        // 创建过滤器
        JsonDslFilter<Map<String, Object>> filter = DslFilterFactory.createSimpleFilter(
            "positiveFilter", "value", "gt", 0
        );
        
        // 测试数据
        List<Map<String, Object>> data = List.of(
            Map.of("name", "A", "value", 10),
            Map.of("name", "B", "value", -5),
            Map.of("name", "C", "value", 20),
            Map.of("name", "D", "value", 0)
        );
        
        // 批量过滤
        List<Map<String, Object>> results = filter.filterList(data);
        
        // 验证
        assertThat(results).hasSize(2); // 只有10和20通过过滤
        assertThat(results).extracting("name").containsExactly("A", "C");
    }
} 